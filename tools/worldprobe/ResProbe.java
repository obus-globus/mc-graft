import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Read-only probe for the LiquidBounce "liquidbounce namespace does not resolve" investigation.
 *
 * Attaches as its OWN agent (its own Agent-Class), exactly like WorldProbe, so FullInjectAgent's
 * re-injection guard does not apply and it can be attached to a client that already took a payload.
 * It mutates nothing.
 *
 * What it answers, each with a positive control so a null result cannot be mistaken for a blind probe:
 *
 *   A. FabricLoader — is a ModContainer registered for 'liquidbounce'?      (control: 'minecraft')
 *   B. Live resource manager — which namespaces does it carry, which packs are in it, and what does
 *      namespace 'liquidbounce' actually contain?                          (control: 'minecraft' listing)
 *   C. Do the specific ResourceLocations in the reported errors resolve
 *      from the live resource manager?                                     (control: a vanilla shader)
 *   D. CLASSPATH reachability from LiquidBounce's own defining loader — the path LB actually uses
 *      (LiquidBounce.resource -> getResourceAsStream("/resources/liquidbounce/...")).
 *   E. ClientShaders — is LB's own in-memory ShaderSource populated, and with which identifiers?
 *   F. TextureManager — is liquidbounce:logo registered?
 *   G. ClientInteropServer — is it up, on which port, and does GET /resource/liquidbounce/ answer?
 *
 * Usage:  java -cp <dir with Attach.class> Attach <pid> resprobe.jar <outfile>
 */
public class ResProbe {
    static PrintStream o;

    public static void agentmain(String args, Instrumentation inst) {
        String out = (args == null || args.isBlank()) ? "/tmp/resprobe.txt" : args.trim();
        try { o = new PrintStream(new FileOutputStream(out), true); } catch (Exception e) { return; }
        try { run(inst); } catch (Throwable t) { o.println("PROBE FAILED: " + t); t.printStackTrace(o); }
        o.println("PROBE END");
        o.flush();
    }

