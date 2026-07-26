import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;
import java.util.function.Predicate;
import mcgraft.rt.InjectionLogger;

/**
 * Schema-neutral converter core. Given an ALREADY-LOADED class's original bytes O and the Mixin-transformed
 * bytes X (X = O + added members {fields, methods, interfaces} + body-only call-site edits), produce:
 *   - target': X with all ADDED members removed and their references rewritten to an external sidecar
 *     -> same schema as O (same fields/methods/interfaces) -> legal for Instrumentation.retransformClasses.
 *   - sidecar: a standalone class holding the relocated state + handler methods.
 *
 * Relocation:
 *   added instance field  -> a field on a per-instance State object kept in an IdentityHashMap (one map),
 *                            accessed via static get_/set_ helpers (no boxing; matches the benchmark).
 *   added static field    -> a static field on the sidecar.
 *   added method          -> a static method on the sidecar with the target as param0 (instance slot0=this
 *                            maps 1:1 to static slot0=param0, so bodies need no local reindexing).
 *   added interface       -> dropped from target' (external the payload call-sites are rewritten at build time).
 */
public class RetransformConverter {
    public static final class Result {
        public byte[] target, sidecar; public String sidecarName;
        public List<String> droppedInterfaces = new ArrayList<>();
        public List<String> relocatedFields = new ArrayList<>(), relocatedMethods = new ArrayList<>();
        /** Members an EARLIER payload had already relocated off this target, which this conversion stripped from
         *  target' without re-relocating (docs/multi-payload.md §1.2). Non-empty only from the second injection on. */
        public List<String> foreignMembers = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
    }

