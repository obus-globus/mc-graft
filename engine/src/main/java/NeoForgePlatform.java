import org.spongepowered.asm.mixin.Mixins;                                  // FML's copy (same App-loader identity as the agent)
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.MixinService;
import java.io.*; import java.lang.instrument.Instrumentation; import java.lang.reflect.*;
import java.net.URL; import java.nio.file.*; import java.security.*; import java.util.*; import java.util.jar.*;
import mcgraft.rt.InjectionLogger;

/** NeoForge (FML 11 / TransformingClassLoader) late-attach platform.
 *
 *  FML's Sponge Mixin (MixinService/Mixins/MixinTransformer) and
 *  FMLMixinService all live on the SYSTEM/App classloader — the SAME loader the dynamically-attached agent lands on —
 *  so the Mixin types share one identity with the agent (like Fabric's Option B, but here for free). We therefore use
 *  the typed Mixin API directly (VanillaPlatform-style) and reach FMLMixinService.getMixinTransformer()/processor
 *  reflectively only where the concrete FML type isn't on the compile classpath.
 *
 *  Module system (the crux): MC is in named module `minecraft` on the TCL; sidecars CANNOT enter it (a package is owned
 *  by one module per loader). So the payload is staged onto an PayloadLoader (child of the TCL, unnamed module) and sidecars are
 *  RELOCATED (RetransformConverter.SIDECAR_PKG) into a payload-owned package defined on the PayloadLoader; a target' running on
 *  the TCL resolves its sidecar across the loader boundary via the TCL's fallbackClassLoader (= PayloadLoader). The relocated
 *  sidecar is cross-package from every MC target, so the converter's pkg(caller)!=pkg(owner) checks route ALL non-public
 *  MC access through AwReflect. The transformer input / diff baseline stay RAW (non-AW-widened) so oFieldAcc reflects
 *  the true (non-public) access of already-loaded targets. TCL.parentLoaders/fallback are in the unnamed module -> no
 *  module opens needed. */
final class NeoForgePlatform implements LoaderPlatform {
    static final String TCL_NAME = "net.neoforged.fml.classloading.transformation.TransformingClassLoader";
    // Package the relocated sidecars live in (must NOT be net/minecraft/* — that module is owned by the Transforming
    // ClassLoader). Neutral default; overridable early (before staging) via -Dmcgraft.sidecarPackage=some/pkg/ .
    static final String SIDECAR_PKG = normalizePkg(System.getProperty("mcgraft.sidecarPackage", "mcgraft/sc/"));
    static String normalizePkg(String p){ p=p.replace('.','/'); return p.endsWith("/")?p:p+"/"; }
    private final Instrumentation inst;
    private ClassLoader tcl;                         // TransformingClassLoader (owns net.minecraft.* in module `minecraft`)
    private ClassLoader agentLoader;                 // system/App loader (owns the agent + mcgraft.rt.* single identity + FML's Mixin)
    private PayloadLoader lb;                             // child-first LB loader (parent = TCL), holds LB + relocated sidecars
    private Object transformer;                      // org.spongepowered.asm.mixin.transformer.MixinTransformer (Object handle)
    private Object processor; private Field fTransformedCount, fErrorState;
    private Method mTransformClassBytes, mGenerateClass; private Object env;
    private Object aw; private Method awApply;
    private File agentJar;
    private final Map<String,byte[]> baseline = new HashMap<>();
    private final Set<String> ownedPkgs = new HashSet<>();
    private boolean lbActivated;

    NeoForgePlatform(Instrumentation inst){ this.inst = inst; }

