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
