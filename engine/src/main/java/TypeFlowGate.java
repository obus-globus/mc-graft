import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/** Pre-publish TYPE-FLOW gate: a data-flow proof, over the exact bytes we are about to define, that every value
 *  reaching a use is assignable to what that use demands.
 *
 *  <p><b>Why this exists.</b> The converter deliberately leaves some values typed {@code java/lang/Object}. When a
 *  reflective lowering ({@link RetransformConverter#rewriteAwCtorInline}, {@code rewriteAwMethodInline},
 *  {@code rewriteFieldAw}) produces a value whose real type the emitting class may not NAME, the CHECKCAST back to
 *  that type is omitted — emitting it would be {@code IllegalAccessError}. That erasure is correct in isolation, but
 *  it is only half an invariant: every CONSUMER of that value must also accept an Object. Nothing checked the other
 *  half, and a real client died on it —
 *
 *  <pre>
 *  VerifyError: Bad type on operand stack
 *    AbstractContainerScreen$$Sidecar$liquidbounce.h$handler$bgo000$hookDrawSlot @224: invokestatic
 *    Type 'java/lang/Object' is not assignable to 'net/minecraft/client/MouseHandler$LastClick'
 *  </pre>
 *
 *  <p>None of the existing gates could see it. The schema gate compares shapes, the residual-reference gate compares
 *  NAMES, and the pre-publish access gate applies JVMS 5.4.4 to member references — but this is neither a shape, a
 *  name, nor an access fault: the descriptor mention of a package-private type is perfectly legal (5.4.4 governs the
 *  referenced class and member, not the descriptor's types), and the fault is raised by the VERIFIER at LINK time,
 *  i.e. the first time gameplay touches any hook on that target. A sidecar is DEFINED at attach and LINKED on first
 *  use, so "injects cleanly" never said anything about it.
 *
 *  <p><b>Why it does not load classes.</b> The hierarchy oracle is {@link RetransformConverter}'s bytes-backed
 *  {@code isIface}/{@code superOf}/{@code isAssignable}, never {@code Class.forName}. Resolving descriptors during
 *  conversion force-loads mixin targets before the agent's transformer exists (measured on Wurst: +67 classes, three
 *  targets silently inert), which is exactly the hazard the access gate had to be moved to avoid.
 *
 *  <p><b>Fail-closed on proof, fail-open on ignorance.</b> {@link Verifier#isAssignableFrom} only answers "no" when
 *  it can prove it from bytes it actually has, or from the one relation that needs no hierarchy at all: nothing but
 *  an interface type accepts a {@code java/lang/Object}. Everything unknown is assignable, so a missing class can
 *  never fail an injection — it can only let a fault through, which is the same state as before this gate. */
final class TypeFlowGate {
    private TypeFlowGate() {}

    private static final Type NULL_TYPE = Type.getObjectType("null");
    private static final Type OBJECT_TYPE = Type.getObjectType("java/lang/Object");

    /** Cost, so it stays visible in the injection log rather than being assumed negligible. */
    static final java.util.concurrent.atomic.LongAdder NANOS = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder CLASSES = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder METHODS = new java.util.concurrent.atomic.LongAdder();
    static String cost() {
        return CLASSES.sum() + " classes / " + METHODS.sum() + " methods in " + (NANOS.sum() / 1_000_000) + "ms";
    }

    /** Analyse {@code bytes} and record an ERROR per method that cannot verify. {@code artifact} is the role
     *  ("sidecar"/"target"/"caller") the reports are filed under. */
    static void check(String owner, String artifact, byte[] bytes) { check(owner, artifact, bytes, true); }

    /** {@code fatal=false} records the same finding as a WARN. Used for CALLER classes: those are the payload's own
     *  bytecode, only patched at the duck/AW sites, so a finding there is as likely to be a limit of this analysis as
     *  a real fault — and rejecting a caller rewrite is WORSE than the fault (the class then loads with its duck sites
     *  un-rewritten, i.e. NoClassDefFoundError on a dropped interface). Sidecars are ours end to end, so they gate. */
    static void check(String owner, String artifact, byte[] bytes, boolean fatal) {
        if (bytes == null) return;
        long t0 = System.nanoTime();
        try { check0(owner, artifact, bytes, fatal); } finally { NANOS.add(System.nanoTime() - t0); CLASSES.increment(); }
    }

