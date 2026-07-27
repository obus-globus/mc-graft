# LiquidBounce 0.39.0 — late-attach mixin hazard audit

Reconnaissance only. Nothing here is fixed; the point is to have the map before deciding what is worth the effort.
(One exception since: §5.1 — the observability of H3/H11 — was implemented on 2026-07-27. Every claim below is as
originally measured; the two boxes marked **FIXED** say what changed and what that fix itself turned up.)

Everything is marked **VERIFIED** (observed in an artifact or a run) or **INFERRED** (read from code, not executed).
Where a claim is negative ("this category is empty in LB"), the scan that produced it is named so it can be re-run.

---

## 0. Corrections to the brief

* LiquidBounce 0.39.0 ships **one** mixin config, `liquidbounce.mixins.json`. There is **no
  `liquidbounce-fabric.mixins.json`** in the jar (`fabric.mod.json` declares `"mixins": ["liquidbounce.mixins.json"]`,
  and `unzip -l` finds exactly one `*.mixins.json`). The only companion config the repo references is
  `liquidbounce-neoforge.mixins.json`, named in `examples/liquidbounce/bootstrap.properties` for the NeoForge path and
  likewise absent from this jar. VERIFIED.
* The config lists **165** mixins, not ~156. The mixin package holds 168 classes (3 are inner/helper classes with no
  `@Mixin`). They name **157** distinct targets, of which **151** are present in a vanilla-Fabric 26.2 install
  (6 are Sodium/Lithium/ViaVersion compat mixins and are skipped). VERIFIED.
* LB's access widener widens **7** classes, of which **6** are non-public in vanilla bytes (`CreativeModeTab$Output` is
  already `public interface`). Three of those six are already loaded at a title-screen attach; the brief's "4" does not
  match either count. VERIFIED (`liquidbounce.accesswidener` + class access flags read from the 26.2 client jar).

---

## 1. What was measured, and on what

| artifact | what it is |
|---|---|
| `logs/2026-07-27-7.log.gz` | LB alone, **title-screen attach**, engine `f54ad99dddd64b7d` (= `540fbc5` working tree). 31 targets WHOLE. |
| `/tmp/lbdump3/` | `-Dmcgraft.agent.dumpDir` from the equivalent run: 119 sidecars + 119 `$State` + 31 `.whole-in`/`.whole-out` pairs. |
| `/tmp/mcgraft-world/20260727-124526-liquidbounce/` | **NEW, run for this audit.** `PRELOAD_CLASSES=<28 of the 31 WHOLE targets> tools/world-join-test.sh --no-pack liquidbounce`. Flips the WHOLE set to 3 and forces the *converted* branch for the other 28. **RESULT: PASS**, world joined. |

Static analysis tooling written for this audit lives in `/tmp/lbaudit-tool/` (`Audit.java` — ASM dump of all 168 mixin
classes plus the 26.2 class hierarchy; `WholeAccess.java`; `DuckScan.java`; `SuperScan.java`; `an.py`, `an2.py`).

### 1.1 The two publish paths and which gate covers which artifact (VERIFIED, read from source)

| artifact | schema | residual-ref `scan` | dropped-iface checks | `TypeFlowGate` | pre-publish access gate |
|---|---|---|---|---|---|
| `target'` (converted) | `verifySchema` | yes | yes | **no** | **no** (deliberate — see `verifyMemberAccessGate` javadoc) |
| sidecar | — | yes | yes | **yes, fatal** | **yes** |
| `$State` | — | yes | yes | no | no |
| **WHOLE bytes** | — | yes | **no — `droppedIfaces` is `Map.of()`** (`LateAttachVerifier:51`) | **no** | **no — `if (cv.whole) continue`** (`FullInjectAgent:1204`) |
| caller-rewritten payload class | `verifySchema` (iface-erased) | yes | yes | **yes, non-fatal (WARN)** | no |

The WHOLE row is the thin one, and it is thin in three places at once. Section 3 is mostly about that row.