    static void run(Instrumentation inst) throws Exception {
        Class<?> mcCls = null;
        for (Class<?> c : inst.getAllLoadedClasses())
            if (c.getName().equals("net.minecraft.client.Minecraft")) { mcCls = c; break; }
        if (mcCls == null) { o.println("Minecraft class not loaded"); return; }
        final ClassLoader knot = mcCls.getClassLoader();
        final Object mc = mcCls.getMethod("getInstance").invoke(null);
        o.println("knot loader = " + knot);

        // Is LiquidBounce even loaded, and by whom?
        Class<?> lbCls = null;
        for (Class<?> c : inst.getAllLoadedClasses())
            if (c.getName().equals("net.ccbluex.liquidbounce.LiquidBounce")) { lbCls = c; break; }
        o.println("LiquidBounce class loaded = " + (lbCls != null)
                  + (lbCls != null ? "  (loader=" + lbCls.getClassLoader() + ")" : ""));
        final Class<?> LB = lbCls;

        // ---- A. FabricLoader mod containers ---------------------------------------------------------
        o.println();
        o.println("== A. FabricLoader ModContainers");
        try {
            Class<?> flCls = Class.forName("net.fabricmc.loader.api.FabricLoader", false, knot);
            Object fl = flCls.getMethod("getInstance").invoke(null);
            for (String id : new String[]{"minecraft", "fabric-api", "fabricloader", "liquidbounce"}) {
                Object opt = flCls.getMethod("getModContainer", String.class).invoke(fl, id);
                boolean present = (boolean) opt.getClass().getMethod("isPresent").invoke(opt);
                o.println("  getModContainer(\"" + id + "\") present = " + present
                          + (id.equals("minecraft") ? "   [POSITIVE CONTROL]" : ""));
            }
            Collection<?> all = (Collection<?>) flCls.getMethod("getAllMods").invoke(fl);
            o.println("  total mods registered = " + all.size());
        } catch (Throwable t) { o.println("  A FAILED: " + t); }

        // ---- D. CLASSPATH reachability (no game thread needed) --------------------------------------
        // This is the path LiquidBounce ACTUALLY uses for shaders, the logo and the browser theme:
        //   LiquidBounce.resource(p) = LiquidBounce.class.getResourceAsStream("/resources/liquidbounce/" + p)
        o.println();
        o.println("== D. CLASSPATH reads (the path LB actually uses for shaders/logo/theme)");
        String[] cpPaths = {
            "shaders/blur/ui_blur_h.frag",
            "shaders/blur/ui_blur_v.frag",
            "logo_banner.png",
            "themes/liquidbounce.zip",
            "lang/en_us.json",
        };
        for (String p : cpPaths) {
            String full = "/resources/liquidbounce/" + p;
            // via LiquidBounce's own class, i.e. byte-for-byte what LB does
            String viaLb = "n/a (LiquidBounce not loaded)";
            if (LB != null) {
                try (InputStream in = (InputStream) Class.class.getMethod("getResourceAsStream", String.class)
                        .invoke(LB, full)) {
                    viaLb = in == null ? "NULL" : ("OK " + count(in) + " bytes");
                } catch (Throwable t) { viaLb = "THREW " + t; }
            }
            String viaKnot;
            try (InputStream in = knot.getResourceAsStream(full.substring(1))) {
                viaKnot = in == null ? "NULL" : ("OK " + count(in) + " bytes");
            } catch (Throwable t) { viaKnot = "THREW " + t; }
            o.println("  " + full);
            o.println("      LiquidBounce.class.getResourceAsStream -> " + viaLb);
            o.println("      knot.getResourceAsStream               -> " + viaKnot);
        }
        // POSITIVE CONTROL: a resource that certainly exists on Knot.
        try (InputStream in = knot.getResourceAsStream("net/minecraft/client/Minecraft.class")) {
            o.println("  [POSITIVE CONTROL] knot.getResourceAsStream(net/minecraft/client/Minecraft.class) -> "
                      + (in == null ? "NULL  <-- PROBE IS BLIND, ignore the nulls above" : "OK " + count(in) + " bytes"));
        } catch (Throwable t) { o.println("  [POSITIVE CONTROL] THREW " + t); }
        // And LB's own class file through LB's own loader, proving the jar is reachable as a resource at all.
        if (LB != null) {
            try (InputStream in = (InputStream) Class.class.getMethod("getResourceAsStream", String.class)
                    .invoke(LB, "/net/ccbluex/liquidbounce/LiquidBounce.class")) {
                o.println("  [POSITIVE CONTROL] LiquidBounce.class.getResourceAsStream(own .class) -> "
                          + (in == null ? "NULL  <-- LB's jar is not reachable as a RESOURCE at all" : "OK " + count(in) + " bytes"));
            } catch (Throwable t) { o.println("  [POSITIVE CONTROL] THREW " + t); }
        }
        // Direct call into LB's own accessor, so the measurement is LB's code, not our reconstruction of it.
        if (LB != null) {
            for (String p : new String[]{"shaders/blur/ui_blur_h.frag", "themes/liquidbounce.zip"}) {
                try {
                    Method m = LB.getMethod("resource", String.class);
                    m.setAccessible(true);
                    Object f = LB.getField("INSTANCE").get(null);
                    Object in = m.invoke(f, p);
                    o.println("  LiquidBounce.resource(\"" + p + "\") -> OK " + count((InputStream) in) + " bytes");
                } catch (InvocationTargetException e) {
                    o.println("  LiquidBounce.resource(\"" + p + "\") THREW " + e.getCause());
                } catch (Throwable t) {
                    o.println("  LiquidBounce.resource(\"" + p + "\") probe error: " + t);
                }
            }
        }

        // ---- G. ClientInteropServer (no game thread needed) -----------------------------------------
        o.println();
        o.println("== G. ClientInteropServer (serves the integration browser its page)");
        try {
            Class<?> isCls = Class.forName("net.ccbluex.liquidbounce.integration.interop.ClientInteropServer", false, knot);
            Object inst2 = null;
            try { inst2 = isCls.getField("INSTANCE").get(null); } catch (Throwable ignored) {}
            o.println("  class loaded, INSTANCE = " + (inst2 == null ? "null" : "present"));
            Integer port = null;
            for (Method m : isCls.getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().toLowerCase().contains("port")
                        && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)) {
                    try { m.setAccessible(true); port = (Integer) m.invoke(inst2); o.println("  " + m.getName() + "() = " + port); }
                    catch (Throwable t) { o.println("  " + m.getName() + "() threw " + t); }
                }
            }
            for (Field f : isCls.getDeclaredFields()) {
                if (f.getType() == int.class && f.getName().toLowerCase().contains("port")) {
                    f.setAccessible(true);
                    Object v = Modifier.isStatic(f.getModifiers()) ? f.get(null) : (inst2 == null ? null : f.get(inst2));
                    o.println("  field " + f.getName() + " = " + v);
                    if (v instanceof Integer && port == null) port = (Integer) v;
                }
            }
            if (port != null && port > 0) {
                for (String path : new String[]{"/", "/resource/liquidbounce/"}) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
                        c.setConnectTimeout(3000); c.setReadTimeout(5000);
                        int rc = c.getResponseCode();
                        int len = 0;
                        try (InputStream in = (rc < 400 ? c.getInputStream() : c.getErrorStream())) { if (in != null) len = count(in); }
                        o.println("  GET " + path + " -> HTTP " + rc + ", " + len + " bytes");
                    } catch (Throwable t) { o.println("  GET " + path + " -> " + t); }
                }
            } else {
                o.println("  no port discovered; server probably never started");
            }
        } catch (ClassNotFoundException e) {
            o.println("  ClientInteropServer not loaded");
        } catch (Throwable t) { o.println("  G FAILED: " + t); }

        // ---- game-thread section --------------------------------------------------------------------
        final StringBuilder sb = new StringBuilder();
        final CountDownLatch latch = new CountDownLatch(1);
        mcCls.getMethod("execute", Runnable.class).invoke(mc, (Runnable) () -> {
            try { onGameThread(mc, knot, sb); }
            catch (Throwable t) {
                sb.append("GAME-THREAD PROBE FAILED: ").append(t).append('\n');
                StringWriter w = new StringWriter(); t.printStackTrace(new PrintWriter(w)); sb.append(w);
            } finally { latch.countDown(); }
        });
        if (!latch.await(30, TimeUnit.SECONDS))
            o.println("\nGAME THREAD DID NOT RUN THE TASK IN 30s (client hung or not ticking)");
        o.println();
        o.print(sb);
    }

    @SuppressWarnings("unchecked")
    static void onGameThread(Object mc, ClassLoader knot, StringBuilder s) throws Exception {
        s.append("== on the game thread (").append(Thread.currentThread().getName()).append(")\n");

        Object rm = mc.getClass().getMethod("getResourceManager").invoke(mc);
        s.append("resourceManager = ").append(rm.getClass().getName()).append('\n');

        // ---- B. namespaces + packs in the LIVE resource manager -------------------------------------
        s.append('\n');
        s.append("== B. LIVE resource manager\n");
        Set<String> ns = new TreeSet<>();
        try {
            ns.addAll((Set<String>) rm.getClass().getMethod("getNamespaces").invoke(rm));
        } catch (Throwable t) { s.append("  getNamespaces FAILED: ").append(t).append('\n'); }
        s.append("  namespace count = ").append(ns.size()).append('\n');
        s.append("  contains 'minecraft'    = ").append(ns.contains("minecraft")).append("   [POSITIVE CONTROL]\n");
        s.append("  contains 'liquidbounce' = ").append(ns.contains("liquidbounce")).append("   <== THE HYPOTHESIS\n");
        s.append("  all namespaces = ").append(ns).append('\n');

        try {
            List<Object> packs = ((java.util.stream.Stream<Object>) rm.getClass().getMethod("listPacks").invoke(rm)).toList();
            s.append("  packs in the live manager = ").append(packs.size()).append('\n');
            Class<?> packTypeCls = Class.forName("net.minecraft.server.packs.PackType", false, knot);
            Field cr = packTypeCls.getDeclaredField("CLIENT_RESOURCES"); cr.setAccessible(true);
            Object clientRes = cr.get(null);
            for (Object p : packs) {
                Object nsSet = "?";
                try { nsSet = p.getClass().getMethod("getNamespaces", packTypeCls).invoke(p, clientRes); }
                catch (Throwable ignored) {}
                String id = "?";
                try { id = String.valueOf(p.getClass().getMethod("packId").invoke(p)); } catch (Throwable ignored) {}
                s.append("    - ").append(id).append("  ns=").append(nsSet).append('\n');
            }
        } catch (Throwable t) { s.append("  listPacks FAILED: ").append(t).append('\n'); }

        // What does namespace 'liquidbounce' actually contain in the live manager?
        try {
            Method listRes = rm.getClass().getMethod("listResources", String.class, java.util.function.Predicate.class);
            for (String root : new String[]{"shaders", "textures", "sounds", "lang", "shader"}) {
                Map<Object, Object> found = (Map<Object, Object>) listRes.invoke(rm, root,
                    (java.util.function.Predicate<Object>) id -> {
                        try { return "liquidbounce".equals(id.getClass().getMethod("getNamespace").invoke(id)); }
                        catch (Throwable t) { return false; }
                    });
                s.append("  listResources(\"").append(root).append("\", ns=liquidbounce) -> ")
                 .append(found.size()).append(" entries");
                if (!found.isEmpty()) {
                    List<String> keys = new ArrayList<>();
                    for (Object k : found.keySet()) keys.add(String.valueOf(k));
                    Collections.sort(keys);
                    s.append(' ').append(keys.subList(0, Math.min(12, keys.size())));
                }
                s.append('\n');
            }
            // POSITIVE CONTROL: the same call for vanilla must be non-empty.
            Map<Object, Object> ctl = (Map<Object, Object>) listRes.invoke(rm, "shaders",
                (java.util.function.Predicate<Object>) id -> {
                    try { return "minecraft".equals(id.getClass().getMethod("getNamespace").invoke(id)); }
                    catch (Throwable t) { return false; }
                });
            s.append("  [POSITIVE CONTROL] listResources(\"shaders\", ns=minecraft) -> ").append(ctl.size())
             .append(ctl.isEmpty() ? " entries  <-- PROBE IS BLIND\n" : " entries\n");
        } catch (Throwable t) { s.append("  listResources FAILED: ").append(t).append('\n'); }

        // ---- C. the exact ResourceLocations from the reported errors --------------------------------
        s.append('\n');
        s.append("== C. do the reported ResourceLocations resolve from the LIVE resource manager?\n");
        try {
            Class<?> idCls;
            try { idCls = Class.forName("net.minecraft.resources.Identifier", false, knot); }
            catch (ClassNotFoundException e) { idCls = Class.forName("net.minecraft.resources.ResourceLocation", false, knot); }
            Method mk = idCls.getMethod("fromNamespaceAndPath", String.class, String.class);
            Method getRes = rm.getClass().getMethod("getResource", idCls);
            String[][] probes = {
                {"liquidbounce", "shader/fragment/blur_h"},
                {"liquidbounce", "shaders/blur/ui_blur_h.frag"},
                {"liquidbounce", "shaders/fragment/blur_h.fsh"},
                {"liquidbounce", "textures/logo.png"},
                {"liquidbounce", "logo"},
                {"liquidbounce", "sounds.json"},                 // the ONE thing that IS under assets/
                {"minecraft",    "shaders/core/position.vsh"},   // positive control candidates
                {"minecraft",    "sounds.json"},
            };
            for (String[] p : probes) {
                Object id = mk.invoke(null, p[0], p[1]);
                Object opt = getRes.invoke(rm, id);
                boolean present = (boolean) opt.getClass().getMethod("isPresent").invoke(opt);
                s.append("  ").append(p[0]).append(':').append(p[1]).append(" -> ").append(present ? "PRESENT" : "absent")
                 .append(p[0].equals("minecraft") ? "   [POSITIVE CONTROL]" : "").append('\n');
            }
        } catch (Throwable t) { s.append("  C FAILED: ").append(t).append('\n'); }

        // ---- E. LB's own in-memory ShaderSource -----------------------------------------------------
        s.append('\n');
        s.append("== E. LB's own ShaderSource (ClientShaders) — where the shaders REALLY come from\n");
        for (String cn : new String[]{"net.ccbluex.liquidbounce.render.ClientShaders$Fragment",
                                      "net.ccbluex.liquidbounce.render.ClientShaders$Vertex"}) {
            try {
                Class<?> c = Class.forName(cn, false, knot);
                Object inst = c.getField("INSTANCE").get(null);
                Field f = null;
                for (Field ff : c.getSuperclass().getDeclaredFields())
                    if (Map.class.isAssignableFrom(ff.getType())) f = ff;
                if (f == null) { s.append("  ").append(cn).append(": no map field found\n"); continue; }
                f.setAccessible(true);
                Map<Object, Object> m = (Map<Object, Object>) f.get(inst);
                s.append("  ").append(cn).append(": INSTANCE ok, ").append(m.size()).append(" shader sources\n");
                List<String> keys = new ArrayList<>();
                for (Object k : m.keySet()) keys.add(String.valueOf(k));
                Collections.sort(keys);
                for (String k : keys) s.append("      ").append(k).append('\n');
            } catch (Throwable t) { s.append("  ").append(cn).append(" -> ").append(t).append('\n'); }
        }
        // Are LB's pipelines actually compiled? (ClientRenderPipelines.precompiled flag)
        try {
            Class<?> c = Class.forName("net.ccbluex.liquidbounce.render.ClientRenderPipelines", false, knot);
            Object inst = c.getField("INSTANCE").get(null);
            for (Field f : c.getDeclaredFields())
                if (f.getType() == boolean.class) {
                    f.setAccessible(true);
                    s.append("  ClientRenderPipelines.").append(f.getName()).append(" = ")
                     .append(Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(inst)).append('\n');
                }
        } catch (Throwable t) { s.append("  ClientRenderPipelines -> ").append(t).append('\n'); }

        // ---- F. TextureManager: is liquidbounce:logo registered? ------------------------------------
        s.append('\n');
        s.append("== F. TextureManager registration of liquidbounce:logo\n");
        try {
            Object tm = mc.getClass().getMethod("getTextureManager").invoke(mc);
            Field byPath = null;
            for (Field f : tm.getClass().getDeclaredFields())
                if (Map.class.isAssignableFrom(f.getType())) { byPath = f; break; }
            if (byPath != null) {
                byPath.setAccessible(true);
                Map<Object, Object> m = (Map<Object, Object>) byPath.get(tm);
                s.append("  TextureManager.").append(byPath.getName()).append(" size=").append(m.size()).append('\n');
                int lb = 0;
                for (Object k : m.keySet()) {
                    String ks = String.valueOf(k);
                    if (ks.startsWith("liquidbounce")) { s.append("      ").append(ks).append(" -> ")
                        .append(m.get(k).getClass().getName()).append('\n'); lb++; }
                }
                s.append("  liquidbounce-namespaced textures registered = ").append(lb).append('\n');
            } else s.append("  no map field on TextureManager\n");
        } catch (Throwable t) { s.append("  F FAILED: ").append(t).append('\n'); }
    }

    static int count(InputStream in) throws IOException {
        if (in == null) return -1;
        int n = 0, r; byte[] b = new byte[8192];
        while ((r = in.read(b)) > 0) n += r;
        return n;
    }
}
