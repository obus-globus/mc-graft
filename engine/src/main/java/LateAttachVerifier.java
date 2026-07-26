import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import mcgraft.rt.InjectionLogger;

/** Fail-fast structural and residual-reference validation for schema-neutral late-attach output. */
public final class LateAttachVerifier {
    public record Issue(String severity, String code, String owner, String artifact, String method, String message) {}
    private static final List<Issue> ISSUES = new CopyOnWriteArrayList<>();
    private static final Set<String> DEDUP = Collections.synchronizedSet(new HashSet<>());
    // Per-invocation error counter. Each verify* method reads it before/after its SYNCHRONOUS scan on the calling
    // thread, so the delta counts exactly the errors that scan recorded — errors logged by other threads bump THEIR
    // own counter, never this one, so a conversion cannot be falsely passed by an unrelated concurrent error. int[]
    // holder rather than ThreadLocal<Integer>: no boxing, and the single reused array means no per-thread leak growth.
    private static final ThreadLocal<int[]> LOCAL_ERRORS = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile boolean REPORT_STARTED;

    private LateAttachVerifier() {}

    public static boolean verifyConversion(String owner, byte[] original, byte[] target, byte[] sidecar, byte[] state,
            Map<String,String[]> removedMethods, Map<String,String[]> removedFields, Map<String,List<String[]>> droppedIfaces) {
        int before = localErrors();
        verifySchema(owner, "target", original, target);
        scan(owner, "target", target, removedMethods, removedFields, droppedIfaces);
        scan(owner, "sidecar", sidecar, removedMethods, removedFields, droppedIfaces);
        scan(owner, "state", state, removedMethods, removedFields, droppedIfaces);
        return localErrors() == before;
    }

    public static boolean verifyRetransform(String owner, byte[] actualBaseline, byte[] candidate) {
        int before = localErrors();
        verifySchema(owner, "retransform", actualBaseline, candidate);
        return localErrors() == before;
    }

    public static boolean verifyCaller(String owner, byte[] baseline, byte[] candidate, Map<String,String[]> removedMethods,
            Map<String,String[]> removedFields, Map<String,List<String[]>> droppedIfaces) {
        int before = localErrors();
        verifySchema(owner, "caller", baseline, candidate);
        scan(owner, "caller", candidate, removedMethods, removedFields, droppedIfaces);
        detectAccessorStubs(owner, candidate);
        return localErrors() == before;
    }

    public static void warn(String code, String owner, String artifact, String message) {
        add("WARN", code, owner, artifact, "", message);
    }

    public static void error(String code, String owner, String artifact, String message) {
        add("ERROR", code, owner, artifact, "", message);
    }

    public static boolean hasErrors() { return errorCount() > 0; }
    /** Clear the accumulated issue list so a SECOND injection is gated on its OWN findings. The list is static and was
     *  never reset, so injection A's residue would abort injection B at the conversion gate for reasons that have
     *  nothing to do with B. A's report has already been written by the time this runs. */
    public static synchronized void reset() { ISSUES.clear(); DEDUP.clear(); LOCAL_ERRORS.get()[0] = 0; }
    public static int issueMark(){ return ISSUES.size(); }
    public static java.util.List<String> issuesSince(int mark){ java.util.List<String> out=new java.util.ArrayList<>();
        for(int i=Math.max(0,mark);i<ISSUES.size();i++){ Issue is=ISSUES.get(i); out.add(is.code()+"/"+is.artifact()+": "+is.message()); } return out; }
    public static int errorCount() { int n=0; for(Issue i:ISSUES) if(i.severity.equals("ERROR")) n++; return n; }
    /** Non-fatal codes: best-effort late-attach residue (an individual hook that could not be (re)transformed live,
     *  e.g. ChatComponent's field-adding mixin, or an incidental on-load transform miss). These are recorded and
     *  logged but must NOT abort injection or permanently gate multiplayer — the client keeps running minus that hook. */
    private static final Set<String> SOFT_ERROR_CODES = Set.of(
        // TARGET_PUBLISH_FAILURE is soft for the same reason: it is only reachable AFTER the payload has bootstrapped
        // on the game thread, so aborting undoes nothing and would only leave the join gate shut. It is still an
        // ERROR line naming every payload involved — see FullInjectAgent.publishFailed.
        "TARGET_RETRANSFORM_FAILURE", "CALLER_RETRANSFORM_FAILURE", "TRANSFORM_FAILURE", "TARGET_PUBLISH_FAILURE");
    /** True only if a FATAL error was recorded (any ERROR whose code is not in {@link #SOFT_ERROR_CODES}). Bootstrap/
     *  activation/gate decisions use this so tolerable residue does not block init or lock the join gate closed. */
    public static boolean hasFatalErrors() { for(Issue i:ISSUES) if(i.severity.equals("ERROR")&&!SOFT_ERROR_CODES.contains(i.code)) return true; return false; }
    private static int localErrors() { return LOCAL_ERRORS.get()[0]; }
    public static List<Issue> issues() { return List.copyOf(ISSUES); }

