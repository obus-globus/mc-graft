import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Read-only in-world probe for the P5 world-join test.
 *
 * Attaches as its OWN agent (its own Agent-Class), so FullInjectAgent's re-injection guard does not
 * apply to it and it can be attached repeatedly to a client that already took one or two payloads.
 *
 * It changes nothing. What it reports:
 *   - whether the client is actually in a world (mc.level / mc.player / mc.screen)
 *   - a DIRECT invocation of the co-hooked methods Camera.calculateFov(F)F and Camera.calculateHudFov(F)F
 *     on the LIVE camera instance, on the game thread. A returned number is proof the published
 *     (two-payload-woven) method body executed to completion — first-use linking of every sidecar it
 *     touches has happened, and no VerifyError / NoClassDefFoundError / IllegalAccessError was raised.
 *   - Options.sensitivity(), which Wurst's ZoomOtf.changeFovBasedOnZoom mutates as a side effect.
 *     That method is reachable ONLY from Wurst's Camera.calculateFov / calculateHudFov mixins, so a
 *     changed sensitivity while the probe is NOT running is evidence the GAME LOOP executed the hook.
 *   - which $$Sidecar$ classes are defined, and whether they are INITIALIZED (static init has run).
 *   - the zoom keybinds each payload registered, and whether they are currently down.
 *
 * Usage:  java -cp <dir with Attach.class> Attach <pid> worldprobe.jar <outfile>
 */
public class WorldProbe {
    static PrintStream o;

    public static void agentmain(String args, Instrumentation inst) {
        String out = (args == null || args.isBlank()) ? "/tmp/worldprobe.txt" : args.trim();
        try { o = new PrintStream(new FileOutputStream(out), true); } catch (Exception e) { return; }
        try { run(inst); } catch (Throwable t) { o.println("PROBE FAILED: " + t); t.printStackTrace(o); }
        o.println("PROBE END");
        o.flush();
    }

    static Map<String, Class<?>> loaded = new HashMap<>();

    static void run(Instrumentation inst) throws Exception {
        for (Class<?> c : inst.getAllLoadedClasses()) loaded.putIfAbsent(c.getName(), c);

        Class<?> mcCls = loaded.get("net.minecraft.client.Minecraft");
        if (mcCls == null) { o.println("Minecraft class not loaded"); return; }
        final ClassLoader knot = mcCls.getClassLoader();
        final Object mc = mcCls.getMethod("getInstance").invoke(null);
        o.println("knot loader = " + knot);

        // ---- sidecars: defined, and initialized? -------------------------------------------------
        o.println();
        o.println("== $$Sidecar$ classes defined in this JVM");
        List<Class<?>> sidecars = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses())
            if (c.getName().contains("$$Sidecar$")) sidecars.add(c);
        sidecars.sort(Comparator.comparing(Class::getName));
        o.println("count=" + sidecars.size());
        for (Class<?> c : sidecars) o.println("  " + c.getName());

