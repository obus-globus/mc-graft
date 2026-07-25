import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import mcgraft.rt.InjectionLogger;

/** Reopen fabric-api's keybinding registry for late (post-attach) registration.
 *
 *  fabric-api's {@code KeyMappingRegistryImpl.registerKeyMapping} opens with a hard guard:
 *      if (Minecraft.getInstance().options != null) throw new IllegalStateException("GameOptions has already been initialised");
 *  which rejects every registration once the client has started — exactly the situation under late-attach. A mod that
 *  registers keybinds in its client init (e.g. Xaero's Minimap, on a deferred tick) then throws and fails to initialise.
 *
 *  This strips that leading guard so the registration proceeds. The KeyMapping objects a mod constructs are already
 *  entered into Minecraft's static {@code KeyMapping.MAP}/{@code ALL} at construction time, so the keybinds still
 *  respond to input; stripping the guard just lets fabric-api's own bookkeeping (its modded-keybinding list) complete
 *  without throwing.
 *
 *  <p>That bookkeeping alone is not enough: the registry's modded list is drained into {@code Options.keyMappings} by a
 *  mixin on the Options constructor, which under late-attach ran before the payload existed. A keybind that is only in
 *  the modded list therefore <em>works</em> but never appears in the Controls screen (which iterates
 *  {@code Options.keyMappings}). So the rewrite also appends a call to {@link mcgraft.rt.KeyBindSync#sync(Class)} at
 *  every return of the registration method, which merges the modded list into the live array. Hooking the method
 *  itself — rather than splicing once after the payload's entrypoints return — is what makes a mod that registers its
 *  keybinds on a later tick (Xaero's Minimap) work as well as one that registers them inline (Zoomify).
 *
 *  <p>Armed only when the payload sets {@code bootstrap.reopenKeyBindings=true}; disabled otherwise. */
public final class KeyBindingReopen {
    /** fabric-api's keybinding registry impl — the common case, so it stays the default and `=true` keeps working. Any
     *  other loader/library with the same late-registration guard is reachable through
     *  bootstrap.reopenKeyBindings.owner/.method instead of by editing the engine. */
    private static final String DEFAULT_IMPL = "net/fabricmc/fabric/impl/client/keymapping/KeyMappingRegistryImpl";
    static final String DEFAULT_METHOD = "registerKeyMapping";
    /** Owner (internal name) whose registration method carries the guard. null -> the feature is disabled. */
    public static volatile String TARGET = null;
    /** The registration method to strip the guard from. */
    public static volatile String METHOD = DEFAULT_METHOD;

    private KeyBindingReopen() {}

    public static void arm(String owner, String method) {
        TARGET = (owner == null || owner.isBlank()) ? DEFAULT_IMPL : owner;
        METHOD = (method == null || method.isBlank()) ? DEFAULT_METHOD : method;
    }

    /** Internal name of the runtime helper that merges the registry's modded list into the live Options array. Lives in
     *  {@code mcgraft.rt.*}, the package the injector exposes to the game classloader via parent delegation, so the
     *  appended call resolves to the same class the agent holds. */
    private static final String SYNC = "mcgraft/rt/KeyBindSync";

    /** Remove the leading {@code Minecraft.getInstance().options != null -> throw} guard from registerKeyMapping, and
     *  append a live-Options merge to each of its returns.
     *  Idempotent: a class with no such guard (already stripped, or a future fabric-api that drops it) keeps its body,
     *  and a method already carrying the merge call is left alone. */
    public static byte[] rewrite(String owner, byte[] bytes) {
        ClassNode c = new ClassNode();
        new ClassReader(bytes).accept(c, 0);
        boolean changed = false;
        for (MethodNode m : c.methods) {
            if (!m.name.equals(METHOD)) continue;
            changed |= stripGuard(owner, m);
            changed |= appendSync(owner, m);
        }
        if (!changed) return bytes;
        ClassWriter w = new ClassWriter(new ClassReader(bytes), ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        c.accept(w);
        return w.toByteArray();
    }

    /** Excise the leading {@code options != null -> throw} prologue. Returns true if the method was changed. */
    private static boolean stripGuard(String owner, MethodNode m) {
        // The guard is the method's leading `... IFNULL <rest>; new IllegalStateException; ...; athrow; <rest>:`.
        // The only IFNULL in this method is that guard (the dedup loop uses IFEQ / IF_ACMPNE); everything before its
        // target label is the guard prologue, and the label is reached solely via that branch (the fall-through path
        // ends in athrow). Excising [start, target) removes the guard cleanly, leaving an empty stack at the label.
        JumpInsnNode ifnull = null;
        for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
            if (p.getOpcode() == Opcodes.IFNULL) { ifnull = (JumpInsnNode) p; break; }
        }
        if (ifnull == null) return false;   // no guard present -> nothing to strip
        LabelNode target = ifnull.label;
        // Confirm the branch-not-taken path actually throws IllegalStateException (the real guard signature) before
        // excising the prologue. Guards against a future fabric-api whose leading IFNULL is something else — stripping
        // then would corrupt the method, so we leave it unchanged and warn instead.
        if (!throwsIllegalState(ifnull.getNext(), target)) {
            InjectionLogger.warn("KeyBindingReopen: leading IFNULL in " + owner + "." + METHOD
                + " does not guard an IllegalStateException throw — leaving the method unchanged");
            return false;
        }
        for (AbstractInsnNode p = m.instructions.getFirst(); p != null && p != target; ) {
            AbstractInsnNode next = p.getNext();
            m.instructions.remove(p);
            p = next;
        }
        return true;
    }

    /** Append {@code KeyBindSync.sync(<owner>.class)} before every return, so each registration — whenever it happens —
     *  re-merges the registry's modded list into the live Options array. The owner is pushed as a class constant, which
     *  resolves in the game's classloader and hands the helper that loader for free. No new branches are introduced, so
     *  the surrounding stack map is unaffected. */
    private static boolean appendSync(String owner, MethodNode m) {
        for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext())
            if (p instanceof MethodInsnNode mi && mi.owner.equals(SYNC)) return false;   // already hooked
        boolean added = false;
        for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
            int op = p.getOpcode();
            if (op < Opcodes.IRETURN || op > Opcodes.RETURN) continue;   // ATHROW is outside this range, by design
            InsnList call = new InsnList();
            call.add(new LdcInsnNode(Type.getObjectType(owner)));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SYNC, "sync", "(Ljava/lang/Class;)V", false));
            m.instructions.insertBefore(p, call);
            added = true;
        }
        return added;
    }

    /** True if the span {@code [from, to)} constructs and throws an IllegalStateException (the guard's throw path). */
    private static boolean throwsIllegalState(AbstractInsnNode from, AbstractInsnNode to) {
        boolean sawNew = false;
        for (AbstractInsnNode p = from; p != null && p != to; p = p.getNext()) {
            if (p.getOpcode() == Opcodes.NEW && ((TypeInsnNode) p).desc.equals("java/lang/IllegalStateException")) sawNew = true;
            if (p.getOpcode() == Opcodes.ATHROW && sawNew) return true;
        }
        return false;
    }
}