Also note the severity plumbing: `TARGET_RETRANSFORM_FAILURE` / `TARGET_PUBLISH_FAILURE` /
`TRANSFORM_FAILURE` / `CALLER_RETRANSFORM_FAILURE` are in `SOFT_ERROR_CODES` — they are recorded as `ERROR` in the JSON
report but printed to the injection log as `[WARN]`. Both harnesses assert `NO_AGENT_ERRORS` by grepping the log for
`[ERROR]`, so **a target going permanently inert passes every assertion this repo has.** VERIFIED — see H3 below, which
is exactly that, in a run this document reports as PASS.

> **FIXED, 2026-07-27** (§5.1). `LateAttachVerifier.inert()` is now the single route for "a target the payload asked to
> hook ended up with none of its hooks": it logs an `[ERROR] INERT TARGET …` line, records the issue, and bumps a new
> `summary.inertTargets` count in the report (schemaVersion 2). Both harnesses assert `NO_INERT_TARGETS` on the log AND
> on the report, and `tools/world-join-test.sh` now reads the JSON report at all (it never did). Two things had to be
> fixed for the JSON channel to mean anything: the report path used to be a fresh temp file per issue (measured: 48
> paths in one injection, the harness reading the one written at the FIRST issue), and H11's detector had to become a
> positive sweep rather than a side effect of something happening to retransform the class. Positive control:
> `PRELOAD_CLASSES=…ServerNameResolver tools/world-join-test.sh liquidbounce` now goes `RESULT: FAIL` on the run this
> document reports as PASS. `mixin target not present` is deliberately untouched and stays a `[WARN]`.

---

## 2. Flagged mixins / targets

Sorted by "would reach a user" first. "Gate?" = does a *current* gate fail closed before publication.

