import org.spongepowered.asm.mixin.Mixins;                                  // in the agent bundle; at runtime resolves to Knot's (shared, parent-delegated)
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
import java.io.*; import java.lang.instrument.Instrumentation; import java.lang.reflect.*;
import java.nio.file.*; import java.security.*; import java.util.*; import java.util.jar.*;
import mcgraft.rt.InjectionLogger;

/** Fabric (Knot) late-attach platform.
 *
 *  Cross-loader identity (Option B): the converter classes run on the SYSTEM loader (agent mechanism), but Sponge Mixin
 *  and ASM are on the process classpath and Knot delegates those packages to its parent, so they share one identity
 *  with the agent. Every Knot/Fabric/Mixin seam is therefore driven through REFLECTION on Object handles (the live
 *  transformer object is a Knot-visible MixinTransformer that we never cast to a system-loader type), and the payload + its
 *  runtime helpers (mcgraft.rt.*) resolve on Knot via parent delegation — a single mcgraft.rt.Platform identity for agent + the payload.
 *
 *  X-acquisition workaround (proven by the probe): a post-boot Mixins.addConfiguration is only applied by a direct
 *  transformClassBytes when (1) the payload configs are downgraded to required:false (else MixinTargetAlreadyLoadedException
 *  on already-loaded targets) and (2) MixinProcessor.transformedCount is reset to 0 before the transform (forces
 *  checkSelect -> select -> prepareConfigs to promote the pending config). Both are asserted; a Mixin version drift
 *  fails loud. */
final class FabricPlatform implements LoaderPlatform {
    private ClassLoader knot;               // KnotClassLoader (owns net.minecraft.* + staged LB)
    private Object launcher;                // FabricLauncher (Knot)
    private Object delegate;                // KnotClassDelegate (getRawClassBytes / getPreMixinClassBytes)
    private Object transformer;             // IMixinTransformer impl (Knot-visible; Object handle only)
    private Object processor;               // MixinProcessor (transformedCount reset)
    private Field  fTransformedCount;       // MixinProcessor.transformedCount (private int)
    private Method mTransformClassBytes;    // IMixinTransformer.transformClassBytes(String,String,byte[])
    private Method mGenerateClass;          // IMixinTransformer.generateClass(MixinEnvironment,String)
    private Object env;                     // MixinEnvironment.getCurrentEnvironment()
    private Method mGetRawBytes, mGetPreMixinBytes; // on the delegate
    private Method mDefineClassFwd;         // KnotClassLoader.defineClassFwd(String,byte[],int,int,CodeSource)
    private Method mAddToClassPath;         // FabricLauncher.addToClassPath(Path,String...)
    private Object aw;                      // vspike.AccessWidener (parsed as DATA for INACC/Resolver)
    private File agentJar;
    private final Map<String,byte[]> baseline = new HashMap<>();   // internal -> X_others (all mods except LB) = loaded schema
    private boolean payloadActivated;                                   // has the transformedCount reset selected the payload's config yet

    // ---- §1a: KnotClassLoader ---------------------------------------------------------------------------------------
    public ClassLoader targetLoader(){ try {
        Class<?> flb = fc("net.fabricmc.loader.impl.launch.FabricLauncherBase");
        launcher = flb.getMethod("getLauncher").invoke(null);
        if (launcher == null) throw new IllegalStateException("FabricLauncherBase.getLauncher() == null; not a live Knot launch");
        knot = (ClassLoader) launcher.getClass().getMethod("getTargetClassLoader").invoke(launcher);
        mAddToClassPath = launcher.getClass().getMethod("addToClassPath", Path.class, String[].class);
        Method getDelegate = null;                                                 // package-private on KnotClassLoader
        for (Class<?> k = knot.getClass(); k != null && getDelegate == null; k = k.getSuperclass()) { try { getDelegate = k.getDeclaredMethod("getDelegate"); } catch(NoSuchMethodException e){} }
        if (getDelegate == null) throw new NoSuchMethodException("getDelegate() on "+knot.getClass().getName());
        getDelegate.setAccessible(true);
        delegate = getDelegate.invoke(knot);
        mGetRawBytes     = findByteMethod(delegate, "getRawClassBytes");
        mGetPreMixinBytes= findByteMethod(delegate, "getPreMixinClassBytes");
        mDefineClassFwd  = knot.getClass().getMethod("defineClassFwd", String.class, byte[].class, int.class, int.class, CodeSource.class);
        mDefineClassFwd.setAccessible(true);                                        // method is public but KnotClassLoader itself is a package-private class
        return knot;
    } catch (Throwable t){ throw rt("targetLoader (Knot acquisition)", t); } }

