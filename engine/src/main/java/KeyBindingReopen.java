import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

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
 *  without throwing. Armed only when the payload sets {@code bootstrap.reopenKeyBindings=true}; disabled otherwise. */
public final class KeyBindingReopen {
    /** fabric-api's keybinding registry impl (stable internal name). null -> the feature is disabled. */
    public static volatile String TARGET = null;
    private static final String IMPL = "net/fabricmc/fabric/impl/client/keymapping/KeyMappingRegistryImpl";

    private KeyBindingReopen() {}

    public static void arm() { TARGET = IMPL; }

    /** Remove the leading {@code Minecraft.getInstance().options != null -> throw} guard from registerKeyMapping.
     *  Idempotent: a class with no such guard (already stripped, or a future fabric-api that drops it) is returned as-is. */
    public static byte[] rewrite(String owner, byte[] bytes) {
        ClassNode c = new ClassNode();
        new ClassReader(bytes).accept(c, 0);
        boolean changed = false;
        for (MethodNode m : c.methods) {
            if (!m.name.equals("registerKeyMapping")) continue;
            // The guard is the method's leading `... IFNULL <rest>; new IllegalStateException; ...; athrow; <rest>:`.
            // The only IFNULL in this method is that guard (the dedup loop uses IFEQ / IF_ACMPNE); everything before its
            // target label is the guard prologue, and the label is reached solely via that branch (the fall-through path
            // ends in athrow). Excising [start, target) removes the guard cleanly, leaving an empty stack at the label.
            JumpInsnNode ifnull = null;
            for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
                if (p.getOpcode() == Opcodes.IFNULL) { ifnull = (JumpInsnNode) p; break; }
            }
            if (ifnull == null) continue;   // no guard present -> nothing to strip
            LabelNode target = ifnull.label;
            for (AbstractInsnNode p = m.instructions.getFirst(); p != null && p != target; ) {
                AbstractInsnNode next = p.getNext();
                m.instructions.remove(p);
                p = next;
            }
            changed = true;
        }
        if (!changed) return bytes;
        ClassWriter w = new ClassWriter(new ClassReader(bytes), ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        c.accept(w);
        return w.toByteArray();
    }
}
