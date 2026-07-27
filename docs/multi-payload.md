# Sequential payload injection — design

Goal: inject payload A into a running client, then later inject payload B into the **same** JVM
(e.g. Zoomify now, LiquidBounce ten minutes later), with both live and neither corrupting the other.

~~Today this is refused outright: `FullInjectAgent.everInjected` (FullInjectAgent.java:42) rejects a
second attach. The guard is correct — everything below is what it is guarding against.~~

**As of P3 this works.** Two different payloads inject into one JVM, in either order, with both live on
targets they share — asserted per method, from the bytes actually published. What remains refused is
re-injecting the **same** payload, which would double its own hooks.

The blanket guard's stated reason ("its runtime cannot be re-staged onto the already-populated loader")
was **false**, and was never tested. Staging a second payload onto the live Knot loader is additive and
works: measured `staged onto Knot: pushed 1 jar(s)`, `payload packages exposed to Knot: [net.wurstclient]`,
and the second payload's entrypoint then ran on the game thread.

Findings here were established by reading the code, and each is marked VERIFIED (traced to a
mechanism) or INFERRED (read but not executed). Nothing in this document has been run yet.

> **Update after P3.** That last sentence is the important one. §1 was written entirely from reading,
> and "VERIFIED" in it meant "traced to a mechanism", not "observed". Three of its claims have since
> been falsified by execution — §1.6's `Mixins.addConfiguration` hypothesis (P0), §1.6's
> `payloadActivated` claim and §1.2's central conclusion (P3). Each falsified claim is struck through
> in place rather than deleted. Read §1 as a hypothesis list.

---

## 1. Why a second attach could not work (pre-P3)

### 1.1 Agent identity — the engine has no per-jar identity (VERIFIED)

* `Agent-Class: FullInjectAgent`, declared in the generated manifest, and the class is in the
  **default package** (FullInjectAgent.java:9 — no `package` statement).
* Both agent jars are appended to the **same system class loader**
  (`Instrumentation.appendToSystemClassLoaderSearch`, VanillaPlatform.java:47/52). There is no
  per-agent loader.
* The JDK's attach handler resolves `Agent-Class` **by name**. The name is already defined from
  jar #1, so jar #2's copy is never defined: agent #1's code runs `agentmain` again.
* Both routes the engine uses to find its own config therefore point at jar #1:
  * `FullInjectAgent.class.getProtectionDomain().getCodeSource().getLocation()`
    (FullInjectAgent.java:68) — the `ProtectionDomain` is fixed at class-definition time.
  * `sys.getResourceAsStream("graft.properties")` (VanillaPlatform.java:32) — classpath order,
    jar #1 first.

Consequence: **the agent cannot read its own payload's config.** This is a correctness bug in its
own right, independent of sequential injection — it makes a second attach behave as something other
than what the user asked for. Today `everInjected` masks it by refusing first.

### 1.2 Byte-source composition — the real break, and the conclusion that was wrong

**The symptom was real; the diagnosis and the prescription were both wrong.** This section originally
concluded "payload B's mixins must be applied to the current bytes, not the pristine ones". That is
**FALSIFIED by execution** (P3 spike, 2026-07-26). Corrected account below.

Two claims did survive:

* The CFT is registered with `canRetransform=true` and left installed (FullInjectAgent.java:276), and
  `retransformClasses` restarts from the class's **original** bytes.
* `rebase(candidate, actualBaseline)` (RetransformConverter.java:1444) keeps `candidate`'s method
  bodies and restores `actualBaseline`'s schema via `restoreOriginalSchema`.

What was wrong:

**(a) "A's transformer output becomes B's transformer input… composes for free" — WRONG, and the
opposite of free.** The retransform baseline is the bytes the JVM was handed at *define* time, not any
transformer's later output. Measured with a standalone JVMTI harness (`Target.v()` returning 1 vs 2, a
retransform-capable transformer, positive control showing the transform does apply): once the
transformer returns `null` — or throws — the class **reverts to the pre-transformer bytes**. So a
transformer that fails during a second injection does not "leave things as they are"; it silently tears
out the hooks of a payload that was already installed and working. Observed live: with a naive second
injection, Camera / Hud / MouseHandler each logged `CFT fail`, `retransformed 47/47` still reported
success, and all three went back to their pre-injection state.

**(b) "B's conversion never saw A's edits" — WRONG. Mixin already composes for free.** Payload A's
mixin config stays permanently selected in the live Mixin once promoted (P0 established that it stays
in `MixinProcessor.configs`). So a *single* `transformClassBytes` on **pristine** bytes during
injection B re-derives every registered payload together and yields X_others+A+B. Measured: on
injection B, Camera's freshly captured baseline contains exactly Zoomify's three handlers, which the
pristine pre-mixin bytes do not.