        // ---- everything below must run on the game thread ----------------------------------------
        final StringBuilder sb = new StringBuilder();
        final CountDownLatch latch = new CountDownLatch(1);
        Method exec = mcCls.getMethod("execute", Runnable.class);
        exec.invoke(mc, (Runnable) () -> {
            try { onGameThread(mc, mcCls, knot, sb); }
            catch (Throwable t) {
                sb.append("GAME-THREAD PROBE FAILED: ").append(t).append('\n');
                StringWriter w = new StringWriter(); t.printStackTrace(new PrintWriter(w));
                sb.append(w);
            } finally { latch.countDown(); }
        });
        if (!latch.await(20, TimeUnit.SECONDS)) {
            o.println();
            o.println("GAME THREAD DID NOT RUN THE TASK IN 20s (client hung or not ticking)");
        }
        o.println();
        o.print(sb);
    }

    static void onGameThread(Object mc, Class<?> mcCls, ClassLoader knot, StringBuilder s) throws Exception {
        s.append("== on the game thread (").append(Thread.currentThread().getName()).append(")\n");

        Object level  = fieldVal(mc, mcCls, "level");
        Object player = fieldVal(mc, mcCls, "player");
        Object screen = fieldVal(mc, mcCls, "screen");
        s.append("IN_WORLD = ").append(level != null).append('\n');
        s.append("  mc.level  = ").append(level == null ? "null" : level.getClass().getName()).append('\n');
        s.append("  mc.player = ").append(player == null ? "null" : player.getClass().getName()).append('\n');
        s.append("  mc.screen = ").append(screen == null ? "null (no GUI open — in-game)" : screen.getClass().getName()).append('\n');
        if (player != null) {
            try {
                double x = (double) player.getClass().getMethod("getX").invoke(player);
                double y = (double) player.getClass().getMethod("getY").invoke(player);
                double z = (double) player.getClass().getMethod("getZ").invoke(player);
                s.append(String.format("  player pos = %.2f %.2f %.2f%n", x, y, z));
            } catch (Throwable t) { s.append("  player pos -> ").append(t).append('\n'); }
        }

        // ---- the co-hooked methods, invoked directly on the live Camera --------------------------
        s.append('\n');
        s.append("== DIRECT invocation of the co-hooked Camera methods (published bytes)\n");
        Object camera = null;
        try {
            Object gr = fieldVal(mc, mcCls, "gameRenderer");
            if (gr != null) {
                Method mainCam = gr.getClass().getMethod("mainCamera");
                mainCam.setAccessible(true);
                camera = mainCam.invoke(gr);
            }
        } catch (Throwable t) { s.append("  camera lookup -> ").append(t).append('\n'); }
        s.append("  camera = ").append(camera == null ? "null" : camera.getClass().getName()).append('\n');
        if (camera != null) {
            for (String m : new String[]{"calculateFov", "calculateHudFov"}) {
                try {
                    Method mm = camera.getClass().getDeclaredMethod(m, float.class);
                    mm.setAccessible(true);
                    Object v = mm.invoke(camera, 1.0f);
                    s.append("  ").append(m).append("(1.0f) = ").append(v).append("   [EXECUTED, no linkage error]\n");
                } catch (InvocationTargetException e) {
                    s.append("  ").append(m).append("(1.0f) THREW ").append(e.getCause()).append('\n');
                    StringWriter w = new StringWriter(); e.getCause().printStackTrace(new PrintWriter(w));
                    s.append(w);
                } catch (Throwable t) {
                    s.append("  ").append(m).append(" -> ").append(t).append('\n');
                }
            }
        }

        // ---- mouse sensitivity: Wurst's ZoomOtf side effect ---------------------------------------
        s.append('\n');
        s.append("== Options.sensitivity  (Wurst ZoomOtf.changeFovBasedOnZoom writes this; that method is\n");
        s.append("   reachable only from Wurst's Camera.calculateFov / calculateHudFov mixins)\n");
        try {
            Object opts = fieldVal(mc, mcCls, "options");
            Object oi = opts.getClass().getMethod("sensitivity").invoke(opts);
            Object v = oi.getClass().getMethod("get").invoke(oi);
            s.append("  sensitivity = ").append(v).append('\n');
        } catch (Throwable t) { s.append("  -> ").append(t).append('\n'); }

        // ---- scene state: everything that could move the lightmap between two screenshots ----------
        // (a run-to-run drift in scene brightness would otherwise be silently attributed to a payload)
        s.append('\n');
        s.append("== scene state\n");
        try {
            Object opts = fieldVal(mc, mcCls, "options");
            for (String g : new String[]{"gamma"}) {
                Object oi = opts.getClass().getMethod(g).invoke(opts);
                s.append("  options.").append(g).append(" = ").append(oi.getClass().getMethod("get").invoke(oi)).append('\n');
            }
        } catch (Throwable t) { s.append("  options -> ").append(t).append('\n'); }
        if (level != null) {
            for (String m : new String[]{"getDayTime", "getGameTime", "getSkyDarken", "getRainLevel", "getThunderLevel"}) {
                try {
                    Method mm;
                    try { mm = level.getClass().getMethod(m); }
                    catch (NoSuchMethodException e) { mm = level.getClass().getMethod(m, float.class); }
                    Object v = mm.getParameterCount() == 0 ? mm.invoke(level) : mm.invoke(level, 1.0f);
                    s.append("  level.").append(m).append(" = ").append(v).append('\n');
                } catch (Throwable t) { s.append("  level.").append(m).append(" -> ").append(t.getClass().getSimpleName()).append('\n'); }
            }
        }

        // ---- keybinds -----------------------------------------------------------------------------
        s.append('\n');
        s.append("== key mappings of interest (name | bound key | isDown)\n");
        try {
            Class<?> km = Class.forName("net.minecraft.client.KeyMapping", false, knot);
            Field all = km.getDeclaredField("ALL"); all.setAccessible(true);
            Collection<?> vals;
            Object a = all.get(null);
            vals = (a instanceof Map) ? ((Map<?, ?>) a).values() : (Collection<?>) a;
            for (Object k : vals) {
                String name = (String) km.getMethod("getName").invoke(k);
                if (!(name.contains("zoomify") || name.contains("wurst"))) continue;
                String bound = "?";
                try {
                    Method gk = km.getDeclaredMethod("getKey"); gk.setAccessible(true);
                    bound = String.valueOf(gk.invoke(k));
                } catch (Throwable ignored) {}
                Object down = "?";
                try { Method d = km.getMethod("isDown"); down = d.invoke(k); } catch (Throwable ignored) {}
                s.append("  ").append(name).append(" | ").append(bound).append(" | down=").append(down).append('\n');
            }
        } catch (Throwable t) { s.append("  -> ").append(t).append('\n'); }

        // ---- raw GLFW key state for the keys we drive ---------------------------------------------
        s.append('\n');
        s.append("== raw GLFW key state (Wurst's ZoomOtf reads this directly)\n");
        try {
            Class<?> ic = Class.forName("com.mojang.blaze3d.platform.InputConstants", false, knot);
            Object win = mcCls.getMethod("getWindow").invoke(mc);
            Method isDown = ic.getMethod("isKeyDown", win.getClass(), int.class);
            for (Object[] p : new Object[][]{{"C", 67}, {"V", 86}}) {
                Object d = isDown.invoke(null, win, (int) (Integer) p[1]);
                s.append("  key ").append(p[0]).append(" down=").append(d).append('\n');
            }
        } catch (Throwable t) { s.append("  -> ").append(t).append('\n'); }

        // ---- payload-side zoom state ---------------------------------------------------------------
        s.append('\n');
        s.append("== payload zoom state\n");
        try {
            Class<?> wc = Class.forName("net.wurstclient.WurstClient", false, knot);
            Object inst = wc.getField("INSTANCE").get(null);
            Object otfs = wc.getMethod("getOtfs").invoke(inst);
            Object zoom = otfs.getClass().getField("zoomOtf").get(otfs);
            Field cur = zoom.getClass().getDeclaredField("currentLevel"); cur.setAccessible(true);
            Field dms = zoom.getClass().getDeclaredField("defaultMouseSensitivity"); dms.setAccessible(true);
            Field lvl = zoom.getClass().getDeclaredField("level"); lvl.setAccessible(true);
            Object lv = lvl.get(zoom);
            Object lval = lv.getClass().getMethod("getValue").invoke(lv);
            s.append("  wurst ZoomOtf: level=").append(lval)
             .append(" currentLevel=").append(cur.get(zoom))
             .append(" defaultMouseSensitivity=").append(dms.get(zoom)).append('\n');
        } catch (Throwable t) { s.append("  wurst -> ").append(t).append('\n'); }
        try {
            Class<?> z = Class.forName("dev.isxander.zoomify.Zoomify", false, knot);
            s.append("  zoomify class present, loader=").append(z.getClassLoader()).append('\n');
            for (Field f : z.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!(n.contains("zoom") || n.contains("helper"))) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                String desc = String.valueOf(v);
                if (v != null && n.contains("helper")) desc = v.getClass().getName() + " " + dumpDoubles(v);
                s.append("    ").append(f.getName()).append(" = ").append(desc).append('\n');
            }
        } catch (Throwable t) { s.append("  zoomify -> ").append(t).append('\n'); }
    }

    static String dumpDoubles(Object v) {
        StringBuilder b = new StringBuilder("{");
        for (Field f : v.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t != double.class && t != float.class && t != boolean.class) continue;
            try { f.setAccessible(true); b.append(f.getName()).append('=').append(f.get(v)).append(' '); }
            catch (Throwable ignored) {}
        }
        return b.append('}').toString();
    }

    static Object fieldVal(Object obj, Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
            catch (NoSuchFieldException ignored) {} catch (Throwable t) { return null; }
        }
        return null;
    }
}