    // ---- §1a: find the TransformingClassLoader via a loaded net.minecraft.* class -----------------------------------
    public ClassLoader targetLoader(){ if (tcl != null) return tcl; try {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            ClassLoader l = c.getClassLoader();
            if (c.getName().startsWith("net.minecraft.") && l != null && l.getClass().getName().equals(TCL_NAME)) { tcl = l; break; }
        }
        if (tcl == null) throw new IllegalStateException("TransformingClassLoader not found among loaded net.minecraft.* classes");
        agentLoader = NeoForgePlatform.class.getClassLoader();
        return tcl;
    } catch (Throwable t){ throw rt("targetLoader (TCL acquisition)", t); } }

    // ---- §1a/§1d/§3.3: stage the payload onto an PayloadLoader; install it as the TCL fallback + register parentLoaders -----------
    public Path stageBundle(Instrumentation inst, File agentJar) throws Exception {
        this.agentJar = agentJar;
        RetransformConverter.SIDECAR_PKG = SIDECAR_PKG;                          // relocate sidecars out of module `minecraft`
        Path tmp = Files.createTempDirectory("mcgraft-nf-"); tmp.toFile().deleteOnExit();
        List<URL> urls = new ArrayList<>(); Path lbBundle = null; int pushed = 0, skipped = 0;
        try (JarFile jf = new JarFile(agentJar)) { for (var en = jf.entries(); en.hasMoreElements();) { JarEntry e = en.nextElement(); String n = e.getName();
            if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
            String base = new File(n).getName();
            boolean isLb = base.equals("payload.jar");
            if (!isLb && isLoaderProvided(base)) { skipped++; continue; }         // asm/mixin/mixinextras -> NeoForge-provided (parent-first)
            Path o = tmp.resolve(base);
            try (InputStream in = jf.getInputStream(e)) { Files.copy(in, o, StandardCopyOption.REPLACE_EXISTING); } o.toFile().deleteOnExit();
            if (isLb) { lbBundle = o; scanPackages(o, ownedPkgs); }
            urls.add(o.toUri().toURL()); pushed++;
            // Stage this jar's bundled (jar-in-jar) dependencies too, onto the same PayloadLoader.
            for (java.nio.file.Path nested : FullInjectAgent.extractNestedJars(o, tmp)) {
                if (isLoaderProvided(nested.getFileName().toString())) { skipped++; continue; }
                scanPackages(nested, ownedPkgs); urls.add(nested.toUri().toURL()); pushed++;
            }
        } }
        if (lbBundle == null) throw new IllegalStateException("Bundled agent-libs/payload.jar not found");
        ownedPkgs.add(SIDECAR_PKG);                                              // relocated sidecars are defined + resolved here
        // Install PayloadLoader as the TCL's child-first fallback (chained to FML's original fallback) and register LB
        // packages in parentLoaders so FML's mixin byte reader locates the payload mixin classes during transformation.
        ClassLoader original = readLoaderField(tcl, "fallbackClassLoader");
        lb = new PayloadLoader(urls.toArray(new URL[0]), tcl, original, agentLoader, ownedPkgs);
        tcl.getClass().getMethod("setFallbackClassLoader", ClassLoader.class).invoke(tcl, lb);
        registerParentLoaders(tcl, lb);
        InjectionLogger.info("staged payload onto loader: "+pushed+" jar(s), "+skipped+" loader-provided skipped; ownedPkgs="+ownedPkgs.size()+"; TCL="+tcl);
        return lbBundle;
    }
    /** Hard identity-split hazards NeoForge provides itself — resolved parent-first via the TCL, never on the PayloadLoader.
     *  mcef is excluded too: the bundled Fabric-flavoured mcef jar's MCEF.<clinit> transitively references Sodium
     *  classes absent from sodium-neoforge, crashing the (stretch) browser stage; without it the payload skips the browser. */
    private static boolean isLoaderProvided(String base){ String b=base.toLowerCase();
        return b.startsWith("asm-")||b.startsWith("asm.")||b.contains("sponge-mixin")||b.contains("mixinextras")||b.contains("mcef"); }

    static void scanPackages(Path jar, Set<String> owned) {
        try (JarFile jf = new JarFile(jar.toFile())) { for (var en = jf.entries(); en.hasMoreElements();) {
            String n = en.nextElement().getName();
            if (n.endsWith(".class")) { int i = n.lastIndexOf('/'); if (i > 0) owned.add(n.substring(0, i + 1)); }
        } } catch (Exception ignored) {}
    }
    @SuppressWarnings("unchecked")
    private void registerParentLoaders(ClassLoader t, ClassLoader lb) throws Exception {
        Field f = declaredUp(t.getClass(), "parentLoaders"); f.setAccessible(true);
        Map<String,ClassLoader> cur = (Map<String,ClassLoader>) f.get(t);
        Map<String,ClassLoader> map = cur; boolean replace = false;
        try { map.put("__lb_probe__", lb); map.remove("__lb_probe__"); }
        catch (UnsupportedOperationException uoe) { map = new HashMap<>(cur); replace = true; }
        int n = 0;
        for (String pkg : ownedPkgs) { String dotted = (pkg.endsWith("/") ? pkg.substring(0, pkg.length()-1) : pkg).replace('/', '.');
            if (map.putIfAbsent(dotted, lb) == null) n++; }
        if (replace) f.set(t, map);
        InjectionLogger.info("registered "+n+" payload packages into TCL.parentLoaders");
    }
    private static ClassLoader readLoaderField(Object o, String name){ try { Field f = declaredUp(o.getClass(), name); f.setAccessible(true); return (ClassLoader) f.get(o); } catch(Throwable t){ return null; } }
    private static Field declaredUp(Class<?> k, String name) throws NoSuchFieldException { for (; k != null; k = k.getSuperclass()) { try { return k.getDeclaredField(name); } catch(NoSuchFieldException e){} } throw new NoSuchFieldException(name); }

    // ---- §1c/§3.2: register the payload configs into the LIVE FMLMixinService (required:false), acquire the live transformer -
    public void initMixin() throws Exception {
        PayloadConfig pc = PayloadConfig.get();
        if (pc.accessWidener != null) {
            byte[] awBytes = bundleResource(pc.accessWidener);
            if (awBytes == null) throw new IllegalStateException(pc.accessWidener+" not readable");
            aw = Class.forName("vspike.AccessWidener").getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(awBytes));
            awApply = aw.getClass().getMethod("apply", String.class, byte[].class);
        }

        Object svc = MixinService.getService();                                 // FMLMixinService (App loader)
        InjectionLogger.info("FML mixin service = "+svc.getClass().getName());
        Method addContent = findMethod(svc.getClass(), "addMixinConfigContent", String.class, byte[].class);
        // NeoForge runs FML-PATCHED MC classes, so the Fabric companions' injection points don't match and would fail
        // (skipping their whole target). Register the payload's NeoForge companion set (payload.mixins.neoforge) instead.
        for (String cfg : pc.neoforgeMixins) {
            byte[] cb = bundleResource(cfg);
            if (cb != null && addContent != null) addContent.invoke(svc, cfg, cb);
            Mixins.addConfiguration(cfg);
        }
        int downgraded = 0;
        for (Object cfg : Mixins.getConfigs()) {
            String name = (String) cfg.getClass().getMethod("getName").invoke(cfg);
            if (name == null || !pc.neoforgeMixins.contains(name)) continue;
            Object mixinConfig; try { mixinConfig = cfg.getClass().getMethod("getConfig").invoke(cfg); } catch(Throwable t){ continue; }
            if (mixinConfig == null) continue;
            try { Field req = mixinConfig.getClass().getDeclaredField("required"); req.setAccessible(true); req.setBoolean(mixinConfig, false); downgraded++; } catch(Throwable t){}
        }
        if (downgraded == 0) throw new IllegalStateException("required:false workaround failed: no payload config via Mixins.getConfigs()");
        InjectionLogger.info("registered payload mixin configs, downgraded "+downgraded+" to required:false");

        transformer = findMethod(svc.getClass(), "getMixinTransformer").invoke(svc);
        if (transformer == null) throw new IllegalStateException("FMLMixinService.getMixinTransformer() == null");
        Class<?> iTr = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer");
        mTransformClassBytes = iTr.getMethod("transformClassBytes", String.class, String.class, byte[].class); mTransformClassBytes.setAccessible(true);
        mGenerateClass = iTr.getMethod("generateClass", MixinEnvironment.class, String.class); mGenerateClass.setAccessible(true);
        env = MixinEnvironment.getCurrentEnvironment();
        Field fProc = declaredUp(transformer.getClass(), "processor"); fProc.setAccessible(true); processor = fProc.get(transformer);
        fTransformedCount = declaredUp(processor.getClass(), "transformedCount"); fTransformedCount.setAccessible(true);
        try { fErrorState = declaredUp(processor.getClass(), "errorState"); fErrorState.setAccessible(true); }
        catch(Throwable t){ fErrorState = null; InjectionLogger.warn("Mixin processor 'errorState' field not found ("+t.getClass().getSimpleName()+"); a Fabric-incompatible mixin skip on NeoForge will fail loud instead of recovering"); }
        InjectionLogger.info("acquired live FML transformer "+transformer.getClass().getName()+" (transformedCount="+fTransformedCount.getInt(processor)+")");
    }

    // ---- §1c: baselines (X_others) captured while the payload's late-added config is UNVISITED -------------------------------
    public void prepareBaselines(java.util.List<String> internalTargets) throws Exception {
        if (lbActivated) throw new IllegalStateException("prepareBaselines after payload already activated");
        int n = 0;
        for (String internal : internalTargets) {
            byte[] pre = rawBytes(internal); if (pre == null) continue;
            byte[] xOthers;
            try { xOthers = (byte[]) mTransformClassBytes.invoke(transformer, internal.replace('/','.'), internal.replace('/','.'), pre); }
            catch (Throwable t) {
                // Another mod's mixin whose injection point diverges on this already-loaded target throws here. Either
                // way it is logged. Default is FAIL-FAST: log an error naming the target + abort. With
                // tolerateBaselineFailures=true, log a warning and skip just this target (its transform() then falls back
                // to originalO) so one bad mod mixin doesn't kill the whole attach.
                Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                String head = internal+" -> "+c.getClass().getSimpleName()+": "+String.valueOf(c.getMessage()).split("\n")[0];
                if (!FullInjectAgent.tolerateBaselineFailures) {
                    InjectionLogger.error("baseline capture FAILED for "+head+" — aborting injection (enable tolerateBaselineFailures / tick 'Tolerate other-mod mixin failures' to skip it and continue)");
                    if (t instanceof Error e) throw e; if (t instanceof Exception e) throw e; throw new RuntimeException(t);
                }
                InjectionLogger.warn("baseline capture skipped (tolerated) for "+head);
                continue;
            }
            baseline.put(internal, xOthers == null ? pre : xOthers); n++;
        }
        InjectionLogger.info("captured "+n+" non-LB mod baselines (X_others)");
    }

    // ---- §1c: X for a target = X_all after forcing the payload's config to select; delta isolated against X_others ----------
    public byte[] transform(String dotted, byte[] originalO) throws Exception {
        String internal = dotted.replace('.','/');
        byte[] in = rawBytes(internal); if (in == null) in = originalO; if (in == null) return null;
        fTransformedCount.setInt(processor, 0);                                  // probe workaround: promote LB's pending config
        lbActivated = true;
        byte[] X;
        try { X = (byte[]) mTransformClassBytes.invoke(transformer, dotted, dotted, in); }
        catch (Throwable t) {
            // A Fabric-distribution mixin whose target method diverges on NeoForge-patched MC throws a critical
            // InjectionError ("Scanned 0 target(s)"). Treat the whole target as UNCONVERTED (skip) rather than a hard
            // failure that would abort the transactional gate — BUT only if we can clear the processor's error state.
            // If the reset is unavailable or fails, the processor stays poisoned and every later transform would fail
            // spuriously, so fail loud here instead of silently corrupting the rest of the phase.
            boolean cleared=false;
            if (fErrorState != null) try { fErrorState.setBoolean(processor, false); cleared=true; } catch(Throwable ig){}
            Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
            if (!cleared) throw new IllegalStateException("Mixin processor errorState could not be cleared after "+internal
                +" failed ("+c.getClass().getSimpleName()+"); aborting to avoid poisoning subsequent transforms", c);
            InjectionLogger.warn("mixin skip "+internal+" -> "+c.getClass().getSimpleName()+": "+String.valueOf(c.getMessage()).split("\n")[0]);
            return null;
        }
        if (X == null) return null;
        byte[] base = originalO != null ? originalO : baseline.get(internal);
        if (base != null) X = FabricPlatform.alignSynthetics(base, X);
        return (base != null && Arrays.equals(X, base)) ? null : X;
    }
    public byte[] generateClass(String dotted){ try { return (byte[]) mGenerateClass.invoke(transformer, env, dotted); } catch(Throwable t){ return null; } }

    public Object accessWidener(){ return aw; }
    public boolean cftAppliesAw(){ return true; }                               // our CFT widens FUTURE MC classes (like vanilla)
    public byte[] applyAw(String internalName, byte[] bytes){ try { return (byte[]) awApply.invoke(aw, internalName, bytes); } catch(Throwable t){ return null; } }

    // ---- §1d: define relocated sidecar/state into the payload-owned package on the PayloadLoader; target' resolves via fallback
    public boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd){ try {
        lb.define(dotted, bytes); return true;
    } catch(Throwable t){ Throwable c = t.getCause()!=null?t.getCause():t; String m = String.valueOf(c.getMessage());
        if (c instanceof LinkageError && m.contains("duplicate")) return true;
        InjectionLogger.error("DEFINE-FAIL "+dotted+" -> "+c.getClass().getSimpleName()+": "+c.getMessage());
        return false; } }

    // ---- §1b: schema baseline O. mixin target -> X_others (loaded schema minus LB); else raw TCL bytes -------------
    public byte[] originalBytes(String internalName){ byte[] b = baseline.get(internalName); return b != null ? b : rawBytes(internalName); }
    private byte[] rawBytes(String internal){ try (InputStream in = tcl.getResourceAsStream(internal + ".class")) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; } }

    public byte[] bundleResource(String path){
        try (InputStream in = agentLoader.getResourceAsStream(path)) { if (in != null) return in.readAllBytes(); } catch(Throwable t){}
        try (InputStream in = lb != null ? lb.getResourceAsStream(path) : null) { return in==null?null:in.readAllBytes(); } catch(Throwable t){ return null; }
    }

    private static Method findMethod(Class<?> k, String name, Class<?>... params){ for (; k != null; k = k.getSuperclass()) { try { Method m = k.getDeclaredMethod(name, params); m.setAccessible(true); return m; } catch(NoSuchMethodException e){} } return null; }
    private static RuntimeException rt(String where, Throwable t){ Throwable c = t instanceof InvocationTargetException && t.getCause()!=null ? t.getCause() : t;
        return new IllegalStateException("NeoForgePlatform."+where+" failed: "+c.getClass().getSimpleName()+": "+c.getMessage(), c); }
}