**(c) "Apply B's mixins to the current bytes" — FALSIFIED as a prescription.** Handing
`IMixinTransformer.transformClassBytes` payload A's published `target'` was tested directly:

* `net/minecraft/client/Camera` — **succeeds, and that is the bad case.** The output contains
  `modifyReturnValue$beb000$modifyFovWithZoom` again: Mixin **re-applied payload A's mixins** on top of
  bytes whose woven call sites already carry them. Zoomify's `@ModifyReturnValue` on `calculateFov`
  would run twice. Silent double-application of every already-injected payload, on every later injection.
* `net/minecraft/client/MouseHandler` — **hard failure.** `InvalidMixinException:
  wrapOperation$zfi000$fabric-events-interaction-v0$wrapSelectedSlot … cannot overwrite method in
  net.minecraft.client.MouseHandler because @Overwrite is required` — re-applying a config whose method
  is already present collides with it.

There is no supported way to deselect a config, so this is wrong by construction, not merely risky.

**The corrected finding.** Mixin needs no help and must keep receiving pristine bytes. The entire defect
is in the converter's **baseline** and **publish** path:

* The converter's schema baseline `O` must stay the **true original** — the plane the loaded class is
  actually at, captured before any payload of ours. `FabricPlatform.baseline` (:35) was a per-instance
  field on a platform rebuilt every injection (`PLATFORM = detectPlatform(inst)`,
  FullInjectAgent.java:129), so injection B recaptured it *with A's config selected* and got X_others+A.
  Two distinct measured publish failures followed from that single cause:
  `Cannot restore original schema for net/minecraft/client/Camera: extra methods [Zoomify's three
  handlers]`, and `… missing method mixinextras$bridge$mouseClicked$43` — MixinExtras numbers its
  synthetic bridges by `methods.size()`, so another payload's members shift every other mod's bridge
  suffix and `alignSynthetics` then realigned to the wrong plane.
* With `O` correct, `diff(O, X)` contains **every** payload's added members. The earlier payloads' are
  *foreign*: strip them from `target'` (the loaded class does not have them) but do **not** relocate
  them — they already live on their own payload's sidecar holding that payload's state, and `gLookup`
  redirects every reference to them there for free, because it searches all scopes.

Net effect: one `target'` carrying both payloads' woven bodies at the original schema. The observable is
`RetransformConverter.sidecarScopesByMethod`, read back out of the bytes actually published and reported
as the `composition:` / `co-hooked methods:` log lines, which `tools/multi-payload-test.sh` asserts.

### 1.3 Generated names collide on shared targets (VERIFIED)

Every generated name is a pure function of (target class, member) with no payload component:

| Artifact | Derivation | Site |
|---|---|---|
| Sidecar class | `target + "$$Sidecar"` (Fabric); `SIDECAR_PKG + mangled(target) + "$$Sidecar"` (NeoForge) | RetransformConverter.java:91-93 |
| State class | `sidecar + "$State"` | :110 |
| Relocated method | `"h$" + name` | :565 |
| Field accessors | `"get$"/"set$" + name` | :489-490 |
| Reflective field | `"F$"/"refGet$"/"refSet$" + name` | :849-862 |

`rmi$`, `smi$` and `ifd$` names mangle the full (owner, name, descriptor) triple and are injective,
so those are safe *in principle* — but they live **on the sidecar**, which is already defined from
payload A, so B still cannot add them.

LiquidBounce and Zoomify both hook the render/input path, so shared targets are the expected case,
not the edge case.

### 1.4 Global maps are keyed by target only (VERIFIED)

`GADDED`, `GFIELD` (RetransformConverter.java:605), `GIFACE` (:606), `PAYLOAD_CLASSES` (:611),
`CALLER_REWRITE_PREFIXES` (:617), `SHARED_IFACES` (:628) are all populated once
(FullInjectAgent.java:71/78/129/133) and read live by the CFT. Same for `DuckDispatch.IMPLEMENTATIONS`,
`StateReflect.FIELD_STATE` / `STATE_SIDECAR`.

Two distinct failure modes:

* **Masking** — `DuckDispatch.register` skips a duplicate `(iface, target)` pair (DuckDispatch.java:28),
  so B's sidecar is never registered and B's hooks are inert.
* **Invisibility** — B's classes are absent from `PAYLOAD_CLASSES` and `CALLER_REWRITE_PREFIXES`, so
  B's own code is never caller-rewritten. Its casts to ducked interfaces would fail at runtime.

### 1.5 Caller rewriting cannot re-adapt (INFERRED)

`rewriteCaller` collapses a duck-interface `CHECKCAST`/`INVOKEINTERFACE` to a concrete target when
the interface has a single implementation (RetransformConverter.java:1109-1129). If B later adds a
second implementation, A's already-rewritten callers keep the collapsed form and will reject B's
instances. Classes rewritten during injection A are not revisited when the `ifaceMap` grows.

### 1.6 Mixin/loader state assumes one activation — two of four claims falsified

This section was labelled VERIFIED but was written **from reading, not from running**, and two of its
four bullets did not survive contact. Treat the remaining two as read-only findings too.

* ~~`FabricPlatform.payloadActivated` (:36) makes `prepareBaselines` throw if called twice (:321).~~
  **WRONG.** `payloadActivated` is a **non-static instance field**, and `agentmain` does
  `PLATFORM = detectPlatform(inst)` (FullInjectAgent.java:129) — a **fresh `FabricPlatform` per
  injection**. It can never be `true` on entry to a second `prepareBaselines`. VERIFIED by execution:
  the second injection logs `captured 63 non-payload mod baselines (X_others)` and proceeds. Nothing
  in the engine was blocking a second `prepareBaselines`; the blanket `everInjected` guard was.

  Same shape in `NeoForgePlatform` (:163-164), also per-instance, also therefore inert.

  The *real* problem in that area was the opposite of a block: the second `prepareBaselines` ran
  happily and returned the **wrong plane** (X_others+A), because the earlier payload's config is still
  selected — see §1.2.