| # | mixin / target | hazard class | mechanism | gate? | confirm by |
|---|---|---|---|---|---|
| **H1** | any WHOLE target (31 at title screen) | 2 — inaccessible / AW-widened members | `rewriteWholeTarget` redirects **only** references to members another target relocated. It performs **no** AW lowering: `rewriteFieldAw` / `rewriteAwMethodInline` run only under `rewriteRefs(m, inSidecar=true)` (`RetransformConverter:1054-1112`). A WHOLE class's woven handler body that touches an AW-widened member of an **already-loaded** class therefore keeps a raw `GETFIELD`/`INVOKE` → `IllegalAccessError` on first execution. The access gate skips WHOLE (`FullInjectAgent:1204`) on the reasoning "it genuinely receives the widened bytes on load" — true of its *own* members, not of its *references*. | **NO** | `WholeAccess.java` over `*.whole-out.class`. **Ran it: 0 instances in LB.** The 35 non-public references it found are all nest-mate accesses inside the class's own outer class (`awWidened=false` on every one). VERIFIED-clean, mechanism VERIFIED. |
| **H2** | any WHOLE target | 3 — duck interfaces | `verifyWhole` passes `droppedIfaces = Map.of()`, so `DROPPED_INTERFACE_INVOKE/CAST/INSTANCEOF/DESCRIPTOR` and `BOOTSTRAP_RESIDUAL_REF` are never evaluated on WHOLE bytes. The rewriter that is supposed to fix them (`rewriteCaller`, applied to `cv.target` in the CFT) **does not visit `InvokeDynamicInsnNode` at all** — `rewriteWholeTarget` and `rewriteRefs` both do, `rewriteCaller` does not (`RetransformConverter:1405-1470`). So a WHOLE target carrying a method-ref `Handle` onto a de-implemented duck interface publishes broken *and* unexamined. | **NO** | grep the 31 `.whole-out.class` for the 28 duck-interface names. **Ran it: 0 hits, of any shape.** VERIFIED-clean, mechanism VERIFIED. |
| **H3** | any already-loaded target whose `<clinit>` has already **failed** | 5 / 7 | JVMTI returns `JVMTI_ERROR_INVALID_CLASS` for a class in the erroneous state, so it cannot be retransformed. It kills the **batch** first, and the engine then falls back to per-class isolation. Cost and outcome measured: `retransformed 137/138 … (10076ms total)` vs `110/110 … (383ms)` in the title-screen run; the odd class out is logged `[WARN] retransform fail … InternalError: class redefinition failed: invalid class` and its hook is inert for the session. | **YES, since 2026-07-27** — `inert()`: `[ERROR]` line + report issue + `inertTargets` count; both harnesses assert `NO_INERT_TARGETS`. Still non-fatal to the injection by design. Was: SOFT + `[WARN]` only, so `NO_AGENT_ERRORS` passed. | **VERIFIED by execution**, `net/minecraft/client/multiplayer/resolver/ServerNameResolver`, this audit's run. **Caveat, stated because it changes the reading:** the erroneous state was created by the preload agent's `Class.forName(…, initialize=true)` hitting `ServiceConfigurationError: com.mojang.patchy.MojangBlockListSupplier not a subtype` in this bare harness, not by LB. It is nonetheless the exact upstream failure `MixinAddressCheck` exists to swallow, so a real client that trips the blocklist service *before* attach lands in the same state. |
| **H4** | `MixinHud` → `Hud$ContextualInfo` | 2 — inaccessible types, caller path | `Hud$ContextualInfo` is already loaded and package-private, so it is in `INACC`. `rewritePayloadAw` erases the `CHECKCAST` to it, leaving `Object` on the stack, while `MixinHud.tweakExpBar`'s **declared return type is still `Hud$ContextualInfo`**. The engine's own `TypeFlowGate` says so verbatim: *"will not verify: Error at instruction 13: Incompatible return type"*. On the caller path that gate is `fatal=false` **by design** (`LateAttachVerifier:67`), so `verifyCaller` returns true and the CFT publishes the unverifiable bytes. | **NO** (deliberately downgraded to WARN) | **VERIFIED** — the sole `TYPE_FLOW` issue in this run's report. Impact is latent, not live: the class is a *mixin* class, and Mixin reads mixin classes as bytes rather than defining them, so nothing linked it in either run. It *is* definable — Wurst's mixin classes were retransformed as loaded payload classes in `logs/2026-07-27-*` because it ships an `IMixinConfigPlugin`; LB ships none. Confirm by forcing `Class.forName` on it. |
| **H5** | `MixinGuiMessage` + `MixinGuiMessageLineVisible` → `GuiMessage`, `GuiMessage$Line` | 3 — duck interfaces, multi-implementor | `GuiMessageLineAddition` is the **only** LB duck interface with two implementors, so it can never collapse; every site goes through `DuckDispatch.cast` / an `ifd$` trampoline that resolves the concrete implementor at runtime by `Method.invoke`. Both targets are records. Both are forced onto the converted path by `addsIface`, so load order cannot vary it. | partial — `SIDECAR_LINKAGE` proves the trampolines verify, not that dispatch picks the right implementor | **VERIFIED present**: `ChatComponent$$Sidecar$liquidbounce` carries exactly two `ifd$…GuiMessageLineAddition…` trampolines and is the only sidecar with any. Confirm by rendering real chat messages with LB live; neither harness ever does. |
| **H6** | 6 absent targets — `sodium/MixinSodiumBlockRenderer`, `MixinSodiumAbstractBlockRenderContext`, `MixinSodiumLightDataAccess`, `lithium/MixinChunkAwareBlockCollisionSweeper{BlockPos,VoxelShape}`, `viaversion/MixinPacketWrapper` | 1 / 5 / 7 | Skipped with a `[WARN] mixin target not present` in every run this repo has ever made. With Sodium or Lithium installed they become live targets, and they are the *only* LB mixins that combine an added static field with a non-MC target (`FULL_BRIGHT_LIGHTMAP`, `MAX_LIGHT_LEVEL`) or a mixin whose Java superclass is a real class (`extends ChunkAwareBlockCollisionSweeper`) plus `@Local`. Zero coverage. | n/a (never reached) | install Sodium+Lithium in `$GAME_DIR/mods` and re-run `tools/world-join-test.sh liquidbounce`. INFERRED. |
| **H7** | `MixinEntity` → `Entity`; `MixinChatComponent` → `ChatComponent` | 6 — MixinExtras `@Local` needing LVT | The 2026-07-25 `LVTGeneratorError` shape: a MixinExtras `@Local` on a class with no LVT whose target method the AW widened. These are the **only 2** of LB's 31 `@Local` mixins whose target has an `accessible method` line in `liquidbounce.accesswidener`. Fixed by `reconcileClassInfoWithAw`; measured live as `ClassInfo AW reconcile: 57 member access flag(s) re-synced across 23 already-cached class(es)` in both runs. | **YES**, fails closed — a phase-A failure is `MIXIN_TRANSFORM_FAILURE`, fatal, and phase A is load-order independent (pristine bytes). | VERIFIED-clean. Regress by reverting `reconcileClassInfoWithAw` and re-running. |
| **H8** | `MixinMouseHandlerAccessor` → `MouseHandler$LastClick`; `MixinEntityFluidInteractionTrackerAccessor` → `EntityFluidInteraction$Tracker` | 2 / 3 | The two shapes commits `6bdb3a5` and `f5fd360` were written for, and they each fire **exactly once** in LB: collapse declined for the package-private `EntityFluidInteraction$Tracker`, and `setLastClick(LMouseHandler$LastClick;)V` boxed through `DuckDispatch.invoke` from `AbstractContainerScreen$$Sidecar` because that class cannot name the type. Single instances mean a regression here has a sample size of one. | **YES** — `TypeFlowGate` (fatal on sidecars) and the access gate are what catch the failure mode | VERIFIED — both log lines present, identically, in both runs. |
| **H9** | `MixinHudAccessor` (x2), `MixinRenderSetupAccessor`, `MixinStyleAccessor` | 3 — static `@Accessor`/`@Invoker` | 4 payload sites do `INVOKESTATIC` **onto the duck interface itself** (`MixinHudAccessor.getHotbarSelectionTexture()`, `.getScoreboardEntryComparator()`, `MixinRenderSetupAccessor.liquid_bounce$invokeInit(…)` — a constructor invoker — and `MixinStyleAccessor.create(…)`). `rewriteCaller` only handles `INVOKEINTERFACE` on a duck owner, so these are `AccessorBridgeRewriter`'s job alone. | **YES** — `detectAccessorStubs` / `ACCESSOR_STUB` is fatal | VERIFIED-clean: `payload caller preflight: 4250 classes, 157/160 rewritten, 0 failed`, no `ACCESSOR_STUB`. `DuckScan.java` enumerates the 4 sites. |
| **H10** | any payload class calling `super.<protected MC method>()` | 4 — super calls | `rewritePayloadAw` lowers `INVOKESPECIAL` to `AwReflect.inv` whenever `methodNeedsReflect` is true, and `AwReflect.inv` uses `Method.invoke`, which **re-dispatches virtually**. The `findSpecial` fix that closed this lives in `rewriteRefs`'s sidecar branch only (`RetransformConverter:1077-1095`); **the caller path has no equivalent**. A payload subclass's `super.protectedMethod()` into an already-loaded MC superclass would become `this.protectedMethod()` — silent infinite recursion. | **NO** | `SuperScan.java`. **Ran it: 0 instances in LB.** Positive control fires (12 `super.` invokespecial sites onto MC methods exist — all onto **public** methods, so `methodNeedsReflect` is false for every one). VERIFIED-clean, mechanism VERIFIED. |
| **H11** | any WHOLE target that loads during the attach window | 7 — load order | `preLoaded` is snapshotted before staging, but staging + phase A load a lot: `refreshed attach-window classes: +225` (title run) / `+283` (preload run). A target that loads inside that window is still classified future-loaded and staged WHOLE; the CFT then finds `c != null`, logs `whole target … was already loaded … (inert this session)` and returns `null`, so the class loads with **no** LB hooks. | **YES, since 2026-07-27** — swept for directly in `activateLoadedTargets` (loaded AND absent from `PUBLISHED`) and reported through `inert()`. Was: `[WARN]` only, not even a verifier issue, and only ever reached when something happened to retransform the class. | grep for `whole target .* was already loaded`. **Ran it: 0 occurrences in both runs.** VERIFIED-clean, mechanism VERIFIED. |
| **H12** | `INACC` growth over a session | 2 | 6 of LB's 7 AW-widened classes are non-public. Only 3 are loaded at a title-screen attach (`Hud$ContextualInfo`, `GuiRenderer$Draw`, `GuiRenderState$Node`). `MouseHandler$LastClick`, `ItemCooldowns$CooldownInstance` and `GuiGraphicsExtractor$ScissorStack` join `INACC` as soon as they load (a mouse click / an item cooldown / a scissored GUI draw), and each new entry forces more erasure and boxing into the sidecars — the shapes that produced `f5fd360`. | **YES** — new bad shapes hit `TypeFlowGate` (fatal on sidecars) and the access gate, so the injection aborts rather than crashing. User-visible as a failed injection, not a crash. | attach mid-session after clicking/using an item, and diff the `inaccessible already-loaded AW classes:` line. INFERRED that these three do load in-session — they were still absent after this audit's world join + inventory open, because `INACC` is only sampled at attach. |

