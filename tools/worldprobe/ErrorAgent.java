import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/**
 * POSITIVE CONTROL for tools/world-join-test.sh.
 *
 * Deliberately raises, on the game thread, exactly the shape of failure the world-join test claims to
 * be watching for (a linkage error from first-use linking of a sidecar). If the test's log scanner
 * reports "clean" after this has run, the scanner is blind and every "no errors in the world" verdict
 * it ever produced is worthless.
 *
 * This KILLS the client. It is the last thing a run does.
 *
 * Its own Agent-Class, so mc-graft's re-injection guard does not apply.
 */
public class ErrorAgent {
    public static void agentmain(String args, Instrumentation inst) {
        try {
            Class<?> mcCls = null;
            for (Class<?> c : inst.getAllLoadedClasses())
                if (c.getName().equals("net.minecraft.client.Minecraft")) { mcCls = c; break; }
            if (mcCls == null) { System.out.println("ERRORAGENT: Minecraft not loaded"); return; }
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            Method exec = mcCls.getMethod("execute", Runnable.class);
            exec.invoke(mc, (Runnable) () -> {
                throw new NoClassDefFoundError(
                    "MCGRAFT_P5_POSITIVE_CONTROL/net/minecraft/client/Camera$$Sidecar$deliberate");
            });
            System.out.println("ERRORAGENT: queued");
        } catch (Throwable t) {
            System.out.println("ERRORAGENT failed: " + t);
        }
    }
}
