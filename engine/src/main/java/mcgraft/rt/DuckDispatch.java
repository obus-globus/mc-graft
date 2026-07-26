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

    /** One payload's duck implementation of {@code iface} on {@code targetName}. {@code payloadScope} is what makes two
     *  payloads that both mixin the same interface onto the same target two DISTINCT implementations rather than one. */
    private record Impl(String targetName, String sidecarName, String payloadScope) {}
    private record Key(Class<?> receiver, String iface, String name, String desc) {}

    private DuckDispatch() {}

    public static synchronized void register(String iface, String target, String sidecar, String payloadScope) {
        List<Impl> list = new ArrayList<>(IMPLEMENTATIONS.getOrDefault(iface, List.of()));
        // Deduplicate within ONE payload only. Skipping on target alone (as this did) meant a second payload's sidecar
        // for the same target was never registered at all, so that payload's hooks on the interface were inert.
        for (Impl i : list) if (i.targetName.equals(target) && i.payloadScope.equals(payloadScope)) return;
        // Registration must not load the target: doing so before the CFT sees it would bypass conversion/AW.
        list.add(new Impl(target, sidecar, payloadScope));
        list.sort(Comparator.comparing(Impl::targetName).thenComparing(Impl::payloadScope));
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
            List<Impl> impls = IMPLEMENTATIONS.getOrDefault(key.iface, List.of());
            // Pick the most specific TARGET class, exactly as before — the ambiguity error still guards genuinely
            // unrelated targets. Selection is over target classes, not over Impls, so several payloads implementing the
            // interface on the SAME target are not mistaken for an ambiguity.
            Class<?> bestTarget = null;
            for (Impl i : impls) {
                Class<?> it = target(i);
                if (!it.isAssignableFrom(key.receiver)) continue;
                if (bestTarget == null || bestTarget.isAssignableFrom(it)) bestTarget = it;
                else if (!it.isAssignableFrom(bestTarget))
                    throw new IncompatibleClassChangeError("Ambiguous duck interface " + key.iface + " for " + key.receiver.getName());
            }
            if (bestTarget == null) throw new IncompatibleClassChangeError("No duck implementation " + key.iface + " for " + key.receiver.getName());
            MethodType mt = MethodType.fromMethodDescriptorString(key.desc, SYS);
            Class<?>[] original = mt.parameterArray();
            Class<?>[] params = new Class<?>[original.length + 1];
            params[0] = bestTarget; System.arraycopy(original, 0, params, 1, original.length);
            // Then pick the payload. Each payload's sidecar only carries the methods THAT payload's mixin added, so
            // "the sidecar that actually declares h$<name>" is the discriminator; a payload that added the interface
            // but not this method simply doesn't answer.
            Throwable miss = null;
            for (Impl i : impls) {
                if (target(i) != bestTarget) continue;
                try {
                    Class<?> sidecar = Class.forName(i.sidecarName.replace('/','.'), false, SYS);
                    Method m = sidecar.getMethod("h$" + key.name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException | ClassNotFoundException e) { miss = e; }
            }
            throw miss != null ? miss : new NoSuchMethodException("h$" + key.name + " on any sidecar of " + bestTarget.getName()
                + " for duck interface " + key.iface);
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
