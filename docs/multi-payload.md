# Sequential payload injection — design

Goal: inject payload A into a running client, then later inject payload B into the **same** JVM
(e.g. Zoomify now, LiquidBounce ten minutes later), with both live and neither corrupting the other.

Today this is refused outright: `FullInjectAgent.everInjected` (FullInjectAgent.java:42) rejects a
second attach. The guard is correct — everything below is what it is guarding against.

Findings here were established by reading the code, and each is marked VERIFIED (traced to a
mechanism) or INFERRED (read but not executed). Nothing in this document has been run yet.

---

## 1. Why a second attach cannot work today

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

### 1.2 Byte-source composition — payload B would silently erase payload A (VERIFIED)

The `ClassFileTransformer` is registered with `canRetransform=true` and left installed
(FullInjectAgent.java:276). `retransformClasses` restarts from the class's **original** bytes and
runs every retransform-capable transformer in registration order, so A's transformer output becomes
B's transformer input. That much composes for free and is the one piece of good news.

The break is at FullInjectAgent.java:240-241:

```java
if (c==null) t = cv.target;                                  // future-loaded: use conversion as-is
else        t = RetransformConverter.rebase(cv.target, b);   // already-loaded: rebase onto current
```

`rebase(candidate, actualBaseline)` (RetransformConverter.java:1340) keeps **`candidate`'s method
bodies** and only restores `actualBaseline`'s *schema* (field modifiers, method ordering, exception
lists) via `restoreOriginalSchema`. Meanwhile payload B's `cv.target` is computed in Phase A from
**pristine** bytes — `originalBytes()` → baseline / `getRawClassBytes()` (FullInjectAgent.java:123).

So for any Minecraft method that **both** payloads modify, B's conversion never saw A's edits, and
rebase re-emits B's bodies over them. Payload A's hook on that method is lost, silently.

This is the core of the work: **payload B's mixins must be applied to the current bytes, not the
pristine ones.**

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

### 1.6 Mixin/loader state assumes one activation (VERIFIED)

* `FabricPlatform.payloadActivated` (:36) makes `prepareBaselines` throw if called twice (:321).
* `reconcileClassInfoWithAw` (:260-312) mutates cached `ClassInfo.Member` access flags in place —
  deliberately *not* eviction, because eviction was measured to drop 7 of 8 registered `MixinInfo`s.
  It syncs **access flags only**, not member lists, so after injection A the cache does not describe
  A's added members.
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

### P3 — Byte-source composition (fixes §1.2, §1.6) — the hard one

1. The CFT records what it published per target (`Map<String, byte[]> published`).
2. For injection N>1, `originalBytes(target)` returns the published bytes when present, so Mixin
   applies B on top of A's `target'` rather than on pristine bytes.
3. Refresh Mixin's `ClassInfo` for those targets so it describes the current shape — extending the
   existing in-place mutation (§1.6), **not** eviction, for the measured reason.
4. Replace `payloadActivated` / `everInjected` with per-scope state.

Known limitation to document rather than solve: payload A's *added* members live on A's sidecar, not
on `target'`, so a mixin in B that tried to inject into a method A added would not see it. Two mods
hooking each other's additions is not a case we need.

### P4 — Caller re-adaptation (fixes §1.5)

When `ifaceMap` grows, previously-rewritten caller classes must be retransformed so their collapsed
casts re-expand. We know their names. Cheaper alternative if that proves unstable: disable the
single-implementation collapse once more than one payload is registered, and always route through
`DuckDispatch` — slower, but uniform.

### P5 — Verification

On a real client, not a harness:

* Zoomify → LiquidBounce, and LiquidBounce → Zoomify (order matters; both must pass).
* A deliberately overlapping pair — both payloads hooking the same target method — asserting **both**
  hooks fire. This is the §1.2 regression test and the one that actually matters.
* Payload A still functional after B lands (zoom still zooms; LB UI still renders).
* Both orders re-run after P4 to confirm duck dispatch survives a growing `ifaceMap`.

---

## 3. Risks

* **P3 is the phase that can fail on contact.** Mixin applying to already-converted bytes is not a
  path the library was designed for; if `ClassInfo` cannot be made to describe `target'` accurately,
  the fallback is to re-derive the full mixin output for *all* registered payloads from pristine bytes
  on every injection and publish the combined result — more work per injection, but it keeps Mixin on
  its intended path. Decide this with a spike, before building on top of it.
* **Mixin version drift.** The `transformedCount` reset and the pending-config queue are internal
  behaviour, already asserted to fail loud. More injections means more exposure to it.
* **Memory.** Retaining published bytes per target for the session is bounded by target count
  (~151 for LiquidBounce), so it is small — but it is a new retention, worth measuring.
* **A single-payload regression is the worst outcome.** Every phase must keep the existing
  single-injection path working; P1 and P2 are refactors under a working test, not rewrites.