---

## 3. Category-by-category result

### Class 1 — inheritance edge, WHOLE vs converted (`540fbc5`)

**The fix covers every such pair LB has. VERIFIED, and the coverage is exhaustive rather than sampled.**

There are exactly **41** inheritance edges where both ends are LB targets (computed from the 26.2 class hierarchy over
the 151 present targets; `an.py`, `edges.json`). At a title-screen attach, **3** of them are the dangerous asymmetry —
WHOLE subclass over converted superclass:

| edge | redirect emitted? |
|---|---|
| `KeyboardInput` → `ClientInput` | **yes, 2 sites** — `set$initial`, `set$untransformed`. This is the bug `540fbc5` fixed. |
| `ConnectScreen` → `Screen` | no — and correctly so |
| `DisconnectedScreen` → `Screen` | no — and correctly so |

The last two are **confirmed safe by content, not by luck**: byte-comparing every `.whole-in.class` against its
`.whole-out.class` shows `KeyboardInput` is the *only* one of the 31 WHOLE targets `rewriteWholeTarget` changed, and
disassembling `ConnectScreen`/`DisconnectedScreen` shows their handler bodies reference no member of `Screen`'s sidecar
at all (`Screen`'s `$State` is empty; `MixinScreen` adds methods and no instance fields). VERIFIED.

The other 38 edges are converted-over-converted, where `gLookup`'s superclass walk has always handled the reference.
The deepest cluster is `LocalPlayer → AbstractClientPlayer → Player → Avatar → LivingEntity → Entity`, six chained
targets each with relocated members. It cannot degrade: `MixinLocalPlayer` adds `LocalPlayerAddition`, so `addsIface`
pins `LocalPlayer` to the converted path whatever the load order, and every class above it in that chain is loaded
before the title screen finishes.

**The asymmetry constraint holds in the direction the brief describes.** `addsIface` ⇒ never WHOLE, and 14 of the 151
targets add an interface. A "WHOLE superclass over converted subclass" edge would need the superclass unloaded (hence
the subclass unloaded) *and* the subclass adding an interface; **no such pair exists in LB** — none of the 14
interface-adding targets has an LB-target superclass that is WHOLE-eligible. INFERRED from the edge table; the code
path for it (`gLookup` finding a member the WHOLE class kept) has no instance to measure.

### Class 2 — inaccessible types

Live instances: H1 (structural gap, 0 LB instances), H4 (**one real, engine-diagnosed, non-fatal-by-design**), H8, H12.
The `pre-publish access gate: 151 conversions checked, 0 illegal member access(es)` in both runs.

### Class 3 — duck interfaces

28 entries in `ifaceMap`: 14 from `implements` on a mixin, 14 from `@Accessor`/`@Invoker` interface mixins. LB has
**zero shared duck interfaces** (no `shared duck interfaces` line is ever logged), so every one is pure-duck.
26 of 28 collapse to a single public implementor (all 14 accessor interfaces are single-implementor; 13 of their
targets are public, and 13 of the 14 `implements` ducks likewise). The two that do not are H5 (`GuiMessageLineAddition`,
two implementors) and H8 (`MixinEntityFluidInteractionTrackerAccessor`, whose sole implementor `EntityFluidInteraction$Tracker`
is package-private). Separately, `MixinMouseHandlerAccessor` *does* collapse but one of its methods' **descriptors**
names the package-private `MouseHandler$LastClick`, which is the `f5fd360` boxing case. `DuckScan.java` over all 4250 payload classes finds **no** `INDY` bootstrap handle, no
`INDY` type argument, no `LDC` class constant and no non-interface invoke onto a duck interface other than the 4
static-accessor sites of H9 — i.e. **the method-ref/method-handle-onto-a-duck-interface category is empty in LB.**
VERIFIED.

### Class 4 — `super` calls in relocated bodies

**Empty in LiquidBounce.** Two independent confirmations:

* **From the emitted bytes:** `grep -al 'smi\$'` over all 300 dumped classes returns **zero**. The engine emits an
  `smi$` bridge for exactly this shape, so zero bridges means zero sites. (Controls in the same scan: `rmi$` 12 files,
  `refGet$` 35, `AwReflect` 19, `DuckDispatch` 2 — the scan is not blind.)
* **From the mixin sources:** an ASM sweep for `INVOKESPECIAL` to a non-`<init>` method of another class across all 168
  mixin classes finds exactly one hit, `MixinLevelLoadingScreen.init` → `MixinScreen.handler$bhm003$init`. That is a
  call to a **super-mixin's handler**, not a vanilla super; the emitted sidecar routes it to
  `Screen$$Sidecar$liquidbounce.h$handler$bhm003$init`, which is the correct cross-target redirect, not a super call.

On the shapes the fix *would* have to cover: the branch predicate is `isAssignable(targetInternal, mi.owner)`, which is
access-agnostic and BFS-walks interfaces as well as superclasses, so **protected super targets and `Iface.super.m()`
are both covered** and both precede the `AwReflect` lowering. VERIFIED by reading; no LB instance to measure.
The uncovered variant is H10, and it is on the *caller* path, not the sidecar path.

### Class 5 — static fields / `<clinit>` ordering

**9** mixins add a static field, and the same 9 declare a `<clinit>` — the delta is copied into the sidecar's
`<clinit>` (`copyClinitDeltaToSidecar`). Spot-checked end to end: `MixinGui`'s `RECURSIVE_SCREEN_OPENING` appears on
`Gui$$Sidecar$liquidbounce` as `public static ScopedValue` and the sidecar's `<clinit>` really contains
`invokestatic ScopedValue.newInstance / putstatic RECURSIVE_SCREEN_OPENING`. VERIFIED.

Two of the 9 sit on WHOLE targets (`MixinUtils`/`ai.djl.util.Utils`, `MixinSignText`/`SignText`) where the field stays
on the class and its `<clinit>` runs naturally on load. No ordering hazard found: the sidecar `<clinit>` runs on first
sidecar touch, which is always at or after the first hook execution. **This category is clean.**

### Class 6 — `@Overwrite` / `@Redirect` / `@ModifyConstant`, and `@Local`/`wrapOperation` LVT

Injector census over all 168 mixin classes: `@Inject` 222, `@ModifyExpressionValue` 116, `@WrapOperation` 42,
`@ModifyReturnValue` 32, `@ModifyArg` 15, `@WrapWithCondition` 11, `@ModifyVariable` 9, `@ModifyArgs` 5, `@WrapMethod` 5,
`@ModifyReceiver` 4, `@ModifyConstant` 3, `@Redirect` 1. `@Overwrite` on 7 mixins
(`MixinBlockGetter.clip`, `MixinMutableComponent.append`, `MixinTextColor.hashCode`,
`MixinStringUtil.isAllowedChatCharacter`, `MixinVec3i.distManhattan`, `MixinCallerSensitiveDetection.isCallerSensitive`,
`MixinTruffleLanguage.isThreadAccessAllowed`), all on converted targets, all body-only and therefore
retransform-legal — `verifyRetransform`'s schema check passes on every one.

**This category is empty as a *late-attach* hazard.** All of these are applied in phase A, against pristine bytes, by
the live Mixin, before any load-order question arises; the output is identical whether the target is loaded or not.
The one genuine late-attach interaction is the AccessWidener→`ClassInfo` staleness that feeds MixinExtras' LVT
resolution, which is H7, is fixed, and fails closed.

### Class 7 — load-order sensitivity

**137** of the 151 present targets add no interface and are therefore WHOLE-eligible. At a title-screen attach —
the earliest possible attach, and therefore the **maximal** WHOLE set — 31 take the WHOLE branch and 106 are already
loaded. Since `preLoaded` only grows, a user attaching in-session sees a **subset** of those 31 stay WHOLE; everything
else flips to converted. That flip is the whole of the variable, and it had never been produced for 30 of the 31.

**This audit produced it.** Forcing all 28 loadable WHOLE targets into memory before attach and re-running:

| | title screen (`2026-07-27-7`) | **all 28 preloaded (this audit)** |
|---|---|---|
| targets WHOLE | 31 | **3** (the 3 payload-bundled Truffle/DJL classes, which no preload can reach) |
| already-loaded targets converted | 110 | **138** |
| sidecars eager-defined | 110 | 139 |
| relocated fields registered | 29 | 33 |
| `pre-publish access gate` | 151 checked, 0 illegal | 151 checked, **0 illegal** |
| `type-flow gate` | 120 classes / 1131 methods | 148 classes / **1332 methods** |
| `[ERROR]` lines | 0 | 0 |
| `SIDECAR_LINKAGE` | 234/234 (from the `540fbc5` verification run recorded in `docs/multi-payload.md`; it is a harness assertion, not an engine log line, so it is not in `2026-07-27-7`) | **290 sidecars, 0 VerifyError/LinkageError** |
| verdict | PASS | **PASS** — world joined, inventory opened and rendered, positive control fired |

So the ~28 previously-untested conversions produce sidecars that pass every gate and verify. **VERIFIED.** That is the
single largest INFERRED block in this audit turned into a measurement, and it is the main reason the flagged list below
is short.

What that run does **not** cover, and what therefore stays open in this category:

* the multiplayer path — `ConnectScreen`, `AddressCheck`, `ServerNameResolver`, `ClientIntentionPacket`, `PacketType`,
  `ServerStatusPinger$1`, `DisconnectedScreen`. Their sidecars link, but nothing executed them; and
  `ServerNameResolver`'s conversion was never published at all in that run (H3).
* `SignText` (sign editing), `CommandSuggestions` (chat tab-completion), `EditBox`,
  `ServerboundUseItem{,On}Packet` (block/item use), `LocatorBar`, `EntityHitboxDebugRenderer`,
  `VulkanRenderPass` (only under the Vulkan backend).
* the 3 targets that *stay* WHOLE — `ai/djl/util/Utils`, `com/oracle/truffle/js/…/CommonJSRequireBuiltin`,
  `NpmCompatibleESModuleLoader` — which load only when LB actually runs a script or DJL. Their WHOLE bytes are
  clean by H1/H2, but nothing has ever executed them.

Interface targets are worth one line here: `AddressCheck` is an **interface** that is WHOLE at a title-screen attach
and converted in-session (its mixin adds a `private static` `@WrapOperation` handler). Its converted form was produced
and linked cleanly in this audit's run. `BlockGetter` is the other interface target and adds nothing.

---

## 4. Clean statement — coverage

Of the **165** mixins in `liquidbounce.mixins.json` (168 classes in the package):

* **151 targets present** were examined individually along seven axes — added instance fields, added static fields and
  `<clinit>`, added interfaces, `@Shadow`/`@Accessor`/`@Invoker` shape, injector annotations, `INVOKESPECIAL` shape, and
  every field/method/`INDY`-handle/`LDC` reference out of the compiled mixin — plus, for the 31 WHOLE ones, the actual
  published bytes.
* **16 mixins are named in the table above** — `MixinHud` (H4); `MixinGuiMessage`, `MixinGuiMessageLineVisible` (H5);
  the six Sodium/Lithium/ViaVersion compat mixins (H6); `MixinEntity`, `MixinChatComponent` (H7);
  `MixinMouseHandlerAccessor`, `MixinEntityFluidInteractionTrackerAccessor` (H8); `MixinHudAccessor`,
  `MixinRenderSetupAccessor`, `MixinStyleAccessor` (H9). A further five are named in §3 class 1
  (`MixinKeyboardInput`, `MixinClientInput`, `MixinScreen`, `MixinConnectScreen`, `MixinDisconnectedScreen`), of which
  only the first pair is a live hazard and it is already fixed. H1, H2, H3, H10, H11 and H12 are properties of a *class*
  of target rather than of one mixin.
* **The remaining ~149 mixins produced no hazard** under any of the seven categories. That is not "nothing was found so
  they must be fine": for each of the categories that turned out empty, a specific scan was run and returned zero with
  a working positive control — `smi$` for super calls (class 4), `WholeAccess` for AW-widened access from WHOLE bytes,
  `DuckScan` for non-instruction duck references, `SuperScan` for caller-path super calls, byte-diffing all 31
  `.whole-in`/`.whole-out` pairs for the inheritance edge, and `whole target … was already loaded` for the attach
  window.

Three of the seven categories are **empty in LiquidBounce**: class 4 (super calls in relocated bodies), class 5
(static/`<clinit>` ordering), and class 6 as a late-attach concern. Saying so plainly is a result: it means the
remaining effort belongs on the WHOLE publish path (H1, H2) and on the observability of soft failures (H3).

---

## 5. What is worth doing, if anything (opinion, not a plan)

1. **H3's severity plumbing is the cheapest real win.** A target going permanently inert is currently invisible to both
   harnesses. Either promote `TARGET_RETRANSFORM_FAILURE` to an `[ERROR]` log line, or make the harnesses read the JSON
   report rather than grepping the log. This is what let a 1-in-151 dead hook pass as PASS in this very audit.
   — **DONE, 2026-07-27, both.** See the box in §1.1. Doing only one of the two would not have been enough: the log
   channel alone misses an issue that is recorded and never logged, and the report channel alone was reading a stale
   file. Two things the fix turned up that this audit had not seen: the report path was regenerated per issue (so the
   one harness that already read the report was reading a snapshot of the injection's first issue), and the H11
   `[WARN]` fires on a *harmless* case too — a whole target that was published at load and is merely retransformed
   later by a second payload keeps its members (member-probed before/after: identical member sets on all 7 of Wurst's
   published-WHOLE targets). So H11's real predicate is "loaded AND never published", which is now swept for directly.
2. **H1 and H2 are the same shape of gap**: the WHOLE path is exempt from three gates on the argument that its bytes are
   "the real widened bytes", which is true of its declarations and false of its references. Neither is instantiated in
   LB today, but both are exactly how `540fbc5` got in — a WHOLE-only path with no gate over it.
3. **H4 is a decision already taken** (caller `TypeFlowGate` non-fatal, with a stated reason). Worth revisiting only if
   a payload ever gets its mixin classes defined; LB does not, Wurst does.
4. **H6** is pure coverage: installing Sodium + Lithium would exercise 6 mixins that no run in this repo has ever
   touched, for the cost of two files in `mods/`.

---

## Appendix — reproduce

```bash
# the WHOLE set is read straight out of a title-screen injection log:
zcat logs/2026-07-27-7.log.gz | grep -o 'defined WHOLE.*' \
  | tr -d '[]' | cut -d: -f2 | tr ',' '\n' | tr -d ' ' | sed 's|/|.|g' > /tmp/whole.txt

# the run this document's class-7 table comes from (one client, --no-pack reuses the packed agent).
# The 3 payload-bundled Truffle/DJL classes are dropped: they are not on the loader before injection.
PRELOAD_CLASSES="$(grep -v '^com.oracle\|^ai.djl' /tmp/whole.txt | paste -sd,)" \
  tools/world-join-test.sh --no-pack liquidbounce

# the artifacts every static claim is read out of
mcgraft inject ... -Dmcgraft.agent.dumpDir=/tmp/lbdump      # sidecars, $State, .whole-in/.whole-out
for f in /tmp/lbdump/*.whole-out.class; do cmp -s "${f%.whole-out.class}.whole-in.class" "$f" || echo CHANGED $f; done
grep -al 'smi\$' /tmp/lbdump/*.class                        # super-call bridges: expect none for LB
```

`Audit.java`, `WholeAccess.java`, `DuckScan.java`, `SuperScan.java`, `an.py`, `an2.py` in `/tmp/lbaudit-tool/` are the
scanners; none of them is committed, and each is ~100 lines of ASM.