    private static void verifySchema(String owner, String artifact, byte[] expectedBytes, byte[] actualBytes) {
        try {
            // A CALLER class may legally have payload duck-interface TYPES erased in its field/method signatures
            // (a de-implemented interface can't be a value type). Normalize both sides so that intentional erasure
            // is not a "mismatch". Targets/retransforms (eraseIfaces=false) must preserve their schema exactly.
            boolean cl = artifact.equals("caller");
            Schema e = Schema.read(expectedBytes, cl), a = Schema.read(actualBytes, cl);
            compare(owner, artifact, "class", e.header, a.header);
            compare(owner, artifact, "interfaces", e.interfaces, a.interfaces);
            compare(owner, artifact, "fields", e.fields, a.fields);
            compare(owner, artifact, "methods", e.methods, a.methods);
            compare(owner, artifact, "nest", e.nest, a.nest);
            compare(owner, artifact, "record", e.record, a.record);
            compare(owner, artifact, "permitted", e.permitted, a.permitted);
        } catch (Throwable t) { error("SCHEMA_PARSE", owner, artifact, String.valueOf(t)); }
    }

    private static void compare(String owner, String artifact, String part, Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            error("SCHEMA_MISMATCH", owner, artifact, part + " expected=" + expected + " actual=" + actual);
    }

    private static final class Schema {
        String header; List<String> interfaces, fields, methods, nest, record, permitted;
        static Schema read(byte[] b, boolean eraseIfaces) {
            ClassNode c = new ClassNode(); new ClassReader(b).accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            Schema s = new Schema();
            s.header = c.name + "|" + c.access + "|" + c.superName;
            s.interfaces = List.copyOf(c.interfaces);
            s.fields = new ArrayList<>(); for(FieldNode f:c.fields) s.fields.add(f.name+"|"+(eraseIfaces?RetransformConverter.eraseIfaceType(f.desc):f.desc)+"|"+f.access);
            s.methods = new ArrayList<>(); for(MethodNode m:c.methods) s.methods.add(m.name+"|"+(eraseIfaces?RetransformConverter.eraseIfaceDesc(m.desc):m.desc)+"|"+m.access);
            s.nest = new ArrayList<>(); s.nest.add(String.valueOf(c.nestHostClass)); if(c.nestMembers!=null)s.nest.addAll(c.nestMembers);
            s.record = new ArrayList<>(); if(c.recordComponents!=null)for(RecordComponentNode r:c.recordComponents)s.record.add(r.name+"|"+r.descriptor+"|"+r.signature);
            s.permitted = c.permittedSubclasses==null?List.of():List.copyOf(c.permittedSubclasses);
            return s;
        }
    }

