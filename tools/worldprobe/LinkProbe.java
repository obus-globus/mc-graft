import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

/**
 * FORCE-LINK every sidecar the agent defined, and report what breaks.
 *
 * Why this exists
 * ---------------
 * A sidecar is DEFINED at attach but VERIFIED and LINKED only on FIRST USE, and "first use" for most sidecars is a
 * specific piece of gameplay: AbstractContainerScreen's needs a container screen on screen, MouseHandler's needs a
 * click, KeyboardInput's needs a world. So a `VerifyError` in a sidecar is invisible to any test that stops short of
 * that exact interaction — which is how one shipped:
 *
 *   VerifyError: Bad type on operand stack
 *     AbstractContainerScreen$$Sidecar$liquidbounce.h$handler$bgo000$hookDrawSlot @224: invokestatic
 *     Type 'java/lang/Object' is not assignable to 'net/minecraft/client/MouseHandler$LastClick'
 *
 * The injection log was clean, the world join was clean, and the client died the instant the user pressed E.
 *
 * Class.forName(name, true, loader) initializes, and initialization requires linking, which requires VERIFICATION —
 * so one pass over every defined sidecar exercises the entire deferred-verification surface at once, from the title
 * screen, with no GUI driving and no world. That is a strictly larger net than any single in-game interaction.
 *
 * What it deliberately does NOT claim
 * -----------------------------------
 * This finds VERIFY-time faults. It cannot find IllegalAccessError on a member reference: that is raised at first
 * EXECUTION of the offending instruction, and this never executes a handler body. The two failure modes need
 * different tests and this one is not a substitute for playing the game.
 *
 * Failures are reported in three buckets because they mean different things:
 *   VERIFY  - VerifyError/ClassFormatError/LinkageError from the sidecar itself: a converter bug, always fatal.
 *   INIT    - ExceptionInInitializerError: the sidecar's <clinit> ran and threw (e.g. a getDeclaredField for a member
 *             that is not there). Also ours, but a different bug.
 *   MISSING - the class could not be found by name from the knot loader at all.
 */
public class LinkProbe {
    public static void agentmain(String args, Instrumentation inst) {
        List<String> out = new ArrayList<>();
        int ok = 0;
        List<String> verify = new ArrayList<>(), init = new ArrayList<>(), missing = new ArrayList<>();
        try {
            ClassLoader knot = null;
            for (Class<?> c : inst.getAllLoadedClasses())
                if (c.getName().equals("net.minecraft.client.Minecraft")) { knot = c.getClassLoader(); break; }
            out.add("knot loader = " + knot);

            List<String> names = new ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses())
                // Skip the JVM's own lambda proxies spun inside a sidecar ("...$$Sidecar$x$$Lambda/0x..."): they are
                // hidden classes with no findable binary name, so Class.forName can only ever report them MISSING.
                if (c.getName().contains("$$Sidecar") && !c.getName().contains("$$Lambda")) names.add(c.getName());
            Collections.sort(names);
            out.add("sidecar classes defined = " + names.size());

            for (String n : names) {
                try { Class.forName(n, true, knot); ok++; }
                catch (LinkageError e) {
                    // ExceptionInInitializerError IS a LinkageError, so it has to be discriminated by type, not order.
                    if (e instanceof ExceptionInInitializerError ie) init.add(n + " -> " + ie.getCause());
                    else verify.add(n + " -> " + e);
                }
                catch (ClassNotFoundException e) { missing.add(n); }
                catch (Throwable t) { init.add(n + " -> " + t); }
            }
        } catch (Throwable t) {
            out.add("PROBE_FAILED " + t);
        }
        out.add("LINKED_OK = " + ok);
        out.add("VERIFY_FAILURES = " + verify.size());
        for (String s : verify) out.add("  VERIFY " + s);
        out.add("INIT_FAILURES = " + init.size());
        for (String s : init) out.add("  INIT " + s);
        out.add("MISSING = " + missing.size());
        for (String s : missing) out.add("  MISSING " + s);
        String text = String.join("\n", out) + "\n";
        try { Files.write(Path.of(args), text.getBytes()); } catch (IOException ignored) {}
        System.out.println("[link-probe] linked=" + ok + " verifyFailures=" + verify.size()
            + " initFailures=" + init.size() + " missing=" + missing.size());
    }
}
