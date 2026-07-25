import org.objectweb.asm.ClassReader;
import java.io.*; import java.lang.instrument.*; import java.lang.reflect.Method;
import java.nio.file.*; import java.security.ProtectionDomain; import java.util.*; import java.util.concurrent.*; import java.util.jar.*;
import mcgraft.rt.InjectionLogger;

/** Attach to a fully-running client and inject the full payload mod. Uniform-convert every mixin target
 *  (already-loaded -> retransform; future -> on-load CFT returns target'); rewrite payload callers to sidecars;
 *  then manually kick the payload's start event so it initializes on the already-running game. */
public class FullInjectAgent {
    static LoaderPlatform PLATFORM;                                               // loader-specific seams (vanilla/Fabric/NeoForge)
    static ClassLoader SYS;                                                       // == PLATFORM.targetLoader(); holds net.minecraft.* + staged LB
    static final boolean DEBUG = Boolean.getBoolean("mcgraft.agent.debug");
    static final Set<String> definedSynth = ConcurrentHashMap.newKeySet();
    static final Set<String> definingSynth = ConcurrentHashMap.newKeySet();
    static final Map<String,Conv> convMap = new ConcurrentHashMap<>();             // internal -> conversion holder
    static final Map<String,List<String[]>> ifaceMap = new ConcurrentHashMap<>();  // iface -> all [target, sidecar]; read by concurrent CFT threads
    static final Set<String> targetSet = new HashSet<>();                          // internal names of mixin targets
    static Instrumentation INST;
    static volatile RetransformConverter.Resolver AW_RES;   // AW resolver (for the caller-rewrite diagnostic)
    static volatile boolean everInjected;                                         // true once injected; a second inject in the same JVM is refused — the payload can't be re-staged onto the already-populated loader. volatile: successive attaches may land on different attach-listener threads.
    static boolean tolerateBaselineFailures;                                      // agent arg tolerateBaselineFailures=true: on modded loaders, skip an other-mod target whose baseline transform throws instead of aborting the whole inject. Default OFF (fail-fast). Set once in agentmain before prepareBaselines, read on the same thread.
    static final Set<String> preLoaded = ConcurrentHashMap.newKeySet();            // MC classes loaded at attach (can't be AW-widened)
    static final Map<String,Boolean> npField = new ConcurrentHashMap<>();          // owner#name -> non-public?
    static final Map<String,Boolean> npMethod = new ConcurrentHashMap<>();         // owner#name desc -> non-public?
    static final Set<String> INACC = ConcurrentHashMap.newKeySet();                // already-loaded package-private MC classes the payload references by type
    static final Set<String> preBootstrapPayload = ConcurrentHashMap.newKeySet();       // payload classes present before normal on-load caller rewriting
    static Set<String> GENUINE_IFACES = java.util.Collections.emptySet();          // interfaces a real (non-mixin) bundle class implements; ∩ GIFACE -> shared duck interfaces
    /** per-target conversion: target' bytes + sidecar/state (defined LAZILY in the CFT with the target's real PD). */
    static final class Conv { byte[] target, sidecar, state; String sidecarName, stateName; volatile boolean defined;
        boolean whole;   // future-loaded target defined WHOLE (added members kept on the class); no sidecar/state to define
        boolean define(ProtectionDomain pd){ synchronized(this){ if(defined) return true;
            if(whole){ defined=true; return true; }   // the whole class carries its own added members; nothing external to define
            boolean ok = defineSynthetics(target, pd) & defineSynthetics(sidecar, pd)
                       & FullInjectAgent.define(stateName.replace('/','.'), state, pd)
                       & FullInjectAgent.define(sidecarName.replace('/','.'), sidecar, pd);
            if(ok) defined=true;
            else LateAttachVerifier.error("SIDECAR_DEFINE_FAILURE",sidecarName,"sidecar","One or more synthetic/state/sidecar definitions failed");
            return ok; } } }

