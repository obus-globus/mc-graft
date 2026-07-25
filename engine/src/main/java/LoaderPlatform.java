import java.io.File; import java.lang.instrument.Instrumentation; import java.nio.file.Path; import java.security.ProtectionDomain;

/** Host-side abstraction over the loader-specific seams FullInjectAgent needs. Vanilla stands up its OWN Sponge Mixin
 *  service on the SYSTEM loader (VanillaPlatform); Fabric/NeoForge defer into the live Knot/FML service on their own
 *  loader. The transactional convert/verify/publish
 *  algorithm, the CFT, the kick, and RetransformConverter stay in FullInjectAgent and drive these primitives. */
interface LoaderPlatform {
    /** Coupling #1: loader that holds net.minecraft.* and the staged the payload bundle (vanilla: system; Fabric: Knot;
     *  NeoForge: TransformingClassLoader). All Class.forName / kick resolution routes through this. */
    ClassLoader targetLoader();

    /** Coupling #2/#7: stage the bundled payload+deps onto the target loader and define the mcgraft.rt runtime helpers; returns
     *  the extracted payload jar (for caller preflight). agentJar is the agent's own jar. */
    Path stageBundle(Instrumentation inst, File agentJar) throws Exception;

    /** Coupling #3: stand up (vanilla) or acquire (modded) the Mixin service and register the payload configs. Call once,
     *  after stageBundle. The transformer itself stays loader-internal (Fabric's lives on Knot / behind reflection). */
    void initMixin() throws Exception;

    /** Coupling #3: (Fabric-only) pre-compute the schema baseline for each target while the payload's late-added config is still
     *  unvisited, so `originalBytes(target)` returns the "all OTHER mods applied, the payload not yet" plane. On Fabric the live
     *  Knot transformer re-applies the whole mod mixin stack (fabric-api/iris/sodium/...) on every call, so the diff
     *  baseline must be that stack MINUS the payload — otherwise every other mod's additions are misattributed to the payload. No-op on
     *  loaders whose transformer only carries the payload's mixins (vanilla). Call once, right before phase A. */
    default void prepareBaselines(java.util.List<String> internalTargets) throws Exception {}

    /** Coupling #3: mixin-transform a target and return X (the mixin output), or null if the mixin did not change the
     *  input (nothing to convert). `originalO` is the schema baseline (originalBytes); the platform chooses the actual
     *  transform INPUT (vanilla: originalO; Fabric: the pre-mixin/AW-applied plane) and hides its own IMixinTransformer,
     *  so this handle is loader-neutral (Fabric's transformer object is Knot-loaded, not castable on the system loader). */
    byte[] transform(String dotted, byte[] originalO) throws Exception;

    /** Coupling #3: generate a Mixin synthetic class by name (org.spongepowered.asm.synthetic.* / $Anonymous$), or null
     *  if none is produced (on Fabric Knot auto-generates most synthetics, so null is normal). */
    byte[] generateClass(String dotted);

    /** Coupling #4: the parsed AccessWidener (as data, for the already-loaded INACC/Resolver computation). */
    Object accessWidener();
    /** Coupling #4: whether the CFT applies the AccessWidener to FUTURE MC classes itself. Vanilla=true (it owns AW);
     *  Fabric/NeoForge=false (Knot ClassTweaker / FML AT widen future classes on load). */
    boolean cftAppliesAw();
    /** Coupling #4: widen bytes on-load (only meaningful when cftAppliesAw()); null if unchanged/failed. */
    byte[] applyAw(String internalName, byte[] bytes);

    /** Coupling #7: define a generated class (target'/sidecar/state/synthetic/runtime) into the target loader with the
     *  given ProtectionDomain. true on success or benign already-defined, false on a real ClassFormat/Verify defect. */
    boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd);

    /** Coupling #5: ORIGINAL (schema-baseline) bytes of a class by internal name (no ".class"); null if unavailable. */
    byte[] originalBytes(String internalName);

    /** Coupling #5: bytes as they were BEFORE the payload's AccessWidener was registered — the access flags an
     *  ALREADY-LOADED class still enforces, since retransformClasses cannot change member modifiers. Only Fabric
     *  differs from {@link #originalBytes}: Knot hands back pre-mixin bytes with the live ClassTweaker (hence our
     *  widener) already applied. Vanilla/NeoForge widen from our own CFT, so their originalBytes are never widened. */
    default byte[] rawBytes(String internalName){ return originalBytes(internalName); }

    /** Coupling #6: read an agent-bundle resource by path (e.g. lb-mixin-targets.txt); null if absent. */
    byte[] bundleResource(String path);

    /** (Fabric-only, opt-in) Register the payload as a synthetic ModContainer in the live Fabric loader so a mod that
     *  reads its OWN metadata at init — e.g. FabricLoader.getModContainer(id).orElseThrow().getMetadata() — resolves.
     *  Late-attach builds the loader's mod list before the payload exists, so without this such a lookup throws. No-op
     *  on loaders without a Fabric mod registry (vanilla/NeoForge). payloadJar is the staged payload jar path. */
    default void registerPayloadMod(PayloadConfig cfg, Path payloadJar) {}

    /** (Fabric-only, opt-in) Bridge fabric-api's PayloadTypeRegistry into MC's live custom-payload codec dispatch, so a
     *  mod that registers a custom packet type can encode/decode it under late-attach even though fabric-api's own
     *  networking mixins (which normally wire that up) were never applied. No-op on other loaders. */
    default void bridgePayloadCodecs() {}
}