    private static void scan(String owner, String artifact, byte[] bytes, Map<String,String[]> removedMethods,
            Map<String,String[]> removedFields, Map<String,List<String[]>> droppedIfaces) {
        try {
            ClassNode c=new ClassNode(); new ClassReader(bytes).accept(c,0);
            // The class's OWN declared fields. A reference to a field this class declares itself (owner == this class)
            // is that local field — never a relocated TARGET field — even when this class extends a target whose
            // same-named field was relocated (e.g. a mixin class that `extends TargetSuper` and declares a @Unique
            // field of its own). Without this guard the removed-ref hierarchy walk false-flags the mixin's own field.
            Set<String> ownFields=new HashSet<>();
            for(FieldNode f:c.fields) ownFields.add(f.name+" "+f.desc);
            for(FieldNode f:c.fields) scanDesc(owner,artifact,f.name,f.desc,droppedIfaces);
            for(MethodNode m:c.methods) {
                scanDesc(owner,artifact,m.name,m.desc,droppedIfaces);
                if(m.instructions==null)continue;
                for(AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext()) {
                    if(p instanceof FieldInsnNode fi) {
                        scanDesc(owner,artifact,m.name,fi.desc,droppedIfaces);
                        if(!(fi.owner.equals(c.name) && ownFields.contains(fi.name+" "+fi.desc)))
                            checkField(owner,artifact,m.name,fi.owner,fi.name,fi.desc,removedFields);
                    }
                    else if(p instanceof MethodInsnNode mi) {
                        scanDesc(owner,artifact,m.name,mi.desc,droppedIfaces);
                        if(droppedIfaces.containsKey(mi.owner) && mi.getOpcode()==Opcodes.INVOKEINTERFACE)
                            add("ERROR","DROPPED_INTERFACE_INVOKE",owner,artifact,m.name,mi.owner+"."+mi.name+mi.desc);
                        checkMethod(owner,artifact,m.name,mi.owner,mi.name,mi.desc,removedMethods);
                    } else if(p instanceof TypeInsnNode ti && droppedIfaces.containsKey(ti.desc)) {
                        String code=ti.getOpcode()==Opcodes.INSTANCEOF?"DROPPED_INTERFACE_INSTANCEOF":"DROPPED_INTERFACE_CAST";
                        add("ERROR",code,owner,artifact,m.name,ti.desc);
                    } else if(p instanceof InvokeDynamicInsnNode id) {
                        scanDesc(owner,artifact,m.name,id.desc,droppedIfaces);
                        inspectConst(owner,artifact,m.name,id.bsm,removedMethods,removedFields,droppedIfaces,true);
                        for(Object x:id.bsmArgs)inspectConst(owner,artifact,m.name,x,removedMethods,removedFields,droppedIfaces,true);
                    } else if(p instanceof MultiANewArrayInsnNode ma) {
                        scanDesc(owner,artifact,m.name,ma.desc,droppedIfaces);
                    } else if(p instanceof LdcInsnNode ldc) inspectConst(owner,artifact,m.name,ldc.cst,removedMethods,removedFields,droppedIfaces,true);
                }
            }
        } catch(Throwable t){ error("BYTECODE_PARSE",owner,artifact,String.valueOf(t)); }
    }

    private static void inspectConst(String owner,String artifact,String method,Object x,Map<String,String[]> rm,
            Map<String,String[]> rf,Map<String,List<String[]>> di,boolean bootstrap) {
        if(x instanceof Handle h) {
            scanDesc(owner,artifact,method,h.getDesc(),di);
            if(h.getTag()>=Opcodes.H_GETFIELD&&h.getTag()<=Opcodes.H_PUTSTATIC)checkField(owner,artifact,method,h.getOwner(),h.getName(),h.getDesc(),rf);
            else checkMethod(owner,artifact,method,h.getOwner(),h.getName(),h.getDesc(),rm);
            // A handle onto a SHARED interface method (a method-ref like IFoo::m) resolves against the real interface,
            // which still exists; only a PURE-duck interface handle is a genuine residual. (A method-ref invoked on a
            // de-implemented duck receiver would still ICCE at runtime — a known gap for pure method-refs.)
            if(di.containsKey(h.getOwner()) && !RetransformConverter.isSharedIface(h.getOwner()))add("ERROR","BOOTSTRAP_RESIDUAL_REF",owner,artifact,method,h.toString());
        } else if(x instanceof ConstantDynamic cd) {
            scanDesc(owner,artifact,method,cd.getDescriptor(),di);
            inspectConst(owner,artifact,method,cd.getBootstrapMethod(),rm,rf,di,true);
            for(int i=0;i<cd.getBootstrapMethodArgumentCount();i++)inspectConst(owner,artifact,method,cd.getBootstrapMethodArgument(i),rm,rf,di,true);
        } else if(x instanceof Type t) scanDesc(owner,artifact,method,t.getDescriptor(),di);
    }