    public static void agentmain(String a, Instrumentation inst) throws Exception {
        InjectionLogger.configure(a);
        if (everInjected) { InjectionLogger.error("re-injection refused: a payload was already injected into this JVM. Re-injecting is not supported (its runtime cannot be re-staged onto the already-populated loader). Restart the client to inject again.");
            throw new IllegalStateException("re-injection is not supported in the same JVM; restart the client"); }
        INST = inst;
        PLATFORM = detectPlatform(inst);
        SYS = PLATFORM.targetLoader();
        mcgraft.rt.Platform.LOADER = SYS;
        tolerateBaselineFailures = "true".equalsIgnoreCase(InjectionLogger.argValue(a, "tolerateBaselineFailures"));
        verifyAsmRuntime();
        for (Class<?> c : inst.getAllLoadedClasses()) preLoaded.add(c.getName().replace('.','/'));   // snapshot BEFORE we load anything
        inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(), Map.of("java.lang", Set.of(FullInjectAgent.class.getModule())), Set.of(), Map.of());
        // A sidecar's smi$ super-call bridge needs MethodHandles.privateLookupIn on its target. On vanilla/Fabric the
        // sidecar is defined into the target's own package and loader, so both sit in the same unnamed (hence open)
        // module and this never fires. Only a JPMS loader that relocated sidecars out of the target's module
        // (RetransformConverter.SIDECAR_PKG, i.e. NeoForge) can fail that check — open the package on demand then.
        // Registered as a lazy fallback rather than a blanket up-front open so we never widen a module we didn't need to.
        mcgraft.rt.AwReflect.MODULE_OPENER = (target, sidecarClass) -> {
            Module tm = target.getModule(), sm = sidecarClass.getModule();
            if (tm == null || !tm.isNamed() || tm.isOpen(target.getPackageName(), sm)) return;
            InjectionLogger.info("opening "+target.getPackageName()+" of module "+tm.getName()+" to "+sm+" for a super-call bridge");
            inst.redefineModule(tm, Set.of(), Map.of(), Map.of(target.getPackageName(), Set.of(sm)), Set.of(), Map.of());
        };
        RetransformConverter.CLASS_BYTES = PLATFORM::originalBytes;
        RetransformConverter.RAW_CLASS_BYTES = PLATFORM::rawBytes;   // access flags an ALREADY-LOADED class still enforces: the payload's AW could not reach it
        RetransformConverter.ALREADY_LOADED = preLoaded::contains;   // ...which is exactly this set. See RetransformConverter's "ENFORCED access" block
        RetransformConverter.TYPE_INACCESSIBLE = INACC::contains;    // the class-level equivalent, computed just below
        InjectionLogger.info("staging payload bundle ("+preLoaded.size()+" classes already loaded)");
        File self = new File(FullInjectAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path payloadBundle = PLATFORM.stageBundle(inst, self);
        final PayloadConfig cfg = PayloadConfig.init(PLATFORM.bundleResource("graft.properties"));
        RetransformConverter.PAYLOAD_CLASSES = readPayloadClasses(payloadBundle, cfg.callerRewritePackages);
        GENUINE_IFACES = scanGenuineImplementors(payloadBundle, cfg.callerRewritePackages);
        // Also caller-rewrite the applied dependency (fabric-api) runtime: its own classes cast MC objects to duck
        // interfaces its mixins add. Match by prefix (they are not in the payload jar). Only when dep-mixins are applied.
        Set<String> crPrefixes = new HashSet<>(cfg.callerRewritePackages);
        if (!cfg.depCallerRewritePackages.isEmpty()) crPrefixes.addAll(cfg.depCallerRewritePackages);   // explicit override
        else if (!cfg.depMixinConfigs.isEmpty()) crPrefixes.add("net/fabricmc/fabric");                 // default: fabric-api's namespace
        RetransformConverter.CALLER_REWRITE_PREFIXES = crPrefixes;
        applyGameDirProperty(cfg, resolveGameDir(a));
        if (cfg.joinGateScreen != null) JoinGateRewriter.CONNECT_SCREEN = cfg.joinGateScreen;   // arm the deferred-join gate
        if (cfg.reopenKeyBindings) KeyBindingReopen.arm(cfg.reopenKeyBindingsOwner, cfg.reopenKeyBindingsMethod);   // arm the keybinding-registry guard strip
        if (cfg.registerModContainer) PLATFORM.registerPayloadMod(cfg, payloadBundle);   // so the mod's self-metadata lookups resolve
        InjectionLogger.info("payload '"+cfg.id+"': mixins="+cfg.mixinConfigs+" aw="+cfg.accessWidener
            +" entrypoints(main="+cfg.mainEntrypoints+", client="+cfg.clientEntrypoints+")"
            +(cfg.hasEventBootstrap()?" eventBootstrap="+cfg.evtStart+(cfg.hasReadinessProbe()?" (async)":" (sync)"):""));

        PLATFORM.initMixin();
        Object aw = PLATFORM.accessWidener();   // null when the payload declares no access widener (a common, valid case)
        // Inaccessible types: AW-widened classes that are ALREADY loaded (so can't be widened) and still non-public.
        // the payload references these by type; the CFT erases those references to Object + reflection.
        if (aw == null) { InjectionLogger.info("no access widener declared — skipping inaccessible-type computation"); }
        else try { var caF = aw.getClass().getDeclaredField("classAccessible"); caF.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> ca = (Set<String>) caF.get(aw);
            for (String cn : ca) { if (!preLoaded.contains(cn)) continue; try { Class<?> k = Class.forName(cn.replace('/','.'), false, SYS); if (!java.lang.reflect.Modifier.isPublic(k.getModifiers())) INACC.add(cn); } catch(Throwable t){} }
            InjectionLogger.info("inaccessible already-loaded AW classes: "+INACC.size()+" "+INACC);
        } catch(Throwable t){ InjectionLogger.warn("INACC compute failed -> "+rootMsg(t)); }

        List<String> targets = new ArrayList<>();
        try (var r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(PLATFORM.bundleResource("mixin-targets.txt")), java.nio.charset.StandardCharsets.UTF_8))) { String l; while ((l=r.readLine())!=null) if(!l.isBlank()) targets.add(l.trim().replace('.','/')); }
        targetSet.addAll(targets);
        PLATFORM.prepareBaselines(targets);   // Fabric: capture the non-payload baseline before the payload's config is selected

        // Convert transactionally. Nothing is published/defined until every transformed target verifies, because the
        // global relocation tables permit cross-target sidecar calls and therefore cannot be safely partially accepted.
        Map<String,Class<?>> loadedMap = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) loadedMap.put(c.getName().replace('.','/'), c);
        // Phase A: mixin-transform every target and collect the GLOBAL added-method table (so cross-target / base-class
        // @Unique calls route to the right sidecar during conversion).
        LinkedHashMap<String,byte[][]> txMap = new LinkedHashMap<>();
        Map<String,String[]> gadded = new HashMap<>(); Map<String,String[]> gfield = new HashMap<>(); Map<String,List<String[]>> giface = new HashMap<>();
        List<String> phaseFailures = new ArrayList<>();
        for (String internal : targets) { try {
            // A dropped target must not be a SILENT `continue` — it contributes nothing to GADDED/GFIELD/GIFACE, so
            // payload callers keep direct refs to members the class never received. But the two ways it can drop are
            // not equally serious:
            //
            //   no original bytes  -> the target class isn't present in THIS installation. Normal and benign: mixin
            //                         configs routinely carry compatibility mixins for optional mods (Wurst ships
            //                         Sodium ones), and Mixin itself just skips those. There are no callers to dangle,
            //                         because a class that doesn't exist here has no members anyone can reference.
            //   transform -> null  -> the class IS here and the transformer declined to produce bytes. Unexpected;
            //                         that one is a real failure and still aborts.
            byte[] O = PLATFORM.originalBytes(internal);
            if (O == null) { InjectionLogger.warn("mixin target not present in this installation, skipping: "+internal); continue; }
            byte[] X = PLATFORM.transform(internal.replace('/','.'), O);
            if (X == null) { phaseFailures.add(internal); LateAttachVerifier.error("MIXIN_TARGET_MISSING",internal,"target","mixin transformer returned null"); InjectionLogger.error("transform returned null for "+internal); continue; }
            txMap.put(internal, new byte[][]{O, X}); RetransformConverter.collectAdded(internal, O, X, gadded, gfield, giface);
        } catch (Throwable e) { phaseFailures.add(internal); LateAttachVerifier.error("MIXIN_TRANSFORM_FAILURE",internal,"target",rootMsg(e)); InjectionLogger.error("transform fail "+internal+" -> "+rootMsg(e)); } }
        RetransformConverter.GADDED = gadded; RetransformConverter.GFIELD = gfield; RetransformConverter.GIFACE = giface;
        // A mixin-added interface that a REAL bundle class also implements is "shared": keep it (phantom) instead of
        // erasing it, and route its sites through DuckDispatch (which discriminates genuine implementors from ducks).
        Set<String> shared = new HashSet<>(giface.keySet()); shared.retainAll(GENUINE_IFACES);
        RetransformConverter.SHARED_IFACES = shared;
        if (!shared.isEmpty()) InjectionLogger.info("shared duck interfaces (phantom, guarded dispatch): " + shared);
        InjectionLogger.info("phase A: "+txMap.size()+" transformed; global added-methods="+gadded.size()+" added-fields="+gfield.size());
        // A FUTURE-loaded target (not loaded at attach) has no schema constraint at define time, so it can be defined
        // WHOLE — the mixin-added members kept on the class — letting a mod reflectively read its own added field
        // (LevelChunk.getDeclaredField("xaero_chunkClean")). Eligible: not preloaded AND adds no interface (an added
        // interface stays dropped so a value of it dispatches uniformly; the guarded DuckDispatch still resolves a whole
        // target that genuinely implements a shared one). Their added methods/fields are self-referenced (handlers called
        // by the target's own woven methods; the field read natively), so remove them from the global caller-rewrite
        // tables — callers then access them natively on the whole class instead of routing to a (nonexistent) sidecar.
        Set<String> wholeTargets = new HashSet<>();
        for (String t : targets) {
            if (preLoaded.contains(t)) continue;
            boolean addsIface = false; for (var e : giface.values()) for (String[] v : e) if (v[0].equals(t)) addsIface = true;
            if (addsIface) continue;
            boolean adds = false; for (String[] v : gadded.values()) if (v[0].equals(t)) { adds = true; break; }
            if (!adds) for (String[] v : gfield.values()) if (v[0].equals(t)) { adds = true; break; }
            if (adds) wholeTargets.add(t);
        }
        if (!wholeTargets.isEmpty()) {
            gadded.values().removeIf(v -> wholeTargets.contains(v[0]));
            gfield.values().removeIf(v -> wholeTargets.contains(v[0]));
            InjectionLogger.info("future-loaded targets defined WHOLE (added members kept on the class): " + wholeTargets);
        }
        // Phase B: stage every conversion against the complete global tables.
        int conv=0, eager=0; List<String> skipped=new ArrayList<>();
        LinkedHashMap<String,Conv> pendingConv=new LinkedHashMap<>();
        Map<String,List<String[]>> pendingIfaces=new HashMap<>();
        for (var e : txMap.entrySet()) { String internal = e.getKey(); try {
            byte[] O = e.getValue()[0], X = e.getValue()[1];
            if (wholeTargets.contains(internal)) {   // define the full mixin output at (future) load time — no sidecar/state
                Conv cw = new Conv(); cw.whole = true; cw.target = X; pendingConv.put(internal, cw); conv++; continue;
            }
            RetransformConverter c = new RetransformConverter(internal); RetransformConverter.Result res = c.run(O, X);
            Conv cv = new Conv(); cv.target=res.target; cv.sidecar=res.sidecar; cv.state=c.stateBytes(); cv.sidecarName=res.sidecarName; cv.stateName=c.stateName();
            if (!LateAttachVerifier.verifyConversion(internal, O, cv.target, cv.sidecar, cv.state, gadded, gfield, giface)) {
                skipped.add(internal); continue;
            }
            for (String di : res.droppedInterfaces) {
                List<String[]> impls = pendingIfaces.computeIfAbsent(di, k -> new ArrayList<>());
                boolean duplicate=false; for(String[] impl:impls) if(impl[0].equals(internal)){duplicate=true;break;}
                if(!duplicate) impls.add(new String[]{internal, res.sidecarName});
            }
            pendingConv.put(internal, cv); conv++;
        } catch (Throwable ex) { skipped.add(internal); LateAttachVerifier.error("CONVERSION_FAILURE",internal,"target",rootMsg(ex)); InjectionLogger.error("convert fail "+internal+" -> "+rootMsg(ex)); } }
        // Every mcgraft.rt call the converter emitted must actually resolve, PUBLICLY, before anything is published.
        // Nothing else checks this contract: the descriptors are hand-built strings, and a mismatch stays invisible
        // until the game executes that path — long after this gate, when the injection can no longer be rolled back.
        for (String bad : RetransformConverter.unresolvedRtCalls(mcgraft.rt.Platform.LOADER)) {
            LateAttachVerifier.error("RT_CONTRACT_UNRESOLVED", bad, "runtime", "emitted call does not resolve");
            InjectionLogger.error("runtime contract broken: " + bad);
        }
        if(!phaseFailures.isEmpty()||!skipped.isEmpty()||LateAttachVerifier.hasErrors()){
            LateAttachVerifier.writeReport();
            throw new IllegalStateException("Late-attach conversion gate failed; no partial conversion was published");
        }
        convMap.putAll(pendingConv);ifaceMap.putAll(pendingIfaces);
        // Eager-define only for targets that were already loaded, using that target's exact protection domain.
        for(var e:pendingConv.entrySet()){Class<?> lc=loadedMap.get(e.getKey());if(lc==null)continue;
            if(e.getValue().define(lc.getProtectionDomain()))eager++;}
        if(LateAttachVerifier.hasErrors()){
            LateAttachVerifier.writeReport();
            throw new IllegalStateException("Late-attach sidecar definition gate failed");
        }
        InjectionLogger.info("converted "+conv+" targets ("+eager+" sidecars eager-defined); ifaceMap="+ifaceMap.size()+" interfaces; skipped="+skipped.size()+(skipped.isEmpty()?"":" "+skipped));
        InjectionLogger.info("ifaceMap keys: " + ifaceMap.keySet());
        for (var ie : ifaceMap.entrySet()) for (String[] impl : ie.getValue())
            mcgraft.rt.DuckDispatch.register(ie.getKey(), impl[0], impl[1]);
        // Register relocated fields with the reflection shim so a mod that reflectively reads its OWN added field on an
        // already-loaded target (which had to move to $State) resolves to the per-instance state. gfield here already
        // excludes whole targets (whose fields stayed on the class).
        int rf = 0;
        for (var ge : gfield.entrySet()) {   // key = "owner name desc"; parse by owner length (names/descs are space-free)
            String key = ge.getKey(); String[] v = ge.getValue();   // v = [owner, sidecar, S/I]
            int sp = key.lastIndexOf(' '); if (sp <= v[0].length()) continue;
            String name = key.substring(v[0].length() + 1, sp);
            mcgraft.rt.StateReflect.register(v[0], name, v[1], v[1] + "$State"); rf++;
        }
        InjectionLogger.info("reflection shim: registered "+rf+" relocated field(s)");

        // Resolver: the payload access of a non-public member of an ALREADY-LOADED MC class must be reflection-routed (that
        // class could not be AW-widened, since retransform bans modifier changes). Future MC classes get the AW on-load.
        RetransformConverter.Resolver awRes = AW_RES = new RetransformConverter.Resolver(){
            public boolean fieldNeedsReflect(String o,String n,String d){ return INACC.contains(o) || (isPreloadedMc(o) && fieldNonPublic(o,n)); }
            public boolean methodNeedsReflect(String o,String n,String d){ return INACC.contains(o) || (isPreloadedMc(o) && methodNonPublic(o,n,d)); }
            public boolean typeInaccessible(String in){ return INACC.contains(in); }
        };
        // CFT: mixin target loads/retransforms -> lazily define sidecar+state (class PD), return target' (AW-widened if
        // future-loaded). Future non-target MC class -> apply AW on-load. the payload class -> caller-rewrite to sidecars + reflect
        // its direct accesses to already-loaded MC members.
        ClassFileTransformer cft = new ClassFileTransformer(){ public byte[] transform(ClassLoader l,String n,Class<?> c,ProtectionDomain p,byte[] b){
            if (n==null) return null;
            try {
                boolean connect = n.equals(JoinGateRewriter.CONNECT_SCREEN);
                Conv cv = convMap.get(n);
                if (cv!=null && cv.whole) {
                    // Whole future-loaded target: define the full mixin output, with its references to OTHER (dropped)
                    // targets caller-rewritten. If it somehow already loaded (c!=null), a schema-adding redefine is
                    // illegal — let the JVM keep the pristine class (that mod hook is inert this session).
                    if (c != null) { InjectionLogger.warn("whole target "+n+" was already loaded; cannot add its mixin members via retransform (inert this session)"); return null; }
                    byte[] t = ifaceMap.isEmpty() ? cv.target : RetransformConverter.rewriteCaller(cv.target, ifaceMap);
                    if(connect) t = JoinGateRewriter.rewrite(n, t);
                    byte[] w = awApply(n, t); if (w != null) t = w;
                    return t;
                }
                if (cv!=null) {
                    byte[] t;
                    if (c==null) t = cv.target;
                    else { long s=System.nanoTime(); t = RetransformConverter.rebase(cv.target, b); tRebase += System.nanoTime()-s; nRebase++; }
                    if(connect)t=JoinGateRewriter.rewrite(n,t);
                    if (c!=null) { long s=System.nanoTime(); boolean ok=LateAttachVerifier.verifyRetransform(n, b, t); tVerify += System.nanoTime()-s; if(!ok) return null; }
                    if(!cv.define(p)){
                        // Future-loaded target (c==null) can hit this AFTER the join gate opened; the JVM then defines the
                        // pristine original with NO the payload hooks, so the feature backing this target is silently inert for the
                        // session. Surface it to the live injection log (not just the verifier report) so it is observable.
                        InjectionLogger.error("sidecar/state define failed for "+n+" — it will load WITHOUT payload hooks (that feature is inert this session; restart the client to retry)");
                        return null;
                    }
                    if (c==null) { byte[] w=awApply(n,t); if(w!=null) t=w; }
                    return t; }
                if (RetransformConverter.isCallerRewrite(n)) {
                    byte[] rw = AccessorBridgeRewriter.rewrite(n, b);
                    rw = ifaceMap.isEmpty()? rw : RetransformConverter.rewriteCaller(rw, ifaceMap);
                    if (n.equals(KeyBindingReopen.TARGET)) rw = KeyBindingReopen.rewrite(n, rw);   // dep classes: also strip the keybind guard (this branch pre-empts the standalone KeyBindingReopen branch below)
                    if (DEBUG) InjectionLogger.info("CFT caller "+n+" changed="+(rw!=b));
                    rw = RetransformConverter.rewritePayloadAw(rw, awRes);
                    if (!LateAttachVerifier.verifyCaller(n, b, rw, gadded, gfield, ifaceMap)) return null;
                    return rw==b? null : rw;
                }
                if (connect) {
                    byte[] rw=JoinGateRewriter.rewrite(n,b);
                    if(c!=null&&!LateAttachVerifier.verifyRetransform(n,b,rw))return null;
                    if(c==null){byte[] w=awApply(n,rw);if(w!=null)rw=w;}
                    return rw==b?null:rw;
                }
                if (n.equals(KeyBindingReopen.TARGET)) {   // strip fabric-api's post-GameOptions keybinding-registration guard
                    byte[] rw=KeyBindingReopen.rewrite(n,b);
                    if(c!=null&&!LateAttachVerifier.verifyRetransform(n,b,rw))return null;
                    return rw==b?null:rw;
                }
                if (n.startsWith("net/minecraft/")||n.startsWith("com/mojang/")) return PLATFORM.cftAppliesAw()? awApply(n,b) : null;  // widen future MC classes (on-load) + AW-class retransforms (modded loaders widen future classes themselves)
            } catch(Throwable x){ LateAttachVerifier.error("TRANSFORM_FAILURE",n,"cft",rootMsg(x)); InjectionLogger.error("CFT fail "+n, x); }
            return null; } };
        inst.addTransformer(cft, true);
        everInjected = true;   // committed: payload staged + targets converted + transformer live -> refuse any re-inject
        refreshLoadedAccessState(inst,aw);

        // Payload classes loaded early (e.g. pulled in during mixin-config processing, before the CFT existed) missed
        // the on-load caller rewriting, so their dropped-interface casts (e.g. (IMinecraftClient) mc) still name a
        // de-implemented interface. Retransform them now (a body-only edit -> retransform-legal) so those route to
        // DuckDispatch/the concrete class before the mod bootstraps.
        try { List<Class<?>> lp = new ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) { String cn = c.getName().replace('.','/');
                if (RetransformConverter.isCallerRewrite(cn) && !convMap.containsKey(cn) && inst.isModifiableClass(c)) lp.add(c); }
            if (!lp.isEmpty()) { inst.retransformClasses(lp.toArray(new Class[0])); InjectionLogger.info("retransformed "+lp.size()+" already-loaded payload classes for caller rewriting: "+lp); }
        } catch(Throwable t){ InjectionLogger.warn("payload caller retransform failed: "+rootMsg(t)); }

        // Force the join entry point through the installed CFT now (only when the join-gate feature is armed). A later
        // first-click load is too late to prove that the gate exists before registries are thawed.
        if(cfg.joinGateScreen!=null) try{
            boolean connectWasLoaded=preLoaded.contains(JoinGateRewriter.CONNECT_SCREEN);
            Class<?> connectClass=Class.forName(JoinGateRewriter.CONNECT_SCREEN.replace('/','.'),false,SYS);
            if(connectWasLoaded){
                if(!inst.isModifiableClass(connectClass))throw new UnmodifiableClassException(connectClass.getName());
                inst.retransformClasses(connectClass);
            }
        }catch(Throwable e){LateAttachVerifier.error("JOIN_GATE_PREFLIGHT_FAILURE",JoinGateRewriter.CONNECT_SCREEN,"join-gate",rootMsg(e));}

        // Strip fabric-api's keybinding-registration guard now (before the payload's client init registers keybinds,
        // which for some mods happens on a later tick). The impl class was loaded at startup (its process() runs from
        // Options' constructor), so it is already loadable + retransformable.
        if(cfg.reopenKeyBindings) try{
            Class<?> kri=Class.forName(KeyBindingReopen.TARGET.replace('/','.'),false,SYS);
            if(inst.isModifiableClass(kri)){ inst.retransformClasses(kri); InjectionLogger.info("reopened keybinding registry "+KeyBindingReopen.TARGET+" (late registration enabled)"); }
            else InjectionLogger.warn("keybinding registry class not modifiable; late keybinds may fail");
        }catch(Throwable e){ InjectionLogger.warn("reopenKeyBindings failed (late keybinds may throw): "+rootMsg(e)); }

        verifyMemberAccessGate(inst);
        preflightPayloadClasses(payloadBundle,awRes,gadded,gfield,ifaceMap);
        if(LateAttachVerifier.hasFatalErrors()){
            LateAttachVerifier.writeReport();
            throw new IllegalStateException("Late-attach preflight failed before target retransformation; see report");
        }

        for(Class<?> c:inst.getAllLoadedClasses())if(RetransformConverter.PAYLOAD_CLASSES.contains(c.getName().replace('.','/')))preBootstrapPayload.add(c.getName());
        if(cfg.joinGateScreen!=null) mcgraft.rt.JoinGate.block();

        // Bootstrap the payload on the Minecraft thread while target hooks are still INACTIVE. A payload whose init is
        // asynchronous (e.g. it launches a coroutine from an event) can have later render/entity hooks run before its
        // managers finish and circularly initialize singletons; for those the readiness probe below publishes the
        // target retransforms only after the payload reports ready. A synchronous ModInitializer publishes immediately.
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, SYS);
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            Runnable kick = () -> { RegistryThawSession thaw=null; try {
                if(LateAttachVerifier.hasFatalErrors())throw new IllegalStateException("Bootstrap class preflight produced verification errors");
                if(cfg.bridgePayloadCodecs) PLATFORM.bridgePayloadCodecs();   // wire fabric-api PayloadTypeRegistry into MC's live codec dispatch
                // Off the reload path: thaw ALL registries so the payload can register into them post-freeze.
                // On the reload path: thawing everything collides with the reload re-freezing the dynamic RegistryAccess
                // ("Tags already present"), so thaw ONLY the specific built-in registries the payload writes to
                // (bootstrap.thawRegistries, e.g. minecraft:sound_event) and keep that narrow thaw open across the reload.
                if (!cfg.reloadResources) thaw = RegistryThawSession.begin();
                else if (!cfg.thawRegistries.isEmpty()) thaw = RegistryThawSession.begin(cfg.thawRegistries);
                bootstrapPayload(cfg);
                if(LateAttachVerifier.hasFatalErrors())throw new IllegalStateException("payload init produced verification errors");
                if (cfg.reloadResources) {
                    // The payload registered a resource-reload listener during its start event and defers real init to
                    // it; the boot-time reload is long gone on late-attach, so drive one now (async; the readiness probe
                    // below waits for the init the reload triggers).
                    InjectionLogger.info("(MC main thread) reloadResourcePacks() to drive payload resource-reload init");
                    mcCls.getMethod("reloadResourcePacks").invoke(mc);
                }
                if (cfg.hasReadinessProbe()) {
                    restoreRegistriesAfterInitialization(mcCls, mc, thaw, cfg);   // async: wait for ready, then publish
                } else {
                    closeThaw(thaw); thaw=null;                                   // synchronous: init is done, publish now
                    activateLoadedTargets(INST);
                    mcgraft.rt.JoinGate.cancelAndOpen();
                    InjectionLogger.info("payload injection complete");
                }
            } catch (Throwable t) {
                // Always reopen the gate on ANY kick failure, including a throw BEFORE RegistryThawSession.begin()
                // (payload clinit, reflection, or the fatal-error check) where thaw is still null — otherwise the gate
                // blocked above stays closed for the session and multiplayer is permanently locked out.
                if(thaw!=null)thaw.close();
                mcgraft.rt.JoinGate.cancelAndOpen();
                LateAttachVerifier.error("BOOTSTRAP_KICK_FAILURE",cfg.id,"bootstrap",rootMsg(t));
                InjectionLogger.error("kick error", t); } };
            mcCls.getMethod("execute", Runnable.class).invoke(mc, kick);
            InjectionLogger.info("scheduled "+cfg.id+" bootstrap on MC main thread");
        } catch (Throwable e) { LateAttachVerifier.error("BOOTSTRAP_SCHEDULE_FAILURE",cfg.id,"bootstrap",rootMsg(e)); mcgraft.rt.JoinGate.cancelAndOpen(); InjectionLogger.error("bootstrap kick failed", e); }
    }

    /** Initialize the payload on the MC thread: dispatch its configured client-start event, or (default) call the
     *  Fabric entrypoints it declares in fabric.mod.json (main -> ModInitializer.onInitialize; client ->
     *  ClientModInitializer.onInitializeClient). */
    static void bootstrapPayload(PayloadConfig cfg) throws Exception {
        if (cfg.hasEventBootstrap()) {
            // Load+initialize the mod's main singleton FIRST: its clinit is what registers the start-event handler.
            // Dispatching the event before this would fire into an empty listener set and the mod never initializes.
            if (cfg.readyClass != null) Class.forName(cfg.readyClass, true, SYS);
            Class<?> em = Class.forName(cfg.evtManager, true, SYS);
            Object emInst = em.getField(cfg.evtManagerInstance).get(null);
            Object ev = Class.forName(cfg.evtStart, true, SYS).getField(cfg.evtStartInstance).get(null);
            java.lang.reflect.Method call = em.getMethod(cfg.evtCall, Class.forName(cfg.evtBase, true, SYS));
            InjectionLogger.info("(MC main thread) "+cfg.evtCall+"("+simpleName(cfg.evtStart)+")");
            call.invoke(emInst, ev);
            InjectionLogger.info(simpleName(cfg.evtStart)+" dispatched");
            return;
        }
        if (cfg.mainEntrypoints.isEmpty() && cfg.clientEntrypoints.isEmpty())
            throw new IllegalStateException("payload '"+cfg.id+"' declares no fabric entrypoints and no event-bootstrap override; nothing to initialize");
        // Applied dependency-module client entrypoints first (e.g. indigo registering the active renderer), best-effort.
        for (String cn : cfg.depClientEntrypoints) { try { callEntrypoint(cn, "onInitializeClient"); }
            catch (Throwable t) { InjectionLogger.warn("dependency-module entrypoint "+cn+" failed (best-effort, continuing): "+rootMsg(t)); } }
        for (String cn : cfg.mainEntrypoints) callEntrypoint(cn, "onInitialize");
        for (String cn : cfg.clientEntrypoints) callEntrypoint(cn, "onInitializeClient");
        // Guaranteed merge point for keybinds the entrypoints just registered. The registration method is also hooked
        // (KeyBindingReopen.appendSync) to cover deferred registration, but that hook is absent if its rewrite did not
        // apply; this call keeps the inline case working regardless. Both paths are idempotent.
        if (cfg.reopenKeyBindings) mcgraft.rt.KeyBindSync.syncFrom(SYS, KeyBindingReopen.TARGET);
    }
    /** Invoke a Fabric entrypoint the way the loader's language adapters do, rather than assuming a public no-arg ctor.
     *
     *  <p>A Kotlin {@code object} — which is how many mods declare their entrypoint — compiles to a class with a PRIVATE
     *  constructor plus a static {@code INSTANCE} field holding the singleton. Calling {@code newInstance()} on it fails
     *  with "cannot access a member ... with modifiers \"private\"", and the mod simply never initialises. Prefer the
     *  singleton field when present, exactly as Fabric's Kotlin adapter does; otherwise construct, making the
     *  constructor accessible first, since a Java entrypoint may legitimately keep its no-arg ctor non-public. */
    static void callEntrypoint(String className, String method) throws Exception {
        InjectionLogger.info("(MC main thread) "+className+"."+method+"()");
        Class<?> k = Class.forName(className, true, SYS);
        Object inst = null;
        try {
            java.lang.reflect.Field f = k.getDeclaredField("INSTANCE");
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && k.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                inst = f.get(null);
            }
        } catch (NoSuchFieldException ignored) { /* not a Kotlin object — construct it below */ }
        if (inst == null) {
            java.lang.reflect.Constructor<?> c = k.getDeclaredConstructor();
            c.setAccessible(true);
            inst = c.newInstance();
        }
        java.lang.reflect.Method m = k.getMethod(method);
        m.setAccessible(true);
        m.invoke(inst);
    }
    static String simpleName(String fqcn) { int i = fqcn.lastIndexOf('.'); return i < 0 ? fqcn : fqcn.substring(i + 1); }

    /** Internal names of every class in the staged payload jar; used to tell a payload-defined duck interface from a
     *  pre-existing/vanilla interface a mixin also implements, and as the caller-rewrite set. When callerRewritePackages
     *  is set (bundled library mods whose own runtime casts to their mixins' dropped duck interfaces, e.g. xaerolib),
     *  the payload's jar-in-jar module classes under those internal-name prefixes are included so those callers are
     *  rewritten too — bundled libraries are effectively part of the payload. */
    static Set<String> readPayloadClasses(Path payloadJar, List<String> callerRewritePackages) {
        Set<String> names = new HashSet<>();
        try (JarFile jf = new JarFile(payloadJar.toFile())) {
            for (var en = jf.entries(); en.hasMoreElements();) { String n = en.nextElement().getName();
                if (n.endsWith(".class")) names.add(n.substring(0, n.length() - 6));
                else if (!callerRewritePackages.isEmpty() && n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                    try (var in = jf.getInputStream(jf.getJarEntry(n)); var jin = new java.util.jar.JarInputStream(in)) {
                        for (java.util.jar.JarEntry e; (e = jin.getNextJarEntry()) != null; ) { String cn = e.getName();
                            if (!cn.endsWith(".class")) continue;
                            String internal = cn.substring(0, cn.length() - 6);
                            for (String pre : callerRewritePackages) if (internal.startsWith(pre)) { names.add(internal); break; }
                        }
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) { InjectionLogger.warn("could not read payload class names: " + rootMsg(t)); }
        InjectionLogger.info("payload classes indexed: " + names.size()
            + (callerRewritePackages.isEmpty() ? "" : " (incl. bundled-library classes under " + callerRewritePackages + ")"));
        return names;
    }

    /** Lightweight class-header reader: name, superclass, direct super-interfaces, and whether it is a @Mixin. */
    static final class Hdr extends org.objectweb.asm.ClassVisitor {
        String name, superName; String[] ifaces; boolean mixin;
        Hdr() { super(org.objectweb.asm.Opcodes.ASM9); }
        public void visit(int v, int a, String n, String sig, String sup, String[] is) { name = n; superName = sup; ifaces = is; }
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean vis) {
            if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) mixin = true; return null;
        }
    }

    /** Every interface that a REAL (non-@Mixin) class in the staged bundle genuinely implements, transitively. Later
     *  intersected with the mixin-ADDED interface set (GIFACE) to find the "shared" interfaces: those a payload mixin
     *  adds to an MC target AND a real bundle class also implements, so a value of that type may be either kind at
     *  runtime and the interface must be kept (phantom) rather than erased. Scans the payload jar + its jar-in-jar
     *  library modules under the caller-rewrite prefixes (the libraries whose mixins are applied). */
    static Set<String> scanGenuineImplementors(Path payloadJar, List<String> callerRewritePackages) {
        Map<String,String[]> ifaces = new HashMap<>(); Map<String,String> supers = new HashMap<>(); Set<String> mixins = new HashSet<>();
        java.util.function.BiConsumer<String,byte[]> parse = (n, b) -> { try {
            Hdr h = new Hdr(); new ClassReader(b).accept(h, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (h.name == null) return;
            ifaces.put(h.name, h.ifaces == null ? new String[0] : h.ifaces);
            if (h.superName != null) supers.put(h.name, h.superName);
            if (h.mixin) mixins.add(h.name);
        } catch (Throwable ignore) {} };
        try (JarFile jf = new JarFile(payloadJar.toFile())) {
            for (var en = jf.entries(); en.hasMoreElements();) { var e = en.nextElement(); String n = e.getName();
                if (n.endsWith(".class")) { try (var in = jf.getInputStream(e)) { parse.accept(n, in.readAllBytes()); } catch (Throwable ignore) {} }
                else if (!callerRewritePackages.isEmpty() && n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                    try (var in = jf.getInputStream(e); var jin = new java.util.jar.JarInputStream(in)) {
                        for (java.util.jar.JarEntry je; (je = jin.getNextJarEntry()) != null; ) { String cn = je.getName();
                            if (!cn.endsWith(".class")) continue; String internal = cn.substring(0, cn.length() - 6);
                            boolean inScope = false; for (String pre : callerRewritePackages) if (internal.startsWith(pre)) { inScope = true; break; }
                            if (inScope) { try { parse.accept(cn, jin.readAllBytes()); } catch (Throwable ignore) {} }
                        }
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) { InjectionLogger.warn("genuine-implementor scan failed: " + rootMsg(t)); return java.util.Collections.emptySet(); }
        Set<String> genuine = new HashSet<>();
        for (String cls : ifaces.keySet()) {
            if (mixins.contains(cls)) continue;   // a @Mixin's `implements IFoo` is the duck declaration, not a genuine impl
            ArrayDeque<String> stack = new ArrayDeque<>(); Set<String> seen = new HashSet<>(); stack.push(cls);
            while (!stack.isEmpty()) { String cur = stack.pop(); if (!seen.add(cur) || seen.size() > 4096) continue;
                String[] is = ifaces.get(cur); if (is != null) for (String i : is) { genuine.add(i); stack.push(i); }
                String sup = supers.get(cur); if (sup != null) stack.push(sup);
            }
        }
        return genuine;
    }

    static long tRebase, tVerify; static int nRebase;   // per-class CFT cost instrumentation (retransform window)

    /** Publish all already-loaded target/caller rewrites from the Minecraft thread after bootstrap readiness. */
    static void activateLoadedTargets(Instrumentation inst) throws Exception {
        long t0=System.nanoTime();
        List<Class<?>> targets=new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) { String in=c.getName().replace('.','/');
            if (convMap.containsKey(in)&&preLoaded.contains(in)&&!in.equals(JoinGateRewriter.CONNECT_SCREEN)&&inst.isModifiableClass(c)) targets.add(c); }
        int rt=batchRetransform(inst, targets, "target");
        long dt=(System.nanoTime()-t0)/1_000_000;
        InjectionLogger.info("retransformed "+rt+"/"+targets.size()+" already-loaded targets on MC main thread ("
            +dt+"ms total; rebase "+(tRebase/1_000_000)+"ms/"+nRebase+" classes, verify "+(tVerify/1_000_000)+"ms)");
        List<Class<?>> callers=new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) if (preBootstrapPayload.contains(c.getName())&&inst.isModifiableClass(c)) callers.add(c);
        batchRetransform(inst, callers, "caller");
        LateAttachVerifier.writeReport();
        // Only a FATAL error aborts here; tolerable per-target/caller residue must not force the join gate closed.
        if(LateAttachVerifier.hasFatalErrors())throw new IllegalStateException("Late-attach verification failed during target activation; see report");
    }

    /** Retransform the whole set in ONE VM op (one safepoint, not N). retransformClasses is all-or-nothing, so if a
     *  class legitimately cannot be retransformed live it fails the batch -> we then fall back to per-class ONLY for
     *  that set to isolate the offender while still applying the rest. Common case (all succeed) is a single fast call. */
    /** Diagnostic: reproduce the caller-rewrite for a class whose retransform failed and log which methods the rewrite
     *  adds/drops vs the original (a schema change is what retransformClasses rejects). */
    static void diagCallerSchema(String internal) {
        try {
            byte[] o; try (InputStream in = SYS.getResourceAsStream(internal+".class")) { if(in==null){InjectionLogger.warn("  diag: no original bytes for "+internal);return;} o=in.readAllBytes(); }
            byte[] rw = AccessorBridgeRewriter.rewrite(internal, o);
            if(!ifaceMap.isEmpty()) rw = RetransformConverter.rewriteCaller(rw, ifaceMap);
            rw = RetransformConverter.rewritePayloadAw(rw, AW_RES);
            java.util.Set<String> om = methodSet(o), rm = methodSet(rw);
            java.util.Set<String> dropped = new java.util.TreeSet<>(om); dropped.removeAll(rm);
            java.util.Set<String> added = new java.util.TreeSet<>(rm); added.removeAll(om);
            InjectionLogger.warn("  diag "+internal+": rewrite dropped methods "+dropped+" ; added "+added);
            int mark = LateAttachVerifier.issueMark();
            boolean ok = LateAttachVerifier.verifyCaller(internal, o, rw, RetransformConverter.GADDED, RetransformConverter.GFIELD, RetransformConverter.GIFACE);
            InjectionLogger.warn("  diag verifyCaller="+ok+" newIssues="+LateAttachVerifier.issuesSince(mark));
        } catch (Throwable t) { InjectionLogger.warn("  diag "+internal+" failed: "+rootMsg(t)); }
    }
    static java.util.Set<String> methodSet(byte[] b){ org.objectweb.asm.tree.ClassNode c=new org.objectweb.asm.tree.ClassNode(); new ClassReader(b).accept(c,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        java.util.Set<String> s=new java.util.HashSet<>(); if(c.methods!=null)for(org.objectweb.asm.tree.MethodNode m:c.methods)s.add(m.name+m.desc); return s; }

    static int batchRetransform(Instrumentation inst, List<Class<?>> cs, String kind) {
        if (cs.isEmpty()) return 0;
        try { inst.retransformClasses(cs.toArray(new Class<?>[0])); return cs.size(); }
        catch (Throwable batch) {
            InjectionLogger.warn("batch "+kind+" retransform failed ("+rootMsg(batch)+"); isolating per-class");
            int ok=0;
            for (Class<?> c : cs) { String in=c.getName().replace('.','/');
                try { inst.retransformClasses(c); ok++; }
                catch (Throwable e) { LateAttachVerifier.error(kind.toUpperCase()+"_RETRANSFORM_FAILURE",in,kind,rootMsg(e));
                    InjectionLogger.warn("retransform fail "+in+" -> "+rootMsg(e));
                    if("caller".equals(kind)) diagCallerSchema(in); } }
            return ok;
        }
    }
    /** define into the app loader with the given PD (matches signer of signed target packages); true on success or
     *  benign already-defined, false on a real ClassFormat/Verify defect. */
    static boolean define(String dotted, byte[] b, ProtectionDomain pd){
        if (DUMP_DIR != null) try { Path d = Path.of(DUMP_DIR); Files.createDirectories(d); Files.write(d.resolve(dotted+".class"), b); } catch(Throwable ignored){}
        return PLATFORM.defineClass(dotted, b, pd); }
    /** -Dmcgraft.agent.dumpDir=<dir> on the TARGET jvm: write every class the agent defines (sidecar/state/synthetic)
     *  to <dir> for offline inspection. Diagnostic only; unset by default. */
    static final String DUMP_DIR = System.getProperty("mcgraft.agent.dumpDir");
    static synchronized boolean defineSynthetics(byte[] cb, ProtectionDomain pd){
        boolean ok=true;
        for(String in:scanSyn(cb)){
            String d=in.replace('/','.');
            try{
                try{Class.forName(d,false,SYS);definedSynth.add(d);continue;}catch(ClassNotFoundException expected){}
                if(definedSynth.contains(d)||definingSynth.contains(d))continue;
                definingSynth.add(d);boolean one=false;
                try{
                    byte[] sb=PLATFORM.generateClass(d);
                    if(sb==null)syntheticError("SYNTHETIC_GENERATE_FAILURE",d,"Mixin transformer returned no bytes");
                    else if(!defineSynthetics(sb,pd))syntheticError("SYNTHETIC_DEPENDENCY_FAILURE",d,"A generated synthetic dependency failed");
                    else if(!define(d,sb,d.startsWith("org.spongepowered.")?null:pd))syntheticError("SYNTHETIC_DEFINE_FAILURE",d,"Generated class could not be defined");
                    else{definedSynth.add(d);one=true;}
                }finally{definingSynth.remove(d);}
                ok&=one;
            }catch(Throwable t){definingSynth.remove(d);syntheticError("SYNTHETIC_PIPELINE_FAILURE",d,rootMsg(t));ok=false;}
        }
        return ok;
    }
    static void syntheticError(String code,String dotted,String message){
        InjectionLogger.error("SYNTH-FAIL "+dotted+" -> "+message);
        LateAttachVerifier.error(code,dotted.replace('.','/'),"synthetic",message);
    }
    static List<String> scanSyn(byte[] b){ LinkedHashSet<String> o=new LinkedHashSet<>(); try{ ClassReader cr=new ClassReader(b);String self=cr.getClassName(); char[] bu=new char[cr.getMaxStringLength()]; for(int i=1;i<cr.getItemCount();i++){int off=cr.getItem(i); if(off==0||off-1<0)continue; if((b[off-1]&0xff)!=7)continue; try{String n=cr.readUTF8(off,bu); if(n!=null&&!n.equals(self)&&(n.startsWith("org/spongepowered/asm/synthetic/")||n.contains("$Anonymous$")))o.add(n);}catch(Throwable x){}}}catch(Throwable x){} return new ArrayList<>(o); }
    /** Exact, idempotent registry-state transaction. Originally-unfrozen registries stay unfrozen. */
    static final class RegistryThawSession implements AutoCloseable {
        final java.lang.reflect.Field frozen;
        final IdentityHashMap<Object,Boolean> original;
        // A thawed registry (frozen=false) still has its tag set bound, so any later re-freeze (e.g. the resource
        // reload rebuilding ClientRegistryLayer's RegistryAccess) hits MappedRegistry.freeze's "Tags already present
        // before freezing" guard. To stay compatible we temporarily swap allTags to an unbound TagSet for the thaw
        // window and restore it on close (unless something rebound it meanwhile). Best-effort: null field -> skip.
        java.lang.reflect.Field allTagsField;
        java.lang.reflect.Method tagsUnbound, tagsIsBound;
        final IdentityHashMap<Object,Object> tagBackup = new IdentityHashMap<>();
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean restored;
        RegistryThawSession(java.lang.reflect.Field frozen, IdentityHashMap<Object,Boolean> original){this.frozen=frozen;this.original=original;}
        static RegistryThawSession begin() throws Exception { return begin(java.util.List.of()); }
        /** Thaw the built-in registries so the payload can register into them post-freeze. With an empty scope, thaw
         *  ALL of them (used off the reload path). With a scope (registry ids like "minecraft:sound_event"), thaw ONLY
         *  those — needed on the resource-reload path, where thawing everything collides with the reload re-freezing
         *  the dynamic RegistryAccess ("Tags already present before freezing"); a bare reload does not re-freeze the
         *  static registries the payload actually writes to. */
        static RegistryThawSession begin(java.util.List<String> scope) throws Exception {
            Class<?> bir=Class.forName("net.minecraft.core.registries.BuiltInRegistries",false,SYS);
            java.lang.reflect.Field wr=bir.getDeclaredField("WRITABLE_REGISTRY");wr.setAccessible(true);Object root=wr.get(null);
            Class<?> mapped=Class.forName("net.minecraft.core.MappedRegistry",false,SYS);
            java.lang.reflect.Field fz=mapped.getDeclaredField("frozen");fz.setAccessible(true);
            boolean all = scope==null || scope.isEmpty();
            IdentityHashMap<Object,Boolean> states=new IdentityHashMap<>();
            if(all){
                if(mapped.isInstance(root))states.put(root,fz.getBoolean(root));
                for(Object reg:(Iterable<?>)root)if(mapped.isInstance(reg))states.put(reg,fz.getBoolean(reg));
            } else {
                // Resolve each scoped registry id directly on the root registry-of-registries (root.getValue(loc)).
                // Derive the id class (ResourceLocation / Identifier, mapping-dependent) from getValue's own
                // parameter type — the overload whose parameter exposes a static parse(String) — so no class name
                // is hardcoded and the class comes from the live registry's own loader.
                java.lang.reflect.Method getVal=null,parse=null;
                for(java.lang.reflect.Method m:root.getClass().getMethods()){
                    if(!m.getName().equals("getValue")||m.getParameterCount()!=1)continue;
                    Class<?> pt=m.getParameterTypes()[0];
                    try{ java.lang.reflect.Method pm=pt.getMethod("parse",String.class);
                        if(java.lang.reflect.Modifier.isStatic(pm.getModifiers())){getVal=m;parse=pm;break;} }
                    catch(NoSuchMethodException ignore){}
                }
                if(getVal==null) throw new IllegalStateException("Registry.getValue(<id>) not found on "+root.getClass().getName());
                for(String id:scope){ try{ Object reg=getVal.invoke(root,parse.invoke(null,id.trim()));
                        if(reg!=null&&mapped.isInstance(reg)) states.put(reg,fz.getBoolean(reg));
                        else InjectionLogger.warn("thawRegistries: '"+id+"' resolved to "+reg+" (not a MappedRegistry) — skipped");
                    }catch(Throwable t){ InjectionLogger.warn("thawRegistries: failed to resolve '"+id+"' -> "+rootMsg(t)); } }
            }
            RegistryThawSession session=new RegistryThawSession(fz,states);
            try{ session.allTagsField=mapped.getDeclaredField("allTags"); session.allTagsField.setAccessible(true);
                 Class<?> tagSet=session.allTagsField.getType();
                 session.tagsUnbound=tagSet.getMethod("unbound"); session.tagsUnbound.setAccessible(true);
                 session.tagsIsBound=tagSet.getMethod("isBound"); session.tagsIsBound.setAccessible(true);
            }catch(Throwable t){ session.allTagsField=null; InjectionLogger.warn("thaw: allTags swap unavailable ("+rootMsg(t)+") — re-freeze of a thawed registry may fault"); }
            try{
                int tagsSwapped=0;
                for(var e:states.entrySet())if(e.getValue()){
                    Object reg=e.getKey(); fz.setBoolean(reg,false);
                    if(session.allTagsField!=null) try{ Object cur=session.allTagsField.get(reg);
                        if(cur!=null&&(Boolean)session.tagsIsBound.invoke(cur)){ session.tagBackup.put(reg,cur);
                            session.allTagsField.set(reg,session.tagsUnbound.invoke(null)); tagsSwapped++; } }
                        catch(Throwable t){ InjectionLogger.warn("thaw: allTags unbind failed for a registry -> "+rootMsg(t)); }
                }
                InjectionLogger.info("thawed "+states.values().stream().filter(Boolean::booleanValue).count()+"/"+states.size()+" registries"+(all?"":" (scoped: "+scope+")")+(tagsSwapped>0?" (allTags unbound on "+tagsSwapped+" for re-freeze safety)":""));
                return session;
            }catch(Throwable t){session.close();if(session.restored)try{mcgraft.rt.JoinGate.open();}catch(Throwable ignored){}
                if(t instanceof Exception e)throw e;if(t instanceof Error e)throw e;throw new RuntimeException(t);}
        }
        /** Re-assert the thaw: a resource reload re-freezes the static registry access mid-init (ClientRegistryLayer
         *  rebuild), so a one-shot thaw is undone before the payload registers. Called repeatedly while waiting for the
         *  payload to report ready, this holds the scoped registries writable across those re-freezes. No-op once closed. */
        void reassertThaw(){ if(closed.get())return;
            for(var e:original.entrySet()) if(e.getValue()) try{ if(frozen.getBoolean(e.getKey())) frozen.setBoolean(e.getKey(),false); }catch(Throwable ignored){} }
        public void close(){if(!closed.compareAndSet(false,true))return;int count=0,failures=0;
            // Restore the tag sets we swapped out first — but only where nothing rebound them meanwhile (a rebind is
            // authoritative and must win). Then restore each registry's original frozen flag.
            for(var e:tagBackup.entrySet())try{ Object reg=e.getKey(),cur=allTagsField.get(reg);
                if(cur==null||!(Boolean)tagsIsBound.invoke(cur)) allTagsField.set(reg,e.getValue()); }
                catch(Throwable t){LateAttachVerifier.error("REGISTRY_RESTORE_FAILURE","registries","bootstrap-tags",rootMsg(t));}
            for(var e:original.entrySet())try{frozen.setBoolean(e.getKey(),e.getValue());count++;}
                catch(Throwable t){failures++;LateAttachVerifier.error("REGISTRY_RESTORE_FAILURE","registries","bootstrap",rootMsg(t));}
            restored=failures==0;
            InjectionLogger.info("restored original state of "+count+"/"+original.size()+" registries"+(restored?"":"; join gate remains closed"));}
    }
    static void closeThaw(RegistryThawSession t){ if(t!=null) t.close(); }   // t may be null (no registries thawed)
    static void restoreAndOpen(RegistryThawSession thaw,boolean initializationSucceeded){
        if (thaw != null) thaw.close();   // close() computes thaw.restored — must run before we read it below
        boolean restored = thaw == null || thaw.restored;   // null thaw = reload-driven path: registries were never thawed
        // The gate must ALWAYS be released here so a failed/partial bootstrap can never leave multiplayer permanently
        // locked. On full success replay the deferred join; otherwise (registries not fully restored, or the payload did not
        // initialize) don't replay into a half-set-up client, but still unblock so the user can retry the join.
        if(restored && initializationSucceeded){
            try{mcgraft.rt.JoinGate.open();}
            catch(Throwable t){LateAttachVerifier.error("DEFERRED_JOIN_FAILURE","ConnectScreen","join-gate",rootMsg(t));mcgraft.rt.JoinGate.cancelAndOpen();}
        } else {
            InjectionLogger.error("initialization incomplete; dropping deferred join and unblocking gate");
            mcgraft.rt.JoinGate.cancelAndOpen();
        }
    }
    /** An async-bootstrap payload initializes after its start event. Poll the configured readiness probe, then publish
     *  the target retransforms. Restore on every outcome, including timeout/interruption. */
    static void restoreRegistriesAfterInitialization(Class<?> mcCls, Object mc, RegistryThawSession thaw, PayloadConfig cfg) {
        Thread waiter = new Thread(() -> { java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();try {
            Class<?> readyCls = Class.forName(cfg.readyClass, false, SYS);
            Object readyObj = readyCls.getField(cfg.readyInstance).get(null);
            java.lang.reflect.Method initialized = readyCls.getMethod(cfg.readyMethod);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while (!(Boolean) initialized.invoke(readyObj)) {
                if (thaw != null) thaw.reassertThaw();   // hold scoped registries thawed across the reload's re-freeze
                if (System.nanoTime() >= deadline) {
                    LateAttachVerifier.error("BOOTSTRAP_TIMEOUT","registries","bootstrap","Timed out waiting for "+cfg.id+" initialization");
                    break;
                }
                Thread.sleep(50L);
            }
            if(!LateAttachVerifier.hasFatalErrors()){InjectionLogger.info(cfg.id+" initialization completed");ready.set(true);}
        } catch (InterruptedException t) {
            Thread.currentThread().interrupt();
            LateAttachVerifier.error("BOOTSTRAP_INTERRUPTED","registries","bootstrap",String.valueOf(t));
        } catch (Throwable t) {
            LateAttachVerifier.error("BOOTSTRAP_WAIT_FAILURE","registries","bootstrap",rootMsg(t));
        } finally {
            CompletableFuture<Void> done=new CompletableFuture<>();
            // Exactly one of {main-thread restore, timeout/failure fallback} performs the terminal gate action via the
            // CAS below; the loser only ensures registries are restored (thaw.close is idempotent). This removes the
            // race where both the waiter and a slow-but-live main thread drive the join gate.
            java.util.concurrent.atomic.AtomicBoolean finalized=new java.util.concurrent.atomic.AtomicBoolean();
            Runnable restore=()->{try{
                if(ready.get())try{activateLoadedTargets(INST);}
                catch(Throwable t){ready.set(false);LateAttachVerifier.error("TARGET_ACTIVATION_FAILURE","targets","bootstrap",rootMsg(t));InjectionLogger.error("target activation failed -> "+rootMsg(t));}
                if(finalized.compareAndSet(false,true))restoreAndOpen(thaw,ready.get());else closeThaw(thaw);
            }finally{done.complete(null);}};
            try {
                mcCls.getMethod("execute", Runnable.class).invoke(mc, restore);
                // If the main-thread restore runnable (which releases the gate via restoreAndOpen) never completes,
                // release the gate here too so a stalled/failed activation cannot leave multiplayer locked out.
                try{done.get(60,TimeUnit.SECONDS);}
                catch(TimeoutException t){LateAttachVerifier.warn("REGISTRY_RESTORE_MAIN_THREAD_TIMEOUT","registries","bootstrap","Main thread did not finish activation/restore within 60 seconds; applying state fallback");if(finalized.compareAndSet(false,true)){closeThaw(thaw);mcgraft.rt.JoinGate.cancelAndOpen();}else closeThaw(thaw);}
                catch(InterruptedException t){Thread.currentThread().interrupt();if(finalized.compareAndSet(false,true)){closeThaw(thaw);mcgraft.rt.JoinGate.cancelAndOpen();}else closeThaw(thaw);}
                catch(ExecutionException t){LateAttachVerifier.error("REGISTRY_RESTORE_TASK_FAILURE","registries","bootstrap",rootMsg(t));if(finalized.compareAndSet(false,true)){closeThaw(thaw);mcgraft.rt.JoinGate.cancelAndOpen();}else closeThaw(thaw);}
            } catch(Throwable t){ if(finalized.compareAndSet(false,true)){closeThaw(thaw);mcgraft.rt.JoinGate.cancelAndOpen();}else closeThaw(thaw);LateAttachVerifier.error("REGISTRY_RESTORE_SCHEDULE_FAILURE","registries","bootstrap",rootMsg(t)); }
        } }, "mcgraft-registry-restore");
        waiter.setDaemon(true);
        waiter.start();
    }
    static byte[] awApply(String n, byte[] b){ return PLATFORM.applyAw(n, b); }
    /** Resolve the game/data directory to hand the payload: the injector-supplied path (agentArg gameDir), else the
     *  client's REAL game directory — so the payload's data lands next to a normal install instead of in whatever
     *  working directory the client was launched from. Returns null if it can't be resolved. */
    static String resolveGameDir(String a) {
        try {
            String override = InjectionLogger.argValue(a, "gameDir");
            if (override == null || override.isBlank()) {
                Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, SYS);
                Object gd = mcCls.getField("gameDirectory").get(mcCls.getMethod("getInstance").invoke(null));
                if (gd instanceof File file) return file.getAbsolutePath();
            } else if (override.equals(".")) {
                return System.getProperty("user.dir");      // the client's working directory (CWD)
            } else {
                return new File(override).getAbsoluteFile().toPath().normalize().toString();
            }
        } catch (Throwable t) { InjectionLogger.warn("could not resolve game directory ("+rootMsg(t)+"); the payload falls back to the process working directory"); }
        return null;
    }
    /** If the payload declares a system property for its data dir (bootstrap.gameDirProperty), set it to the resolved
     *  game directory. No property declared -> nothing to do (the injector makes no assumption about the payload). */
    static void applyGameDirProperty(PayloadConfig cfg, String dir) {
        if (cfg.gameDirProperty == null || dir == null) return;
        System.setProperty(cfg.gameDirProperty, dir);
        InjectionLogger.info("payload game directory: " + dir + " (via " + cfg.gameDirProperty + ")");
    }
    /** Pick the platform by probing which loader owns the live client. Default vanilla; modded selects a stub host. */
    static LoaderPlatform detectPlatform(Instrumentation inst) throws Exception {
        if (loaderPresent(inst,"net.fabricmc.loader.impl.launch.knot.KnotClassLoader")) { InjectionLogger.info("detected Fabric (Knot) loader"); return new FabricPlatform(); }
        if (loaderPresent(inst,"net.neoforged.fml.classloading.transformation.TransformingClassLoader")) { InjectionLogger.info("detected NeoForge (FML) loader"); return new NeoForgePlatform(inst); }
        InjectionLogger.info("detected vanilla (system) loader"); return new VanillaPlatform();
    }
    static boolean loaderPresent(Instrumentation inst, String dotted){ for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(dotted)) return true; return false; }
    static void verifyAsmRuntime() throws Exception {
        Class<?> opcodes=Class.forName("org.objectweb.asm.Opcodes",true,SYS);
        String version=opcodes.getPackage().getImplementationVersion();
        Object source=opcodes.getProtectionDomain().getCodeSource()==null?"bootstrap/unknown":opcodes.getProtectionDomain().getCodeSource().getLocation();
        Class<?> asmInfo=Class.forName("org.spongepowered.asm.util.asm.ASM",true,SYS);
        boolean supported=(Boolean)asmInfo.getMethod("isAtLeastVersion",int.class,int.class).invoke(null,9,8);
        InjectionLogger.info("ASM runtime="+version+" source="+source+" Java25Compatible="+supported);
        if(!supported)throw new IllegalStateException("the payload requires ASM >= 9.8, but active ASM is "+version+" from "+source+". Restart with a correctly packaged agent or remove the preloaded ASM collision.");
    }
    static void preflightPayloadClasses(Path lbJar,RetransformConverter.Resolver resolver,Map<String,String[]> gadded,
            Map<String,String[]> gfield,Map<String,List<String[]>> ifaces){int total=0,changed=0,failed=0;
        PayloadConfig cfg=PayloadConfig.get();
        try(JarFile jf=new JarFile(lbJar.toFile())){for(var en=jf.entries();en.hasMoreElements();){JarEntry e=en.nextElement();String n=e.getName();
            if(!n.endsWith(".class"))continue;
            // Scope to the payload's own packages when the build derived them; else accept every class in the jar
            // (the payload jar holds the mod's classes; nested dependency jars live under META-INF/jars, not here).
            if(!cfg.payloadPackages.isEmpty()&&!cfg.ownsClass(n.substring(0,n.length()-6)))continue; total++;
            try{byte[] b;try(InputStream in=jf.getInputStream(e)){b=in.readAllBytes();}
                byte[] rw=AccessorBridgeRewriter.rewrite(n.substring(0,n.length()-6),b);
                if(!ifaces.isEmpty())rw=RetransformConverter.rewriteCaller(rw,ifaces);
                rw=RetransformConverter.rewritePayloadAw(rw,resolver);
                if(!LateAttachVerifier.verifyCaller(n.substring(0,n.length()-6),b,rw,gadded,gfield,ifaces))failed++;
                if(!Arrays.equals(b,rw))changed++;
            }catch(Throwable t){failed++;LateAttachVerifier.error("CALLER_PREFLIGHT_FAILURE",n,"caller",rootMsg(t));}
        }}catch(Throwable t){LateAttachVerifier.error("CALLER_PREFLIGHT_IO_FAILURE",String.valueOf(lbJar),"caller",rootMsg(t));failed++;}
        InjectionLogger.info("payload caller preflight: "+total+" classes, "+changed+" rewritten, "+failed+" failed");
    }
    /** Pre-publish ACCESS gate — see {@link LateAttachVerifier#verifyMemberAccess}. Checks every member reference the
     *  emitted SIDECAR bytes actually contain against the LIVE class, i.e. the modifiers the JVM will enforce, and
     *  fails the injection before any target is retransformed.
     *
     *  <p>SIDECARS ONLY. target' is not scanned: it is the same class the JVM already accepted, with the same schema
     *  and the same member references, minus the mixin-added members — rewriteRefs only ever redirects a reference to
     *  a member of OUR sidecar, and those are public by construction. Nothing there can newly become inaccessible.
     *  Scanning it as well was measured to cost +948 resolved classes on LiquidBounce (see below) and produced only
     *  false positives (a record's super() into protected Record.&lt;init&gt;, a nested record reading its outer
     *  class's private fields), because a target' that has not loaded yet has no live Class to read its nest or
     *  superclass chain from. The sidecar is where the bug class lives and where the answer is unambiguous.
     *
     *  <p>Placed HERE — after {@code addTransformer}, before the retransform that publishes target' — rather than at
     *  the conversion gate, for a measured reason: the check reflects on already-loaded classes, and
     *  {@code Class.getDeclaredFields/getDeclaredMethods} RESOLVE every member descriptor, which force-loads those
     *  types. Run before the CFT exists, that pulls mixin targets in unhooked (measured on Wurst: +67 classes loaded
     *  during conversion and CommandSuggestions/StatsScreen/DirectJoinServerScreen silently went inert). Here the CFT
     *  is live, so anything it drags in converts normally, and nothing that could execute an illegal access has been
     *  published yet: sidecars are defined but no target' references them until the retransform below. */
    static void verifyMemberAccessGate(Instrumentation inst){
        Map<String,Class<?>> live = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) { String n = c.getName().replace('.','/');
            Class<?> prev = live.get(n); if (prev == null || (prev.getClassLoader() != SYS && c.getClassLoader() == SYS)) live.put(n, c); }
        int mark = LateAttachVerifier.issueMark(), errs0 = LateAttachVerifier.errorCount();
        int loaded0 = inst.getAllLoadedClasses().length;
        for (var e : convMap.entrySet()) { Conv cv = e.getValue();
            if (cv.whole) continue;   // future-loaded and defined WHOLE: it genuinely receives the widened bytes on load
            LateAttachVerifier.verifyMemberAccess(e.getKey(), "sidecar", cv.sidecar, live::get);
        }
        List<String> found = LateAttachVerifier.issuesSince(mark);
        int illegal = LateAttachVerifier.errorCount() - errs0;   // the remainder are ACCESS_RESOLVE/ACCESS_SCAN notes (WARN), which never gate
        // Resolving a member forces the JVM to resolve that member's descriptor types, so the scan itself pulls
        // classes in. They all arrive through the CFT (installed above) and are converted normally — a future-loaded
        // target dragged in here still gets its target'/WHOLE definition. Counted anyway so the cost stays visible.
        InjectionLogger.info("pre-publish access gate: "+convMap.size()+" conversions checked, "+illegal+" illegal member access(es)"
            +" (+"+(inst.getAllLoadedClasses().length-loaded0)+" classes resolved by the scan)"
            +(found.size()>illegal ? " ("+(found.size()-illegal)+" reference(s) not resolvable here, noted only)" : ""));
        for (String f : found) { if (illegal > 0) InjectionLogger.error("  "+f); else InjectionLogger.warn("  "+f); }
    }
    static void refreshLoadedAccessState(Instrumentation inst,Object aw){try{
        // No access widener (aw==null) -> no widened classes to reconcile, but still refresh the attach-window class set.
        Set<String> ca=java.util.Collections.emptySet();
        if(aw!=null){ java.lang.reflect.Field caF=aw.getClass().getDeclaredField("classAccessible");caF.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> c2=(Set<String>)caF.get(aw); ca=c2; }
        int added=0;for(Class<?> c:inst.getAllLoadedClasses()){String n=c.getName().replace('.','/');
            if(!(n.startsWith("net/minecraft/")||n.startsWith("com/mojang/")))continue;
            if(preLoaded.add(n))added++;
            if(ca.contains(n)&&!java.lang.reflect.Modifier.isPublic(c.getModifiers()))INACC.add(n);
        }
        InjectionLogger.info("refreshed attach-window classes: +"+added+"; inaccessible="+INACC.size());
    }catch(Throwable t){LateAttachVerifier.error("ACCESS_STATE_REFRESH_FAILURE","access-widener","preflight",rootMsg(t));}}
    static boolean isPreloadedMc(String o){ return preLoaded.contains(o) && (o.startsWith("net/minecraft/")||o.startsWith("com/mojang/")); }
    static boolean fieldNonPublic(String owner, String name){ return cachedBoolean(npField,owner+"#"+name,() -> {
        try { Class<?> c = Class.forName(owner.replace('/','.'), false, SYS);
            for (Class<?> t=c; t!=null; t=t.getSuperclass()) { for (var f : t.getDeclaredFields()) if (f.getName().equals(name)) return !java.lang.reflect.Modifier.isPublic(f.getModifiers()); }
        } catch(Throwable t){} return false; }); }
    static boolean methodNonPublic(String owner, String name, String desc){ return cachedBoolean(npMethod,owner+"#"+name+" "+desc,() -> {
        try { Class<?> c = Class.forName(owner.replace('/','.'), false, SYS);
            if (name.equals("<init>")) { for (var ctor : c.getDeclaredConstructors()) if (org.objectweb.asm.Type.getConstructorDescriptor(ctor).equals(desc)) return !java.lang.reflect.Modifier.isPublic(ctor.getModifiers()); return false; }
            for (Class<?> t=c; t!=null; t=t.getSuperclass()) { for (var m : t.getDeclaredMethods()) if (m.getName().equals(name) && org.objectweb.asm.Type.getMethodDescriptor(m).equals(desc)) return !java.lang.reflect.Modifier.isPublic(m.getModifiers()); }
        } catch(Throwable t){} return false; }); }
    static boolean cachedBoolean(Map<String,Boolean> cache,String key,java.util.function.BooleanSupplier resolver){
        Boolean cached=cache.get(key);if(cached!=null)return cached;
        boolean resolved=resolver.getAsBoolean();Boolean raced=cache.putIfAbsent(key,resolved);
        return raced==null?resolved:raced;
    }
    static String rootMsg(Throwable t){ while(t.getCause()!=null)t=t.getCause(); return t.getClass().getSimpleName()+": "+t.getMessage(); }

    /** Recursively extract a jar's Fabric jar-in-jar dependencies (META-INF/jars/&lt;name&gt;.jar) into {@code tmpDir},
     *  returning every nested jar at all levels. Late-attach bypasses the Fabric loader, which is what normally unpacks
     *  and classpaths a mod's bundled dependencies — so the injector does it here, letting the payload (and any staged
     *  dependency mod, e.g. fabric-language-kotlin bundling the Kotlin stdlib) bring its own libraries. */
    static java.util.List<java.nio.file.Path> extractNestedJars(java.nio.file.Path jar, java.nio.file.Path tmpDir) {
        java.util.List<java.nio.file.Path> out = new ArrayList<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            for (var en = jf.entries(); en.hasMoreElements();) {
                java.util.jar.JarEntry e = en.nextElement(); String n = e.getName();
                if (!n.startsWith("META-INF/jars/") || !n.endsWith(".jar") || e.isDirectory()) continue;
                java.nio.file.Path o = tmpDir.resolve(new File(n).getName());
                try (InputStream in = jf.getInputStream(e)) { java.nio.file.Files.copy(in, o, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                o.toFile().deleteOnExit();
                out.add(o);
                out.addAll(extractNestedJars(o, tmpDir));   // nested-of-nested
            }
        } catch (Throwable t) { InjectionLogger.warn("jar-in-jar extract failed for "+jar.getFileName()+" -> "+rootMsg(t)); }
        return out;
    }
}
