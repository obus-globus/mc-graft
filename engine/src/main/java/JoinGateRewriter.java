import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Schema-neutral prologue injection for a client's static multiplayer-connect entry point (e.g. Minecraft's
 *  ConnectScreen.startConnecting). Optional: armed only when the payload configures bootstrap.joinGate.screen. */
public final class JoinGateRewriter {
    /** MC screen internal name whose startConnecting is gated. null -> the join-gate feature is disabled. */
    public static volatile String CONNECT_SCREEN = null;
    private static final String GATE = "mcgraft/rt/JoinGate";

    private JoinGateRewriter() {}

    public static byte[] rewrite(String owner, byte[] bytes) {
        if (CONNECT_SCREEN == null) return bytes;
        ClassNode c = new ClassNode();
        new ClassReader(bytes).accept(c, 0);
        boolean found = false, changed = false;
        for (MethodNode m : c.methods) {
            if (!m.name.equals("startConnecting") || (m.access & Opcodes.ACC_STATIC) == 0
                    || Type.getReturnType(m.desc).getSort() != Type.VOID) continue;
            found = true;
            if (alreadyGuarded(m)) continue;
            InsnList guard = new InsnList();
            guard.add(new LdcInsnNode(owner));
            guard.add(new LdcInsnNode(m.name));
            guard.add(new LdcInsnNode(m.desc));
            Type[] args = Type.getArgumentTypes(m.desc);
            guard.add(intConst(args.length));
            guard.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            int slot = 0;
            for (int i = 0; i < args.length; i++) {
                Type t = args[i];
                guard.add(new InsnNode(Opcodes.DUP));
                guard.add(intConst(i));
                guard.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
                box(guard, t);
                guard.add(new InsnNode(Opcodes.AASTORE));
                slot += t.getSize();
            }
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GATE, "deferStatic",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Z", false));
            LabelNode proceed = new LabelNode();
            guard.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(proceed);
            guard.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));
            m.instructions.insertBefore(m.instructions.getFirst(), guard);
            changed = true;
        }
        if (!found) throw new IllegalStateException("No static void startConnecting method in " + owner);
        if (!changed) return bytes;
        ClassWriter w=new ClassWriter(new ClassReader(bytes),ClassWriter.COMPUTE_MAXS);
        c.accept(w);
        return w.toByteArray();
    }

    private static boolean alreadyGuarded(MethodNode m) {
        for (AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext())
            if (p instanceof MethodInsnNode mi && mi.owner.equals(GATE) && mi.name.equals("deferStatic")) return true;
        return false;
    }

    private static void box(InsnList in, Type t) {
        if (t.getSort() < Type.BOOLEAN || t.getSort() > Type.DOUBLE) return;
        String o = switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character"; case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer"; case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float"; default -> "java/lang/Double";
        };
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC,o,"valueOf","("+t.getDescriptor()+")L"+o+";",false));
    }

    private static AbstractInsnNode intConst(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH,v);
        return new IntInsnNode(Opcodes.SIPUSH,v);
    }
}