    private static void check0(String owner, String artifact, byte[] bytes, boolean fatal) {
        ClassNode c;
        try { c = new ClassNode(); new ClassReader(bytes).accept(c, ClassReader.SKIP_DEBUG); }
        catch (Throwable t) { LateAttachVerifier.warn("TYPE_FLOW_SCAN", owner, artifact, "unreadable: " + t); return; }
        Verifier v;
        try { v = new Verifier(c); } catch (Throwable t) { LateAttachVerifier.warn("TYPE_FLOW_SCAN", owner, artifact, "setup: " + t); return; }
        for (MethodNode m : c.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            METHODS.increment();
            try {
                new Analyzer<>(v).analyze(c.name, m);
            } catch (AnalyzerException e) {
                Reporter report = fatal ? LateAttachVerifier::error : LateAttachVerifier::warn;
                report.report("TYPE_FLOW", owner, artifact, c.name + "." + m.name + m.desc
                    + " will not verify: " + e.getMessage() + (e.node == null ? "" : " [at " + describe(e.node) + "]")
                    + ". If the message reads 'expected L<T>;, but found Ljava/lang/Object;', the known cause is an"
                    + " erased-but-still-consumed inaccessible type: a producer dropped the CHECKCAST because this class"
                    + " may not name T, so no consumer here may name T either. Any other message is a converter fault"
                    + " this gate has not seen before.");
            } catch (Throwable t) {
                // Analyzer limitations (maxStack/maxLocals, exotic shapes) are OUR blind spot, not the payload's fault.
                LateAttachVerifier.warn("TYPE_FLOW_SCAN", owner, artifact, c.name + "." + m.name + m.desc + ": " + t);
            }
        }
    }

    @FunctionalInterface private interface Reporter { void report(String code, String owner, String artifact, String message); }

    private static String describe(AbstractInsnNode n) {
        if (n instanceof MethodInsnNode mi) return "invoke " + mi.owner + "." + mi.name + mi.desc;
        if (n instanceof FieldInsnNode fi) return "field " + fi.owner + "." + fi.name + " " + fi.desc;
        if (n instanceof TypeInsnNode ti) return "type " + ti.desc;
        return "opcode " + n.getOpcode();
    }

    /** {@link SimpleVerifier} with every class-loading path replaced by the converter's bytes-backed oracle.
     *
     *  <p>{@code isSubTypeOf} is overridden as well as {@code isAssignableFrom}: the stock implementation falls back
     *  to {@code getClass(expectedType).isInterface()} when assignability fails, which is a {@code Class.forName}. */
    private static final class Verifier extends SimpleVerifier {
        Verifier(ClassNode c) {
            super(Opcodes.ASM9, Type.getObjectType(c.name),
                  c.superName == null ? null : Type.getObjectType(c.superName),
                  c.interfaces == null ? null : c.interfaces.stream().map(Type::getObjectType).toList(),
                  (c.access & Opcodes.ACC_INTERFACE) != 0);
        }

        private static boolean known(Type t) {
            return t.getSort() == Type.OBJECT && RetransformConverter.classAccess(t.getInternalName()) >= 0;
        }
        private static boolean isObject(Type t) {
            return t.getSort() == Type.OBJECT && t.getInternalName().equals("java/lang/Object");
        }

        @Override protected boolean isInterface(Type t) {
            return t.getSort() == Type.OBJECT && RetransformConverter.isIface(t.getInternalName());
        }

        @Override protected Type getSuperClass(Type t) {
            if (t.getSort() != Type.OBJECT) return OBJECT_TYPE;
            if (isObject(t)) return null;
            String s = RetransformConverter.superOf(t.getInternalName());
            return s == null ? OBJECT_TYPE : Type.getObjectType(s);
        }

        /** Never reached (every caller of it is overridden); present so an ASM change cannot silently start loading
         *  classes from inside a conversion. */
        @Override protected Class<?> getClass(Type t) { return Object.class; }

        @Override protected boolean isAssignableFrom(Type target, Type value) {
            if (target.equals(value)) return true;
            if (isObject(target)) return true;                       // everything widens to Object
            if (NULL_TYPE.equals(value)) return true;
            if (isObject(value)) {
                // THE rule this gate exists for, and the one case that needs no hierarchy: JVMS 4.10.1.2
                // isJavaAssignable(class(_,_), class(To,_)) holds only when To is an INTERFACE. An Object flowing
                // into a slot typed as a concrete class or an array is a VerifyError, always.
                return isInterface(target);
            }
            if (target.getSort() == Type.OBJECT && isInterface(target)) return true;   // classes widen to interfaces
            if (target.getSort() != Type.OBJECT || value.getSort() != Type.OBJECT) return true;   // arrays: not our business
            if (!known(target) || !known(value)) return true;        // no bytes -> no proof -> permissive
            return RetransformConverter.isAssignable(value.getInternalName(), target.getInternalName());
        }

        @Override protected boolean isSubTypeOf(BasicValue value, BasicValue expected) {
            Type expectedType = expected.getType(), type = value.getType();
            if (expectedType == null || type == null) return true;
            switch (expectedType.getSort()) {
                case Type.INT: case Type.FLOAT: case Type.LONG: case Type.DOUBLE:
                    return type.equals(expectedType);
                case Type.ARRAY: case Type.OBJECT:
                    if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) return false;
                    return isAssignableFrom(expectedType, type);
                default:
                    return true;
            }
        }
    }
}