    // ---- ENFORCED access for ALREADY-LOADED classes -----------------------------------------------------------------
    // Everything the converter reads comes from CLASS_BYTES, i.e. the loader's PRE-MIXIN bytes — which on Fabric are
    // handed back with the payload's AccessWidener ALREADY APPLIED. For a class that was not yet loaded at attach that
    // is the truth (it gets DEFINED widened). For an ALREADY-LOADED class it is a LIE: the widener was registered on
    // the live ClassTweaker after the class was defined, and retransformClasses cannot change member modifiers, so the
    // loaded class keeps its original access forever. Believing the widened bytes made the converter emit a direct
    // GETFIELD/INVOKE of a still-private member from the sidecar — a different class — which the JVM rejects with
    // IllegalAccessError the first time that handler runs (observed: ChatComponent.trimmedMessages under Wurst's AW).
    //
    // The correction reads the RAW bytes (what the loader had before the payload's widener existed) rather than the
    // live java.lang.Class. Reflecting on the live class is the more direct statement of "what the JVM enforces", but
    // Class.getDeclaredFields/getDeclaredMethods RESOLVE every member descriptor, which force-LOADS those types — and
    // conversion runs before the agent's ClassFileTransformer is installed, so a mixin target dragged in that way
    // loads unhooked. Measured on Wurst: +67 classes loaded during conversion and three targets
    // (CommandSuggestions, StatsScreen, DirectJoinServerScreen) silently went inert. Raw bytes cost nothing and can
    // only ever be MORE restrictive than the live class (the only thing between them is another mod's boot-time
    // widener), so the worst case is a redundant trip through the reflective accessor.
    /** Class bytes as they were BEFORE the payload's AccessWidener was registered. Installed by the agent. */
    public static volatile java.util.function.Function<String,byte[]> RAW_CLASS_BYTES;
    /** Was this class already loaded when we attached (so the payload's widener could not reach it)? */
    public static volatile Predicate<String> ALREADY_LOADED = n -> false;
    /** Already-loaded classes an `accessible class` widening could not reach (the agent's INACC set): the bytes claim
     *  ACC_PUBLIC, the loaded class is not. The same lie, at class level. */
    public static volatile Predicate<String> TYPE_INACCESSIBLE = n -> false;
    private static boolean alreadyLoaded(String internal){ try { return ALREADY_LOADED.test(internal); } catch(Throwable t){ return false; } }
    private static final java.util.concurrent.ConcurrentHashMap<String,Optional<ClassNode>> RAW_NODES = new java.util.concurrent.ConcurrentHashMap<>();
    private static ClassNode rawNode(String internal){
        return RAW_NODES.computeIfAbsent(internal, k -> { try {
            var f = RAW_CLASS_BYTES; byte[] b = f == null ? null : f.apply(k); if (b == null) return Optional.<ClassNode>empty();
            ClassNode n = new ClassNode(); new ClassReader(b).accept(n, ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            return Optional.of(n);
        } catch(Throwable t){ return Optional.<ClassNode>empty(); } }).orElse(null);
    }
    /** Access flags the JVM enforces for the field {@code owner.name:desc}. Null when {@code owner} was not already
     *  loaded (the widened bytes are then the truth) or does not DECLARE that field (caller falls back / keeps walking). */
    static Integer enforcedFieldAccess(String owner, String name, String desc){
        if (desc == null || !alreadyLoaded(owner)) return null;
        ClassNode n = rawNode(owner); if (n == null) return null;
        for (FieldNode f : n.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f.access;
        return null;
    }
    /** Same for a method/constructor declared by {@code owner}. */
    static Integer enforcedMethodAccess(String owner, String name, String desc){
        if (!alreadyLoaded(owner)) return null;
        ClassNode n = rawNode(owner); if (n == null) return null;
        for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m.access;
        return null;
    }
    /** Class access with an inert (already-loaded) `accessible class` widening masked back off. */
    static int effectiveClassAccess(String internal, int fromBytes){
        if (fromBytes >= 0 && (fromBytes & Opcodes.ACC_PUBLIC) != 0) { try { if (TYPE_INACCESSIBLE.test(internal)) return fromBytes & ~Opcodes.ACC_PUBLIC; } catch(Throwable ignored){} }
        return fromBytes;
    }

    /** NeoForge (JPMS): sidecars cannot enter module `minecraft` (it owns net/minecraft/*), so they are RELOCATED into
     *  a payload-owned package. Null (vanilla/Fabric) keeps the sidecar in the target's own package. When set, the sidecar
     *  becomes cross-package for EVERY target, so the pkg(caller)!=pkg(owner) checks in illegalSidecarMethod /
     *  rewriteRefs automatically route ALL non-public MC access (package-private included) through AwReflect — no other
     *  logic change is needed. Must be a payload-owned package dir ending in '/', e.g. "mcgraft/sc/". */
    public static volatile String SIDECAR_PKG = null;
    /** Injective-enough mangle: net/a/B$C -> <pkg>net_a_B_C$$Sidecar$<scope>. Target internal names are a fixed, unique
     *  set, and the trailing scope key makes the name per-PAYLOAD as well as per-target: two payloads that mixin the
     *  same Minecraft class each get their own sidecar instead of the second silently colliding with the first.
     *
     *  <p>Everything else the converter generates is derived from this name — {@code $State}, and the {@code h$},
     *  {@code get$/set$}, {@code refGet$/refSet$}, {@code F$}, {@code RM$}, {@code SMH$}, {@code rmi$}, {@code smi$},
     *  {@code ifd$} members, all of which are emitted ON the sidecar (or its {@code $State}) and referenced through it.
     *  So they inherit the scoping without needing a scope component of their own. */
    static String sidecarInternal(PayloadScope scope, String internal) {
        String base = SIDECAR_PKG == null ? internal + "$$Sidecar"
                                          : SIDECAR_PKG + internal.replace('/', '_').replace('$', '_') + "$$Sidecar";
        return base + "$" + scope.key;
    }

    final PayloadScope scope;
    final String targetInternal, targetDesc, sidecar, stateName;
    final Set<String> addedFieldKeys = new HashSet<>();   // name+" "+desc
    final Set<String> addedInstanceFields = new HashSet<>(); // name (instance only)
    final Set<String> addedStaticFields = new HashSet<>();
    final Set<String> addedMethodKeys = new HashSet<>();  // name+" "+desc
    // Members an EARLIER payload already relocated off this target: removed from target' like our own, but never
    // relocated into THIS payload's sidecar (they belong to the other payload's). See the block in run().
    final Set<String> foreignFieldKeys = new HashSet<>();
    final Set<String> foreignMethodKeys = new HashSet<>();
    final Set<String> foreignIfaces = new HashSet<>();
    final Map<String,String> fieldDesc = new HashMap<>();

    RetransformConverter(PayloadScope scope, String targetInternal) {
        this.scope = scope;
        this.targetInternal = targetInternal;
        this.targetDesc = "L" + targetInternal + ";";
        // Sidecar lives in the TARGET'S OWN PACKAGE so relocated handler bodies keep package-private access to
        // target-package internals (e.g. a package-private inner class like GuiRenderState$Node). Only genuinely
        // PRIVATE members still need the reflective path (B). Defined with the target's ProtectionDomain.
        this.sidecar = sidecarInternal(scope, targetInternal);
        this.stateName = sidecar + "$State";
    }

    final Map<String,Integer> oFieldAcc = new HashMap<>();     // original field name -> access (for B: non-public => reflect)
    final Map<String,String> oFieldDesc = new HashMap<>();
    final LinkedHashSet<String> reflectFields = new LinkedHashSet<>();  // "name desc" of non-public target fields accessed
    final Map<String,Integer> oMethodAcc = new HashMap<>();    // original "name desc" -> access
    final LinkedHashSet<String> reflectMethods = new LinkedHashSet<>(); // "name desc" of private target methods called from sidecar
    final Set<String> reflectStaticMethods = new HashSet<>();  // subset of reflectMethods invoked without a receiver
    /** {@code "<refcOwner> <name> <desc>"} of every {@code super.x()} site found in a body we relocated, lowered to a
     *  {@code findSpecial} MethodHandle bridge. Keyed by OWNER as well as name+desc: one target can super-call the same
     *  signature against two different levels of its hierarchy. */
    final LinkedHashSet<String> superMethods = new LinkedHashSet<>();
    final LinkedHashMap<String,Object[]> ifaceDispatchTramps = new LinkedHashMap<>();

    /** Access of an EXISTING target field, as the JVM enforces it. O is the AW-WIDENED pre-mixin view; for an
     *  already-loaded target that widening was inert, so the pre-widener (raw) flags override it. A future-loaded
     *  target has no raw override and O's widened flags stand, which is correct there (it IS defined widened). */
    int targetFieldAccess(String name) {
        Integer enforced = enforcedFieldAccess(targetInternal, name, oFieldDesc.get(name));
        return enforced != null ? enforced : oFieldAcc.getOrDefault(name, 0);
    }
    /** Same for an existing target method/ctor. Unknown (i.e. a mixin-ADDED method) -> 0, as before. */
    int targetMethodAccess(String name, String desc) {
        String key = name + " " + desc;
        if (!oMethodAcc.containsKey(key)) return 0;
        Integer enforced = enforcedMethodAccess(targetInternal, name, desc);
        return enforced != null ? enforced : oMethodAcc.get(key);
    }

    Result run(byte[] original, byte[] transformed) {
        ClassNode O = read(original), X = read(transformed);
        for (FieldNode f : O.fields) { oFieldAcc.put(f.name, f.access); oFieldDesc.put(f.name, f.desc); }
        for (MethodNode m : O.methods) oMethodAcc.put(m.name + " " + m.desc, m.access);
        // (oFieldAcc/oMethodAcc hold O's WIDENED flags; targetFieldAccess/targetMethodAccess correct them against the
        //  pre-widener bytes for an already-loaded target, where the widening was inert. Read them, not these maps.)
        Result r = new Result(); r.sidecarName = sidecar;

        Set<String> oF = keysF(O), oM = keysM(O); Set<String> oI = new HashSet<>(O.interfaces);
        // O is the TRUE original schema — the plane the loaded class is actually at, captured before ANY payload was
        // injected and deliberately kept stable across injections (FullInjectAgent.SCHEMA_BASELINE). For injection N>1
        // the mixin output X therefore carries every EARLIER payload's added members too, because their configs stay
        // selected in the live Mixin and one transformClassBytes re-derives all of them together.
        //
        // Those members are FOREIGN: strip them from target' (the loaded class does not have them, so keeping them is
        // exactly the "extra methods" schema failure) but do NOT relocate them here. They already live on THEIR
        // payload's sidecar holding that payload's state, and rewriteRefs redirects every reference to them there for
        // free through gLookup, which searches all scopes. Relocating a second copy would fork the state.
        // See docs/multi-payload.md §1.2.
        List<FieldNode> addedFields = new ArrayList<>();
        for (FieldNode f : X.fields) if (!oF.contains(f.name + " " + f.desc)) {
            PayloadScope fo = foreignFieldOwner(scope, targetInternal, f.name, f.desc);
            if (fo != null) { foreignFieldKeys.add(f.name + " " + f.desc); r.foreignMembers.add(fo.id + ": field " + f.name); continue; }
            addedFields.add(f); addedFieldKeys.add(f.name + " " + f.desc); fieldDesc.put(f.name, f.desc);
            if ((f.access & Opcodes.ACC_STATIC) != 0) addedStaticFields.add(f.name); else addedInstanceFields.add(f.name);
            r.relocatedFields.add(((f.access & Opcodes.ACC_STATIC) != 0 ? "static " : "") + f.name + " " + f.desc);
        }
        List<MethodNode> addedMethods = new ArrayList<>();
        for (MethodNode m : X.methods) if (!oM.contains(m.name + " " + m.desc)) {
            PayloadScope mo = foreignMethodOwner(scope, targetInternal, m.name, m.desc);
            if (mo != null) { foreignMethodKeys.add(m.name + " " + m.desc); r.foreignMembers.add(mo.id + ": " + m.name); continue; }
            addedMethods.add(m); addedMethodKeys.add(m.name + " " + m.desc);
            r.relocatedMethods.add(m.name + " " + m.desc);
        }
        for (String i : X.interfaces) if (!oI.contains(i)) {
            PayloadScope io = foreignIfaceOwner(scope, targetInternal, i);
            if (io != null) { foreignIfaces.add(i); r.foreignMembers.add(io.id + ": iface " + i); }
            else r.droppedInterfaces.add(i);
        }

        // ---- build sidecar ----
        ClassNode S = new ClassNode();
        S.version = X.version; S.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER; S.name = sidecar; S.superName = "java/lang/Object";
        // State object (one field per added instance field) + the identity map
        ClassNode St = new ClassNode();
        St.version = X.version; St.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER; St.name = stateName; St.superName = "java/lang/Object";
        St.methods.add(defaultCtor(St.superName));
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) == 0)
            St.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, f.name, f.desc, null, null));
        // static fields (relocated static @Unique) go straight on the sidecar. Force PUBLIC and non-FINAL: they are
        // now read/written cross-class from target' (a different class than the sidecar), which private/final forbids.
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) != 0)
            S.fields.add(new FieldNode((f.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC, f.name, f.desc, f.signature, f.value));
        // STATE map + getState()
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "STATE", "Ljava/util/Map;", null, null));
        addSidecarClinit(S);
        addGetState(S);
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) == 0) addFieldAccessors(S, f);
        S.methods.add(buildInstanceStateInitializer(X));
        // relocate the added methods as sidecar statics (body rewritten); an added <clinit> (static @Unique field
        // initializer the mixin merged into the target) is MERGED into the sidecar's own <clinit>, not relocated
        // as an (illegal) h$<clinit>.
        for (MethodNode m : addedMethods) {
            if (m.name.equals("<clinit>")) { mergeIntoSidecarClinit(S, m); continue; }
            if (m.name.equals("<init>")) { r.notes.add("WARNING: mixin added <init> to " + targetInternal + " — dropped (unexpected)"); continue; }
            S.methods.add(relocateMethod(m));
        }

        // MODIFIED <clinit>: the mixin APPENDED static @Unique field init to the target's EXISTING <clinit> (so it is
        // not an "added" method). For an ALREADY-LOADED target that <clinit> won't re-run after retransform, leaving
        // the relocated static field null -> copy the appended delta into the sidecar <clinit> (which runs fresh when
        // the sidecar loads). Harmlessly redundant for future-loaded targets.
        if (!addedStaticFields.isEmpty()) {
            MethodNode xc = findMethod(X, "<clinit>"), oc = findMethod(O, "<clinit>");
            if (xc != null && oc != null) copyClinitDeltaToSidecar(S, oc, xc);
        }

        // ---- rewrite target' ----
        X.interfaces.removeIf(i -> r.droppedInterfaces.contains(i) || foreignIfaces.contains(i));
        X.fields.removeIf(f -> addedFieldKeys.contains(f.name + " " + f.desc) || foreignFieldKeys.contains(f.name + " " + f.desc));
        X.methods.removeIf(m -> addedMethodKeys.contains(m.name + " " + m.desc) || foreignMethodKeys.contains(m.name + " " + m.desc));
        for (MethodNode m : X.methods) rewriteRefs(m, false);
        for (var e : ifaceDispatchTramps.entrySet()) {
            Object[] v = e.getValue();
            S.methods.add(buildIfaceDispatchTramp(e.getKey(), (String)v[0], (String)v[1], (String)v[2]));
        }
        // B: generate reflective accessors in the sidecar for every non-public target field we rewrote
        for (String key : reflectFields) { String nm = key.substring(0, key.indexOf(' ')), dc = key.substring(key.indexOf(' ') + 1); addReflectiveAccessors(S, nm, dc); }
        if (!reflectFields.isEmpty()) addReflectInit(S);
        r.notes.add("reflective (non-public @Shadow) fields: " + reflectFields);
        // B (methods): reflective invokers for private target methods the relocated sidecar body calls
        for (String key : reflectMethods) { String nm = key.substring(0, key.indexOf(' ')), dc = key.substring(key.indexOf(' ') + 1); addReflectiveInvoker(S, nm, dc, reflectStaticMethods.contains(key)); }
        if (!reflectMethods.isEmpty()) addReflectMethodInit(S);
        r.notes.add("reflective (private @Shadow) methods: " + reflectMethods);
        // super.x() bridges: findSpecial MethodHandles bound with specialCaller = the target (see addSuperInvoker).
        // Every rewriteRefs(inSidecar=true) call has already run by this point (relocateMethod, the merged/appended
        // <clinit> deltas and buildInstanceStateInitializer are all above), so superMethods is complete here — the
        // same invariant reflectFields/reflectMethods rely on.
        for (String key : superMethods) { String[] k = key.split(" "); addSuperInvoker(S, k[0], k[1], k[2]); }
        if (!superMethods.isEmpty()) addSuperMethodInit(S);
        r.notes.add("super-call (findSpecial) bridges: " + superMethods);

        // Retransform must publish the loaded class's exact redefinition-constrained schema. Mixin can reorder overwritten methods
        // and @Mutable deliberately clears ACC_FINAL on @Shadow fields; both are useful while producing X but neither
        // is legal to publish over an already-loaded class. Put the surviving transformed members back in O's order
        // and restore their original modifiers/structural attributes while retaining X's transformed method bodies and
        // supported class-file version (Mixin intentionally lifts older dependency classes to its compatibility level).
        restoreOriginalSchema(O, X);
        recordRtCalls(X); recordRtCalls(S);
        r.target = write(X, original);
        // emit sidecar + state as one combined verify pass isn't needed; caller defines both
        r.sidecar = write(S, null);
        r.notes.add("state class: " + stateName + " (" + addedInstanceFields.size() + " instance fields)");
        // state bytes returned via stateBytes()
        stateBytes = write(St, null);
        return r;
    }
    byte[] stateBytes;
    public byte[] stateBytes() { return stateBytes; }

    /** Every {@code mcgraft/rt/*} call site the converter actually EMITTED, as {@code owner|name|desc}.
     *
     *  <p>These descriptors are hand-built by string concatenation at a dozen-odd emit sites, and the runtime helpers
     *  they name are an implicit contract that nothing checks: a mismatch compiles fine, passes the verifier, and only
     *  surfaces as NoSuchMethodError/IllegalAccessError when the game happens to execute that path — i.e. AFTER the
     *  retransforms are published and the injection can no longer be rolled back. Two review criticals were exactly
     *  this shape (an emitted call to a method that didn't exist; a trampoline emitted private but called cross-class).
     *
     *  <p>Recording what was emitted, rather than maintaining a hand-written list of what we THINK is emitted, is the
     *  point: a list would drift from the emit sites, which is the failure mode being defended against. */
    public static final Set<String> EMITTED_RT_CALLS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Record a single emitted {@code mcgraft/rt/*} call site. Used where the emit does NOT go through a ClassNode the
     *  converter later hands to {@link #recordRtCalls} — i.e. the caller-rewrite path, whose output classes are not
     *  re-scanned. */
    static void recordRtCall(String owner, String name, String desc) { EMITTED_RT_CALLS.add(owner + "|" + name + "|" + desc); }

    static void recordRtCalls(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext())
                if (p instanceof MethodInsnNode mi && mi.owner.startsWith("mcgraft/rt/"))
                    EMITTED_RT_CALLS.add(mi.owner + "|" + mi.name + "|" + mi.desc);
        }
    }

    /** Resolve every recorded call against {@code loader}; returns a description of each one that does NOT resolve
     *  (empty list = contract intact). Uses {@code getMethod}, i.e. PUBLIC only — which is what an INVOKESTATIC from a
     *  different class actually requires, so this also catches a helper that exists but isn't reachable. */
    public static List<String> unresolvedRtCalls(ClassLoader loader) {
        List<String> bad = new ArrayList<>();
        for (String sig : EMITTED_RT_CALLS) {
            int a = sig.indexOf('|'), b = sig.indexOf('|', a + 1);
            String owner = sig.substring(0, a), name = sig.substring(a + 1, b), desc = sig.substring(b + 1);
            // <clinit> is never the target of an emitted call site; it runs on class init.
            if (name.equals("<clinit>")) continue;
            try {
                Class<?> c = Class.forName(owner.replace('/', '.'), false, loader);
                Type[] at = Type.getArgumentTypes(desc);
                Class<?>[] ps = new Class<?>[at.length];
                for (int i = 0; i < at.length; i++) ps[i] = classForType(at[i], loader);
                // A constructor is INVOKESPECIAL <init>, not a method — getMethod can never find one, so looking it up
                // that way reports a false break on every injection (the sidecar <clinit> does
                // `NEW WeakIdentityHashMap; INVOKESPECIAL <init>()V`). Resolve it as a constructor instead.
                if (name.equals("<init>")) { c.getConstructor(ps); continue; }
                java.lang.reflect.Method m = c.getMethod(name, ps);
                Class<?> want = classForType(Type.getReturnType(desc), loader);
                if (!m.getReturnType().equals(want))
                    bad.add(sig + " -> resolves but returns " + m.getReturnType().getName() + ", expected " + want.getName());
            } catch (Throwable t) {
                bad.add(sig + " -> " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            }
        }
        return bad;
    }

    private static Class<?> classForType(Type t, ClassLoader loader) throws ClassNotFoundException {
        switch (t.getSort()) {
            case Type.VOID:    return void.class;
            case Type.BOOLEAN: return boolean.class;
            case Type.CHAR:    return char.class;
            case Type.BYTE:    return byte.class;
            case Type.SHORT:   return short.class;
            case Type.INT:     return int.class;
            case Type.FLOAT:   return float.class;
            case Type.LONG:    return long.class;
            case Type.DOUBLE:  return double.class;
            default:           return Class.forName(t.getInternalName().replace('/', '.'), false, loader);
        }
    }
    public String stateName() { return stateName; }

    static void restoreOriginalSchema(ClassNode O, ClassNode X) {
        if (!Objects.equals(O.name, X.name)) throw schemaFailure(O.name, "class name changed to " + X.name);
        if (!Objects.equals(O.superName, X.superName)) throw schemaFailure(O.name, "superclass changed from " + O.superName + " to " + X.superName);
        int classKind = Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_MODULE | Opcodes.ACC_RECORD;
        if (((O.access ^ X.access) & classKind) != 0) throw schemaFailure(O.name, "class kind modifiers changed");
        if (O.interfaces.size() != X.interfaces.size() || !new HashSet<>(O.interfaces).equals(new HashSet<>(X.interfaces)))
            throw schemaFailure(O.name, "original interface set changed from " + O.interfaces + " to " + X.interfaces);
        X.access = O.access;
        X.name = O.name;
        X.signature = O.signature;
        X.superName = O.superName;
        X.interfaces = new ArrayList<>(O.interfaces);
        X.outerClass = O.outerClass;
        X.outerMethod = O.outerMethod;
        X.outerMethodDesc = O.outerMethodDesc;
        X.nestHostClass = O.nestHostClass;
        X.nestMembers = copy(O.nestMembers);
        X.permittedSubclasses = copy(O.permittedSubclasses);
        X.recordComponents = O.recordComponents == null ? null : new ArrayList<>(O.recordComponents);
        X.innerClasses = O.innerClasses == null ? null : new ArrayList<>(O.innerClasses);

        LinkedHashMap<String,FieldNode> fields = new LinkedHashMap<>();
        for (FieldNode f : X.fields) putUnique(fields, f.name + " " + f.desc, f, "field", X.name);
        List<FieldNode> orderedFields = new ArrayList<>(O.fields.size());
        for (FieldNode of : O.fields) {
            String key = of.name + " " + of.desc;
            FieldNode xf = fields.remove(key);
            if (xf == null) throw schemaFailure(X.name, "missing field " + key);
            if (((of.access ^ xf.access) & Opcodes.ACC_STATIC) != 0)
                throw schemaFailure(X.name, "field static kind changed for " + key);
            xf.access = of.access;
            xf.signature = of.signature;
            xf.value = of.value;
            orderedFields.add(xf);
        }
        if (!fields.isEmpty()) throw schemaFailure(X.name, "extra fields " + fields.keySet());
        X.fields = orderedFields;

        LinkedHashMap<String,MethodNode> methods = new LinkedHashMap<>();
        for (MethodNode m : X.methods) putUnique(methods, m.name + " " + m.desc, m, "method", X.name);
        List<MethodNode> orderedMethods = new ArrayList<>(O.methods.size());
        for (MethodNode om : O.methods) {
            String key = om.name + " " + om.desc;
            MethodNode xm = methods.remove(key);
            if (xm == null) throw schemaFailure(X.name, "missing method " + key);
            int codeKind = Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
            if (((om.access ^ xm.access) & codeKind) != 0)
                throw schemaFailure(X.name, "method static/abstract/native kind changed for " + key);
            boolean originalHasCode = (om.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            boolean transformedHasCode = xm.instructions != null && xm.instructions.size() != 0;
            if (originalHasCode != transformedHasCode)
                throw schemaFailure(X.name, "method code kind changed for " + key);
            xm.access = om.access;
            xm.signature = om.signature;
            xm.exceptions = copy(om.exceptions);
            orderedMethods.add(xm);
        }
        if (!methods.isEmpty()) throw schemaFailure(X.name, "extra methods " + methods.keySet());
        X.methods = orderedMethods;
    }

    static <T> List<T> copy(List<T> values) {
        return values == null ? null : new ArrayList<>(values);
    }

    static <T> void putUnique(Map<String,T> values, String key, T value, String kind, String owner) {
        if (values.put(key, value) != null) throw schemaFailure(owner, "duplicate " + kind + " " + key);
    }

    static IllegalStateException schemaFailure(String owner, String detail) {
        return new IllegalStateException("Cannot restore original schema for " + owner + ": " + detail);
    }

    // ---- sidecar helpers ----
    static MethodNode defaultCtor(String superName) {
        MethodNode c = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
        c.instructions.add(new InsnNode(Opcodes.RETURN));
        c.maxStack = 1; c.maxLocals = 1; return c;
    }
    void addSidecarClinit(ClassNode S) {
        MethodNode c = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        InsnList in = c.instructions;
        // WEAK-IDENTITY per-instance store (mcgraft.rt.WeakIdentityHashMap): keys weakly by object identity, so relocated
        // @Unique state is collected with its target instead of leaking for the whole session — a real @Unique field
        // dies with its object, and this must match (targets like per-frame EntityRenderState / per-line GuiMessage /
        // PlayerInfo churn hard). It must NOT call target.hashCode()/equals(): a mixin-modified hashCode() reading a
        // relocated field would recurse into getState() -> map.get(self) -> hashCode(). WeakHashMap can't be used
        // (it keys on equals/hashCode); IdentityHashMap can't (strong keys -> unbounded leak). Thread-safe internally.
        in.add(new TypeInsnNode(Opcodes.NEW, "mcgraft/rt/WeakIdentityHashMap"));
        in.add(new InsnNode(Opcodes.DUP));
        in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "mcgraft/rt/WeakIdentityHashMap", "<init>", "()V", false));
        in.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "STATE", "Ljava/util/Map;"));
        in.add(new InsnNode(Opcodes.RETURN));
        c.maxStack = 2; c.maxLocals = 0; S.methods.add(c);
    }
    void addGetState(ClassNode S) {
        // static synchronized State getState(Target self){ State s=(State)STATE.get(self); if(s==null){ s=new State(); STATE.put(self,s);} return s; }
        // (explicit get/init/put rather than computeIfAbsent, so a throwing State <init> never publishes a half-built instance)
        MethodNode g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
            "getState", "(" + targetDesc + ")L" + stateName + ";", null, null);
        InsnList in = g.instructions;
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "STATE", "Ljava/util/Map;"));
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // State s = (State) STATE.get(self); if(s==null){ s=new State(); initState(self,s); STATE.put(self,s);} return s;
        in.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        in.add(new TypeInsnNode(Opcodes.CHECKCAST, stateName));
        in.add(new VarInsnNode(Opcodes.ASTORE, 1));
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        LabelNode notNull = new LabelNode();
        in.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        in.add(new TypeInsnNode(Opcodes.NEW, stateName));
        in.add(new InsnNode(Opcodes.DUP));
        in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, stateName, "<init>", "()V", false));
        in.add(new VarInsnNode(Opcodes.ASTORE, 1));
        // Initialize BEFORE publishing into the map. If initState throws, nothing is stored, so a later getState retries
        // cleanly instead of forever returning a half-initialized State (get()!=null would skip re-init).
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "initState", "("+targetDesc+"L"+stateName+";)V", false));
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "STATE", "Ljava/util/Map;"));
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        in.add(new InsnNode(Opcodes.POP));
        in.add(notNull);
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new InsnNode(Opcodes.ARETURN));
        g.maxStack = 3; g.maxLocals = 2; S.methods.add(g);
    }

    /** Replay mixin-added instance-field initializer statements for objects whose vanilla constructor ran before
     *  attachment. Mixin appends these assignments to every transformed constructor; a compact straight-line
     *  assignment is copied into initState(Target,State) and runs once when sidecar state is first materialized. */
    MethodNode buildInstanceStateInitializer(ClassNode transformed) {
        MethodNode out=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"initState","("+targetDesc+"L"+stateName+";)V",null,null);
        Set<String> done=new HashSet<>(), sawInit=new HashSet<>();
        for(MethodNode ctor:transformed.methods){if(!ctor.name.equals("<init>")||ctor.instructions==null)continue;
            try{
                Analyzer<BasicValue> analyzer=new Analyzer<>(new BasicInterpreter());Frame<BasicValue>[] frames=analyzer.analyze(transformed.name,ctor);AbstractInsnNode[] ins=ctor.instructions.toArray();
                for(int i=0;i<ins.length;i++){if(!(ins[i] instanceof FieldInsnNode f)||f.getOpcode()!=Opcodes.PUTFIELD||!f.owner.equals(targetInternal)||!addedInstanceFields.contains(f.name))continue;
                    String fk=f.name+" "+f.desc;sawInit.add(fk);if(!done.add(fk))continue;
                    Frame<BasicValue> at=frames[i];if(at==null||at.getStackSize()<2){done.remove(fk);continue;}int base=at.getStackSize()-2,start=-1;
                    for(int s=i-1;s>=0;s--)if(ins[s].getOpcode()>=0&&frames[s]!=null&&frames[s].getStackSize()==base){start=s;break;}
                    if(start<0||!(ins[start] instanceof VarInsnNode recv)||recv.getOpcode()!=Opcodes.ALOAD||recv.var!=0){done.remove(fk);continue;}
                    boolean safe=true;for(int s=start+1;s<i;s++){AbstractInsnNode p=ins[s];if(p instanceof JumpInsnNode||p instanceof TableSwitchInsnNode||p instanceof LookupSwitchInsnNode||p instanceof IincInsnNode||(p instanceof VarInsnNode v&&v.var!=0)){safe=false;break;}}
                    if(!safe){done.remove(fk);continue;}
                    out.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));Map<LabelNode,LabelNode> labels=new HashMap<>();
                    for(int s=start+1;s<i;s++)if(ins[s].getOpcode()>=0)out.instructions.add(ins[s].clone(labels));
                    out.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,stateName,f.name,f.desc));
                }
            }catch(Throwable ignored){}
        }
        // Make the known "hard @Local residue" VISIBLE: a mixin-added instance field that HAD a constructor initializer
        // we could not straight-line replay (branchy/multi-local init, or analysis failure) defaults to zero/null on
        // objects constructed before attach. Record it in the verification report instead of dropping it silently.
        for(String fk:sawInit)if(!done.contains(fk))
            LateAttachVerifier.warn("FIELD_INIT_UNREPLAYED",targetInternal,"state",
                "instance field "+fk+" has a constructor initializer that could not be replayed (defaults to zero/null on objects constructed before attach)");
        out.instructions.add(new InsnNode(Opcodes.RETURN));rewriteRefs(out,true);out.maxLocals=2;out.maxStack=8;return out;
    }
    void addFieldAccessors(ClassNode S, FieldNode f) {
        Type t = Type.getType(f.desc);
        // get_f(Target self){ return getState(self).f; }
        MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, accGet(f.name), "(" + targetDesc + ")" + f.desc, null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "getState", "(" + targetDesc + ")L" + stateName + ";", false));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, stateName, f.name, f.desc));
        get.instructions.add(new InsnNode(t.getOpcode(Opcodes.IRETURN)));
        get.maxStack = Math.max(1, t.getSize()); get.maxLocals = 1; S.methods.add(get);
        // set_f(Target self, T v){ getState(self).f = v; }
        MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, accSet(f.name), "(" + targetDesc + f.desc + ")V", null, null);
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        set.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "getState", "(" + targetDesc + ")L" + stateName + ";", false));
        set.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), 1));
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, stateName, f.name, f.desc));
        set.instructions.add(new InsnNode(Opcodes.RETURN));
        set.maxStack = 1 + t.getSize(); set.maxLocals = 1 + t.getSize(); S.methods.add(set);
    }
    String accGet(String f) { return "get$" + f; }
    String accSet(String f) { return "set$" + f; }

    static MethodNode findMethod(ClassNode c, String name) { for (MethodNode m : c.methods) if (m.name.equals(name)) return m; return null; }
    static List<AbstractInsnNode> realInsns(MethodNode m) { List<AbstractInsnNode> l = new ArrayList<>(); for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) if (p.getOpcode() >= 0) l.add(p); return l; }
    /** The mixin appends its static-field init to the target's existing <clinit>; extract that appended delta (the
     *  suffix of X's <clinit> past O's body) and, ref-rewritten, append it to the sidecar's <clinit>. */
    void copyClinitDeltaToSidecar(ClassNode S, MethodNode oc, MethodNode xc) {
        List<AbstractInsnNode> oR = realInsns(oc), xR = realInsns(xc);
        int oBody = oR.size(); while (oBody > 0 && oR.get(oBody-1).getOpcode() == Opcodes.RETURN) oBody--;
        int xEnd = xR.size(); while (xEnd > 0 && xR.get(xEnd-1).getOpcode() == Opcodes.RETURN) xEnd--;
        if (oBody > xEnd) return;                                        // unexpected shape -> skip
        for (int i = 0; i < oBody; i++) if (oR.get(i).getOpcode() != xR.get(i).getOpcode()) return;   // X must extend O's body (append)
        if (oBody == xEnd) return;                                       // no delta
        // clone the delta instructions (build a label map for any labels inside the delta window in X)
        AbstractInsnNode from = xR.get(oBody), to = xR.get(xEnd - 1);
        Map<LabelNode,LabelNode> lm = new HashMap<>();
        for (AbstractInsnNode p = from; p != null; p = p.getNext()) { if (p instanceof LabelNode ln) lm.put(ln, new LabelNode()); if (p == to) break; }
        InsnList delta = new InsnList();
        for (AbstractInsnNode p = from; p != null; p = p.getNext()) { delta.add(p.clone(lm)); if (p == to) break; }
        MethodNode tmp = new MethodNode(); tmp.instructions = delta; rewriteRefs(tmp, true);   // relocate added-static PUTSTATIC -> sidecar etc.
        MethodNode sc = null; for (MethodNode m : S.methods) if (m.name.equals("<clinit>")) { sc = m; break; }
        AbstractInsnNode ret = sc.instructions.getLast(); while (ret != null && ret.getOpcode() != Opcodes.RETURN) ret = ret.getPrevious();
        sc.instructions.insertBefore(ret, tmp.instructions);
    }
    /** Merge a mixin-added <clinit> (initializes relocated static @Unique fields) into the sidecar's own <clinit>. */
    void mergeIntoSidecarClinit(ClassNode S, MethodNode added) {
        rewriteRefs(added, true);   // retarget added static-field PUTSTATIC -> sidecar; lambda-init Handle -> h$...
        MethodNode clinit = null; for (MethodNode m : S.methods) if (m.name.equals("<clinit>")) { clinit = m; break; }
        AbstractInsnNode ret = clinit.instructions.getLast(); while (ret != null && ret.getOpcode() != Opcodes.RETURN) ret = ret.getPrevious();
        AbstractInsnNode last = added.instructions.getLast(); while (last != null && last.getOpcode() != Opcodes.RETURN) last = last.getPrevious();
        if (last != null) added.instructions.remove(last);   // strip the added body's trailing RETURN
        clinit.instructions.insertBefore(ret, added.instructions);
        if (added.tryCatchBlocks != null && !added.tryCatchBlocks.isEmpty()) {
            if (clinit.tryCatchBlocks == null) clinit.tryCatchBlocks = new ArrayList<>();
            clinit.tryCatchBlocks.addAll(added.tryCatchBlocks);
        }
        clinit.maxStack = Math.max(clinit.maxStack, added.maxStack);
        clinit.maxLocals = Math.max(clinit.maxLocals, added.maxLocals);
    }

    /** A dropped PAYLOAD duck interface used as a value TYPE names a de-implemented target, so a relocated method's
     *  signature/field type must be erased to its single concrete implementor (matching the concrete receiver the
     *  h$/duck-dispatch rewriting already produces) or Object for multi-implementor interfaces. Vanilla/pre-existing
     *  interfaces still exist as types and are left untouched. */
    static String eraseIfaceInternal(String internal) {
        if (GIFACE == null || internal == null || !GIFACE.containsKey(internal) || !isPayloadIface(internal)) return internal;
        if (isSharedIface(internal)) return internal;   // shared: keep the interface (phantom); sites route via DuckDispatch
        List<String[]> impls = GIFACE.get(internal);
        // MUST agree with canCollapse (see its javadoc): a site that does NOT collapse leaves an Object on the stack,
        // so a descriptor erased to the concrete implementor would be fed a value the verifier types as Object. An
        // implementor we may not NAME is therefore erased to Object, exactly like the multi-implementor case.
        if (impls != null && impls.size() == 1) return collapseTargetNameable(impls.get(0)[0]) ? impls.get(0)[0] : "java/lang/Object";
        return "java/lang/Object";
    }
    static Type eraseIfaceType(Type t) {
        if (t.getSort() == Type.OBJECT) { String e = eraseIfaceInternal(t.getInternalName()); return e.equals(t.getInternalName()) ? t : Type.getObjectType(e); }
        if (t.getSort() == Type.ARRAY && t.getElementType().getSort() == Type.OBJECT) {
            String e = eraseIfaceInternal(t.getElementType().getInternalName());
            if (!e.equals(t.getElementType().getInternalName())) return Type.getType("[".repeat(t.getDimensions()) + "L" + e + ";");
        }
        return t;
    }
    /** Erase payload duck-interface types in a single field/type descriptor. */
    static String eraseIfaceType(String typeDesc) {
        if (GIFACE == null || PAYLOAD_CLASSES.isEmpty() || typeDesc == null || typeDesc.isEmpty()) return typeDesc;
        char c = typeDesc.charAt(0);
        if (c != 'L' && c != '[') return typeDesc;
        return eraseIfaceType(Type.getType(typeDesc)).getDescriptor();
    }
    static String eraseIfaceDesc(String desc) {
        if (GIFACE == null || PAYLOAD_CLASSES.isEmpty()) return desc;
        StringBuilder sb = new StringBuilder("(");
        for (Type a : Type.getArgumentTypes(desc)) sb.append(eraseIfaceType(a).getDescriptor());
        return sb.append(')').append(eraseIfaceType(Type.getReturnType(desc)).getDescriptor()).toString();
    }

    MethodNode relocateMethod(MethodNode m) {
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        String newDesc = eraseIfaceDesc(isStatic ? m.desc : "(" + targetDesc + m.desc.substring(1));
        MethodNode s = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "h$" + m.name, newDesc, null,
            m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
        s.instructions = m.instructions; s.tryCatchBlocks = m.tryCatchBlocks;
        s.maxStack = m.maxStack; s.maxLocals = m.maxLocals;
        rewriteRefs(s, true);   // rewrite added-member refs inside the moved body
        reportIllegalSuperCalls(m, s);
        return s;
    }

    /** BACKSTOP: a non-{@code <init>} {@code invokespecial} left in a body we just moved to the sidecar.
     *
     *  <p>JVMS 4.10.1.9 requires the receiver of a non-{@code <init>} {@code invokespecial} to be assignable to the
     *  CURRENT class as well as to the reference class. The sidecar is a bare {@code java.lang.Object} subclass that is
     *  never instantiated, so NO such instruction can verify here, whatever the owner — the JVM rejects the entire
     *  sidecar with {@code VerifyError: Bad invokespecial instruction: current class isn't assignable to reference
     *  class}, and only at LINK time, i.e. the first time gameplay touches any hook on that target.
     *
     *  <p>{@link #rewriteRefs} lowers all three legitimate shapes before this runs: a call to a mixin-ADDED method
     *  becomes an {@code h$} static, a call to a private method becomes the reflective {@code rmi$}/AwReflect bridge,
     *  and a genuine {@code super.x()} becomes the {@code smi$} findSpecial bridge. So reaching this check means the
     *  converter failed to classify a site, not that the payload is unsupported — hence it stays FATAL, but it is a
     *  self-check on us and the message must not assert anything about the mixin's intent (the site is just as likely
     *  a {@code @Unique} helper reaching for inherited behaviour as an override calling super). */
    private void reportIllegalSuperCalls(MethodNode original, MethodNode relocated) {
        if (relocated.instructions == null) return;
        for (AbstractInsnNode p = relocated.instructions.getFirst(); p != null; p = p.getNext()) {
            if (!(p instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKESPECIAL) continue;
            if (mi.name.equals("<init>") || mi.owner.equals(sidecar) || mi.owner.equals(stateName)) continue;
            LateAttachVerifier.error("SUPER_CALL_IN_RELOCATED_METHOD", targetInternal, original.name + original.desc,
                "relocated body contains super." + mi.name + " (" + mi.owner + "." + mi.name + mi.desc + ") that the "
                + "converter did not lower; no invokespecial can verify from a sidecar, so this would VerifyError the "
                + "whole sidecar at link time. This is a converter bug — the site should have become an h$/rmi$/smi$ call.");
        }
    }

    /** GLOBAL table of mixin-ADDED methods across ALL targets, keyed by "scope owner name desc" -> [owner, sidecar,
     *  isStatic]. Keyed by OWNER (not just name+desc) because the same handler name+desc can be added to multiple
     *  targets; a call is resolved by walking the receiver's class hierarchy to the declaring target. Keyed by SCOPE as
     *  well, because two payloads can add the same handler to the same target and each has its own sidecar. */
    // volatile: assigned once by the agent AFTER Phase A completes, then read from many concurrent CFT threads. The
    // volatile write/read gives the happens-before that safely publishes each fully-populated table to those readers.
    //
    // These are the UNION over every live PayloadScope, materialized by republish(). Readers keep a single map lookup
    // on the hot CFT path; the per-payload tables live on the scopes (docs/multi-payload.md §1.4). GADDED/GFIELD keys
    // carry the scope (see gkey), so the union is a collision-free putAll; GIFACE keys stay the interface name (which
    // is what every reader asks for) and each entry carries its scope in slot [2].
    public static volatile Map<String,String[]> GADDED, GFIELD;
    public static volatile Map<String,List<String[]>> GIFACE;

    /** Every payload injected into this JVM, in injection order. CopyOnWrite: appended rarely, iterated on the CFT
     *  path. */
    static final java.util.concurrent.CopyOnWriteArrayList<PayloadScope> SCOPES = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static List<PayloadScope> scopes() { return SCOPES; }

    /** Register a payload and hand back its scope. The sanitized key is made UNIQUE across live scopes here, because
     *  {@link PayloadScope#sanitize} is lossy ({@code a-b} and {@code a_b} collapse together) and two payloads sharing
     *  a key would share every generated class name — the exact collision this phase exists to remove. */
    public static synchronized PayloadScope newScope(String id) {
        String base = PayloadScope.sanitize(id), k = base;
        for (int n = 2; keyTaken(k); n++) k = base + "_" + n;
        PayloadScope s = new PayloadScope(id, k);
        SCOPES.add(s);
        return s;
    }
    private static boolean keyTaken(String k) { for (PayloadScope s : SCOPES) if (s.key.equals(k)) return true; return false; }
    /** The live scope for a payload id, or null. Used to refuse a re-injection of the SAME payload (which would apply
     *  its still-selected mixin config a second time) while allowing a different one. */
    public static PayloadScope scopeForId(String id) { for (PayloadScope s : SCOPES) if (s.id.equals(id)) return s; return null; }

    /** The scope that owns {@code internal}: the payload whose own classes contain it, else one whose caller-rewrite
     *  prefix matches. Null when no payload claims it (the caller then falls back to searching every scope). */
    static PayloadScope scopeFor(String internal) {
        if (internal == null) return null;
        for (PayloadScope s : SCOPES) if (s.payloadClasses.contains(internal)) return s;
        for (PayloadScope s : SCOPES) for (String p : s.callerRewritePrefixes) if (internal.startsWith(p)) return s;
        return null;
    }

    /** Recompute the materialized unions from the live scopes. Called by the agent after it fills a scope's tables;
     *  each union is built fully and then published through the volatile write, so a CFT thread never observes a
     *  half-built map. GADDED/GFIELD/GIFACE stay NULL until at least one payload has produced them, preserving the
     *  "not populated yet" contract the converter's early-outs rely on. */
    public static synchronized void republish() {
        Map<String,String[]> ga = null, gf = null;
        Map<String,List<String[]>> gi = null;
        Set<String> pc = new HashSet<>(), cr = new HashSet<>(), sh = new HashSet<>();
        for (PayloadScope s : SCOPES) {
            if (s.gadded != null) { if (ga == null) ga = new HashMap<>(); ga.putAll(s.gadded); }
            if (s.gfield != null) { if (gf == null) gf = new HashMap<>(); gf.putAll(s.gfield); }
            if (s.giface != null) { if (gi == null) gi = new HashMap<>();
                for (var e : s.giface.entrySet()) gi.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue()); }
            pc.addAll(s.payloadClasses); cr.addAll(s.callerRewritePrefixes); sh.addAll(s.sharedIfaces);
        }
        GADDED = ga; GFIELD = gf; GIFACE = gi;
        PAYLOAD_CLASSES = pc; CALLER_REWRITE_PREFIXES = cr; SHARED_IFACES = sh;
    }
    /** Internal names of classes provided by the staged payload (the mod jar) — the UNION over every live payload;
     *  the per-payload set is {@code PayloadScope.payloadClasses}. Assign through {@link #republish()}, never directly.
     *  Lets us tell a mixin-ADDED payload
     *  "duck" interface (a real problem when used as a value type - the de-implemented target no longer satisfies it)
     *  from a pre-existing/vanilla interface the mixin merely also implements (whose type still exists and is used
     *  legitimately by real implementors elsewhere, so a descriptor mention of it is fine). */
    public static volatile Set<String> PAYLOAD_CLASSES = java.util.Collections.emptySet();
    /** True if the mixin-added interface is one the payload itself defines (vs a pre-existing runtime interface). */
    public static boolean isPayloadIface(String internal) { return PAYLOAD_CLASSES.contains(internal); }
    /** Internal-name prefixes of DEPENDENCY runtime whose classes must be caller-rewritten too (their own code casts
     *  MC objects to duck interfaces the applied dependency mixins add, e.g. fabric-api's ClientLifecycleEventsImpl ->
     *  (LoadedChunksCache) clientLevel). Matched by prefix since the classes are not in the payload jar. UNION over
     *  every live payload ({@code PayloadScope.callerRewritePrefixes}); assign through {@link #republish()}. */
    public static volatile Set<String> CALLER_REWRITE_PREFIXES = java.util.Collections.emptySet();
    public static boolean isCallerRewrite(String internal) {
        if (PAYLOAD_CLASSES.contains(internal)) return true;
        for (String p : CALLER_REWRITE_PREFIXES) if (internal.startsWith(p)) return true;
        return false;
    }
    /** Payload duck interfaces that are ALSO genuinely implemented by a real (non-mixin) bundle class — i.e. at runtime
     *  a value of this interface type may be a genuine implementor OR a de-implemented (duck) target. Such interfaces
     *  are NOT erased from descriptors ("phantom": the interface type is legal per JVMS since it verifies like Object);
     *  their cast/instanceof/invokeinterface sites are always routed through DuckDispatch, which discriminates per
     *  receiver (real interface call for genuine implementors, sidecar for duck targets). UNION over every live
     *  payload ({@code PayloadScope.sharedIfaces}); assign through {@link #republish()}. */
    public static volatile Set<String> SHARED_IFACES = java.util.Collections.emptySet();
    public static boolean isSharedIface(String internal) { return SHARED_IFACES.contains(internal); }

    /** Whether a use site of mixin-added interface {@code iface} may be COLLAPSED to its single implementor.
     *
     *  <p>This MUST agree with {@link #eraseIfaceInternal}, which only erases descriptors for payload-scope interfaces.
     *  Collapsing rewrites a site to name a concrete class, so collapsing an interface whose descriptors are NOT erased
     *  leaves an interface-typed value feeding a class-typed site — and JVMS 4.10.1.2 treats an interface-typed value as
     *  Object for assignability to a class type, i.e. VerifyError. It would also break a genuine non-mixin implementor,
     *  whose instances would now fail a CHECKCAST that names one specific target. Interfaces failing this test route
     *  through DuckDispatch instead, which discriminates real implementors from de-implemented targets per receiver.
     *
     *  <p>The predicate used to be `impls.size() == 1 && !isSharedIface(...)` alone, which silently mis-rewrote any
     *  mixin-added interface living outside the payload scope (e.g. in a bundled library jar) rather than reporting it.
     *
     *  <p>It then still lacked any check on the IMPLEMENTOR, which is the type the rewritten instruction names — see
     *  {@link #collapseTargetNameable}. */
    static boolean canCollapse(String iface, List<String[]> impls) {
        return impls.size() == 1 && isPayloadIface(iface) && !isSharedIface(iface) && collapseTargetNameable(impls.get(0)[0]);
    }

    /** May bytecode we write name {@code target} directly?
     *
     *  <p>Collapsing rewrites a duck-interface site to name the CONCRETE implementor: {@code CHECKCAST <target>},
     *  {@code INSTANCEOF <target>}, {@code INVOKESTATIC <sidecar>.h$x(L<target>;...)}, and the matching descriptor
     *  erasure. Those instructions are written into the PAYLOAD'S OWN classes (rewriteCaller) as well as into
     *  target'/the sidecar, and a payload class lives outside {@code net.minecraft} — so resolving a NON-PUBLIC
     *  Minecraft class from there is {@code IllegalAccessError} the first time the instruction executes (link-clean,
     *  verify-clean: it is a run-time resolution failure, which is why nothing before this caught it).
     *
     *  <p>Measured instance: LiquidBounce's {@code MixinEntityFluidInteractionTrackerAccessor} has exactly one
     *  implementor, the PACKAGE-PRIVATE {@code net/minecraft/world/entity/EntityFluidInteraction$Tracker}. The collapse
     *  turned {@code SimulatedPlayer$Companion}'s {@code CHECKCAST <accessor>} into {@code CHECKCAST <$Tracker>} and
     *  Killaura died on it on first use.
     *
     *  <p>The rule is therefore PUBLIC-only, not "public or same package": one of the writers (rewriteCaller) always
     *  emits into another package, and {@link #eraseIfaceInternal} — which must agree with the collapse decision — has
     *  no caller to be relative to. A non-public implementor keeps the {@link mcgraft.rt.DuckDispatch} path, which
     *  reaches the same sidecar reflectively (no access check on a package-private type) at a dispatch cost.
     *
     *  <p>Accessibility comes from {@link #classAccess}, i.e. {@link #effectiveClassAccess} — the SAME notion the
     *  member-level rewrites use, so an {@code accessible class} widening that was inert (already-loaded class, the
     *  AW could not reach it) is masked back off here too rather than believed. Unknown bytes ({@code -1}) mean we
     *  cannot prove the type is nameable, and the DuckDispatch path is correct for every case the collapse handles, so
     *  the answer is "no": the failure mode of guessing wrong here is a crash, of being conservative a slower call. */
    static boolean collapseTargetNameable(String target) {
        if (target == null || target.isEmpty()) return false;
        int acc = classAccess(target);   // cached per class; effectiveClassAccess is applied on every read, not frozen
        boolean ok = acc >= 0 && (acc & Opcodes.ACC_PUBLIC) != 0;
        if (!ok && COLLAPSE_DECLINED.add(target)) InjectionLogger.info("duck-interface collapse declined for " + target
            + " (class access " + (acc < 0 ? "unknown" : "0x" + Integer.toHexString(acc)) + ", not public): its"
            + " cast/instanceof/invoke sites route through DuckDispatch instead, because payload code outside its"
            + " package cannot name it");
        return ok;
    }
    /** Log-dedupe only (one line per declined target). Deliberately NOT a memo of the ANSWER: the inert-widening mask
     *  behind {@link #effectiveClassAccess} is fed by a set the agent grows as classes load, so a cached "public" could
     *  outlive its truth. */
    private static final Set<String> COLLAPSE_DECLINED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    String[] addedFieldTarget(String owner, String name, String desc) { return gLookup(GFIELD, scope, owner, name, desc); }

    /** Walk {@code owner}'s hierarchy looking for a relocated member, trying {@code preferred}'s entry at each level
     *  before the other payloads'. Keys are scope-qualified, so a level can carry one entry per payload; preferring the
     *  asking payload's own means a mixin body relocated for payload A resolves to A's sidecar even when payload B
     *  added a same-signature member to the same class. A null {@code preferred} searches every scope in order. */
    static String[] gLookup(Map<String,String[]> table, PayloadScope preferred, String owner, String name, String desc) {
        if (table == null) return null;
        String c = owner;
        for (int guard = 0; c != null && !c.equals("java/lang/Object") && guard++ < 256; c = superOf(c)) {
            if (preferred != null) { String[] g = table.get(gkey(preferred, c, name, desc)); if (g != null) return g; }
            for (PayloadScope s : SCOPES) {
                if (s == preferred) continue;
                String[] g = table.get(gkey(s, c, name, desc)); if (g != null) return g;
            }
        }
        return null;
    }
    /** Global table key. The scope key leads so that a relocated member is addressed per PAYLOAD as well as per target;
     *  {@link #gkeyMemberName} is the matching decoder. Neither a scope key, an internal name nor a descriptor can
     *  contain a space, so the four parts stay unambiguous. */
    static String gkey(PayloadScope scope, String owner, String name, String desc) { return scope.key + " " + owner + " " + name + " " + desc; }

    // ---- cross-payload member ownership (docs/multi-payload.md §1.2) ------------------------------------------------
    /** The scope OTHER than {@code self} that has already relocated method {@code name+desc} off {@code target}, or
     *  null when no earlier payload claims it. Reads each scope's OWN table rather than the union, so the answer is
     *  "who owns this" rather than "does anyone own this". */
    static PayloadScope foreignMethodOwner(PayloadScope self, String target, String name, String desc) {
        for (PayloadScope s : SCOPES) {
            if (s == self) continue;
            Map<String,String[]> t = s.gadded; if (t == null) continue;
            String[] v = t.get(gkey(s, target, name, desc));
            if (v != null && target.equals(v[0])) return s;
        }
        return null;
    }
    /** Field equivalent of {@link #foreignMethodOwner}. */
    static PayloadScope foreignFieldOwner(PayloadScope self, String target, String name, String desc) {
        for (PayloadScope s : SCOPES) {
            if (s == self) continue;
            Map<String,String[]> t = s.gfield; if (t == null) continue;
            String[] v = t.get(gkey(s, target, name, desc));
            if (v != null && target.equals(v[0])) return s;
        }
        return null;
    }
    /** Interface equivalent: the scope that already dropped {@code iface} from {@code target} and registered its own
     *  sidecar as the duck implementation. Keeps a second payload from registering a rival DuckDispatch entry for an
     *  interface it did not add. */
    static PayloadScope foreignIfaceOwner(PayloadScope self, String target, String iface) {
        for (PayloadScope s : SCOPES) {
            if (s == self) continue;
            Map<String,List<String[]>> m = s.giface; if (m == null) continue;
            List<String[]> impls = m.get(iface); if (impls == null) continue;
            for (String[] v : impls) if (target.equals(v[0])) return s;
        }
        return null;
    }

    /** The scope key encoded in a sidecar's internal name ({@code <target>$$Sidecar$<key>}, and its {@code $State}),
     *  or null when the name is not one of ours. Scope keys never contain {@code $} (PayloadScope.sanitize), so the
     *  first {@code $} after the marker terminates the key. */
    public static String scopeKeyOfSidecar(String internal) {
        if (internal == null) return null;
        int i = internal.lastIndexOf("$$Sidecar$");
        if (i < 0) return null;
        String rest = internal.substring(i + "$$Sidecar$".length());
        int d = rest.indexOf('$');
        return d < 0 ? rest : rest.substring(0, d);
    }

    /** For each method of {@code classBytes}, which payloads' sidecars its body actually calls into.
     *
     *  <p>This is the direct measurement of sequential composition: a method appearing here with TWO scope keys is a
     *  Minecraft method that two different payloads both hooked, with both hooks present in the bytes we published.
     *  That case is precisely what used to break silently (docs/multi-payload.md §1.2), so it is reported rather than
     *  assumed. Reads the emitted bytes, not our own bookkeeping, so it cannot agree with a wrong table. */
    public static Map<String,Set<String>> sidecarScopesByMethod(byte[] classBytes) {
        Map<String,Set<String>> out = new LinkedHashMap<>();
        if (classBytes == null) return out;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classBytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                Set<String> keys = new java.util.TreeSet<>();
                for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
                    String owner = p instanceof MethodInsnNode mi ? mi.owner
                                 : p instanceof FieldInsnNode fi ? fi.owner
                                 : p instanceof TypeInsnNode ti ? ti.desc : null;
                    String k = scopeKeyOfSidecar(owner);
                    if (k != null && !k.isEmpty()) keys.add(k);
                }
                if (!keys.isEmpty()) out.put(m.name + m.desc, keys);
            }
        } catch (Throwable ignored) {}
        return out;
    }
    /** Recover the member NAME from a {@link #gkey}, given the owner recorded alongside it. Null if the key does not
     *  have the expected shape. */
    static String gkeyMemberName(String key, String owner) {
        int sc = key.indexOf(' ');
        if (sc < 0) return null;
        String rest = key.substring(sc + 1);
        int sp = rest.lastIndexOf(' ');
        if (sp <= owner.length()) return null;
        return rest.substring(owner.length() + 1, sp);
    }
    public static void collectAdded(PayloadScope scope, String internal, byte[] O, byte[] X, Map<String,String[]> gMethods, Map<String,String[]> gFields, Map<String,List<String[]>> gIfaces) {
        ClassNode o = read(O), x = read(X); Set<String> om = keysM(o), of = keysF(o);
        String sc = sidecarInternal(scope, internal);
        // Members an EARLIER payload already claimed on this target are skipped: from the second injection on, O is
        // the true original and X carries every payload's additions, so the raw diff would re-record the earlier
        // payload's members under THIS scope and point them at the wrong sidecar. run() strips them the same way.
        for (MethodNode m : x.methods) { String k = m.name + " " + m.desc;
            if (!om.contains(k) && !m.name.equals("<clinit>") && !m.name.equals("<init>")
                    && foreignMethodOwner(scope, internal, m.name, m.desc) == null)
                gMethods.put(gkey(scope, internal, m.name, m.desc), new String[]{internal, sc, (m.access & Opcodes.ACC_STATIC) != 0 ? "1" : "0"}); }
        for (FieldNode f : x.fields) { if (!of.contains(f.name + " " + f.desc)
                    && foreignFieldOwner(scope, internal, f.name, f.desc) == null)
            gFields.put(gkey(scope, internal, f.name, f.desc), new String[]{internal, sc, (f.access & Opcodes.ACC_STATIC) != 0 ? "S" : "I"}); }
        for (String i : x.interfaces) if (!o.interfaces.contains(i) && foreignIfaceOwner(scope, internal, i) == null) {
            List<String[]> impls = gIfaces.computeIfAbsent(i, k -> new ArrayList<>());
            // Dedup within THIS payload only. A second payload adding the same interface to the same target is a
            // distinct implementation with its own sidecar and must be recorded separately, not swallowed.
            boolean duplicate = false;
            for (String[] impl : impls) if (impl[0].equals(internal) && scope.key.equals(impl[2])) { duplicate = true; break; }
            if (!duplicate) impls.add(new String[]{internal, sc, scope.key});
        }
    }
    /** resolve a call to a mixin-added method: walk the receiver type's hierarchy to the target that declared it. */
    String[] addedCall(String owner, String name, String desc, boolean callStatic) {
        String[] g = gLookup(GADDED, scope, owner, name, desc);
        return g == null ? null : (callStatic == "1".equals(g[2]) ? g : null);
    }
    String[] addedCallTarget(MethodInsnNode mi) { return addedCall(mi.owner, mi.name, mi.desc, mi.getOpcode() == Opcodes.INVOKESTATIC); }
    /** Static variant of {@link #addedCall} over the global GADDED map, for the live caller-rewrite path
     *  ({@link #rewriteCaller}). Walks the receiver type's hierarchy to the target that declared the added method.
     *  {@code scope} is the payload the class being rewritten belongs to (null = unknown -> search every payload). */
    static String[] addedCallStatic(PayloadScope scope, String owner, String name, String desc, boolean callStatic) {
        if (name.equals("<init>") || name.equals("<clinit>")) return null;
        String[] g = gLookup(GADDED, scope, owner, name, desc);
        return g == null ? null : (callStatic == "1".equals(g[2]) ? g : null);
    }

    /** SIDECAR-relocation path: can the sidecar legally name this type in a CHECKCAST? Inaccessible iff the type is
     *  non-public AND in a different package than the sidecar (public or same-package are nameable). Unknown bytes ->
     *  assume accessible (don't erase), matching the caller-path Resolver's conservative default. */
    boolean typeInaccessibleFromSidecar(String internal) {
        if (internal == null || internal.isEmpty() || internal.charAt(0) == '[') return false;
        int acc = classAccess(internal);
        if (acc < 0) return false;
        if ((acc & Opcodes.ACC_PUBLIC) != 0) return false;
        return !pkg(internal).equals(pkg(sidecar));
    }

    /** Rewrite references to added members (field->accessor, method->sidecar static, invokedynamic Handle->
     *  sidecar static [A]) and, inside the sidecar, non-public target field access via reflection [B]. */
    void rewriteRefs(MethodNode m, boolean inSidecar) {
        if (m.instructions == null) return;
        for (AbstractInsnNode p = m.instructions.getFirst(), next; p != null; p = next) {
            next = p.getNext();   // capture BEFORE any set() detaches p (else iteration stops after one rewrite)
            if (GIFACE != null && p instanceof TypeInsnNode gti && GIFACE.containsKey(gti.desc)
                    && (gti.getOpcode() == Opcodes.CHECKCAST || gti.getOpcode() == Opcodes.INSTANCEOF)) {
                List<String[]> impls = GIFACE.get(gti.desc);
                if (canCollapse(gti.desc, impls)) {
                    gti.desc = impls.get(0)[0];
                } else if (gti.getOpcode() == Opcodes.CHECKCAST) {
                    InsnList suffix = new InsnList(); suffix.add(new LdcInsnNode(gti.desc));
                    suffix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mcgraft/rt/DuckDispatch", "cast",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
                    m.instructions.insertBefore(gti, suffix); m.instructions.remove(gti);
                } else {
                    InsnList suffix = new InsnList(); suffix.add(new LdcInsnNode(gti.desc));
                    suffix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mcgraft/rt/DuckDispatch", "isInstance", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
                    m.instructions.insertBefore(gti, suffix); m.instructions.remove(gti);
                }
            } else if (GIFACE != null && p instanceof MethodInsnNode gmi && gmi.getOpcode() == Opcodes.INVOKEINTERFACE && GIFACE.containsKey(gmi.owner)) {
                List<String[]> impls = GIFACE.get(gmi.owner);
                if (canCollapse(gmi.owner, impls)) {
                    String[] ts = impls.get(0);
                    m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, ts[1], "h$" + gmi.name, "(L" + ts[0] + ";" + gmi.desc.substring(1), false));
                } else {
                    String tn = ifaceTrampName(gmi.owner, gmi.name, gmi.desc);
                    ifaceDispatchTramps.putIfAbsent(tn, new Object[]{gmi.owner, gmi.name, gmi.desc});
                    m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, tn,
                        "(Ljava/lang/Object;" + gmi.desc.substring(1), false));
                }
            } else if (p instanceof FieldInsnNode fi && addedFieldTarget(fi.owner, fi.name, fi.desc) != null) {
                // mixin-ADDED field (relocated). GFIELD spans all targets so a base-class @Unique field accessed via a
                // subclass receiver routes to the BASE target's sidecar accessor/static.
                String[] gf = addedFieldTarget(fi.owner, fi.name, fi.desc); String ot = gf[0], os = gf[1];
                if ("S".equals(gf[2])) { m.instructions.set(p, new FieldInsnNode(fi.getOpcode(), os, fi.name, fi.desc)); }
                else if (fi.getOpcode() == Opcodes.GETFIELD) m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, os, accGet(fi.name), "(L" + ot + ";)" + fi.desc, false));
                else if (fi.getOpcode() == Opcodes.PUTFIELD) m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, os, accSet(fi.name), "(L" + ot + ";" + fi.desc + ")V", false));
            } else if (p instanceof FieldInsnNode fi && fi.owner.equals(targetInternal)) {
                if (addedInstanceFields.contains(fi.name)) {
                    if (fi.getOpcode() == Opcodes.GETFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, accGet(fi.name), "(" + targetDesc + ")" + fi.desc, false));
                    else if (fi.getOpcode() == Opcodes.PUTFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, accSet(fi.name), "(" + targetDesc + fi.desc + ")V", false));
                } else if (addedStaticFields.contains(fi.name)) {
                    m.instructions.set(p, new FieldInsnNode(fi.getOpcode(), sidecar, fi.name, fi.desc));
                } else if (inSidecar && oFieldAcc.containsKey(fi.name) && (targetFieldAccess(fi.name) & Opcodes.ACC_PUBLIC) == 0
                        && (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.PUTFIELD)) {
                    // B: non-public target field accessed from the sidecar -> reflective accessor (no AW on target)
                    reflectFields.add(fi.name + " " + fi.desc);
                    if (fi.getOpcode() == Opcodes.GETFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "refGet$" + fi.name, "(" + targetDesc + ")" + fi.desc, false));
                    else
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "refSet$" + fi.name, "(" + targetDesc + fi.desc + ")V", false));
                } else if (inSidecar && nonPublicField(fi.owner,fi.name,fi.desc)) rewriteFieldAw(m,fi,this::typeInaccessibleFromSidecar);
            } else if (inSidecar && p instanceof FieldInsnNode fi && nonPublicField(fi.owner,fi.name,fi.desc)) {
                // Relocated handlers are no longer subclasses/nestmates of their target. Protected or package-private
                // fields inherited from another package (for example Screen.minecraft) must therefore use reflection.
                rewriteFieldAw(m,fi,this::typeInaccessibleFromSidecar);
            } else if (p instanceof MethodInsnNode mi && addedCallTarget(mi) != null) {
                // call to a mixin-ADDED method (relocated to a sidecar). GADDED spans ALL targets, so a call to a base
                // class's @Unique method from a subclass mixin's relocated body routes to the BASE class's sidecar.
                String[] g = addedCallTarget(mi); String ot = g[0], os = g[1]; boolean gStatic = "1".equals(g[2]);
                String nd = eraseIfaceDesc(gStatic ? mi.desc : "(L" + ot + ";" + mi.desc.substring(1));
                m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, os, "h$" + mi.name, nd, false));
            } else if (inSidecar && p instanceof MethodInsnNode mi && mi.owner.equals(targetInternal)
                    && (mi.getOpcode() == Opcodes.INVOKESPECIAL || mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKESTATIC) && !mi.name.equals("<init>")
                    && (targetMethodAccess(mi.name, mi.desc) & Opcodes.ACC_PRIVATE) != 0) {
                // B (methods): a private EXISTING target method called from the relocated sidecar body (invokespecial,
                // invokevirtual for a nestmate/MixinExtras bridge, or invokestatic) is illegal cross-class -> cached
                // reflective invoker.
                String key = mi.name + " " + mi.desc;
                boolean isStatic = mi.getOpcode() == Opcodes.INVOKESTATIC;
                reflectMethods.add(key);
                if (isStatic) reflectStaticMethods.add(key);
                String nd = isStatic ? mi.desc : "(" + targetDesc + mi.desc.substring(1);
                m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "rmi$" + invokerId(mi.name, mi.desc), nd, false));
            } else if (inSidecar && p instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL
                    && !mi.name.startsWith("<") && isAssignable(targetInternal, mi.owner)) {
                // SUPER CALL. A mixin that overrides an inherited target method (or a @Unique helper that just wants the
                // inherited behaviour) compiles to `invokespecial <Super>.x`. JVMS 4.10.1.9 requires the receiver of a
                // non-<init> invokespecial to be assignable to the CURRENT class as well as the reference class, and the
                // sidecar is a bare Object subclass that is never instantiated — so once the body moves here NO
                // invokespecial can verify, whatever the owner. Lower it to a findSpecial MethodHandle bound with
                // specialCaller = the target: that is the JDK's supported super-invocation primitive and it binds the
                // method NON-virtually from a class that is not a subclass of the reference class.
                //
                // This branch must precede illegalSidecarMethod's AwReflect lowering, which routes a super call to a
                // protected/package-private method through Method.invoke — and Method.invoke RE-DISPATCHES VIRTUALLY
                // (JLS 15.12.4.4), silently turning `super.x()` into `this.x()` and inviting infinite recursion. The
                // predicate here is exactly "owner is a supertype of the target", i.e. the sites where that rewrite
                // would have been wrong; a private method of some unrelated class is NOT a super call, stays on the
                // AwReflect path, and is faithful there because a private method cannot be overridden.
                superMethods.add(mi.owner + " " + mi.name + " " + mi.desc);
                m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar,
                    "smi$" + superInvokerId(mi.owner, mi.name, mi.desc), "(" + targetDesc + mi.desc.substring(1), false));
            } else if (inSidecar && p instanceof MethodInsnNode mi && illegalSidecarMethod(sidecar,mi.owner,mi.name,mi.desc)) {
                // Relocation also loses nestmate/subclass privileges for methods and constructors declared outside
                // the target package. Lower them through the same cached reflection runtime used for late AW access.
                if (mi.getOpcode()==Opcodes.INVOKESPECIAL && mi.name.equals("<init>")) {
                    AbstractInsnNode nw=mi.getPrevious();
                    while(nw!=null&&!(nw instanceof TypeInsnNode tn&&tn.getOpcode()==Opcodes.NEW&&tn.desc.equals(mi.owner)))nw=nw.getPrevious();
                    if(nw!=null&&nw.getNext()!=null&&nw.getNext().getOpcode()==Opcodes.DUP){AbstractInsnNode dup=nw.getNext();m.instructions.remove(nw);m.instructions.remove(dup);rewriteAwCtorInline(m,mi,this::typeInaccessibleFromSidecar);}
                    // illegalSidecarMethod already decided this site throws IllegalAccessError from the relocated body.
                    // If the NEW/DUP prologue isn't the shape we can lower (result-discarded allocation, a non-javac
                    // producer), leaving it in place emits the illegal instruction anyway — so it must be visible.
                    // WARN, not error: this is latent, not certain. The site only throws if it actually executes, and a
                    // real client legitimately produces dozens of them, so treating it as fatal aborted every injection.
                    else LateAttachVerifier.warn("CTOR_LOWERING_UNMATCHED", targetInternal, m.name + m.desc,
                        "cannot lower inaccessible " + mi.owner + ".<init>" + mi.desc + ": no NEW/DUP prologue");
                } else if (!mi.name.startsWith("<") && (mi.getOpcode()==Opcodes.INVOKEVIRTUAL||mi.getOpcode()==Opcodes.INVOKESTATIC||mi.getOpcode()==Opcodes.INVOKESPECIAL)) {
                    rewriteAwMethodInline(m,mi,this::typeInaccessibleFromSidecar);
                }
            } else if (p instanceof InvokeDynamicInsnNode idn) {
                // A: rewrite bootstrap Handle args that point at a relocated target method -> sidecar static.
                // The lambda's captured `this` (target) becomes the static's param0 (LambdaMetafactory adapts
                // a captured arg to a leading static parameter identically to an instance receiver).
                for (int k = 0; k < idn.bsmArgs.length; k++) {
                    if (idn.bsmArgs[k] instanceof Handle h) { String[] g = addedCall(h.getOwner(), h.getName(), h.getDesc(), h.getTag() == Opcodes.H_INVOKESTATIC);
                        if (g != null) { boolean gStatic = "1".equals(g[2]);
                            String nd = gStatic ? h.getDesc() : "(L" + g[0] + ";" + h.getDesc().substring(1);
                            idn.bsmArgs[k] = new Handle(Opcodes.H_INVOKESTATIC, g[1], "h$" + h.getName(), nd, false);
                        } else if (inSidecar && h.getOwner().equals(targetInternal)
                                && (h.getTag() == Opcodes.H_INVOKESPECIAL || h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKESTATIC)
                                && (targetMethodAccess(h.getName(), h.getDesc()) & Opcodes.ACC_PRIVATE) != 0) {
                            // A relocated body may contain a lambda/metafactory handle to an ORIGINAL private target
                            // method (for example ClientPacketListener::lambda$handleChunkBlocksUpdate$0). The lambda
                            // is spun with the sidecar as its caller, so leaving that handle unchanged fails at runtime
                            // with IllegalAccessError even though an ordinary MethodInsn would have been routed above.
                            // Point the handle at the same cached reflective bridge used for direct private calls. The
                            // captured target receiver becomes param0 of the static bridge.
                            String key = h.getName() + " " + h.getDesc();
                            boolean isStatic = h.getTag() == Opcodes.H_INVOKESTATIC;
                            reflectMethods.add(key);
                            if (isStatic) reflectStaticMethods.add(key);
                            String nd = isStatic ? h.getDesc() : "(L" + targetInternal + ";" + h.getDesc().substring(1);
                            idn.bsmArgs[k] = new Handle(Opcodes.H_INVOKESTATIC, sidecar,
                                "rmi$" + invokerId(h.getName(), h.getDesc()), nd, false);
                        }
                    }
                }
            }
        }
    }

    /** B: reflective get/set of a non-public target field (Field cached in a sidecar static, setAccessible). */
    void addReflectiveAccessors(ClassNode S, String name, String desc) {
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F$" + name, "Ljava/lang/reflect/Field;", null, null));
        Type t = Type.getType(desc);
        boolean prim = t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE;
        String box = prim ? boxOwner(t) : null;
        // get
        MethodNode g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "refGet$" + name, "(" + targetDesc + ")" + desc, null, null);
        InsnList gi = g.instructions;
        gi.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "F$" + name, "Ljava/lang/reflect/Field;"));
        gi.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (prim) { gi.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", primGetter(t), "(Ljava/lang/Object;)" + t.getDescriptor(), false)); gi.add(new InsnNode(t.getOpcode(Opcodes.IRETURN))); }
        else { gi.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false)); gi.add(new TypeInsnNode(Opcodes.CHECKCAST, t.getInternalName())); gi.add(new InsnNode(Opcodes.ARETURN)); }
        wrapTryCatch(g); g.maxStack = 3; g.maxLocals = 3; S.methods.add(g);
        // set
        MethodNode s = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "refSet$" + name, "(" + targetDesc + desc + ")V", null, null);
        InsnList si = s.instructions;
        si.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "F$" + name, "Ljava/lang/reflect/Field;"));
        si.add(new VarInsnNode(Opcodes.ALOAD, 0));
        si.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), 1));
        if (prim) si.add(new MethodInsnNode(Opcodes.INVOKESTATIC, box, "valueOf", "(" + t.getDescriptor() + ")L" + box + ";", false));
        si.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        si.add(new InsnNode(Opcodes.RETURN));
        wrapTryCatch(s); s.maxStack = 3; s.maxLocals = 1 + t.getSize(); S.methods.add(s);
    }
    static String primGetter(Type t) { switch (t.getSort()) { case Type.BOOLEAN: return "getBoolean"; case Type.BYTE: return "getByte"; case Type.CHAR: return "getChar"; case Type.SHORT: return "getShort"; case Type.INT: return "getInt"; case Type.LONG: return "getLong"; case Type.FLOAT: return "getFloat"; default: return "getDouble"; } }
    static String boxOwner(Type t) { switch (t.getSort()) { case Type.BOOLEAN: return "java/lang/Boolean"; case Type.BYTE: return "java/lang/Byte"; case Type.CHAR: return "java/lang/Character"; case Type.SHORT: return "java/lang/Short"; case Type.INT: return "java/lang/Integer"; case Type.LONG: return "java/lang/Long"; case Type.FLOAT: return "java/lang/Float"; default: return "java/lang/Double"; } }
    void wrapTryCatch(MethodNode m) {
        LabelNode s = new LabelNode(), e = new LabelNode(), h = new LabelNode();
        m.instructions.insertBefore(m.instructions.getFirst(), s);
        m.instructions.add(e);
        m.instructions.add(h);
        m.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        m.instructions.add(new InsnNode(Opcodes.DUP_X1)); m.instructions.add(new InsnNode(Opcodes.SWAP));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V", false));
        m.instructions.add(new InsnNode(Opcodes.ATHROW));
        m.tryCatchBlocks.add(new TryCatchBlockNode(s, e, h, "java/lang/Throwable"));
    }
    /** append FIELD lookups to the sidecar <clinit>. */
    /** Where the reflective-handle initializers must go: immediately AFTER the STATE PUTSTATIC that addSidecarClinit
     *  emits, which is before any merged mixin {@code <clinit>} body.
     *
     *  <p>Appending them before the trailing RETURN instead (as this used to) puts them after the merged body — but that
     *  body is ref-rewritten with inSidecar=true, so it can contain refGet$/refSet$/rmi$ calls that read F$/RM$. Those
     *  would still be null, giving an NPE inside Field.get/AwReflect.invokeResolved, wrapped into an
     *  ExceptionInInitializerError on the sidecar, which fails Conv.define and silently loads the target with no hooks. */
    AbstractInsnNode reflectInitPoint(MethodNode clinit) {
        for (AbstractInsnNode p = clinit.instructions.getFirst(); p != null; p = p.getNext())
            if (p instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC
                    && fi.owner.equals(sidecar) && fi.name.equals("STATE")) return p.getNext();
        AbstractInsnNode ret = clinit.instructions.getLast();   // no STATE field: fall back to the trailing RETURN
        while (ret != null && ret.getOpcode() != Opcodes.RETURN) ret = ret.getPrevious();
        return ret;
    }

    void addReflectInit(ClassNode S) {
        MethodNode clinit = findMethod(S, "<clinit>");
        if (clinit == null) throw new IllegalStateException("sidecar has no <clinit>; addSidecarClinit must run first");
        AbstractInsnNode ret = reflectInitPoint(clinit);
        InsnList add = new InsnList();
        for (String key : reflectFields) {
            String nm = key.substring(0, key.indexOf(' '));
            add.add(new LdcInsnNode(Type.getObjectType(targetInternal)));
            add.add(new LdcInsnNode(nm));
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
            add.add(new InsnNode(Opcodes.DUP));
            add.add(new InsnNode(Opcodes.ICONST_1));
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false));
            add.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "F$" + nm, "Ljava/lang/reflect/Field;"));
        }
        clinit.instructions.insertBefore(ret, add); clinit.maxStack = Math.max(clinit.maxStack, 3);
    }

    static String invokerId(String name, String desc) { return mangle(name) + "__" + mangle(desc); }
    /** Injective mapping of an internal name / method descriptor to a legal Java identifier body (encodes '/', ';',
     *  '(', ')', '[', '&lt;', '&gt;', '.', '|' and escapes '_'), so two generated helpers can never alias onto the same
     *  slot. Replaces the old 24-bit desc.hashCode() key, under which two same-named private overloads could collide
     *  onto one rmi$/RM$ pair (and two interface (owner,name,desc) triples onto one trampoline). The "__" join is safe
     *  because a mangled part never contains two adjacent underscores. */
    static String mangle(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i);
            switch (c) {
                case '_': b.append("_u"); break; case '/': b.append("_s"); break; case ';': b.append("_e"); break;
                case '[': b.append("_a"); break; case '(': b.append("_l"); break; case ')': b.append("_r"); break;
                case '<': b.append("_i"); break; case '>': b.append("_g"); break; case '.': b.append("_p"); break;
                case '|': b.append("_b"); break; default: b.append(c);
            }
        }
        return b.toString();
    }

    /** B (methods): a static invoker rmi$<id>(Target self, args...) that reflectively calls the private target method. */
    void addReflectiveInvoker(ClassNode S, String name, String desc, boolean isStatic) {
        String id = invokerId(name, desc);
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "RM$" + id, "Ljava/lang/reflect/Method;", null, null));
        Type[] at = Type.getArgumentTypes(desc); Type rt = Type.getReturnType(desc);
        String bridgeDesc = isStatic ? desc : "(" + targetDesc + desc.substring(1);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rmi$" + id, bridgeDesc, null, null);
        InsnList in = m.instructions;
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "RM$" + id, "Ljava/lang/reflect/Method;"));
        if (isStatic) in.add(new InsnNode(Opcodes.ACONST_NULL));
        else in.add(new VarInsnNode(Opcodes.ALOAD, 0));                  // receiver
        in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < at.length; i++) {
            in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i));
            in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD), slot));
            box(in, at[i]);
            in.add(new InsnNode(Opcodes.AASTORE));
            slot += at[i].getSize();
        }
        in.add(new LdcInsnNode(targetInternal+"."+name+desc));
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "invokeResolved", "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        if (rt.getSort() == Type.VOID) { in.add(new InsnNode(Opcodes.POP)); in.add(new InsnNode(Opcodes.RETURN)); }
        else if (rt.getSort() <= Type.DOUBLE) { in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "nn", "(Ljava/lang/Object;)Ljava/lang/Object;", false)); unbox(in, rt); in.add(new InsnNode(rt.getOpcode(Opcodes.IRETURN))); }
        else { in.add(new TypeInsnNode(Opcodes.CHECKCAST, rt.getInternalName())); in.add(new InsnNode(Opcodes.ARETURN)); }
        m.maxStack = 9 + at.length; m.maxLocals = slot; S.methods.add(m);
    }

    /** Distinct slot per (refcOwner, name, desc): one target can super-call the same signature at two hierarchy levels. */
    static String superInvokerId(String owner, String name, String desc) {
        return mangle(owner) + "__" + mangle(name) + "__" + mangle(desc);
    }

    /** SUPER-CALL bridge {@code smi$<id>(Target self, args...)}: a direct {@code invokeExact} on the
     *  {@code SMH$<id>} handle that {@link #addSuperMethodInit} bound with {@code findSpecial}.
     *
     *  <p>{@code invokeExact} is signature-polymorphic, so the call site descriptor IS the handle's type
     *  ({@code (Target, args...)ret} — {@code findSpecial} prepends the specialCaller as the receiver parameter). That
     *  means no boxing, no varargs array, no reflective unwrapping, and a JIT-inlinable direct call: this bridge is
     *  cheaper than the {@code rmi$}/{@code Method.invoke} path it replaces, not more expensive. It also propagates the
     *  callee's exceptions unwrapped, matching what the original {@code invokespecial} did. */
    void addSuperInvoker(ClassNode S, String owner, String name, String desc) {
        String id = superInvokerId(owner, name, desc);
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "SMH$" + id, "Ljava/lang/invoke/MethodHandle;", null, null));
        Type[] at = Type.getArgumentTypes(desc); Type rt = Type.getReturnType(desc);
        String bridgeDesc = "(" + targetDesc + desc.substring(1);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "smi$" + id, bridgeDesc, null, null);
        InsnList in = m.instructions;
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "SMH$" + id, "Ljava/lang/invoke/MethodHandle;"));
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));                       // receiver == specialCaller-typed leading arg
        int slot = 1;
        for (Type a : at) { in.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot)); slot += a.getSize(); }
        in.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", bridgeDesc, false));
        in.add(new InsnNode(rt.getSort() == Type.VOID ? Opcodes.RETURN : rt.getOpcode(Opcodes.IRETURN)));
        m.maxStack = slot + 1; m.maxLocals = slot; S.methods.add(m);
    }

    /** Bind each super-call handle in the sidecar {@code <clinit>}, at the same point as the reflective handles (see
     *  {@link #reflectInitPoint}: before any merged mixin {@code <clinit>} body, which may itself contain smi$ calls).
     *
     *  <p>{@code MethodHandles.lookup()} is caller-sensitive, so invoking it HERE yields a full-privilege Lookup on the
     *  SIDECAR — which on vanilla/Fabric lives in the target's own package, classloader and (unnamed, hence open)
     *  module. {@link mcgraft.rt.AwReflect#superHandle} turns that into a private Lookup on the target and calls
     *  {@code findSpecial(refc, name, type, target)}. Routing through the rt helper (rather than emitting the
     *  privateLookupIn/findSpecial pair inline) keeps the JPMS fallback in one place and puts the call under the
     *  existing {@link #unresolvedRtCalls} contract gate. */
    void addSuperMethodInit(ClassNode S) {
        MethodNode clinit = findMethod(S, "<clinit>");
        if (clinit == null) throw new IllegalStateException("sidecar has no <clinit>; addSidecarClinit must run first");
        AbstractInsnNode ret = reflectInitPoint(clinit);
        InsnList add = new InsnList();
        for (String key : superMethods) {
            String[] k = key.split(" ");   // owner name desc — none of the three can contain a space
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false));
            add.add(new LdcInsnNode(Type.getObjectType(targetInternal)));   // specialCaller + privateLookupIn subject
            add.add(new LdcInsnNode(Type.getObjectType(k[0])));             // refc (the super declaring class/interface)
            add.add(new LdcInsnNode(k[1]));
            add.add(new LdcInsnNode(Type.getMethodType(k[2])));             // CONSTANT_MethodType
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "superHandle",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
            add.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "SMH$" + superInvokerId(k[0], k[1], k[2]), "Ljava/lang/invoke/MethodHandle;"));
        }
        clinit.instructions.insertBefore(ret, add); clinit.maxStack = Math.max(clinit.maxStack, 5);
    }

    /** resolve each private target Method reflectively in the sidecar <clinit>. */
    void addReflectMethodInit(ClassNode S) {
        MethodNode clinit = findMethod(S, "<clinit>");
        if (clinit == null) throw new IllegalStateException("sidecar has no <clinit>; addSidecarClinit must run first");
        AbstractInsnNode ret = reflectInitPoint(clinit);   // see reflectInitPoint: must precede any merged mixin <clinit>
        InsnList add = new InsnList();
        for (String key : reflectMethods) {
            String nm = key.substring(0, key.indexOf(' ')), dc = key.substring(key.indexOf(' ') + 1);
            Type[] at = Type.getArgumentTypes(dc);
            add.add(new LdcInsnNode(Type.getObjectType(targetInternal)));
            add.add(new LdcInsnNode(nm));
            add.add(intConst(at.length)); add.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
            for (int i = 0; i < at.length; i++) { add.add(new InsnNode(Opcodes.DUP)); add.add(intConst(i)); loadClassConst(add, at[i]); add.add(new InsnNode(Opcodes.AASTORE)); }
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
            add.add(new InsnNode(Opcodes.DUP));
            add.add(new InsnNode(Opcodes.ICONST_1));
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible", "(Z)V", false));
            add.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "RM$" + invokerId(nm, dc), "Ljava/lang/reflect/Method;"));
        }
        clinit.instructions.insertBefore(ret, add); clinit.maxStack = Math.max(clinit.maxStack, 6);
    }
    static AbstractInsnNode intConst(int v) { if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v); if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, v); if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v); return new LdcInsnNode(v); }
    static void box(InsnList in, Type t) { if (t.getSort() > Type.DOUBLE) return; String b = boxOwner(t); in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, b, "valueOf", "(" + t.getDescriptor() + ")L" + b + ";", false)); }
    static void unbox(InsnList in, Type t) { String b = boxOwner(t); in.add(new TypeInsnNode(Opcodes.CHECKCAST, b)); in.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, b, unboxMethod(t), "()" + t.getDescriptor(), false)); }
    static String unboxMethod(Type t) { switch (t.getSort()) { case Type.BOOLEAN: return "booleanValue"; case Type.BYTE: return "byteValue"; case Type.CHAR: return "charValue"; case Type.SHORT: return "shortValue"; case Type.INT: return "intValue"; case Type.LONG: return "longValue"; case Type.FLOAT: return "floatValue"; default: return "doubleValue"; } }
    static void loadClassConst(InsnList in, Type t) { if (t.getSort() <= Type.DOUBLE) in.add(new FieldInsnNode(Opcodes.GETSTATIC, boxOwner(t), "TYPE", "Ljava/lang/Class;")); else in.add(new LdcInsnNode(t)); }

    /** A class THIS converter generated (a sidecar or its $State). Such a class is a bare Object subclass and is never
     *  a nestmate of its target, however similar the names look. */
    static boolean isGeneratedClass(String internal) {
        if (internal.endsWith("$$Sidecar") || internal.contains("$$Sidecar$")) return true;
        String p = SIDECAR_PKG; return p != null && internal.startsWith(p);
    }

    /** A class or member THIS converter generated (sidecar/state classes, relocated handlers, accessors, reflective
     *  bridges). The pre-publish access gate skips these: they are ours and emitted public by construction, so the
     *  target's modifiers never apply to them. */
    static boolean isGeneratedMember(String owner, String name) {
        if (isGeneratedClass(owner)) return true;
        return name.startsWith("h$") || name.startsWith("get$") || name.startsWith("set$")
            || name.startsWith("refGet$") || name.startsWith("refSet$") || name.startsWith("rmi$")
            || name.startsWith("smi$") || name.startsWith("ifd$") || name.startsWith("F$")
            || name.startsWith("RM$") || name.startsWith("SMH$")
            || name.equals("STATE") || name.equals("getState") || name.equals("initState");
    }

    static String ifaceTrampName(String iface, String name, String desc) {
        return "ifd$" + mangle(name) + "__" + mangle(iface + "|" + desc);
    }

    static MethodNode buildIfaceDispatchTramp(String trampName, String iface, String name, String desc) {
        Type[] at = Type.getArgumentTypes(desc); Type rt = Type.getReturnType(desc);
        String td = "(Ljava/lang/Object;" + desc.substring(1);
        // MUST be public: this lives on the sidecar but is invoked from target' (and from other sidecars), which are
        // separate classes with no nest relationship — a private static cross-class INVOKESTATIC is IllegalAccessError
        // at first execution. Every other generated sidecar member (h$, get$/set$, refGet$/refSet$, rmi$, initState,
        // getState) is public for the same reason.
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, trampName, td, null, null);
        InsnList in = m.instructions;
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new LdcInsnNode(iface)); in.add(new LdcInsnNode(name)); in.add(new LdcInsnNode(desc));
        in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = 1;
        for (int i = 0; i < at.length; i++) {
            in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i));
            in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD), slot)); box(in, at[i]);
            in.add(new InsnNode(Opcodes.AASTORE)); slot += at[i].getSize();
        }
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mcgraft/rt/DuckDispatch", "invoke",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        emitReturn(in, rt);
        m.maxStack = 8 + at.length; m.maxLocals = slot;
        return m;
    }

    /** Schema-neutral payload-caller rewrite for interfaces dropped from already-loaded targets.
     *
     *  <p>{@code scope} is the payload whose class this is. It decides which payload's relocated members an ambiguous
     *  call site resolves to, and which payload's {@code $State} a reflective added-field access is routed to. Null is
     *  accepted (no payload claims the class) and falls back to searching every scope. */
    public static byte[] rewriteCaller(byte[] callerBytes, Map<String,List<String[]>> ifaceMap, PayloadScope scope) {
        ClassNode c = read(callerBytes); int n = 0;
        String scopeKey = scope == null ? "" : scope.key;
        for (MethodNode m : c.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode p = m.instructions.getFirst(), nx; p != null; p = nx) {
                nx = p.getNext();
                if (p instanceof TypeInsnNode ti && ifaceMap.containsKey(ti.desc)
                        && (ti.getOpcode() == Opcodes.CHECKCAST || ti.getOpcode() == Opcodes.INSTANCEOF)) {
                    String iface = ti.desc; List<String[]> impls = ifaceMap.get(iface);
                    if (canCollapse(iface, impls)) ti.desc = impls.get(0)[0];
                    else if (ti.getOpcode() == Opcodes.CHECKCAST) {
                        InsnList call = new InsnList(); call.add(new LdcInsnNode(iface));
                        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mcgraft/rt/DuckDispatch", "cast",
                            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
                        m.instructions.insertBefore(ti, call); m.instructions.remove(ti);
                    }
                    else {
                        InsnList call = new InsnList(); call.add(new LdcInsnNode(iface));
                        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mcgraft/rt/DuckDispatch", "isInstance", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
                        m.instructions.insertBefore(ti, call); m.instructions.remove(ti);
                    }
                    n++;
                } else if (p instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEINTERFACE && ifaceMap.containsKey(mi.owner)) {
                    List<String[]> impls = ifaceMap.get(mi.owner);
                    if (canCollapse(mi.owner, impls)) {
                        String[] ts = impls.get(0);
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, ts[1], "h$" + mi.name, "(L" + ts[0] + ";" + mi.desc.substring(1), false));
                    } else {
                        rewriteDuckInvokeInline(m, mi);
                    }
                    n++;
                } else if (p instanceof MethodInsnNode mi && mi.getOpcode() != Opcodes.INVOKEINTERFACE
                        && addedCallStatic(scope, mi.owner, mi.name, mi.desc, mi.getOpcode() == Opcodes.INVOKESTATIC) != null) {
                    // Call to a mixin-ADDED method (relocated to a sidecar) reached via a CONCRETE-class
                    // INVOKEVIRTUAL/INVOKESPECIAL/INVOKESTATIC rather than through a duck interface. the payload routes added
                    // methods through duck interfaces (handled above); clients that OVERRIDE an inherited vanilla
                    // method call the concrete class directly, so redirect those sites to the sidecar static exactly
                    // as rewriteRefs does — preserving the mixin override behavior instead of silently dispatching to
                    // the surviving inherited implementation.
                    String[] g = addedCallStatic(scope, mi.owner, mi.name, mi.desc, mi.getOpcode() == Opcodes.INVOKESTATIC);
                    String ot = g[0], os = g[1]; boolean gStatic = "1".equals(g[2]);
                    String nd = eraseIfaceDesc(gStatic ? mi.desc : "(L" + ot + ";" + mi.desc.substring(1));
                    m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, os, "h$" + mi.name, nd, false));
                    n++;
                } else if (p instanceof MethodInsnNode mi && rewriteReflectSite(m, mi, scopeKey)) {
                    n++;   // route a reflective added-field access (getDeclaredField + Field.get/set) to $State
                }
            }
        }
        // Erase payload duck-interface TYPES in this caller's declared field/method signatures + their access
        // instructions. The cast/invoke SITES were rewritten above, but a field like `IMinecraftClient IMC` still
        // names a de-implemented interface -> it fails the residual-reference gate and would VerifyError. Same
        // erasure the sidecar gets: payload interface -> its single concrete implementor (or Object).
        int e = 0;
        for (FieldNode f : c.fields) { String nd = eraseIfaceType(f.desc); if (!nd.equals(f.desc)) { f.desc = nd; f.signature = null; e++; } }
        for (MethodNode m : c.methods) {
            String nd = eraseIfaceDesc(m.desc); if (!nd.equals(m.desc)) { m.desc = nd; m.signature = null; e++; }
            if (m.instructions != null) for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
                if (p instanceof FieldInsnNode fi) { String d = eraseIfaceType(fi.desc); if (!d.equals(fi.desc)) { fi.desc = d; e++; } }
                else if (p instanceof MethodInsnNode mi) { String d = eraseIfaceDesc(mi.desc); if (!d.equals(mi.desc)) { mi.desc = d; e++; } }
            }
            if (m.localVariables != null) for (var lv : m.localVariables) { String d = eraseIfaceType(lv.desc); if (d != null && !d.equals(lv.desc)) { lv.desc = d; lv.signature = null; } }
        }
        return (n == 0 && e == 0) ? callerBytes : write(c, callerBytes);
    }

    /** Field.get/set accessor names routed through the $State reflection shim. */
    private static final Set<String> REFLECT_FIELD_OPS = Set.of("get","set","getBoolean","setBoolean","getInt","setInt",
        "getLong","setLong","getFloat","setFloat","getDouble","setDouble","getByte","setByte","getShort","setShort","getChar","setChar");

    /** Route a payload's reflective added-field access to mcgraft.rt.StateReflect: {@code Class.getDeclaredField} and
     *  {@code Field.get/set(...)} become static shim calls (Field prepended) so a relocated ($State) field resolves.
     *  Non-relocated fields pass through the shim unchanged, so this is safe to apply to every such site.
     *
     *  <p>{@code scopeKey} (pushed as a third argument to {@code declaredField}) says WHICH payload's relocated field
     *  is meant: {@code StateReflect}'s table is keyed by (payload, target, field), because two payloads can each add a
     *  field of the same name to the same Minecraft class and each has its own {@code $State}. The {@code Field.get/set}
     *  variants need no such argument — they discriminate on the {@code $State} class the Field is declared by, and
     *  that class name already carries the payload. */
    static boolean rewriteReflectSite(MethodNode m, MethodInsnNode mi, String scopeKey) {
        if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
        if (mi.owner.equals("java/lang/Class") && mi.name.equals("getDeclaredField") && mi.desc.equals("(Ljava/lang/String;)Ljava/lang/reflect/Field;")) {
            m.instructions.insertBefore(mi, new LdcInsnNode(scopeKey));
            m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, "mcgraft/rt/StateReflect", "declaredField",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
            recordRtCall("mcgraft/rt/StateReflect", "declaredField", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/reflect/Field;");
            return true;
        }
        if (mi.owner.equals("java/lang/reflect/Field") && REFLECT_FIELD_OPS.contains(mi.name)) {
            String d = "(Ljava/lang/reflect/Field;" + mi.desc.substring(1);
            m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, "mcgraft/rt/StateReflect", mi.name, d, false));
            recordRtCall("mcgraft/rt/StateReflect", mi.name, d);
            return true;
        }
        return false;
    }

    /** Spill an interface invocation to fresh locals and call the generic dispatcher without adding a helper method. */
    static void rewriteDuckInvokeInline(MethodNode m, MethodInsnNode mi) {
        Type[] at = Type.getArgumentTypes(mi.desc); Type rt = Type.getReturnType(mi.desc);
        int next = m.maxLocals, receiver = next++;
        int[] slots = new int[at.length];
        for (int i=0;i<at.length;i++){slots[i]=next;next+=at[i].getSize();}
        InsnList in = new InsnList();
        for (int i=at.length-1;i>=0;i--) in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ISTORE),slots[i]));
        in.add(new VarInsnNode(Opcodes.ASTORE,receiver));
        in.add(new VarInsnNode(Opcodes.ALOAD,receiver));
        in.add(new LdcInsnNode(mi.owner)); in.add(new LdcInsnNode(mi.name)); in.add(new LdcInsnNode(mi.desc));
        in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
        for(int i=0;i<at.length;i++){in.add(new InsnNode(Opcodes.DUP));in.add(intConst(i));
            in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD),slots[i]));box(in,at[i]);in.add(new InsnNode(Opcodes.AASTORE));}
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"mcgraft/rt/DuckDispatch","invoke",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        adaptInlineResult(in,rt,false,NEVER_INACC);
        m.instructions.insertBefore(mi,in);m.instructions.remove(mi);m.maxLocals=Math.max(m.maxLocals,next);
    }

    /** Decides whether an the payload access of an MC member must be reflection-routed, and which MC types are inaccessible
     *  (already-loaded + package-private: can be neither AW-widened nor referenced from the payload bytecode). */
    public interface Resolver {
        boolean fieldNeedsReflect(String owner, String name, String desc);
        boolean methodNeedsReflect(String owner, String name, String desc);
        boolean typeInaccessible(String internalName);
    }
    static final String AWR = "mcgraft/rt/AwReflect";
    /** Type-inaccessibility predicate used when erasing CHECKCASTs to types the rewritten class cannot name. It is
     *  threaded as a parameter through the AW-inline helpers (NOT a shared static) so the caller path (payload→MC, uses the
     *  Resolver) and the sidecar path (relocated body, uses {@link #typeInaccessibleFromSidecar}) never clobber each
     *  other and both are thread-safe. NEVER_INACC is the no-op used where no erasure applies. */
    static final Predicate<String> NEVER_INACC = t -> false;

    /** Rewrite the payload's own direct access to non-public members / inaccessible TYPES of already-loaded MC classes into
     *  reflective calls (fields/methods/ctors -> mcgraft/rt/AwReflect via string-named owner+param types, so no inaccessible
     *  type appears as a constant), and erase CHECKCASTs to inaccessible types. Classes loaded AFTER attach get the
     *  AccessWidener on-load, so their members stay direct (Resolver returns false for them). */
    public static synchronized byte[] rewritePayloadAw(byte[] payloadBytes, Resolver r) {
        Predicate<String> inacc = r::typeInaccessible;
            ClassNode c = read(payloadBytes);
            boolean[] changed = {false};
            for (MethodNode m : c.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode p = m.instructions.getFirst(), nx; p != null; p = nx) {
                    nx = p.getNext();
                    if (p instanceof FieldInsnNode fi && r.fieldNeedsReflect(fi.owner, fi.name, fi.desc)) {
                        rewriteFieldAw(m, fi, inacc); changed[0] = true;
                    } else if (p instanceof MethodInsnNode ci && ci.getOpcode() == Opcodes.INVOKESPECIAL && ci.name.equals("<init>")
                            && r.methodNeedsReflect(ci.owner, ci.name, ci.desc)) {
                        AbstractInsnNode nw = ci.getPrevious();
                        while (nw != null && !(nw instanceof TypeInsnNode tn && tn.getOpcode() == Opcodes.NEW && tn.desc.equals(ci.owner))) nw = nw.getPrevious();
                        if (nw != null && nw.getNext() != null && nw.getNext().getOpcode() == Opcodes.DUP) {
                            AbstractInsnNode dup = nw.getNext();
                            m.instructions.remove(nw); m.instructions.remove(dup);
                            rewriteAwCtorInline(m,ci,inacc); changed[0] = true;
                        } else {
                            // Same as the sidecar path: we already know this ctor is inaccessible here, so silently
                            // leaving it emits an IllegalAccessError with nothing in the verification report. Worse,
                            // changed[0] stays false, so the class may be returned byte-identical and look untouched.
                            LateAttachVerifier.warn("CTOR_LOWERING_UNMATCHED", c.name, m.name + m.desc,
                                "cannot lower inaccessible " + ci.owner + ".<init>" + ci.desc + ": no NEW/DUP prologue");
                        }
                    } else if (p instanceof MethodInsnNode mi && mi.name.charAt(0) != '<'
                            && (mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKESTATIC || mi.getOpcode() == Opcodes.INVOKESPECIAL)
                            && r.methodNeedsReflect(mi.owner, mi.name, mi.desc)) {
                        rewriteAwMethodInline(m,mi,inacc); changed[0] = true;
                    } else if (p instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.CHECKCAST
                            && ti.desc.charAt(0) != '[' && r.typeInaccessible(ti.desc)) {
                        m.instructions.remove(p);   // erase CHECKCAST to an inaccessible type (value flows as Object)
                        changed[0] = true;
                    }
                }
            }
            return changed[0] ? write(c, payloadBytes) : payloadBytes;
    }

    static void rewriteAwMethodInline(MethodNode m, MethodInsnNode mi, Predicate<String> inacc) {
        boolean isStatic=mi.getOpcode()==Opcodes.INVOKESTATIC;
        Type[] at=Type.getArgumentTypes(mi.desc);Type rt=Type.getReturnType(mi.desc);
        int next=m.maxLocals,receiver=isStatic?-1:next++;
        int[] slots=new int[at.length];for(int i=0;i<at.length;i++){slots[i]=next;next+=at[i].getSize();}
        InsnList in=new InsnList();
        for(int i=at.length-1;i>=0;i--)in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ISTORE),slots[i]));
        if(!isStatic)in.add(new VarInsnNode(Opcodes.ASTORE,receiver));
        in.add(new LdcInsnNode(mi.owner));in.add(new LdcInsnNode(mi.name));pushStrArray(in,at);
        in.add(new LdcInsnNode(mi.owner+"#"+mi.name+mi.desc));
        if(isStatic)in.add(new InsnNode(Opcodes.ACONST_NULL));else in.add(new VarInsnNode(Opcodes.ALOAD,receiver));
        pushObjectArgs(in,at,slots);
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC,AWR,"inv",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        adaptInlineResult(in,rt,true,inacc);
        m.instructions.insertBefore(mi,in);m.instructions.remove(mi);m.maxLocals=Math.max(m.maxLocals,next);
    }

    static void rewriteAwCtorInline(MethodNode m, MethodInsnNode ci, Predicate<String> inacc) {
        Type[] at=Type.getArgumentTypes(ci.desc);int next=m.maxLocals;int[] slots=new int[at.length];
        for(int i=0;i<at.length;i++){slots[i]=next;next+=at[i].getSize();}
        InsnList in=new InsnList();for(int i=at.length-1;i>=0;i--)in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ISTORE),slots[i]));
        in.add(new LdcInsnNode(ci.owner));pushStrArray(in,at);in.add(new LdcInsnNode(ci.owner+"#<init>"+ci.desc));pushObjectArgs(in,at,slots);
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC,AWR,"newInst",
            "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        if(!inacc.test(ci.owner))in.add(new TypeInsnNode(Opcodes.CHECKCAST,ci.owner));
        m.instructions.insertBefore(ci,in);m.instructions.remove(ci);m.maxLocals=Math.max(m.maxLocals,next);
    }

    static void pushObjectArgs(InsnList in,Type[] at,int[] slots){in.add(intConst(at.length));in.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
        for(int i=0;i<at.length;i++){in.add(new InsnNode(Opcodes.DUP));in.add(intConst(i));in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD),slots[i]));box(in,at[i]);in.add(new InsnNode(Opcodes.AASTORE));}}

    static void adaptInlineResult(InsnList in,Type rt,boolean eraseInaccessible,Predicate<String> inacc){
        if(rt.getSort()==Type.VOID){in.add(new InsnNode(Opcodes.POP));return;}
        if(rt.getSort()<=Type.DOUBLE){unbox(in,rt);return;}
        if(rt.getSort()==Type.ARRAY)in.add(new TypeInsnNode(Opcodes.CHECKCAST,rt.getDescriptor()));
        else if(!eraseInaccessible||!inacc.test(rt.getInternalName()))in.add(new TypeInsnNode(Opcodes.CHECKCAST,rt.getInternalName()));
    }
    /** internal name (object) or descriptor (array/primitive) as a STRING to hand AwReflect for Class.forName. */
    static String typeName(Type t) { return t.getSort() == Type.OBJECT ? t.getInternalName() : t.getDescriptor(); }
    static void rewriteFieldAw(MethodNode m, FieldInsnNode fi, Predicate<String> inacc) {
        Type ft = Type.getType(fi.desc); String sfx = awSfx(ft);
        String valDesc = ft.getSort() <= Type.DOUBLE ? ft.getDescriptor() : "Ljava/lang/Object;";
        int op = fi.getOpcode();
        InsnList pre = new InsnList();
        if (op == Opcodes.GETSTATIC || op == Opcodes.GETFIELD) {
            if (op == Opcodes.GETSTATIC) pre.add(new InsnNode(Opcodes.ACONST_NULL));
            pre.add(new LdcInsnNode(fi.owner)); pre.add(new LdcInsnNode(fi.name));
            m.instructions.insertBefore(fi, pre);
            MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "g" + sfx, "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)" + valDesc, false);
            m.instructions.set(fi, call);
            // CHECKCAST back to the field type, UNLESS that type is inaccessible (then leave as Object)
            if (ft.getSort() == Type.ARRAY) m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, ft.getDescriptor()));
            else if (ft.getSort() == Type.OBJECT && !inacc.test(ft.getInternalName())) m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
        } else { // PUTFIELD / PUTSTATIC
            pre.add(new LdcInsnNode(fi.owner)); pre.add(new LdcInsnNode(fi.name));
            m.instructions.insertBefore(fi, pre);
            if (op == Opcodes.PUTFIELD)
                m.instructions.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "s" + sfx, "(Ljava/lang/Object;" + valDesc + "Ljava/lang/String;Ljava/lang/String;)V", false));
            else
                m.instructions.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "ss" + sfx, "(" + valDesc + "Ljava/lang/String;Ljava/lang/String;)V", false));
        }
    }
    static String awSfx(Type t) { switch (t.getSort()) { case Type.INT: return "I"; case Type.LONG: return "J"; case Type.BOOLEAN: return "Z"; case Type.FLOAT: return "F"; case Type.DOUBLE: return "D"; case Type.BYTE: return "B"; case Type.SHORT: return "S"; case Type.CHAR: return "C"; default: return "O"; } }
    static void pushStrArray(InsnList in, Type[] at) { in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String")); for (int i = 0; i < at.length; i++) { in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i)); in.add(new LdcInsnNode(typeName(at[i]))); in.add(new InsnNode(Opcodes.AASTORE)); } }
    static void emitReturn(InsnList in, Type rt) {
        if (rt.getSort() == Type.VOID) { in.add(new InsnNode(Opcodes.POP)); in.add(new InsnNode(Opcodes.RETURN)); }
        else if (rt.getSort() <= Type.DOUBLE) { unbox(in, rt); in.add(new InsnNode(rt.getOpcode(Opcodes.IRETURN))); }
        else if (rt.getSort() == Type.ARRAY) { in.add(new TypeInsnNode(Opcodes.CHECKCAST, rt.getDescriptor())); in.add(new InsnNode(Opcodes.ARETURN)); }
        else { in.add(new TypeInsnNode(Opcodes.CHECKCAST, rt.getInternalName())); in.add(new InsnNode(Opcodes.ARETURN)); }
    }

    // ---- io ----
    static ClassNode read(byte[] b) { ClassReader r = new ClassReader(b); ClassNode n = new ClassNode(); r.accept(n, ClassReader.SKIP_FRAMES); return n; }
    /** Normalize against and re-emit from the authoritative JVM-supplied retransform buffer. */
    public static byte[] rebase(byte[] candidate, byte[] actualBaseline) {
        ClassNode baseline = read(actualBaseline), rebased = read(candidate);
        restoreOriginalSchema(baseline, rebased);
        return write(rebased, actualBaseline);
    }
    /** reads class metadata (superName / interfaces / isInterface) WITHOUT loading the class; set by the agent. */
    public static java.util.function.Function<String,byte[]> CLASS_BYTES;
    /** [BUG22 FIX] When emitting a class that will be REDEFINED over an already-loaded original (target',
     *  rewritten the payload callers), seed the ClassWriter with the ORIGINAL bytes' ClassReader so the emitted constant
     *  pool is an index-preserving EXTENSION of the loaded class's pool (original entries keep their indices, new
     *  entries appended). A freely reordered pool triggers a JVM redefinition bug on records with annotated record
     *  components (GuiMessage: @Nullable on the `signature` component): RetransformClasses succeeds but the class's
     *  field metadata is corrupted -> intermittent `NoSuchFieldError: addedTime` at GuiMessage.<init>, and
     *  EXCEPTION_ACCESS_VIOLATION in Class.getDeclaredFields0 (JDK-8315575 family; reproduced payload-free with a pure
     *  ASM round-trip, and clean with an identity clone and with this CP-preserving mode). NOTE: the basis MUST be
     *  the loaded original O, not the mixin output X
     *  (Mixin's own ClassWriter already reordered X's pool). */
    static byte[] write(ClassNode n, byte[] orig) {
        ClassWriter w = orig != null
            ? new ClassWriter(new ClassReader(orig), ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                  protected String getCommonSuperClass(String a, String b) { return commonSuper(a, b); }
              }
            : new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                  protected String getCommonSuperClass(String a, String b) { return commonSuper(a, b); }
              };
        n.accept(w); return w.toByteArray();
    }
    // --- correct getCommonSuperClass by reading class bytes (no classloading, no init) ---
    static final Map<String,String[]> META = new java.util.concurrent.ConcurrentHashMap<>();   // internal -> [superName, isInterface?"1":"0", iface...]
    static final java.util.concurrent.ConcurrentHashMap<String,Boolean> NON_PUBLIC_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean nonPublicField(String owner,String name,String desc){String key=owner+'\0'+name+' '+desc;Boolean cached=NON_PUBLIC_FIELDS.get(key);if(cached!=null)return cached;
        boolean result=false;String c=owner;int guard=0;while(c!=null&&!c.equals("java/lang/Object")&&guard++<64){
            Integer enforced=enforcedFieldAccess(c,name,desc);if(enforced!=null){result=(enforced&Opcodes.ACC_PUBLIC)==0;break;}   // already-loaded declarer: its pre-widener flags are what the JVM enforces
            try{byte[] b=CLASS_BYTES==null?null:CLASS_BYTES.apply(c);if(b==null)break;ClassReader cr=new ClassReader(b);ClassNode n=new ClassNode();cr.accept(n,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);boolean found=false;for(FieldNode f:n.fields)if(f.name.equals(name)&&f.desc.equals(desc)){result=(f.access&Opcodes.ACC_PUBLIC)==0;found=true;break;}if(found)break;c=cr.getSuperName();}catch(Throwable t){break;}}
        Boolean raced=NON_PUBLIC_FIELDS.putIfAbsent(key,result);return raced==null?result:raced;}
    static final java.util.concurrent.ConcurrentHashMap<String,Integer> CLASS_ACCESS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Class access flags read from CLASS_BYTES (no classloading/init); -1 when the bytes are unavailable. An
     *  `accessible class` widening the bytes carry but the ALREADY-LOADED class never received is masked back off. */
    static int classAccess(String internal){
        return effectiveClassAccess(internal, CLASS_ACCESS.computeIfAbsent(internal,k->{try{byte[] b=CLASS_BYTES==null?null:CLASS_BYTES.apply(k);
            return b==null?-1:new ClassReader(b).getAccess();}catch(Throwable t){return -1;}}));
    }
    record MethodAccess(String owner,int ownerAccess,int memberAccess){}
    static final java.util.concurrent.ConcurrentHashMap<String,Optional<MethodAccess>> METHOD_ACCESS = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean illegalSidecarMethod(String caller,String owner,String name,String desc){
        Optional<MethodAccess> found=METHOD_ACCESS.computeIfAbsent(owner+'\0'+name+desc,k->resolveMethodAccess(owner,name,desc));
        if(found.isEmpty())return false;MethodAccess a=found.get();
        if((a.memberAccess&Opcodes.ACC_PRIVATE)!=0)return true;
        if((a.ownerAccess&Opcodes.ACC_PUBLIC)!=0&&(a.memberAccess&Opcodes.ACC_PUBLIC)!=0)return false;
        return !pkg(caller).equals(pkg(a.owner));
    }
    static Optional<MethodAccess> resolveMethodAccess(String owner,String name,String desc){
        // JVMS 5.4.3.3 resolves the superclass chain to exhaustion BEFORE any superinterface. Walking classes and
        // interfaces together (BFS) let a shallow public interface method shadow a nearer package-private class
        // method, so illegalSidecarMethod judged the call legal and emitted no reflection (fail-open) even though
        // the JVM would bind the restrictive class method -> IllegalAccessError at runtime. Match the JVM order.
        ArrayDeque<String> ifaces=new ArrayDeque<>();
        int guard=0;
        for(String c=owner;c!=null&&guard++<256;){try{byte[] b=CLASS_BYTES==null?null:CLASS_BYTES.apply(c);if(b==null)break;
            ClassNode n=new ClassNode();new ClassReader(b).accept(n,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return Optional.of(enforcedOr(c,n.access,name,desc,m.access));
            ifaces.addAll(n.interfaces);c=n.superName;}catch(Throwable ignored){break;}}
        HashSet<String> seen=new HashSet<>();
        while(!ifaces.isEmpty()){String c=ifaces.removeFirst();if(!seen.add(c))continue;try{byte[] b=CLASS_BYTES==null?null:CLASS_BYTES.apply(c);if(b==null)continue;ClassNode n=new ClassNode();new ClassReader(b).accept(n,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return Optional.of(enforcedOr(c,n.access,name,desc,m.access));ifaces.addAll(n.interfaces);}catch(Throwable ignored){}}
        return Optional.empty();
    }
    /** The declarer's access as the JVM enforces it: the pre-widener flags when {@code owner} was already loaded (its
     *  widening was inert), the widened bytes otherwise. */
    static MethodAccess enforcedOr(String owner,int ownerAccessFromBytes,String name,String desc,int memberAccessFromBytes){
        Integer enforced=enforcedMethodAccess(owner,name,desc);
        return new MethodAccess(owner,effectiveClassAccess(owner,ownerAccessFromBytes),enforced!=null?enforced:memberAccessFromBytes);
    }
    static String pkg(String n){int i=n.lastIndexOf('/');return i<0?"":n.substring(0,i);}
    static String[] meta(String cn) {
        String[] m = META.get(cn); if (m != null) return m;
        try {
            byte[] b = CLASS_BYTES != null ? CLASS_BYTES.apply(cn) : null;
            if (b == null) { m = new String[]{"java/lang/Object","0"}; }
            else { ClassReader cr = new ClassReader(b); List<String> l = new ArrayList<>();
                l.add(cr.getSuperName() == null ? "java/lang/Object" : cr.getSuperName());
                l.add((cr.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "1" : "0");
                for (String i : cr.getInterfaces()) l.add(i);
                m = l.toArray(new String[0]); }
        } catch (Throwable t) { m = new String[]{"java/lang/Object","0"}; }
        String[] prev = META.putIfAbsent(cn, m); return prev != null ? prev : m;
    }
    static boolean isIface(String cn) { return "1".equals(meta(cn)[1]); }
    static String superOf(String cn) { return meta(cn)[0]; }
    static boolean isAssignable(String from, String to) {   // is `from` a subtype of `to`?
        if (to.equals("java/lang/Object")) return true;
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>(); q.add(from); java.util.HashSet<String> seen = new java.util.HashSet<>();
        while (!q.isEmpty()) { String c = q.poll(); if (!seen.add(c)) continue; if (c.equals(to)) return true; if (c.equals("java/lang/Object")) continue;
            String[] m = meta(c); q.add(m[0]); for (int i = 2; i < m.length; i++) q.add(m[i]); }
        return false;
    }
    static String commonSuper(String a, String b) {
        if (a.equals(b)) return a;
        if (a.equals("java/lang/Object") || b.equals("java/lang/Object")) return "java/lang/Object";
        if (isAssignable(a, b)) return b;
        if (isAssignable(b, a)) return a;
        if (isIface(a) || isIface(b)) return "java/lang/Object";
        String c = a; int guard = 0;
        do { c = superOf(c); if (c == null || c.equals("java/lang/Object") || ++guard > 50) return "java/lang/Object"; } while (!isAssignable(b, c));
        return c;
    }
    static Set<String> keysF(ClassNode n) { Set<String> s = new HashSet<>(); for (FieldNode f : n.fields) s.add(f.name + " " + f.desc); return s; }
    static Set<String> keysM(ClassNode n) { Set<String> s = new HashSet<>(); for (MethodNode m : n.methods) s.add(m.name + " " + m.desc); return s; }
}