    // ---- §3.2: stage the payload onto Knot (deps already on the stock classpath are skipped; ASM/Mixin never pushed) ----------
    public Path stageBundle(Instrumentation inst, File agentJar) throws Exception {
        this.agentJar = agentJar;
        exposeRuntimeToParent(agentJar);   // let Knot delegate mcgraft.rt.*/agent classes to the system loader (single identity)
        // basenames already on the process classpath -> pushing a second copy onto Knot would split class identity.
        Set<String> onCp = new HashSet<>();
        for (String p : System.getProperty("java.class.path","").split(File.pathSeparator)) { String b = new File(p).getName().toLowerCase(); if(!b.isEmpty()) onCp.add(b); }
        Path tmp = Files.createTempDirectory("mcgraft-fabric-"); tmp.toFile().deleteOnExit();
        Path payloadBundle = null; int pushed = 0, skipped = 0;
        try (JarFile jf = new JarFile(agentJar)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
            String base = new File(n).getName();
            boolean isPayload = base.equals("payload.jar");
            if (!isPayload && (onCp.contains(base.toLowerCase()) || isLoaderProvided(base))) { skipped++; continue; }   // fabric-api/kotlin/asm/mixin/... already present
            Path o = tmp.resolve(base);
            try (InputStream in = jf.getInputStream(e)) { Files.copy(in, o, StandardCopyOption.REPLACE_EXISTING); } o.toFile().deleteOnExit();
            if (isPayload) { payloadBundle = o; addToClassPath(o, payloadPrefixes(o).toArray(new String[0])); }
            else addToClassPath(o);
            pushed++;
            // Stage this jar's bundled (jar-in-jar) dependencies too — the Fabric loader would; late-attach must.
            for (Path nested : FullInjectAgent.extractNestedJars(o, tmp)) {
                String nb = nested.getFileName().toString();
                if (onCp.contains(nb.toLowerCase()) || isLoaderProvided(nb)) { skipped++; continue; }
                addToClassPath(nested); pushed++;
            }
        } }
        if (payloadBundle == null) throw new IllegalStateException("Bundled agent-libs/payload.jar not found");
        InjectionLogger.info("staged onto Knot: pushed "+pushed+" jar(s), skipped "+skipped+" already-present; Knot="+knot);
        return payloadBundle;
    }
    /** Hard identity-split hazards Fabric provides itself — never push these to Knot even if not on the -cp scan. */
    private static boolean isLoaderProvided(String base){ String b=base.toLowerCase();
        return b.startsWith("asm-")||b.startsWith("asm.")||b.contains("sponge-mixin")||b.contains("mixinextras")||b.startsWith("fabric-loader"); }
    private void addToClassPath(Path jar, String... prefixes) throws Exception { mAddToClassPath.invoke(launcher, jar, prefixes); }
    /** Top-level (2-segment) package prefixes the payload jar provides, so Knot exposes THE MOD'S OWN classes to the
     *  game (its entrypoint, mixin plugin, ...). Derived from the jar instead of hardcoding one mod's root package. */
    private static Set<String> payloadPrefixes(Path jar) throws Exception {
        Set<String> pk = new HashSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) { for (var en = jf.entries(); en.hasMoreElements();) {
            String n = en.nextElement().getName(); if (!n.endsWith(".class")) continue;
            int a = n.indexOf('/'); if (a < 0) continue; int b = n.indexOf('/', a + 1);
            pk.add((b < 0 ? n.substring(0, a) : n.substring(0, b)).replace('/', '.')); } }
        InjectionLogger.info("payload packages exposed to Knot: " + pk);
        return pk;
    }

    // ---- expose the injector runtime to Knot via PARENT delegation (NOT a second Knot-owned copy) --------------------
    // Sidecars run on Knot but reference mcgraft.rt.* helpers (AwReflect/DuckDispatch/JoinGate), which the agent loaded on the
    // SYSTEM loader. Knot's dev-mode isolation refuses to resolve them for game classes ("hasn't been exposed to the
    // game") -> NoClassDefFoundError the first time a late-loaded Knot class (e.g. DisconnectedScreen) links one.
    // Whitelisting the agent jar as a valid PARENT code source makes Knot's tryLoadClass/loadClass delegate those
    // classes to its parent (system) loader via parentClassLoader.loadClass instead of throwing -> a SINGLE identity
    // shared with the agent. This DELEGATES, never defineClass's a Knot-owned copy (that is what addToClassPath would
    // do, forking the stateful helpers into two identities). mcgraft.rt.* is confirmed absent from every jar pushed onto
    // Knot, so Knot can never own a copy -> the single-identity property is structural, not incidental.
    //
    // Blast radius: the whitelist is code-source-granular, so in principle ANY agent-jar-root class Knot fails to find
    // on its own classpath would now parent-delegate rather than throw. In practice this is inert: the root also holds
    // the agent's private Mixin/ASM/MixinExtras/vspike/converter copies, but those packages are Knot-owned (Fabric
    // provides Mixin+ASM; isLoaderProvided refuses to push the agent's) so isValidParentUrl is never consulted for
    // them, and the converter/vspike/agent classes are never referenced by game classes. Accepted latent risk: a class
    // present in the agent jar but absent from Knot's (same-major) Mixin/ASM would cross-delegate silently rather than
    // fail loud. Narrowing to parentSourcedClasses was rejected: that path can re-define from parent bytes (a second
    // identity), which would defeat the whole point for JoinGate/DuckDispatch.
    @SuppressWarnings("unchecked")
    private void exposeRuntimeToParent(File agentJar) throws Exception {
        Path agentPath = normalizeParentPath(agentJar.toPath());
        Field f = null;
        for (Class<?> k = delegate.getClass(); k != null && f == null; k = k.getSuperclass()) {
            try { f = k.getDeclaredField("validParentCodeSources"); } catch (NoSuchFieldException e) { /* walk up */ }
        }
        if (f == null) throw new NoSuchFieldException("validParentCodeSources on " + delegate.getClass().getName());
        f.setAccessible(true);
        // We publish the new set via a single reference write and rely on Knot's field being volatile for the
        // happens-before to its concurrent isValidParentUrl readers. Verify that assumption instead of trusting it: if
        // a future Knot makes the field non-volatile, surface it loudly rather than risk a torn/stale read landmine.
        if (!java.lang.reflect.Modifier.isVolatile(f.getModifiers()))
            InjectionLogger.warn("Knot.validParentCodeSources is NOT volatile on " + f.getDeclaringClass().getName()
                + "; single-identity parent delegation depends on safe publication of this field on this Fabric version");
        Set<Path> cur = (Set<Path>) f.get(delegate);
        // Rebuild the whole set and publish once via the volatile field (safe concurrent read; never mutated after).
        Set<Path> next = new HashSet<>();
        if (cur != null) next.addAll(cur);
        boolean added = next.add(agentPath);
        f.set(delegate, next);
        InjectionLogger.info("exposed injector runtime to Knot via parent delegation (" + (added ? "added " : "already present ")
            + agentPath + "; validParentCodeSources=" + next.size() + ")");
    }
    /** Normalize the same way Knot stores valid-parent paths, so isValidParentUrl's Path comparison matches. */
    private static Path normalizeParentPath(Path p) {
        try { return (Path) Class.forName("net.fabricmc.loader.impl.util.LoaderUtil")
            .getMethod("normalizeExistingPath", Path.class).invoke(null, p); }
        catch (Throwable t) { try { return p.toRealPath(); } catch (Exception e) { return p.toAbsolutePath().normalize(); } }
    }

    /** The namespace an AccessWidener declares in its header line ("accessWidener v2 &lt;namespace&gt;"). The live
     *  ClassTweaker reader remaps entries from this namespace to the running client's — so it must be read from the
     *  file, not hardcoded (an official-mapped build declares "named"/"official"; an intermediary one "intermediary"). */
    static String accessWidenerNamespace(byte[] awBytes) {
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(new ByteArrayInputStream(awBytes), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = br.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] tok = line.split("\\s+");
                // both the legacy AccessWidener ("accessWidener v2 <ns>") and the newer ClassTweaker
                // ("classTweaker v1 <ns>") headers put the namespace in the third whitespace/tab-separated token.
                if (tok.length >= 3 && (tok[0].equals("accessWidener") || tok[0].equals("classTweaker"))) return tok[2];
                break;   // first non-comment line must be the header
            }
        } catch (Throwable ignored) {}
        InjectionLogger.warn("could not read AccessWidener namespace from header; defaulting to 'named'");
        return "named";
    }

    // ---- §1c/§3.3: register the payload configs into the LIVE Knot Mixin (required:false), apply AW to Knot, grab transformer -
    public void initMixin() throws Exception {
        // 1) AccessWidener onto the live Knot ClassTweaker (widens FUTURE classes; already-loaded go via AwReflect).
        PayloadConfig pc = PayloadConfig.get();
        if (pc.accessWidener != null) {
            byte[] awBytes = bundleResource(pc.accessWidener);
            if (awBytes == null) throw new IllegalStateException(pc.accessWidener+" not readable from Knot/agent");
            Object loaderImpl = fc("net.fabricmc.loader.impl.FabricLoaderImpl").getField("INSTANCE").get(null);
            Object classTweaker = loaderImpl.getClass().getMethod("getClassTweaker").invoke(loaderImpl);
            Class<?> ctrCls = fc("net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader");
            Method create = null; for (Method m : ctrCls.getMethods()) if (m.getName().equals("create") && m.getParameterCount()==1) { create=m; break; }
            Object reader = create.invoke(null, classTweaker);
            String ns = accessWidenerNamespace(awBytes);   // read the namespace the payload's AW declares in its header
            reader.getClass().getMethod("read", byte[].class, String.class).invoke(reader, awBytes, ns);
            InjectionLogger.info("applied "+pc.accessWidener+" (namespace "+ns+") to live Knot ClassTweaker ("+awBytes.length+" bytes)");
        }

        // 2) register the payload's mixin configs into the live service, then downgrade them to required:false (probe
        //    workaround #1). Dependency-mod configs (e.g. fabric-api's own modules) are registered the same way, so the
        //    converter applies them to the already-loaded MC classes a late-attached fabric-api never got to patch.
        List<String> allConfigs = new ArrayList<>(pc.mixinConfigs);
        allConfigs.addAll(pc.depMixinConfigs);
        for (String c : allConfigs) { try { Mixins.addConfiguration(c); } catch (Throwable t) { InjectionLogger.warn("mixin config not registrable: "+c+" -> "+t.getClass().getSimpleName()+": "+t.getMessage()); } }
        int downgraded = 0, depConfigs = 0;
        for (Object cfg : Mixins.getConfigs()) {                                   // org.spongepowered.asm.mixin.transformer.Config
            String name = (String) cfg.getClass().getMethod("getName").invoke(cfg);
            if (name == null || !allConfigs.contains(name)) continue;
            Object mixinConfig = cfg.getClass().getMethod("getConfig").invoke(cfg); // IMixinConfig -> MixinConfig
            Field req = mixinConfig.getClass().getDeclaredField("required"); req.setAccessible(true); req.setBoolean(mixinConfig, false);
            downgraded++;
            if (pc.depMixinConfigs.contains(name)) depConfigs++;
        }
        if (downgraded == 0) throw new IllegalStateException("required:false workaround failed: no payload Config found via Mixins.getConfigs() (Mixin API drift)");
        InjectionLogger.info("registered "+downgraded+" mixin config(s) ("+depConfigs+" from dependencies), downgraded to required:false");

        // 3) acquire the LIVE transformer + processor.transformedCount + method handles (all Knot-visible, via reflection).
        Class<?> svcKnot = fc("net.fabricmc.loader.impl.launch.knot.MixinServiceKnot");
        Method getTr = svcKnot.getDeclaredMethod("getTransformer"); getTr.setAccessible(true);
        transformer = getTr.invoke(null);
        if (transformer == null) throw new IllegalStateException("MixinServiceKnot.getTransformer() == null");
        ClassLoader trLoader = transformer.getClass().getClassLoader();
        Class<?> iTr = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, trLoader);
        Class<?> meCls = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, trLoader);
        mTransformClassBytes = iTr.getMethod("transformClassBytes", String.class, String.class, byte[].class); mTransformClassBytes.setAccessible(true);
        mGenerateClass = iTr.getMethod("generateClass", meCls, String.class); mGenerateClass.setAccessible(true);
        env = meCls.getMethod("getCurrentEnvironment").invoke(null);
        Field fProc = transformer.getClass().getDeclaredField("processor"); fProc.setAccessible(true); processor = fProc.get(transformer);
        fTransformedCount = processor.getClass().getDeclaredField("transformedCount"); fTransformedCount.setAccessible(true);
        InjectionLogger.info("acquired live Knot transformer "+transformer.getClass().getName()+" (env="+env+")");
    }

    // ---- §1c: baselines (X_others) captured while the payload is UNVISITED, then the payload activated -------------------------------
    public void prepareBaselines(java.util.List<String> internalTargets) throws Exception {
        if (payloadActivated) throw new IllegalStateException("prepareBaselines after payload already activated");
        // On an IDLE Knot client (bare Fabric: nothing transformed since boot) processor.transformedCount is 0, so the
        // first baseline transform would trip Mixin's checkSelect (unvisited>0 && transformedCount==0) and PROMOTE our
        // still-pending payload config mid-baseline — contaminating X_others with the payload and erasing its delta in
        // phase A. Force the count non-zero so the baseline is captured with the payload unvisited; phase A re-selects
        // it deliberately via the setInt(0) in transform(). No-op on an active host (count already > 0).
        int tc0 = fTransformedCount.getInt(processor);
        if (tc0 == 0) { fTransformedCount.setInt(processor, 1); InjectionLogger.info("baseline: forced transformedCount 0 -> 1 (idle host; keeps payload config unvisited during X_others capture)"); }
        int n = 0;
        for (String internal : internalTargets) {
            byte[] pre = getPreMixinClassBytes(internal); if (pre == null) continue;
            byte[] xOthers;
            try { xOthers = driveTransform(internal.replace('/','.'), pre); } // the payload unvisited -> other mods only
            catch (Throwable t) {
                // Another mod's mixin whose injection point diverges on this already-loaded target throws here. Either
                // way it is logged. Default is FAIL-FAST: log an error naming the target + abort. With
                // tolerateBaselineFailures=true, log a warning and skip just this target (its transform() then falls back
                // to originalO) so one bad mod mixin doesn't kill the whole attach.
                Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                String head = internal+" -> "+causeChain(c);
                if (!FullInjectAgent.tolerateBaselineFailures) {
                    InjectionLogger.error("baseline capture FAILED for "+head+" — aborting injection (enable tolerateBaselineFailures / tick 'Tolerate other-mod mixin failures' to skip it and continue)");
                    // Mixin wraps the real fault in MixinTransformerError ("An unexpected critical error was
                    // encountered"), whose own message says nothing. Without the stack trace there is nothing to act on.
                    InjectionLogger.error("baseline capture cause for "+internal, c);
                    if (t instanceof Error e) throw e; if (t instanceof Exception e) throw e; throw new RuntimeException(t);
                }
                InjectionLogger.warn("baseline capture skipped (tolerated) for "+head);
                continue;
            }
            baseline.put(internal, xOthers == null ? pre : xOthers); n++;

        }
        InjectionLogger.info("captured "+n+" non-payload mod baselines (X_others)");
    }

    // ---- §1c: X for an already-loaded target = X_all (X_others + LB) after forcing the payload's config to select --------------
    public byte[] transform(String dotted, byte[] originalO) throws Exception {
        String internal = dotted.replace('.','/');
        byte[] in = getPreMixinClassBytes(internal);
        if (in == null) in = originalO;                                            // fall back to the raw baseline
        fTransformedCount.setInt(processor, 0);                                    // probe workaround #2: promote the payload's pending config
        payloadActivated = true;
        byte[] X = driveTransform(dotted, in);
        if (X == null) return null;
        byte[] base = originalO != null ? originalO : baseline.get(internal);      // X_others baseline
        if (base != null) X = alignSynthetics(base, X);                            // renumber other mods' counter-suffixed synthetics back to the baseline's names
        return (base != null && Arrays.equals(X, base)) ? null : X;               // isolate the payload's delta
    }

    /** Invoke the live Knot Mixin transformer with the Knot loader installed as the thread context classloader — as a
     *  normal Fabric mixin application would run. Under late-attach we drive the transformer from the attach thread,
     *  whose TCCL is the system loader; a mod whose mixin-config plugin resolves platform services via the single-arg
     *  ServiceLoader.load(Class) (context-CL based, e.g. xaerolib's IPlatformMixinHelper) would then find nothing and
     *  throw mid-transform — leaving Mixin's re-entrance lock stuck and cascading a failure across every later target.
     *  Making the TCCL faithful to apply-time lets those ServiceLoader lookups resolve against Knot's staged classpath. */
    private byte[] driveTransform(String dotted, byte[] in) throws Exception {
        Thread th = Thread.currentThread();
        ClassLoader prev = th.getContextClassLoader();
        th.setContextClassLoader(knot);
        try { return (byte[]) mTransformClassBytes.invoke(transformer, dotted, dotted, widen(dotted.replace('.','/'), in)); }
        finally { th.setContextClassLoader(prev); }
    }

    /** Apply the payload's AccessWidener to bytes before Mixin sees them.
     *
     *  <p>Mixin builds its {@code ClassInfo} metadata from whatever bytes it is handed. Knot's ClassTweaker widens
     *  classes as they LOAD, so a class already loaded before we registered the payload's widener yields un-widened
     *  pre-mixin bytes here — and then a mixin targeting a member the widener makes accessible fails to match, because
     *  {@code ClassInfo.findMethod} is looking at the private original. Mixin surfaces that as
     *  {@code LVTGeneratorError: Could not locate method metadata for <name>}.
     *
     *  <p>Concretely: Wurst widens {@code Minecraft.startUseItem()V} (private) and injects into it; on a production
     *  instance that is exactly this failure. {@code VSpikeBytecodeProvider} already does this on the vanilla path and
     *  its comment describes the same mechanism — the Fabric path simply never had the equivalent. Widening is
     *  idempotent, so applying it to bytes Knot already widened is a no-op. */
    private byte[] widen(String internal, byte[] in) {
        if (aw == null || in == null) return in;
        try {
            if (!(boolean) aw.getClass().getMethod("isTarget", String.class).invoke(aw, internal)) return in;
            byte[] out = (byte[]) aw.getClass().getMethod("apply", String.class, byte[].class).invoke(aw, internal, in);
            return out != null ? out : in;
        } catch (Throwable t) {
            InjectionLogger.warn("access-widening pre-mixin bytes failed for " + internal + " (continuing un-widened): " + rootOf(t));
            return in;
        }
    }

    private static String rootOf(Throwable t) {
        Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    /** MixinExtras (and other injectors) name their synthetic bridges `...$<methods.size()>`; that counter is a global
     *  post-pass over the fully-assembled class, so the payload's added methods shift EVERY other mod's bridge suffix between the
     *  no-payload baseline (X_others) and the with-LB output (X_all). Re-map X_all's non-payload counter-suffixed synthetics back
     *  to the baseline's names (matched by name-without-trailing-$digits + descriptor) so the converter's O-vs-X diff
     *  isolates only the payload's real additions. the payload's own synthetics keep their (unmatched) names and go to the sidecar. */
    static byte[] alignSynthetics(byte[] xOthers, byte[] xAll) {
        ClassNode co = read(xOthers), ca = read(xAll);
        Set<String> keysO = new HashSet<>(), keysA = new HashSet<>();
        for (MethodNode m : co.methods) keysO.add(m.name+" "+m.desc);
        for (MethodNode m : ca.methods) keysA.add(m.name+" "+m.desc);
        Map<String,ArrayDeque<String>> oNorm = new HashMap<>();                    // (normName+" "+desc) -> baseline names available to claim
        for (MethodNode m : co.methods) if (!keysA.contains(m.name+" "+m.desc) && isCounterSynthetic(m.name))
            oNorm.computeIfAbsent(norm(m.name)+" "+m.desc, k -> new ArrayDeque<>()).add(m.name);
        Map<String,String> rename = new HashMap<>();                              // X_all name -> baseline name
        for (MethodNode m : ca.methods) { String k=m.name+" "+m.desc; if (keysO.contains(k) || !isCounterSynthetic(m.name)) continue;
            ArrayDeque<String> dq = oNorm.get(norm(m.name)+" "+m.desc); if (dq!=null && !dq.isEmpty()) rename.put(m.name, dq.poll()); }
        if (rename.isEmpty()) return xAll;
        for (MethodNode m : ca.methods) {
            String nn = rename.get(m.name); if (nn != null) m.name = nn;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode mi && ca.name.equals(mi.owner)) { String r=rename.get(mi.name); if (r!=null) mi.name=r; }
                else if (insn instanceof InvokeDynamicInsnNode id) {
                    for (int i=0;i<id.bsmArgs.length;i++) if (id.bsmArgs[i] instanceof Handle h && ca.name.equals(h.getOwner())) {
                        String r=rename.get(h.getName()); if (r!=null) id.bsmArgs[i]=new Handle(h.getTag(),h.getOwner(),r,h.getDesc(),h.isInterface()); }
                }
            }
        }
        ClassWriter cw = new ClassWriter(0); ca.accept(cw); return cw.toByteArray();
    }
    private static ClassNode read(byte[] b){ ClassNode cn=new ClassNode(); new ClassReader(b).accept(cn,0); return cn; }
    private static final String[] SYN_MARKERS = {"mixinextras","wrapOperation","$bridge$","$wrapped$","handler$","redirect$","modify$","inject$"};
    /** An injector synthetic whose name carries one or more size()-based counter segments (which shift when the payload adds
     *  methods). Restricted to names bearing a known injector marker so ordinary lambdas ($N) are never re-mapped. */
    private static boolean isCounterSynthetic(String name){ boolean marked=false; for (String s:SYN_MARKERS) if (name.contains(s)){ marked=true; break; }
        return marked && !norm(name).equals(name); }
    /** Replace every whole `$<digits>` segment with `$#` so both counter positions in `...$wrapped$171$172` normalize. */
    private static String norm(String name){ return name.replaceAll("\\$\\d+(?=\\$|$)", "\\$#"); }
    public byte[] generateClass(String dotted){ try { return (byte[]) mGenerateClass.invoke(transformer, env, dotted); } catch(Throwable t){ return null; } }

    public Object accessWidener(){ try {
        String awName = PayloadConfig.get().accessWidener;
        if (awName == null) return null;                                            // payload declares no access-widener
        if (aw == null) { byte[] b = bundleResource(awName);
            aw = Class.forName("vspike.AccessWidener").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(b)); }
        return aw;
    } catch(Throwable t){ throw rt("accessWidener parse", t); } }

    public boolean cftAppliesAw(){ return false; }                                 // Knot's ClassTweaker widens future classes
    public byte[] applyAw(String internalName, byte[] bytes){ return null; }        // unused on Fabric (cftAppliesAw==false)

    // ---- §1d: define sidecar/state/synthetic into Knot via the public defineClassFwd -------------------------------
    public boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd){ try {
        CodeSource cs = pd == null ? null : pd.getCodeSource();
        mDefineClassFwd.invoke(knot, dotted, bytes, 0, bytes.length, cs);
        return true;
    } catch(Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        String m = String.valueOf(c.getMessage());
        if (c instanceof LinkageError && m.contains("duplicate")) return true;      // already defined -> fine
        if (c.getClass().getSimpleName().contains("LinkageError") && m.contains("duplicate class")) return true;
        InjectionLogger.error("DEFINE-FAIL "+dotted+" -> "+c.getClass().getSimpleName()+": "+c.getMessage());
        return false; } }

    // ---- §1b: schema baseline O. For a mixin target = X_others (loaded schema: all non-payload mods). For everything else
    //           (hierarchy metadata over arbitrary classes) = raw Knot bytes (no the payload mixin targets them, so X_others==raw). -
    public byte[] originalBytes(String internalName){ byte[] b = baseline.get(internalName); return b != null ? b : getRawClassBytes(internalName); }

    public byte[] bundleResource(String path){
        // agent-jar-root resources (lb-mixin-targets.txt) resolve on the system loader; payload-jar resources resolve on Knot.
        try (InputStream in = FabricPlatform.class.getClassLoader().getResourceAsStream(path)) { if (in != null) return in.readAllBytes(); } catch(Throwable t){}
        try (InputStream in = knot.getResourceAsStream(path)) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; }
    }

    /** Register the payload as a synthetic ModContainer in the live Fabric loader (opt-in via bootstrap.registerMod
     *  Container). Late-attach builds the loader's mod list before the payload exists, so a mod that reads its own
     *  metadata at init (FabricLoader.getModContainer(id).orElseThrow()) throws. Parse its fabric.mod.json into a
     *  LoaderModMetadata, wrap it in a ModCandidate/ModContainer, and insert into FabricLoaderImpl's modMap+mods.
     *  Best-effort + idempotent: any failure is logged, not fatal (the injection itself already succeeded). */
    public void registerPayloadMod(PayloadConfig cfg, Path payloadJar) {
        try {
            Class<?> FLI = fc("net.fabricmc.loader.impl.FabricLoaderImpl");
            Object inst = FLI.getField("INSTANCE").get(null);
            Object opt = FLI.getMethod("getModContainer", String.class).invoke(inst, cfg.id);
            if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                InjectionLogger.info("ModContainer '"+cfg.id+"' already present; skipping synthetic registration"); return;
            }
            byte[] fmj;   // read straight from the payload jar (precise — avoids other mods' fabric.mod.json on the loader)
            try (JarFile jf = new JarFile(payloadJar.toFile())) {
                JarEntry e = jf.getJarEntry("fabric.mod.json");
                if (e == null) throw new IllegalStateException("payload jar has no fabric.mod.json");
                try (InputStream in = jf.getInputStream(e)) { fmj = in.readAllBytes(); }
            }
            Class<?> parser = fc("net.fabricmc.loader.impl.metadata.ModMetadataParser");
            Class<?> voC = fc("net.fabricmc.loader.impl.metadata.VersionOverrides");
            Class<?> doC = fc("net.fabricmc.loader.impl.metadata.DependencyOverrides");
            Class<?> lmmC = fc("net.fabricmc.loader.impl.metadata.LoaderModMetadata");
            Object vo = voC.getConstructor().newInstance();
            Object dov = doC.getConstructor(Path.class).newInstance(Path.of(System.getProperty("java.io.tmpdir")));
            Object meta = parser.getMethod("parseMetadata", InputStream.class, String.class, List.class, voC, doC, boolean.class)
                .invoke(null, new ByteArrayInputStream(fmj), cfg.id, new ArrayList<String>(), vo, dov, false);
            Class<?> mcC = fc("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
            Method createPlain = mcC.getDeclaredMethod("createPlain", List.class, lmmC, boolean.class, java.util.Collection.class);
            createPlain.setAccessible(true);
            Object cand = createPlain.invoke(null, List.of(payloadJar), meta, false, List.of());
            Object container = fc("net.fabricmc.loader.impl.ModContainerImpl").getConstructor(mcC).newInstance(cand);
            Field modMapF = FLI.getDeclaredField("modMap"); modMapF.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,Object> modMap = (Map<String,Object>) modMapF.get(inst);
            modMap.put(cfg.id, container);
            Field modsF = FLI.getDeclaredField("mods"); modsF.setAccessible(true);
            @SuppressWarnings("unchecked") List<Object> mods = (List<Object>) modsF.get(inst);
            try { mods.add(container); }
            catch (UnsupportedOperationException e) { List<Object> nm = new ArrayList<>(mods); nm.add(container); modsF.set(inst, nm); }
            String ver = "?"; try { ver = String.valueOf(lmmC.getMethod("getVersion").invoke(meta)); } catch(Throwable ignore){}
            InjectionLogger.info("registered synthetic ModContainer for '"+cfg.id+"' ("+ver+")");
        } catch (Throwable t) {
            Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
            InjectionLogger.warn("registerModContainer for '"+cfg.id+"' failed (mod self-metadata lookups may throw): "+c.getClass().getSimpleName()+": "+c.getMessage());
        }
    }

    // ---- reflection helpers -----------------------------------------------------------------------------------------
    private byte[] getRawClassBytes(String internal){ try { return (byte[]) mGetRawBytes.invoke(delegate, internal); } catch(Throwable t){ return null; } }
    private byte[] getPreMixinClassBytes(String internal){ try { return (byte[]) mGetPreMixinBytes.invoke(delegate, internal); } catch(Throwable t){ return null; } }
    // ---- Bridge fabric-api's PayloadTypeRegistry into MC's live custom-payload codec dispatch -----------------------
    // MC's custom-payload StreamCodec (CustomPacketPayload$1) dispatches encode/decode by channel id via an immutable
    // `val$idToType` map (vanilla payloads only) + a `val$fallback` that yields DiscardedPayload for unknown ids —
    // whose codec then throws a ClassCastException when a mod tries to send its own payload. fabric-api normally wires
    // mod payloads in via its own networking MIXINS; under late-attach those were never applied. A mod's registration
    // into fabric-api's PayloadTypeRegistry DOES still succeed, so we simply swap each of MC's four dispatch codecs'
    // fallback for one that consults the paired fabric-api registry first (live, so registration order doesn't matter).
    @Override public void bridgePayloadCodecs() {
        try {
            Class<?> reg = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", false, knot);
            Class<?> fbC = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload$FallbackProvider", false, knot);
            Field ptF = reg.getDeclaredField("packetTypes"); ptF.setAccessible(true);
            // Each MC custom-payload dispatch codec paired with the fabric-api registries whose types it must resolve.
            // Clientbound splits its codec by phase (GAMEPLAY/CONFIG); serverbound has a single STREAM_CODEC used for
            // both phases, so it consults both serverbound registries.
            String[][] pairs = {
                {"net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", "GAMEPLAY_STREAM_CODEC", "CLIENTBOUND_PLAY"},
                {"net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", "CONFIG_STREAM_CODEC",   "CLIENTBOUND_CONFIGURATION"},
                {"net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket", "STREAM_CODEC",          "SERVERBOUND_PLAY", "SERVERBOUND_CONFIGURATION"},
            };
            int bridged = 0;
            for (String[] p : pairs) {
                try {
                    Object mcCodec = Class.forName(p[0], false, knot).getField(p[1]).get(null);
                    Object dispatch = unwrapDispatch(mcCodec, 0);
                    if (dispatch == null) { InjectionLogger.warn("payload codec bridge: no dispatch codec inside "+p[0]+"."+p[1]); continue; }
                    Map<?, ?>[] typeMaps = new Map<?, ?>[p.length - 2];
                    for (int i = 2; i < p.length; i++) typeMaps[i - 2] = (Map<?, ?>) ptF.get(reg.getField(p[i]).get(null));
                    Field fbF = dispatch.getClass().getDeclaredField("val$fallback"); fbF.setAccessible(true);
                    Object orig = fbF.get(dispatch);
                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(knot, new Class<?>[]{fbC}, new PayloadFallback(orig, typeMaps));
                    fbF.set(dispatch, proxy);
                    bridged++;
                } catch (Throwable t) {
                    InjectionLogger.warn("payload codec bridge skipped "+p[0]+"."+p[1]+": "+t.getClass().getSimpleName()+": "+t.getMessage());
                }
            }
            InjectionLogger.info("bridged "+bridged+"/3 custom-payload codec(s) to fabric-api PayloadTypeRegistry");
        } catch (Throwable t) {
            InjectionLogger.warn("bridgePayloadCodecs failed (mod custom packets may not encode): "+t.getClass().getSimpleName()+": "+t.getMessage());
        }
    }

    /** Walk a StreamCodec `.map(...)` wrapper chain to the underlying CustomPacketPayload$1 dispatch codec. */
    private Object unwrapDispatch(Object codec, int depth) throws Exception {
        if (codec == null || depth > 8) return null;
        if (codec.getClass().getName().equals("net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")) return codec;
        for (Field f : codec.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!f.getType().getName().equals("net.minecraft.network.codec.StreamCodec")) continue;
            f.setAccessible(true);
            Object inner = unwrapDispatch(f.get(codec), depth + 1);
            if (inner != null) return inner;
        }
        return null;
    }

    /** Fallback that resolves a mod-registered payload codec from fabric-api's registry, else defers to MC's original. */
    private static final class PayloadFallback implements java.lang.reflect.InvocationHandler {
        private final Object orig;          // MC's original FallbackProvider (yields DiscardedPayload for truly-unknown ids)
        private final Map<?, ?>[] typeMaps; // fabric-api PayloadTypeRegistryImpl.packetTypes: Identifier -> CustomPacketPayload$TypeAndCodec
        PayloadFallback(Object orig, Map<?, ?>[] typeMaps) { this.orig = orig; this.typeMaps = typeMaps; }
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if ("create".equals(m.getName()) && args != null && args.length == 1) {
                for (Map<?, ?> types : typeMaps) {
                    Object tac = types.get(args[0]);                     // TypeAndCodec for this channel id, if a mod registered it
                    if (tac != null) return tac.getClass().getMethod("codec").invoke(tac);
                }
            }
            try { return m.invoke(orig, args); }
            catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        }
    }

    private static Class<?> fc(String dotted) throws ClassNotFoundException { return Class.forName(dotted); }
    private static Method findByteMethod(Object target, String name){
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Method m = declared(k, name); if (m != null) { m.setAccessible(true); return m; }
            for (Class<?> ifc : k.getInterfaces()) { m = declared(ifc, name); if (m != null) { m.setAccessible(true); return m; } }
        }
        throw new IllegalStateException("byte method "+name+" not found on "+target.getClass().getName());
    }
    private static Method declared(Class<?> k, String name){ try { return k.getDeclaredMethod(name, String.class); } catch(NoSuchMethodException e){ return null; } }
    private static RuntimeException rt(String where, Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        return new IllegalStateException("FabricPlatform."+where+" failed: "+c.getClass().getSimpleName()+": "+c.getMessage(), c); }

    /** The whole cause chain, `A: msg <- B: msg <- ...`. Mixin reports failures as MixinTransformerError("An unexpected
     *  critical error was encountered"), which identifies nothing on its own — the actionable detail (which mixin, which
     *  injection point) is always in a nested cause, so logging only the outermost message is useless here. */
    static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 900; c = c.getCause()) {
            if (sb.length() > 0) sb.append(" <- ");
            String m = c.getMessage();
            sb.append(c.getClass().getSimpleName());
            if (m != null && !m.isBlank()) sb.append(": ").append(m.split("\n")[0]);
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }
}