* `reconcileClassInfoWithAw` (:260-312) mutates cached `ClassInfo.Member` access flags in place —
  deliberately *not* eviction, because eviction was measured to drop 7 of 8 registered `MixinInfo`s.
  It syncs **access flags only**, not member lists, so after injection A the cache does not describe
  A's added members.

  **Measured, and it does not matter.** `ClassInfo` for `Camera` after a Zoomify injection holds 3
  members the published `target'` does not have — exactly Zoomify's handlers — so the cache is
  genuinely stale *relative to the published class*. But it is stale in precisely the direction Mixin
  needs: Mixin never reads published bytes, it re-derives from `getPreMixinClassBytes` with A's config
  still selected, and that output *does* contain those members. **P3 needed no `ClassInfo` refresh at
  all**, and the plan's step 3 ("refresh `ClassInfo` for those targets") was dropped as unnecessary
  rather than implemented.
* An AccessWidener only affects classes loaded *after* it registers; already-loaded classes keep
  their original access permanently. A second payload's AW inherits that constraint.
* `Mixins.addConfiguration` writes to a **process-global** registry, and the `transformedCount` reset
  (FabricPlatform.java:362) forces `checkSelect → select → prepareConfigs`.

  This was proposed as the explanation for the unexplained `wurst.mixins.json` report and is
  **FALSIFIED**. A probe agent (its own `Agent-Class`, so the re-injection guard does not apply)
  dumped the live Mixin state after a complete Wurst injection: `pendingConfigs count=0`,
  `Mixins.getUnvisitedCount() = 0`, and `wurst.mixins.json` already in `MixinProcessor.configs`.
  There is no leftover *pending* config for a later injection to promote. Positive control: the same
  probe detects the config appearing (61 → 62), so it is not blind. What remains true is weaker —
  the config stays permanently in the live `configs` list.

  Only the "leftover from an earlier injection" precondition was tested. Whether the reset
  re-promotes configs *within* a single injection is still untested.

### 1.7 What actually produces the `wurst.mixins.json` symptom (VERIFIED)

The real mechanism is §1.1, and it is worse than §1.1 as first stated: it does not need a second
*injection*, only a second *jar on the classpath*.

A refused or failed attach still leaves its jar there — the JVM calls
`appendToSystemClassLoaderSearch` **before** `agentmain`, so even an injection that dies at the
`everInjected` guard permanently adds its `graft.properties` to the classpath.

Reproduced end-to-end: attach a decoy jar (a copy of the Wurst agent with `Agent-Class: NoopAgent`,
modelling that residue), then run `mcgraft inject --agent liquidbounce-agent.jar` as the **first**
real injection on a fresh client:

```
[mc-graft] [INFO] payload 'wurst': mixins=[wurst.mixins.json] aw=wurst.accesswidener ...
[Attach Listener/ERROR]: Critical problem: wurst.mixins.json:BlockMixin ... was loaded too early.
```

The user asked for LiquidBounce and got Wurst, on a client with no Wurst installed — the reported
symptom exactly. VERIFIED as a mechanism. That this is what happened *historically* remains
INFERRED: it fits (injections were failing constantly in that era, and every failure leaves this
residue) but was not measured at the time.

---

## 2. Plan

Phases are ordered so each lands independently and is separately shippable. P1 is worth doing even
if sequential injection is later abandoned.

### P0 — Failing harness first

Two-payload local test (Zoomify then LiquidBounce, and the reverse) that asserts both are live.
Expected to fail at the `everInjected` guard. This exists **before** any fix, so every later phase
has a real pass/fail signal rather than "it compiled".

Also: attempt the `wurst.mixins.json` repro — inject payload A, then B, and check whether B's run
announces A's mixin config. Confirms or kills §1.6's hypothesis cheaply.

### P1 — Agent identity and config source (fixes §1.1)

1. `ctl` passes the agent jar's absolute path as the `agentmain` argument (we own both sides).
2. The engine reads `graft.properties` from **that jar** via `JarFile`, never from the classpath.
3. Embed an engine version/build id in the jar. On a second attach, the *loaded* engine compares its
   own version against the attaching jar's; on mismatch, refuse loudly and explain — silently running
   engine A's code for jar B is worse than failing.
4. Keep `everInjected` as-is for now: `ctl` still refuses a second injection, but for a stated reason
   rather than as a side effect.

Correct on its own merits: today a second attach reads the wrong config.

**Status: DONE and verified on a live client.** The §1.7 decoy reproduction was re-run against the
P1 engine and flips from `payload 'wurst'` to `payload 'liquidbounce'`, logging
`attaching jar ... is not the jar that defined this engine ... — reading the payload config from the
attaching jar` and `attaching jar carries the same engine build`. Single-injection regression: all
six payload-A assertions in `tools/multi-payload-test.sh` still pass.

Two limits, stated because they are easy to over-read:

* **P1 fixes the config source, not the runtime-class source.** `mcgraft.rt.*` is still staged from
  the jar that *defined* the engine — which is correct, since those helpers must match the engine
  that is actually running. In the decoy scenario that jar never exposed them to Knot, so the
  injection now fails loudly at the pre-publish contract gate instead of silently injecting the
  wrong mod. Strictly better, not "works".
* **A pre-P1 residue jar is still unprotectable.** If the already-loaded engine predates P1, its
  code runs and does the old thing; nothing shipped later can change that. The first decoy run
  demonstrated exactly this — the fix appeared not to work until the decoy was rebuilt on the P1
  engine.

### P2 — Per-payload scoping (fixes §1.3, §1.4)

Introduce an explicit `PayloadScope` (id + its own tables) and thread the id through:

* `sidecarInternal(target)` → `sidecarInternal(scope, target)`, emitting
  `target + "$$Sidecar$" + sanitized(payloadId)`. Everything downstream (`$State`, `h$`, `get$/set$`,
  `F$`, `rmi$`, `smi$`, `ifd$`) inherits the change for free, since all derive from the sidecar name.
* `gkey(target, name, desc)` → include the scope.
* `StateReflect.FIELD_STATE` → key on `(scope, target, field)`; `DuckDispatch.Impl` → carry the scope
  so `register` stops deduplicating across payloads.
* `PAYLOAD_CLASSES`, `CALLER_REWRITE_PREFIXES`, `SHARED_IFACES` → accumulate per scope, union at read.

Every reader of these names must agree on the new form: the reflection shim, caller rewriting, and
`DuckDispatch` lookup. Grep-complete, not judgement calls.