    private static void checkField(String owner,String artifact,String method,String o,String n,String d,Map<String,String[]> removed) {
        if(containsRemoved(removed,o,n,d))add("ERROR","REMOVED_FIELD_REF",owner,artifact,method,o+"."+n+" "+d);
    }
    private static void checkMethod(String owner,String artifact,String method,String o,String n,String d,Map<String,String[]> removed) {
        if(containsRemoved(removed,o,n,d))add("ERROR","REMOVED_METHOD_REF",owner,artifact,method,o+"."+n+d);
    }
    private static String key(String o,String n,String d){return o+'\0'+n+' '+d;}
    private static boolean containsRemoved(Map<String,String[]> removed,String owner,String name,String desc){
        String c=owner;for(int guard=0;c!=null&&!c.equals("java/lang/Object")&&guard++<64;){
            if(removed.containsKey(key(c,name,desc)))return true;
            try{c=RetransformConverter.superOf(c);}catch(Throwable t){break;}
        }return false;
    }

    private static void scanDesc(String owner,String artifact,String method,String desc,Map<String,List<String[]>> dropped) {
        // Only a PAYLOAD-defined duck interface is a real descriptor problem: the de-implemented target no longer
        // satisfies it. A pre-existing/vanilla interface a mixin merely also implements still exists as a type with
        // real implementors, so a descriptor mentioning it is legal (its instanceof/cast/invokeinterface sites on the
        // de-implemented class are caught precisely + rewritten separately).
        // A SHARED payload interface (genuinely implemented by a real bundle class) is NOT erased from descriptors:
        // per JVMS the interface type verifies like Object, and its checked sites are routed through DuckDispatch. So a
        // descriptor mentioning it is legal — only a PURE-duck payload interface (no genuine implementor) is a problem.
        for(String iface:dropped.keySet())
            if(RetransformConverter.isPayloadIface(iface) && !RetransformConverter.isSharedIface(iface) && desc.contains("L"+iface+";"))
                add("ERROR","DROPPED_INTERFACE_DESCRIPTOR",owner,artifact,method,desc);
    }

