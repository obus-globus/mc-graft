package mcgraft.rt;

import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime dispatcher for duck interfaces which cannot be added to already-loaded targets. */
public final class DuckDispatch {
    private static final ClassLoader SYS = mcgraft.rt.Platform.LOADER;
    private static final Map<String,List<Impl>> IMPLEMENTATIONS = new ConcurrentHashMap<>();
    private static final Map<String,Class<?>> TARGETS = new ConcurrentHashMap<>();
    private static final Map<Key,Method> METHODS = new ConcurrentHashMap<>();
    // For a SHARED duck interface (one a real bundle class genuinely implements), a receiver may be a real implementor
    // rather than a de-implemented mixin target; these cache the interface Class + the real interface method for it.
    private static final Map<String,Optional<Class<?>>> IFACES = new ConcurrentHashMap<>();
    // The real interface method depends only on (iface, name, desc), not the receiver class — key it that way so a
    // shared interface with many receiver classes doesn't spawn a redundant cache entry per class.
    private static final Map<String,Method> REAL_METHODS = new ConcurrentHashMap<>();

    private record Impl(String targetName, String sidecarName) {}
    private record Key(Class<?> receiver, String iface, String name, String desc) {}

    private DuckDispatch() {}

    public static synchronized void register(String iface, String target, String sidecar) {
        List<Impl> list = new ArrayList<>(IMPLEMENTATIONS.getOrDefault(iface, List.of()));
        for (Impl i : list) if (i.targetName.equals(target)) return;
        // Registration must not load the target: doing so before the CFT sees it would bypass conversion/AW.
        list.add(new Impl(target, sidecar));
        list.sort(Comparator.comparing(Impl::targetName));
        IMPLEMENTATIONS.put(iface, List.copyOf(list));
        METHODS.clear();
    }

    public static boolean isInstance(Object receiver, String iface) {
        if (receiver == null) return false;
        Class<?> ic = ifaceClass(iface);                                   // genuine implementor of a shared interface
        if (ic != null && ic.isInstance(receiver)) return true;
        for (Impl i : IMPLEMENTATIONS.getOrDefault(iface, List.of())) if (target(i).isInstance(receiver)) return true;
        return false;
    }

    public static Object cast(Object receiver, String iface) {
        if (receiver == null || isInstance(receiver, iface)) return receiver;
        throw new ClassCastException(receiver.getClass().getName() + " cannot be cast to " + iface.replace('/','.'));
    }

    public static Object invoke(Object receiver, String iface, String name, String desc, Object[] args) {
        if (receiver == null) throw new NullPointerException("duck receiver for " + iface + "." + name);
        try {
            Key key = new Key(receiver.getClass(), iface, name, desc);
            Class<?> ic = ifaceClass(iface);
            if (ic != null && ic.isInstance(receiver)) {                   // genuine implementor: call the real interface method
                Method rm = REAL_METHODS.computeIfAbsent(iface + "\0" + name + "\0" + desc, k -> realMethod(ic, name, desc));
                return rm.invoke(receiver, args);                         // normal instance call (receiver NOT prepended)
            }
            Method m = METHODS.computeIfAbsent(key, DuckDispatch::resolve);   // de-implemented (duck) target: sidecar static
            Object[] actual = new Object[args.length + 1];
            actual[0] = receiver; System.arraycopy(args, 0, actual, 1, args.length);
            return m.invoke(null, actual);
        } catch (InvocationTargetException e) {
            throw re(e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            throw re(t);
        }
    }

    private static Class<?> ifaceClass(String iface) {
        // Optional so the "unavailable" answer is CACHED: computeIfAbsent does not store a null mapping, so returning
        // null bare would re-attempt Class.forName on every dispatch for a pure-duck interface.
        return IFACES.computeIfAbsent(iface, n -> {
            try { return Optional.<Class<?>>of(Class.forName(n.replace('/','.'), false, SYS)); }
            catch (Throwable t) { return Optional.empty(); }   // unavailable -> pure-duck (no genuine implementors)
        }).orElse(null);
    }

    private static Method realMethod(Class<?> ic, String name, String desc) {
        try {
            MethodType mt = MethodType.fromMethodDescriptorString(desc, SYS);
            Method m = ic.getMethod(name, mt.parameterArray());
            m.setAccessible(true);
            return m;
        } catch (Throwable t) { throw re(t); }
    }

    private static Method resolve(Key key) {
        try {
            Impl best = null;
            for (Impl i : IMPLEMENTATIONS.getOrDefault(key.iface, List.of())) {
                Class<?> it = target(i);
                if (!it.isAssignableFrom(key.receiver)) continue;
                if (best == null || target(best).isAssignableFrom(it)) best = i;
                else if (!it.isAssignableFrom(target(best)))
                    throw new IncompatibleClassChangeError("Ambiguous duck interface " + key.iface + " for " + key.receiver.getName());
            }
            if (best == null) throw new IncompatibleClassChangeError("No duck implementation " + key.iface + " for " + key.receiver.getName());
            MethodType mt = MethodType.fromMethodDescriptorString(key.desc, SYS);
            Class<?>[] original = mt.parameterArray();
            Class<?>[] params = new Class<?>[original.length + 1];
            params[0] = target(best); System.arraycopy(original, 0, params, 1, original.length);
            Class<?> sidecar = Class.forName(best.sidecarName.replace('/','.'), false, SYS);
            Method m = sidecar.getMethod("h$" + key.name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            throw re(t);
        }
    }

    private static Class<?> target(Impl impl) {
        return TARGETS.computeIfAbsent(impl.targetName, n -> {
            try { return Class.forName(n.replace('/','.'), false, SYS); }
            catch (Throwable t) { throw re(t); }
        });
    }

    private static RuntimeException re(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
