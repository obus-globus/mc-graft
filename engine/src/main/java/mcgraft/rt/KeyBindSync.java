package mcgraft.rt;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Splice late-registered key mappings into the LIVE {@code Options.keyMappings} array.
 *
 *  <p>Stripping fabric-api's {@code GameOptions has already been initialised} guard (see {@code KeyBindingReopen}) is
 *  only half of what a late-attached keybind needs. The registry's {@code registerKeyMapping} merely appends to its own
 *  {@code MODDED_KEY_BINDINGS} list; that list is drained into the game's keybinding array exactly once, by a mixin on
 *  the {@code Options} constructor. Under late-attach that constructor ran long before the payload existed, so the
 *  mapping never reaches {@code Options.keyMappings}.
 *
 *  <p>The consequence is subtle and easy to mistake for success: the mapping still <em>functions</em>, because
 *  {@code KeyMapping}'s constructor enters every instance into the static {@code ALL}/{@code MAP} registries that input
 *  dispatch reads. But the Controls screen, and the options.txt save/load round-trip, both iterate
 *  {@code Options.keyMappings} — so the keybind works while being invisible and unbindable in the UI.
 *
 *  <p>This re-runs the registry's own array-merge method against the live array and assigns the result back. The merge
 *  is looked up by signature ({@code static KeyMapping[] m(KeyMapping[])}) rather than by name, and is idempotent by
 *  construction (it copies the input, removes the modded entries, then re-adds them), so calling it repeatedly is safe.
 */
public final class KeyBindSync {
    private KeyBindSync() {}

    private static volatile String lastWarning;

    /** Bytecode entry point, invoked at every return of the guard-stripped registration method. The registry class is
     *  passed as an {@code LDC} constant so this inherits the game's classloader without any plumbing. */
    public static void sync(Class<?> registry) {
        try { syncOrThrow(registry); }
        catch (Throwable t) { warnOnce("keybind splice into live Options failed: " + t); }
    }

    /** Reflective entry point for the injector, which holds the game classloader but not the registry Class. */
    public static void syncFrom(ClassLoader loader, String registryInternalName) {
        if (registryInternalName == null) return;
        try { syncOrThrow(Class.forName(registryInternalName.replace('/', '.'), false, loader)); }
        catch (Throwable t) { warnOnce("keybind splice into live Options failed: " + t); }
    }

    private static void syncOrThrow(Class<?> registry) throws Exception {
        ClassLoader cl = registry.getClassLoader();
        Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, cl);
        Object mc = mcCls.getMethod("getInstance").invoke(null);
        if (mc == null) return;   // registration before the client exists — the game's own merge still runs; nothing to do

        Object options = null;
        for (Field f : mcCls.getDeclaredFields()) {
            if (!f.getName().equals("options")) continue;
            f.setAccessible(true); options = f.get(mc); break;
        }
        if (options == null) return;   // pre-Options registration — same as above

        Class<?> kmCls = Class.forName("net.minecraft.client.KeyMapping", false, cl);
        Field arr = liveArrayField(options, kmCls);
        if (arr == null) { warnOnce("no KeyMapping[] field found on " + options.getClass().getName()
            + "; late keybinds will work but stay absent from Controls"); return; }
        Method merge = mergeMethod(registry, kmCls);
        if (merge == null) { warnOnce("no 'static KeyMapping[] m(KeyMapping[])' merge method on " + registry.getName()
            + "; late keybinds will work but stay absent from Controls"); return; }

        Object[] before = (Object[]) arr.get(options);
        if (before == null) return;
        Object[] after = (Object[]) merge.invoke(null, (Object) before);
        if (after == null || after.length == before.length) return;   // already in sync — nothing new to splice
        // Options.keyMappings is a final instance field; setAccessible(true) grants write access to those (only static
        // finals and record components are refused), so no Unsafe is needed here.
        arr.set(options, after);
        InjectionLogger.info("spliced " + (after.length - before.length) + " late-registered key mapping(s) into the live"
            + " Options." + arr.getName() + " (" + before.length + " -> " + after.length + "); they now appear in Controls");
    }

    /** The full keybinding array on Options. Named mappings give it to us directly; otherwise fall back to the largest
     *  {@code KeyMapping[]} field, which is the complete list rather than the hotbar/debug subsets. */
    private static Field liveArrayField(Object options, Class<?> kmCls) throws Exception {
        Class<?> arrType = Array.newInstance(kmCls, 0).getClass();
        Field best = null; int bestLen = -1;
        for (Field f : options.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !f.getType().equals(arrType)) continue;
            f.setAccessible(true);
            if (f.getName().equals("keyMappings")) return f;
            Object v = f.get(options);
            int len = (v == null) ? -1 : ((Object[]) v).length;
            if (len > bestLen) { bestLen = len; best = f; }
        }
        return best;
    }

    /** The registry's array-merge method, found by signature so a rename does not silently disable the splice. */
    private static Method mergeMethod(Class<?> registry, Class<?> kmCls) {
        Class<?> arrType = Array.newInstance(kmCls, 0).getClass();
        for (Method m : registry.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getReturnType().equals(arrType)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !p[0].equals(arrType)) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** The hook fires on every registration, so an unavoidable failure must not spam the log once per keybind. */
    private static void warnOnce(String msg) {
        if (msg.equals(lastWarning)) return;
        lastWarning = msg;
        InjectionLogger.warn(msg);
    }
}