    private static void detectAccessorStubs(String owner, byte[] bytes) {
        try {
            ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,0);
            for(MethodNode m:c.methods){boolean accessor=hasAnn(m,"Lorg/spongepowered/asm/mixin/gen/Accessor;")||hasAnn(m,"Lorg/spongepowered/asm/mixin/gen/Invoker;");
                if(!accessor||(m.access&Opcodes.ACC_STATIC)==0)continue; boolean throwsStub=false;
                if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0)throwsStub=true;
                if(m.instructions!=null)for(AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext())
                    if(p instanceof TypeInsnNode ti&&ti.getOpcode()==Opcodes.NEW&&(ti.desc.endsWith("AssertionError")||ti.desc.endsWith("NotImplementedError")||ti.desc.endsWith("AbstractMethodError")))throwsStub=true;
                if(throwsStub)add("ERROR","ACCESSOR_STUB",owner,"caller",m.name,m.name+m.desc);
            }
        }catch(Throwable t){error("ACCESSOR_SCAN",owner,"caller",String.valueOf(t));}
    }
    // ---- pre-publish ACCESS gate ------------------------------------------------------------------------------------
    // Everything else in this class is STRUCTURAL: schema equality, and "does an emitted class still reference a member
    // we relocated away". Those are link-time properties, and so is everything a forced link can prove — /tmp/linkchk
    // force-links every sidecar and only ever sees VerifyError/NoClassDefFoundError. An IllegalAccessError on a
    // field/method reference is NOT a link-time failure: HotSpot raises it when the instruction FIRST EXECUTES. So a
    // sidecar carrying a direct GETFIELD of a still-private target field injects clean, links clean, reports zero
    // errors — and then kills the client on the first chat message. That is precisely how it shipped.
    //
    // Hence this gate: for every member reference the emitted artifacts ACTUALLY CONTAIN (scanned out of the produced
    // bytes, never a hand-maintained list — same reasoning as unresolvedRtCalls), resolve the member on the LIVE class
    // and decide whether THIS accessor could legally execute that instruction. The live class is the oracle on
    // purpose: the bytes the converter reads are AccessWidener-widened, and for an already-loaded class that widening
    // never took effect, which is the entire bug class being guarded. An owner that is not loaded yet is skipped — it
    // really will be defined widened, so its widened bytes are the truth.
    /** Scan one emitted artifact. {@code loaded} maps an internal name to the already-loaded Class, or null. */
    public static void verifyMemberAccess(String owner, String artifact, byte[] bytes,
            java.util.function.Function<String,Class<?>> loaded) {
        try {
            ClassNode c = new ClassNode(); new ClassReader(bytes).accept(c, 0);
            Class<?> self = loaded.apply(c.name);      // target' -> the live target; a sidecar -> itself if already defined, else null
            for (MethodNode m : c.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
                    if (p instanceof FieldInsnNode fi) checkAccess(owner, artifact, m.name, c.name, self, loaded, fi.owner, fi.name, fi.desc, true);
                    else if (p instanceof MethodInsnNode mi && mi.getOpcode() != Opcodes.INVOKEINTERFACE)
                        checkAccess(owner, artifact, m.name, c.name, self, loaded, mi.owner, mi.name, mi.desc, false);
                    else if (p instanceof InvokeDynamicInsnNode id) for (Object x : id.bsmArgs) if (x instanceof Handle h) {
                        boolean fld = h.getTag() >= Opcodes.H_GETFIELD && h.getTag() <= Opcodes.H_PUTSTATIC;
                        checkAccess(owner, artifact, m.name, c.name, self, loaded, h.getOwner(), h.getName(), h.getDesc(), fld);
                    }
                }
            }
        } catch (Throwable t) { warn("ACCESS_SCAN", owner, artifact, "access gate could not scan this artifact: " + t); }
    }

    private static void checkAccess(String owner, String artifact, String method, String accessor, Class<?> self,
            java.util.function.Function<String,Class<?>> loaded, String o, String n, String d, boolean field) {
      try {
        if (o.equals(accessor) || o.isEmpty() || o.charAt(0) == '[') return;          // own member / array pseudo-owner (Object.clone etc.)
        if (o.startsWith("mcgraft/") || RetransformConverter.isGeneratedMember(o, n)) return;   // our own relocated/synthetic members
        Class<?> ow = loaded.apply(o);
        if (ow == null) return;                                                       // not loaded yet -> it WILL be defined widened
        java.lang.reflect.Member mem = field ? findLiveField(ow, n, d) : findLiveMethod(ow, n, d);
        if (mem == null) return;                                                      // can't resolve it -> say nothing rather than guess
        String why = accessDenial(mem.getDeclaringClass(), mem.getModifiers(), accessor, self);
        if (why == null) return;
        add("ERROR", "ILLEGAL_MEMBER_ACCESS", owner, artifact, method,
            accessor + " cannot access " + why + " " + mem.getDeclaringClass().getName().replace('.','/') + "." + n + (field ? " " : "") + d
            + " — the emitted " + (field ? "GET/PUTFIELD" : "INVOKE") + " would throw IllegalAccessError on first execution");
      } catch (Throwable t) {
        // A member of a class whose hierarchy cannot be resolved here (LiquidBounce carries macOS-only
        // ca.weblite.objc types) is not evidence of anything. Note it and move on — never fail the injection on it.
        warn("ACCESS_RESOLVE", owner, artifact, o + "." + n + " " + d + " -> " + t);
      }
    }

    /** JVMS 5.4.4 access control. {@code self} is the accessor's live Class when it has one; it is null for a target'
     *  that has not loaded yet, and for a sidecar of such a target.
     *
     *  <p>Without {@code self} neither nest membership nor the subclass rule can be evaluated, so the answer would be
     *  a guess — and guessing "denied" produces exactly the false positives LiquidBounce showed (a record's
     *  {@code super()} into protected {@code Record.<init>}, and a nested record reading its outer class's private
     *  fields). The rule is therefore: only report what is denied on EVERY reading. A sidecar is the one accessor we
     *  never need {@code self} for — it is a bare Object subclass in the target's package and can never be a nestmate
     *  or a subclass of anything — so full strictness still applies there, which is where the bug class lives. */
    private static String accessDenial(Class<?> declarer, int mods, String accessor, Class<?> self) {
        if (java.lang.reflect.Modifier.isPublic(mods)) return null;
        boolean sidecar = RetransformConverter.isGeneratedClass(accessor);             // provably neither nestmate nor subclass
        String di = declarer.getName().replace('.','/');
        String dp = RetransformConverter.pkg(di), ap = RetransformConverter.pkg(accessor);
        if (java.lang.reflect.Modifier.isPrivate(mods)) {
            if (sidecar) return "private";
            if (self != null) { try { return self.getNestHost() == declarer.getNestHost() ? null : "private"; } catch (Throwable ignored) { return null; } }
            return topLevel(di).equals(topLevel(accessor)) ? null : "private";         // same outer class -> assume the nest we cannot read
        }
        if (dp.equals(ap)) return null;                                               // protected and package-private are both fine in-package
        if (java.lang.reflect.Modifier.isProtected(mods)) {
            if (sidecar) return "protected";
            if (self == null) return null;                                            // cannot disprove inheritance
            return declarer.isAssignableFrom(self) ? null : "protected";
        }
        return "package-private";                                                     // no inheritance escape hatch for these
    }
    private static String topLevel(String internal){ int i=internal.indexOf('$'); return i<0?internal:internal.substring(0,i); }

    private static java.lang.reflect.Field findLiveField(Class<?> c, String name, String desc) {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (java.lang.reflect.Field f : k.getDeclaredFields())
                if (f.getName().equals(name) && Type.getDescriptor(f.getType()).equals(desc)) return f;
        for (Class<?> i : c.getInterfaces()) { var f = findLiveField(i, name, desc); if (f != null) return f; }
        return null;
    }
    private static java.lang.reflect.Member findLiveMethod(Class<?> c, String name, String desc) {
        if (name.equals("<init>")) { for (var ct : c.getDeclaredConstructors()) if (Type.getConstructorDescriptor(ct).equals(desc)) return ct; return null; }
        if (name.equals("<clinit>")) return null;
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (java.lang.reflect.Method m : k.getDeclaredMethods())
                if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return m;
        for (Class<?> i : c.getInterfaces()) { var m = findLiveMethod(i, name, desc); if (m != null) return m; }
        return null;
    }

    private static boolean hasAnn(MethodNode m,String d){if(m.visibleAnnotations!=null)for(AnnotationNode a:m.visibleAnnotations)if(a.desc.equals(d))return true;if(m.invisibleAnnotations!=null)for(AnnotationNode a:m.invisibleAnnotations)if(a.desc.equals(d))return true;return false;}

    private static void add(String severity,String code,String owner,String artifact,String method,String message){
        if(severity.equals("ERROR"))LOCAL_ERRORS.get()[0]++;
        String k=severity+'|'+code+'|'+owner+'|'+artifact+'|'+method+'|'+message;
        boolean added=DEDUP.add(k);if(added)ISSUES.add(new Issue(severity,code,owner,artifact,method,message));
        if(added&&REPORT_STARTED)writeReport();
    }

    public static synchronized Path writeReport() {
        try {
            // Namespaced under mcgraft.* like every other knob (mcgraft.agent.debug, mcgraft.sidecarPackage); the old
            // lb.agent.* name was a leak from the LiquidBounce-injector fork this engine came from. Still honoured so
            // an existing invocation doesn't silently start writing somewhere else.
            String override=System.getProperty("mcgraft.verificationReport");
            if(override==null||override.isBlank()) override=System.getProperty("lb.agent.verificationReport");
            // Default: a random-named temp file (createTempFile = O_EXCL + owner-only), not a fixed
            // /tmp/liquidbounce-lateattach-verification-<pid>.json a local attacker could pre-plant a symlink at (the
            // path is printed to the injection log, so a fixed name buys nothing). An explicit override is the caller's.
            Path out=override==null||override.isBlank()?Files.createTempFile("mcgraft-lateattach-verification-"+ProcessHandle.current().pid()+"-",".json"):Path.of(override);
            Path tmp=out.resolveSibling(out.getFileName()+".tmp");
            StringBuilder j=new StringBuilder("{\"schemaVersion\":1,\"status\":\"").append(hasErrors()?"error":"ok").append("\",\"summary\":{\"errors\":").append(errorCount()).append(",\"issues\":").append(ISSUES.size()).append("},\"issues\":[");
            for(int i=0;i<ISSUES.size();i++){if(i>0)j.append(',');Issue x=ISSUES.get(i);j.append('{').append(json("severity",x.severity)).append(',').append(json("code",x.code)).append(',').append(json("class",x.owner)).append(',').append(json("artifact",x.artifact)).append(',').append(json("method",x.method)).append(',').append(json("message",x.message)).append('}');}
            j.append("]}");Files.writeString(tmp,j,StandardCharsets.UTF_8);
            try{Files.move(tmp,out,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,out,StandardCopyOption.REPLACE_EXISTING);}
            REPORT_STARTED=true;InjectionLogger.info("verification report: "+out);return out;
        }catch(Throwable t){InjectionLogger.error("verification report failed",t);return null;}
    }
    private static String json(String k,String v){return "\""+k+"\":\""+escape(v)+"\"";}
    private static String escape(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
}
