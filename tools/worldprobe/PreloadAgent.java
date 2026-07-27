import java.lang.instrument.Instrumentation;

/** Force-load named classes into the running client BEFORE mc-graft attaches, so that "was this target already loaded
 *  when we injected?" becomes a controlled variable instead of an accident of how far the user had played.
 *
 *  <p>Why this exists: {@code FullInjectAgent} publishes a mixin target that is NOT yet loaded WHOLE (added members
 *  kept on the class) and one that IS loaded through the sidecar/$State relocation. Which branch a given target takes
 *  is therefore decided by the client's history, and the two branches are not interchangeable across an inheritance
 *  edge. Reproducing that needs the ability to flip one target's load state and change nothing else.
 *
 *  <p>Its own {@code Agent-Class}, so mc-graft's re-injection guard does not apply. Read-only with respect to the
 *  engine: it defines no classes and installs no transformer, it only runs {@code Class.forName(..., initialize=true)}
 *  on the game's own class loader — exactly what gameplay would have done.
 *
 *  <p>Agent arg: comma-separated dotted class names. Writes a one-line-per-class report to
 *  {@code $mcgraft.preload.out} (or stdout).
 */
public final class PreloadAgent {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        StringBuilder out = new StringBuilder();
        ClassLoader game = null;
        for (Class<?> c : inst.getAllLoadedClasses())
            if (c.getName().equals("net.minecraft.client.Minecraft")) { game = c.getClassLoader(); break; }
        out.append("game loader = ").append(game).append('\n');
        for (String n : (args == null ? "" : args).split(",")) {
            n = n.trim(); if (n.isEmpty()) continue;
            boolean already = false;
            for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(n)) { already = true; break; }
            try {
                Class<?> c = Class.forName(n, true, game);
                out.append(already ? "ALREADY-LOADED " : "FORCE-LOADED   ").append(n)
                   .append("  loader=").append(c.getClassLoader()).append('\n');
            } catch (Throwable t) {
                out.append("FAILED         ").append(n).append("  ").append(t).append('\n');
            }
        }
        String dest = System.getProperty("mcgraft.preload.out");
        if (dest != null) java.nio.file.Files.writeString(java.nio.file.Path.of(dest), out.toString());
        System.out.print("[preload-agent] " + out);
    }
}