**Status: DONE, verified through the single-injection path** (P2 makes the bookkeeping *capable* of holding two
payloads; actually admitting a second injection is still P3's `everInjected`).

Landed as `PayloadScope` (id + sanitized `key` + that payload's own tables). `RetransformConverter` keeps the scope
registry and materializes the UNION of every scope's tables into the existing static fields, so the hot CFT reads stay
one map lookup. `sidecarInternal(scope, target)` emits `<target>$$Sidecar$<key>` on both the Fabric and the
`SIDECAR_PKG` (NeoForge) path.

Verified, not assumed:

* **The rename really takes effect.** `-Dmcgraft.agent.dumpDir` writes every class the agent hands to `defineClass`.
  Same client, same payload, before vs after: `net.minecraft.client.Camera$$Sidecar.class` →
  `net.minecraft.client.Camera$$Sidecar$zoomify.class` (10 files, identical target set), and a Wurst run produces 90
  files all carrying `$wurst`.
* **The id is threaded, not hardcoded** — two different payloads (`zoomify`, `wurst`) each produce their own key, and
  a probe confirms `fabric-api` → `fabric_api` while a second payload sanitizing onto the same key is disambiguated to
  `fabric_api_2`.
* **`$State`/`h$`/`get$`/`set$`/`F$`/`RM$`/`SMH$`/`rmi$`/`smi$`/`ifd$` inherit the scoping for free** — claimed in the
  plan, checked at every emit site: each is declared on the sidecar (or its `$State`) and every reference reaches it
  through the sidecar name recorded in `GADDED`/`GFIELD`/`GIFACE`, never by re-deriving it from the target.
* **The one changed `mcgraft.rt` signature resolves.** `StateReflect.declaredField` takes the scope as a third
  argument. An executed probe rewrites a real `getDeclaredField(...).getInt(...)` site, confirms the emitted descriptor
  passes `unresolvedRtCalls`, shows the gate still reports the old 2-arg form as broken, and *runs* the rewritten class
  (proving the extra `LDC` left a verifiable stack).

Two things found while doing it, neither of which the §1.3/§1.4 reading predicted:

* `LateAttachVerifier.containsRemoved` builds its lookup key as `owner + '\0' + name + ' ' + desc`
  (LateAttachVerifier.java:174) while the table it is handed is keyed by `gkey`, which used
  `owner + ' ' + name + ' ' + desc`. The two have never matched, so `REMOVED_FIELD_REF` / `REMOVED_METHOD_REF` have
  been dead since the first commit. Confirmed by execution with a positive control (the same call fires the error when
  fed a `'\0'`-shaped key). Scoping `gkey` does not change this either way. **Left unfixed on purpose** — switching it
  on would arm a broad residual-reference gate at the end of an unrelated refactor; it needs its own change and its own
  verification run.
* The pre-publish `unresolvedRtCalls` gate only ever saw calls the CONVERTER emitted (`recordRtCalls` runs on the
  target'/sidecar ClassNodes). Calls emitted by the CALLER rewrite — i.e. the `StateReflect` shim — were never in
  `EMITTED_RT_CALLS`, so that part of the runtime contract was unchecked. Now recorded at the emit site, with a second
  gate sweep after the payload-jar preflight and still before any target' is published.

### P3 — Byte-source composition (fixes §1.2) — the hard one

**Status: DONE.** `tools/multi-payload-test.sh` passes both payloads in **both orders** (Zoomify → Wurst
and Wurst → Zoomify), including the `COMPOSITION` assertion that a single Minecraft method carries hooks
from both. Each order also serves as that payload's single-injection regression (all six payload-A
assertions), so Zoomify and Wurst are both covered as a lone payload. The measured composition, identical
in both orders:

```
composition: 3 target(s) carry hooks from more than one payload
composition: net/minecraft/client/Camera hooks from [wurst, zoomify]; co-hooked methods:
  [calculateFov(F)F -> [wurst, zoomify], calculateHudFov(F)F -> [wurst, zoomify],
   extractRenderState(...)V -> [wurst, zoomify]]
composition: net/minecraft/client/MouseHandler hooks from [wurst, zoomify]; co-hooked methods:
  [onScroll(JDD)V -> [wurst, zoomify], turnPlayer(D)V -> [wurst, zoomify]]
composition: net/minecraft/client/gui/Hud hooks from [wurst, zoomify]
```

**What that does and does not establish.** It is read from the bytes the CFT actually handed the JVM, so
both payloads' hooks are demonstrably woven into the same method. It is **not** evidence that either hook
executed: every co-hooked method here needs a world, and `tools/multi-payload-test.sh` stops at the title
screen. Execution is established separately by `tools/world-join-test.sh` — see P5.

**The plan below was written before the spike and its central step was wrong.** Kept for the record,
struck through, with what actually shipped underneath.

1. ~~The CFT records what it published per target (`Map<String, byte[]> published`).~~ Kept, but for a
   different purpose (see 1' below).
2. ~~For injection N>1, `originalBytes(target)` returns the published bytes when present, so Mixin
   applies B on top of A's `target'` rather than on pristine bytes.~~ **FALSIFIED** — feeding Mixin
   converted bytes silently double-applies every already-injected payload, and hard-fails where
   re-application collides. §1.2(c) has the measurements.
3. ~~Refresh Mixin's `ClassInfo`.~~ **Not needed.** §1.6 bullet 2 has the measurement.
4. Replace `payloadActivated` / `everInjected` with per-scope state. — Kept.

**What shipped instead.** Mixin keeps receiving pristine bytes and composes all registered payloads by
itself; the fix is entirely in the converter's baseline and publish path.

1'. **Persistent schema baseline.** `FullInjectAgent.SCHEMA_BASELINE` holds, per target, the plane the
    JVM actually loaded (other mods' mixins, none of ours). `FabricPlatform.prepareBaselines` reuses an
    established entry instead of recapturing, because a recapture runs with the earlier payload's config
    selected and yields the wrong plane. This one change fixes both measured publish failures.
2'. **Foreign members.** `diff(O, X)` now contains every payload's additions. Members an earlier scope
    already claimed (`RetransformConverter.foreignMethodOwner` / `foreignFieldOwner` /
    `foreignIfaceOwner`) are stripped from `target'` but not relocated and not re-registered; `gLookup`
    already routes their references to the owning payload's sidecar. `collectAdded` skips them too.
3'. **One transformer, not N.** Each injection installs its own CFT and retires the previous one
    (`FullInjectAgent.INSTALLED_CFT`). Leaving both installed ran every conversion twice and duplicated
    every failure, since `convMap` and the conversion tables are static.
4'. **Fail closed and loud on a publish failure** (`FullInjectAgent.publishFailed`). Returning `null`
    reverts the class to its pre-injection state — §1.2(a) — so a failure used to erase a *working*
    payload silently. It now falls back to the last successfully published bytes, logs an ERROR naming
    both the payload that failed and the payloads still live on that target, and records
    `TARGET_PUBLISH_FAILURE`.
5'. **Observability.** `RetransformConverter.sidecarScopesByMethod` reads the published bytes back and
    reports which payloads' sidecars each method calls into; a `co-hooked methods:` line is the direct
    evidence that two payloads survived on one method. Asserted by `tools/multi-payload-test.sh`
    (`COMPOSITION`, `COMPOSITION_COMPLETE`).
6'. **Duplicate-payload refusal.** `everInjected` no longer refuses a second payload; re-injecting the
    **same** payload id is refused instead (`RetransformConverter.scopeForId`), because its config is
    still selected and a second pass would double every one of its hooks.

Known limitations, documented rather than solved:

* Payload A's *added* members live on A's sidecar, not on `target'`, so a mixin in B that injected into
  a method A added would not see it. Two mods hooking each other's additions is not a case we need.
* **WHOLE targets published by an EARLIER payload, since loaded (still UNTESTED).** A target an earlier
  payload converted WHOLE keeps that payload's members on the class. While it is still unloaded this
  composes (its baseline becomes that whole output). Once it has loaded, its schema is frozen with those
  members and no later payload's conversion can be published over it; phase A detects this and skips the
  target with a warning naming both payloads. **No measured instance of this exists** — every overlapping
  target in the zoomify×wurst pair is pre-loaded, so this path has never executed. It is defensive code,
  not tested behaviour. (The *single-payload* WHOLE hazard immediately below is a different edge, and that
  one has now been measured.)

#### WHOLE vs relocated across an INHERITANCE EDGE — measured and fixed, 2026-07-27

The dangerous thing about WHOLE is not that it is a second strategy; it is that the choice is made **per
target**, so two classes in one inheritance chain can take opposite branches within a *single* payload.
They are not interchangeable across that edge, and nothing checked it.

**Measured, on LiquidBounce 0.39.0 alone, first entity tick after a world join (VERIFIED):**

```
java.lang.NoSuchFieldError: Class net.minecraft.client.player.KeyboardInput
    does not have member field 'net.minecraft.world.entity.player.Input initial'
  at net.minecraft.client.player.KeyboardInput.modifyExpressionValue$bfb000$modifyInput(...)
  at net.minecraft.client.player.KeyboardInput.tick -> LocalPlayer.aiStep -> ... -> Minecraft.tick
```

Every piece of the mechanism, and how each was established:

| | |
|---|---|
| `MixinKeyboardInput extends MixinClientInput`; `initial` is declared on the SUPER-mixin and written by `modifyInput` in the SUB-mixin | VERIFIED — `javap -c` on both classes out of the LB jar |
| `ClientInput` was **converted**: `initial`/`untransformed` relocated to `ClientInput$$Sidecar$liquidbounce$State`, reached through `get$`/`set$` statics | VERIFIED — dumped via `-Dmcgraft.agent.dumpDir` |
| `KeyboardInput` was **defined WHOLE** | VERIFIED — injection log, `future-loaded targets defined WHOLE (…): [… net/minecraft/client/player/KeyboardInput …]` |
| the WHOLE bytes carried a raw `PUTFIELD net/minecraft/client/player/KeyboardInput.initial` | VERIFIED — constant pool of the published bytes, `#200 = Fieldref net/minecraft/client/player/KeyboardInput.initial:Lnet/…/Input;` |

**Why the split is not symmetric.** `ClientInput` can *never* be WHOLE whatever its load state: its mixin
adds the duck interface `ClientInputAddition`, and an added interface disqualifies a target
(FullInjectAgent, the `addsIface` check) so the value dispatches uniformly. So the relocation side of the
edge is fixed by construction; only the **subclass** side varies.

**What the variable actually is.** `KeyboardInput` is WHOLE only because it was not loaded when we attached
— `preLoaded.contains(t) → continue`. Measured at the title screen of a fresh client: unloaded (the preload
agent reports `FORCE-LOADED`, not `ALREADY-LOADED`). Measured in the crash: reached as
`LocalPlayer.aiStep → KeyboardInput.tick`. That it is *first* loaded by `LocalPlayer` construction, and so
that any prior world join in the same JVM would have loaded it, is **INFERRED** from those two facts — it
was not measured by playing into a world and then attaching. So:

* attach at the title screen of a freshly started client → `KeyboardInput` unloaded → WHOLE → crash on the
  first tick after joining (VERIFIED, both directions, below);
* `KeyboardInput` already loaded → converted like any other target, its `PUTFIELD` rewritten by
  `rewriteRefs`, no crash (VERIFIED via the forced preload; that ordinary play produces the same state is
  the inferred step).

Established by execution, not by reading. The control runs the **same pre-fix agent jar** (engine
`f5fd36055ae8`, build `a3ee40e99b3c9add` — the identical build that produced the crash above) and changes
exactly one thing: `PRELOAD_CLASSES=net.minecraft.client.player.KeyboardInput`, which attaches
`tools/worldprobe/PreloadAgent.java` at the title screen and does `Class.forName` on that one class before
mc-graft attaches. Measured, A/B on the same build:

| | crash run | + KeyboardInput preloaded |
|---|---|---|
| preload agent says | (not run) | `FORCE-LOADED` — i.e. it really was unloaded before |
| targets defined WHOLE | 31, incl. `KeyboardInput` | 30, `KeyboardInput` **absent** |
| sidecars eager-defined | 110 | 112 |
| `NoSuchFieldError` in the client log | 2 | **0** |
| harness verdict | `RESULT: FAIL` (ticking entity) | `RESULT: PASS`, world reached |

So the pre-fix engine is not broken for LiquidBounce; it is broken for LiquidBounce *injected before the
JVM has ever built a `LocalPlayer`*. That is why the same build crashes on a fresh client here and did not
on the user's, and why it is absent from their logs: "how far you had played before injecting" is the whole
of the variable.

**The fix** is `RetransformConverter.rewriteWholeTarget`: a WHOLE target's references to members ANOTHER
class relocated are redirected to that class's sidecar, which is exactly what `run()`/`rewriteRefs` already
does for every *converted* target. `gLookup` walks the superclass chain, so the inheritance edge resolves
for free once the site is visited; it also searches every payload scope, so the cross-payload form of the
same edge (a WHOLE target of payload A referencing a member payload B relocated) should be covered by the
identical code — **INFERRED, no such case exists to measure.** A member the WHOLE class declares itself is
skipped (JVMS 5.4.3.2: a declaration on the referenced class wins over anything inherited), the same guard
`LateAttachVerifier.scan` already applied, deliberately identical so the gate cannot flag a site the
rewriter would not touch. Measured on LB: exactly **2** redirected references, both
`KeyboardInput.modifyExpressionValue$bfb000$modifyInput` → `set$initial` / `set$untransformed`.

**Verification.** `tools/world-join-test.sh liquidbounce` goes `RESULT: FAIL` (this exact `NoSuchFieldError`,
ticking entity) on the pre-fix engine and `RESULT: PASS` on the fixed one — world reached, `COHOOKED_EXECUTES`,
inventory, `SIDECAR_LINKAGE` 234/234, positive control firing. Non-regression, on the same fixed engine:
`tools/world-join-test.sh zoomify wurst` PASS (FOV 70 → 17.5 / 23.333334 / **5.8333335** with both held,
identical to the pre-change numbers) and `tools/multi-payload-test.sh` PASS in both orders. Wurst defines 17
WHOLE targets in that run (Zoomify none) and the redirect changed **nothing** in any of them — zero sites,
so `rewriteWholeTarget` returns the input array unmodified and those classes are byte-identical to before.
That is the non-regression evidence and also the limit of it: outside LiquidBounce this code path has no
measured effect.

**The reason it went unseen is worth more than the fix.** A WHOLE target has no sidecar, no `$State` and no
schema delta, so it never entered `verifyConversion` — and the residual-reference gate that exists precisely
to catch "a reference to a member that was relocated off its class" (`REMOVED_FIELD_REF`) was therefore
never run over the one artifact that had one. WHOLE bytes were also invisible to `-Dmcgraft.agent.dumpDir`,
because they are returned from the CFT rather than handed to `defineClass`. Both are now closed:
`LateAttachVerifier.verifyWhole` scans every WHOLE target before publication and fails the gate closed, and
the dump writes `<target>.whole-in.class` / `.whole-out.class` so the redirect is inspectable offline.

### P4 — Caller re-adaptation (fixes §1.5)

When `ifaceMap` grows, previously-rewritten caller classes must be retransformed so their collapsed
casts re-expand. We know their names. Cheaper alternative if that proves unstable: disable the
single-implementation collapse once more than one payload is registered, and always route through
`DuckDispatch` — slower, but uniform.

### P5 — Verification

On a real client, not a harness:

* ~~Zoomify → LiquidBounce, and LiquidBounce → Zoomify.~~ **Not runnable in this environment**, and not
  for a multi-payload reason: `/tmp/lbpay.jar` is a thin LiquidBounce jar whose init dies on
  `NoClassDefFoundError: net/ccbluex/liquidbounce/authlib/account/MinecraftAccount` in **either** slot.
  Measured as payload B once P3 admitted a second injection: LB converts 145 targets and bootstraps,
  then dies at the same missing class, so its retransforms are never published. The harness header
  previously claimed LB was "fine as payload B"; that is corrected.

  Substituted: **Zoomify ↔ Wurst**, which is a stronger pair for this purpose — both hook
  `Camera.calculateFov`, `Camera.calculateHudFov` and `Camera.extractRenderState`, so the overlap is
  natural rather than constructed. Both orders pass.
* A deliberately overlapping pair — both payloads hooking the same target method — asserting **both**
  hooks fire. This is the §1.2 regression test and the one that actually matters.

  Now automated as the `COMPOSITION` assertion, measured from the bytes actually published:
  `Camera.calculateFov(F)F -> [wurst, zoomify]`, in both orders. ~~**Scope of that evidence:** it proves
  both payloads' hooks are woven into the published method, not that the method executed.~~ **Closed
  2026-07-26 — see "In a live world" below.** The scope caveat stood until then, and `COMPOSITION` on
  its own still does not mean "both hooks fired"; it is `tools/world-join-test.sh` that shows that.
* Payload A still functional after B lands (zoom still zooms; LB UI still renders). — **DONE**, below.
* Both orders re-run after P4 to confirm duck dispatch survives a growing `ifaceMap`.

#### In a live world (2026-07-26) — `tools/world-join-test.sh`

The structural gap the 2026-07-25 notes flag ("zero world joins across 20 archived session logs") is
closed. Every co-hooked method needs a world, and a sidecar is only *linked* on first use, so nothing
before this had ever executed one. `tools/world-join-test.sh` boots one client, injects, drives itself
into a deterministic superflat creative world with xdotool, and measures.

Three runs, all PASS: `zoomify` alone (single-payload control), `zoomify wurst`, `wurst zoomify`.
Console output and screenshots for each are in `notes/p5-evidence/`.

**`Camera.calculateFov(F)F`, invoked on the live camera, published bytes, two payloads woven in:**

| held | measured FOV | expected |
|---|---|---|
| nothing | 70.0 | vanilla default |
| Zoomify's key (C) | 17.5 | 70 / 4 — Zoomify `initialZoom: 4` |
| Wurst's key (V) | 23.333334 | 70 / 3 — Wurst `ZoomOtf` level 3.0 |
| **both at once** | **5.8333335** | **70 / 12 — the two hooks composing multiplicatively** |

Identical in both injection orders. That number is the whole point: a single `@ModifyReturnValue` from
each payload, on one method, both running in one invocation. If either had been erased the value would
be 17.5 or 23.33, not 5.83.

What each piece of evidence is, precisely:

* **VERIFIED — the method executes.** `WorldProbe` (its own `Agent-Class`, read-only) reflectively calls
  `Camera.calculateFov` / `calculateHudFov` on the live camera from the game thread and gets a float
  back. Returning at all means the published body linked and ran: no `VerifyError`, `NoClassDefFoundError`,
  `IllegalAccessError`, `AbstractMethodError`, no duck-dispatch failure.
* **VERIFIED — the GAME LOOP executes it, not just the probe.** Holding a zoom key changes ~300k of the
  409,920 viewport pixels (`compare -metric AE -fuzz 5%`); releasing returns the frame to within ~2–4k of
  the un-zoomed baseline. Nothing but `calculateFov` sets the render FOV. Independently, Wurst's
  `ZoomOtf.changeFovBasedOnZoom` writes `Options.sensitivity` (0.5 → 0.1667 while zooming, 0.5 after
  release) and is reachable *only* from Wurst's `Camera.calculateFov`/`calculateHudFov` mixins.
* **VERIFIED — `MouseHandler.onScroll(JDD)V`, the other co-hooked target class, executes.** Wheel-up
  while zooming deepens the zoom: Zoomify 17.5 → 2.30, Wurst 23.33 → 14.49.
* **VERIFIED — `Hud` (third co-hooked target) renders under both payloads:** Wurst's HUD overlay draws
  in-world while Zoomify is live (`notes/p5-evidence/04-both-zooms-at-once.png`).
* **VERIFIED — no linkage failure in world.** The scanner over the post-join window of the client log is
  empty in all three runs. It is **not** blind: the run ends by attaching `ErrorAgent`, which throws a
  `NoClassDefFoundError` on the game thread, and the same scanner catches it. A run whose positive
  control does not fire is reported as a FAIL, because the clean verdict would be worthless.
* **Attribution.** The single-payload `zoomify` run is the control: same world, same late attach, same
  numbers (70.0 → 17.5 → 70.0; 306728 px held, 1568 px released; sidecars 21 vs 116 with both payloads).
  So nothing seen in the two-payload runs is attributable to late attach in general.

Two measurement traps found while building this, both of which would have produced confident nonsense:

* **`pxdiff` needs `-fuzz 5%`.** Under Mesa llvmpipe two frames of an identical scene differ in ~217k of
  409,920 pixels by a channel step. Raw `AE` scored released-zoom 217216 against held-zoom 381693 — a 1.8x
  separation that proves nothing. With the fuzz: 1527 vs 307165.
* **The daylight cycle was running and darkening every successive frame.** The first `zoomify wurst` run
  failed `BOTH_RELEASE` with 305997 px still differing after release. Diagnosed with a **zero-payload
  control**: boot, join, wait 150 s, nothing injected — `level.getSkyDarken` 2 → 11 and 358057 of 409920
  pixels changed. Drift of the same order as the zoom signal, and it would have been read as a payload
  effect. Root cause: 26.2 renamed the gamerules to snake_case (`advance_time`, not `doDaylightCycle`), so
  the world's setup commands had been silently rejected in chat, where nothing reaches the log. The harness
  now issues them every run and asserts `SCENE_FROZEN` (`getSkyDarken` identical between the baseline and
  the last in-world probe), so a frozen-scene claim can never be assumed again.

Known confound, measured and then removed: Zoomify binds zoom to **C** and Wurst's own keybind system
binds **C** to Fullbright, so one keypress drove both. Measured harmless (Fullbright is a no-op in a noon
superflat world — the released frame still returned to within 2398 px of baseline), but the harness now
strips that binding in preflight, and the `wurst`→`zoomify` run is clean of it.

Still not covered: `Camera.extractRenderState` and `MouseHandler.turnPlayer` are asserted co-hooked and
run every frame / on every look input, but no assertion isolates *them* specifically. WHOLE-target
composition remains untested for the reason given under P3.

---

## 3. Risks

* ~~**P3 is the phase that can fail on contact.**~~ It did, and the spike caught it before anything was
  built on top — which is what the spike was for.

  The stated fallback ("re-derive the full mixin output for *all* registered payloads from pristine
  bytes on every injection and publish the combined result") was **the right shape for the wrong
  reason**. It was proposed as a costly retreat for when `ClassInfo` could not be made to describe
  `target'`. In fact `ClassInfo` never needed to describe `target'` at all (§1.6), and the re-derivation
  is not a retreat and costs nothing extra: Mixin performs it **automatically** in a single
  `transformClassBytes`, because every earlier payload's config stays selected. What actually replaced
  it is the same idea minus the imagined cost — keep feeding Mixin pristine bytes, and fix the
  converter's baseline and publish path instead (P3 items 1'–6').

  The real trap was in the opposite direction from the one this risk anticipated: the *primary* plan
  (feed Mixin the converted bytes) is the one that silently double-applies. See §1.2(c).
* **Mixin version drift.** The `transformedCount` reset and the pending-config queue are internal
  behaviour, already asserted to fail loud. More injections means more exposure to it.
* **Memory.** Two retentions now, not one: `PUBLISHED` (the last bytes handed to the JVM per target)
  and `SCHEMA_BASELINE` (the original plane per target). Both are bounded by target count. Measured
  per-class sizes on this client: `Camera` 22.8 KB, `MouseHandler` 30.1 KB. At ~25 KB average that is
  ~1.6 MB per map for Wurst's 63 targets and ~3.6 MB for LiquidBounce's 145 — so single-digit MB for a
  two-payload session (INFERRED from the measured per-class sizes; the totals were not measured).
* **A single-payload regression is the worst outcome.** Every phase must keep the existing
  single-injection path working; P1 and P2 are refactors under a working test, not rewrites.
