# mc-graft — TODO

## Extract `vspike` into its own thing
`mixinservice/` (package `vspike`) is the standalone Sponge-Mixin service mc-graft
uses on bare vanilla (its own `MixinService`/`AccessWidener`/bytecode provider,
plus `McefNative`). It's currently vendored inside this repo. Turn it into its own
standalone project/artifact that mc-graft depends on, so:
- it can be versioned + published independently,
- the MCEF native-staging helper (`vspike.McefNative`, an optional payload-native
  stager) lives with it rather than in the injection engine, and
- the engine depends on a clean published coordinate instead of a sibling source set.

## Notes
- The MCEF native staging is currently the one remaining payload-specific helper
  (it targets LiquidBounce's in-game CEF browser). It's opt-in (only fires when the
  native is bundled), but as part of the vspike extraction it should be generalized
  into a payload-declared "native library to stage" hook or dropped from the core.

## Duck-interface types in a payload's method SIGNATURES (Meteor `BetterChat`)

**Symptom.** Meteor Client (MC 26.1.2) injects cleanly through the whole converter —
179/179 targets, 0 verification issues, AW applied, ModContainer registered, its
`<clinit>` and `onInitializeClient()` run — but the client then NPEs in a `Minecraft.pick`
mixin handler on `Modules.get() == null` because Meteor's init doesn't fully complete. The
proximate cause is one class, `meteordevelopment.meteorclient.systems.modules.misc.BetterChat`.

**What's special about it.** `BetterChat` declares methods whose *parameter type* is a duck
interface, e.g. `drawTexture(GuiGraphicsExtractor, IGuiMessage, int, int)` and
`getSender(IGuiMessage, String)`, where `IGuiMessage` is a payload interface a Meteor mixin
adds to MC's `net.minecraft.client.multiplayer.chat.GuiMessage`. LiquidBounce never does
this — it only uses duck interfaces in method *bodies* (casts/calls), which are
retransform-legal body edits. Duck interfaces in *signatures* are a new payload pattern.

**Why it's hard.** Because retransform cannot add an interface to an already-loaded
`GuiMessage`, the converter drops `IGuiMessage` from `GuiMessage'` (so `GuiMessage` does NOT
implement `IGuiMessage` at runtime) and correctly rewrites duck *calls* to `DuckDispatch`.
For a method *signature* naming `IGuiMessage`, the converter erases the type to the concrete
target (`GuiMessage`) — but that changes the method *descriptor*, a schema change. The
verifier already treats this as legal (`verifyCaller` returns true, `verifySchema` normalizes
duck erasure), so producing the correct bytes is not the problem.

**The real blocker (root-caused with evidence, 2026-07-24).** The on-load CFT *does* fire for
`BetterChat` (after phase A, `ifaceMap=71`) and returns the correctly-erased bytes
(`IN.drawTex=(…IGuiMessage…)` → `OUT.drawTex=(…GuiMessage…)`, verifier-legal). Yet the
*loaded* class retains the original `drawTexture(IGuiMessage)` signature (confirmed by
reflection on the same KnotClassLoader). So **our JVM-instrumentation transformer's
schema-changing on-load output is not honored by the class Fabric Knot actually defines** —
Knot appears to define the class via a path that pre-empts or discards our schema change. The
subsequent recovery retransform (`FullInjectAgent` ~L187 / `activateLoadedTargets`) then sees
`IGuiMessage`→`GuiMessage` as a delete-a-method and fails (`retransformClasses` can't change a
loaded class's signatures), leaving `BetterChat` in its broken original form.

**What was tried and ruled out.**
- "Load-order" fix (arm the CFT before mixin-config processing so the rewrite lands at first
  definition — "Proposal A"): implemented, LB regression passed, but disproved its own
  premise — `BetterChat` loads *after* phase A, not early, so arming earlier changes nothing.
  Reverted (kept the tree clean).
- Applying the descriptor rewrite via retransform: impossible (JVM schema-change restriction).
- Keeping `IGuiMessage` a real type + wrapper/adapter so no erasure is needed: rejected — needs
  a whole-program wrap/unwrap dataflow pass and breaks object identity (`==`, identity maps,
  the sidecar `WeakIdentityHashMap` state keying).
- Redefine/reload the class under a new schema: impossible (`redefineClasses` has the same
  restriction; a fresh-loader copy is a different `Class` identity the existing references
  won't repoint to).

**Next step for whoever picks this up.** Investigate exactly *how* Fabric Knot
(`KnotClassDelegate` / `KnotClassLoader`) defines a non-target payload class, and why an
instrumentation `ClassFileLoadHook` transformer's schema change is lost. Hypotheses to test:
(a) the class is defined twice (a race / a Knot re-define) and the original-schema definition
wins; (b) Knot defines via a path (e.g. its own cached post-mixin bytes / a lookup-based
define) that our hook can't reshape. If it turns out schema-changing on-load rewrites are
fundamentally not honored on Knot, then duck-interface-in-signature payloads are inherently
incompatible with this late-attach model on Fabric, and that should be documented as a known
limitation. Reproduce with:
`./gradlew agentJar -Ppayload=<meteor.jar> -PbootstrapConfig=examples/meteor/bootstrap.properties`
against a bare Fabric 26.1.2 client (see the session harness under `/tmp/mc2612/`).

---

## fabric-api-dependent mods under late-attach (Xaero's Minimap)

Xaero's Minimap (fabric-26.2-26.4.2) injects and its CLIENT fully initialises (all 3 load stages)
and connects to a world. Getting there surfaced a series of generic late-attach gaps, now fixed:
- TCCL set to Knot while driving the transformer (ServiceLoader-based mixin-config plugins, e.g.
  xaerolib's IPlatformMixinHelper) — else the config-plugin throws mid-transform and cascades.
- null access-widener handling (Xaero declares none).
- bootstrap.reopenKeyBindings: ASM-strip fabric-api KeyMappingRegistryImpl's post-GameOptions guard.
- packer invokes bundled jar-in-jar dependency mods' entrypoints (xaerolib) before the payload's.
- bootstrap.bridgePayloadCodecs: bridge fabric-api's PayloadTypeRegistry into MC's live custom-payload
  codec dispatch (swap each dispatch codec's FallbackProvider) so mod packets encode/decode — WITHOUT
  applying fabric-api's own networking mixins. This eliminated the encode ClassCastException and the
  client now logs into the world.

### Root limitation: fabric-api's OWN mixins are not applied under late-attach
mc-graft applies the PAYLOAD's mixin configs, not its dependencies'. fabric-api normally patches MC at
startup via its own mixins (networking codec hooks, keybinding/options wiring, lifecycle events, ...).
Under late-attach those never ran, so any fabric-api runtime path a payload leans on is dormant/broken.
An opt-in `bootstrap.applyDependencyMixins=<filter>` was built (packer scans dep jars' mixin configs +
targets; engine registers them through the converter). Scoped to networking it CONVERTS cleanly (52
targets, 0 skipped) but then fabric-api's OWN runtime casts MC objects to interfaces its accessor
mixins add (e.g. ServerLoginPacketListenerImplAccessor) — and mc-graft only caller-rewrites the
payload's classes, not fabric-api's, so those casts throw. Making fabric-api fully correct under
late-attach ≈ re-implementing fabric-api's load-time contract inside the retransform engine
(caller-rewrite fabric-api's classes; and/or define future-loaded accessor targets whole — but the
BetterChat note above shows Knot may not honor schema-changing on-load output, which would kill the
latter). Judged a multi-week, open-ended effort; not pursued. `applyDependencyMixins` stays experimental
and default-off.

### Xaero remaining blocker: singleplayer server companion
With the codec bridge, Xaero connects to a world, but in SINGLEPLAYER the integrated server disconnects
on join: `xaero.common.events.CommonEvents.onPlayerWorldJoin` reads a null `serverData` (its server-side
session object). Xaero's server bootstrap that creates `serverData` depends on further fabric-api server
integration that did not fire under late-attach. Not fixed. (Multiplayer — client-only half + the codec
bridge — is untested here; no external 26.2 server available in the harness.)

### Xaero in-world: peeled to the fundamental descriptor blocker
Continuing past the codec bridge (which gets the client to log into a world), the singleplayer blockers
peeled one layer at a time, each fix working:
- serverData null -> fabric-api ServerLifecycleEvents.SERVER_STARTED never fired -> applied fabric-api's
  lifecycle mixin (applyDependencyMixins=lifecycle); the event then fires and Xaero's serverStarting runs.
- "Registry is not frozen yet" -> xaerolib's OWN mixin MixinMinecraftClientFabric (which drives its deferred
  loadCommonLater/freeze) was never applied -> applied xaerolib's mixin configs (applyDependencyMixins=xaerolib).
- ClassCastException ServerPlayer -> IServerPlayer -> xaerolib's runtime (ServerPlayerData) casts to a duck
  interface its mixin adds, and only the payload's classes were caller-rewritten -> added
  payload.callerRewrite.packages (caller-rewrite bundled-library classes, e.g. xaero/lib), which the packer
  auto-emits when a bundled library's mixins are applied.
- Then: DROPPED_INTERFACE_DESCRIPTOR on xaero/common/graphics/CustomRenderTypes — its methods take
  xaero/lib/client/graphics/ITextureBinding (a duck interface xaerolib's render mixin adds to an MC class) as a
  PARAMETER. Dropping that interface puts it in a method signature -> descriptor erasure -> schema change. This
  is the SAME fundamental limitation documented for Meteor above (duck interfaces in method signatures are
  inherently incompatible with the retransform model). Not a peelable layer — the architectural ceiling.

So Xaero's Minimap under late-attach: the CLIENT fully initialises and (with bridgePayloadCodecs) connects to a
world; full singleplayer in-world rendering is blocked by xaerolib's render-layer duck-interface-in-signature
use, which is the Meteor-class limit. New generic, opt-in machinery landed along the way (bridgePayloadCodecs,
applyDependencyMixins, payload.callerRewrite.packages) that helps fabric-api/bundled-library mods that do NOT
hit the descriptor blocker.

### Descriptor wall CROSSED (phantom + guarded dispatch); next Xaero blocker = reflective added-field
The DROPPED_INTERFACE_DESCRIPTOR limit (documented above as the architectural ceiling, hit by both Meteor
and Xaero) is crossed for the common SHARED-interface case. Insight (from a Fable design review): a
descriptor mentioning a dropped duck interface is actually LEGAL per JVMS 4.10.1.2 (interface refs verify
like Object); only the checked sites (checkcast/instanceof/invokeinterface/aastore) need rewriting. So a
mixin-added interface that a REAL bundle class ALSO implements ("shared", e.g. xaerolib's ITextureBinding)
is kept in descriptors (phantom) and its sites route through DuckDispatch, which now discriminates per
receiver (genuine implementor -> real interface method; de-implemented duck target -> sidecar). Classified
by scanning the bundle for genuine (non-@Mixin) implementors. Committed; LB unaffected; Xaero passes the
gate (preflight 0 failed) and logs into a world.

Remaining Xaero in-world blocker (orthogonal): xaero.common.core.XaeroMinimapCore.ensureField does
`LevelChunk.class.getDeclaredField("xaero_chunkClean")` + Field.set — a REFLECTIVE get/set of a @Unique
field its mixin adds to LevelChunk, which mc-graft relocates to the $State sidecar -> NoSuchFieldException
-> disconnect. It is a SINGLE field, on a FUTURE-loaded (not loaded at attach), interface-free target
(MixinWorldChunk adds only `boolean xaero_chunkClean`). The clean fix is "define future-loaded targets
whole" (review Option 2): a not-yet-loaded target can legally keep its added members at define-time, so
reflection finds them. This is a real converter change (per-target whole-vs-dropped model; global
GADDED/GFIELD/GIFACE must exclude whole targets' members; the new guarded DuckDispatch already handles a
whole target that genuinely implements a shared interface) with regression risk, plus likely further tail
(the "Unknown custom packet payload" config-phase warnings observed alongside). Not yet done.

### Xaero: ALL mc-graft late-attach barriers crossed — minimap renders in-world
Continuing "no banking", every remaining mc-graft-side blocker was crossed and Xaero's minimap actively
RENDERS map tiles in a live world:
- Descriptor wall -> phantom + guarded dispatch for shared duck interfaces (committed).
- serverData / freeze / IServerPlayer -> lifecycle + xaerolib dep-mixins + caller-rewrite (committed).
- Reflective added-field (xaero_chunkClean on the already-loaded LevelChunk) -> two new generic mechanisms:
  (a) FUTURE-loaded field-adding targets defined WHOLE (added members kept on the class); (b) for
  ALREADY-loaded targets, a reflection shim (mcgraft.rt.StateReflect) routing payload
  getDeclaredField/Field.get/set of a relocated field to the per-instance $State. Both committed, LB-safe.
Result: "New Xaero hud session initialized!" + MinimapWriter.writeChunk/writeTile/calculateBlockColors run
in-world. The client then crashes on a NoSuchMethodError — xaerolib calls
BlockStateModel.particleMaterial(BlockAndTintGetter,BlockPos,BlockState) but the test client's 26.2 jar has
the no-arg particleMaterial(); BlockStateModel is NOT a mixin target, so this is a genuine MC-snapshot API
mismatch (Xaero fabric-26.2-26.4.2 built against a later 26.2 than the harness jar) — environmental, would
crash the same way under normal Fabric. Not a mc-graft limitation.

### Xaero: particleMaterial was fabric-api (fixed); remaining = caller-rewrite fabric-api's own runtime
The in-world NoSuchMethodError (BlockStateModel.particleMaterial 3-arg) was NOT an MC-version mismatch — it is a
fabric-api method: a default on fabric-renderer-api's FabricBlockStateModel interface, injected into vanilla
BlockStateModel by fabric-renderer-api-v1's BlockStateModelMixin. Applying that module resolves it. Doing so pulled
in the section-batching redirect (needs a registered Renderer), fixed by a new packer feature: when a dependency
module's mixins are applied, its client entrypoint is invoked too (best-effort, separate payload.entrypoints.depClient)
— so indigo registers the active renderer. With lifecycle+xaerolib+renderer applied, indigo registered, the
"rendering plug-in" + particleMaterial errors are gone and "New Xaero hud session initialized!" runs in-world.
Remaining blocker: ClassCastException ClientLevel -> net.fabricmc.fabric.impl.event.lifecycle.LoadedChunksCache — a
duck interface fabric-api's lifecycle mixin adds to (already-loaded, dropped) ClientLevel, cast by fabric-api's OWN
runtime, which is not caller-rewritten (only the payload + bundled libraries are). Fixing it = caller-rewrite
fabric-api's own classes (index the resolved fabric-api dep jar into the caller-rewrite set) = the review's Option 3a,
the open-ended "port fabric-api's load-time contract to late-attach" effort, likely with further per-module cascades
(each module's runtime casts + AW needs). Not pursued yet.
