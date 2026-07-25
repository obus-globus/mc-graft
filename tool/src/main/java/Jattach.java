import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attach fallback via the bundled {@code jattach} native binary — lets a plain JRE (no {@code jdk.attach} module)
 * inject the agent. The binary is extracted from the tool jar to a temp dir per platform, then invoked.
 */
final class Jattach {
    private Jattach() {}

    static void loadAgent(String pid, java.io.File agentJar, String agentArgs, Consumer<String> log) throws Exception {
        java.io.File bin = extractBinary();
        log.accept("Attaching to PID " + pid + " via jattach (" + bin.getName() + ") ...");
        // jattach <pid> load instrument false "<agentjar>=<args>"  — "instrument" is the JVM's built-in JPLIS agent,
        // "false" = the option string that follows is not an absolute library path, and the option is jar=args.
        String option = agentJar.getAbsolutePath() + (agentArgs == null || agentArgs.isEmpty() ? "" : "=" + agentArgs);
        // jattach prints the agent's Agent_OnAttach result as "return code: N" (0 = success; non-zero = agentmain failed).
        int[] agentReturn = {Integer.MIN_VALUE};
        Pattern rc = Pattern.compile("return code[:=]\\s*(-?\\d+)");
        int code = run(List.of(bin.getAbsolutePath(), pid.trim(), "load", "instrument", "false", option), line -> {
            log.accept(line);
            Matcher m = rc.matcher(line.toLowerCase());
            if (m.find()) agentReturn[0] = Integer.parseInt(m.group(1));
        });
        if (code != 0)
            throw new IllegalStateException("jattach exited with code " + code + " (could not attach; see the messages above)");
        if (agentReturn[0] != Integer.MIN_VALUE && agentReturn[0] != 0)
            throw new IllegalStateException("the agent's Agent_OnAttach returned " + agentReturn[0]
                    + " (agentmain failed inside the client — check the target's log)");
        if (agentReturn[0] == Integer.MIN_VALUE)   // could not parse a return code -> inconclusive, not a guaranteed success
            log.accept("Warning: jattach did not report an agent return code — the agent MAY have failed; watch the injection log for confirmation.");
        else
            log.accept("Agent load delivered via jattach (return code 0).");
    }

    /** Target JVM system properties via `jattach <pid> jcmd VM.system_properties` (a lightweight jcmd, loads no agent). */
    static Properties systemProperties(String pid) throws Exception {
        java.io.File bin = extractBinary();
        StringBuilder out = new StringBuilder();
        run(List.of(bin.getAbsolutePath(), pid.trim(), "jcmd", "VM.system_properties"), line -> out.append(line).append('\n'));
        Properties p = new Properties();
        try { p.load(new StringReader(out.toString())); } catch (Throwable ignored) {}
        return p;
    }

    private static int run(List<String> command, Consumer<String> log) throws Exception {
        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
            return proc.waitFor();
        } finally {
            if (proc.isAlive()) proc.destroyForcibly();   // don't orphan the child if readLine()/waitFor() throws (e.g. interrupt)
        }
    }

    // SHA-256 of the vendored jattach 2.2 binaries (apangin/jattach, Apache-2.0) — see resources/jattach/PROVENANCE.md.
    // The extracted copy is verified against these before it is made executable, so a tampered jar resource (or a swap
    // of the extracted file) is refused rather than run with the target JVM's privileges.
    private static final java.util.Map<String,String> SHA256 = java.util.Map.of(
        "jattach-linux-x64",       "a08cb795a1e8d11ea6c2dd6adf8c9edead9a7c3bbca07681dad79cc3eaec0ef4",
        "jattach-linux-arm64",     "a2b015914a1e7db4387884d68e5970e2473aea26e3309153644ce2d66a046293",
        "jattach-macos",           "e0a397ab291954d4aa1b980a3ffa78a6792c59bb1bd3b7ed43a55966801bfdd9",
        "jattach-windows-x64.exe", "0a2358700b1294fdd7f3db23b28e4d9e5577025d694c3abf851bed9d73c9c1b1");

    private static void verifySha256(Path file, String expected, String resource) throws IOException {
        if (expected == null) return;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte x : digest) hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            if (!hex.toString().equals(expected))
                throw new IOException("bundled jattach binary " + resource + " failed SHA-256 verification (got "
                        + hex + ", expected " + expected + ") — refusing to execute a tampered binary");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable to verify the jattach binary", e);
        }
    }

    private static volatile java.io.File cachedBinary;

    private static synchronized java.io.File extractBinary() throws IOException {
        if (cachedBinary != null && cachedBinary.canExecute()) return cachedBinary;   // extract once per run
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String resource, outName = "jattach";
        // jattach-macos is a universal x86_64+arm64 binary, so the macOS branch is deliberately arch-blind; Windows-on-arm64
        // runs the x64 exe under emulation. Linux ships one ELF per arch and gets NO catch-all: handing the x64 ELF to an
        // arm/riscv64/ppc64le/s390x JVM only yields an ENOEXEC that the exec probe below would misreport as a noexec mount.
        if (os.contains("win")) { resource = "jattach-windows-x64.exe"; outName = "jattach.exe"; }
        else if (os.contains("mac") || os.contains("darwin")) resource = "jattach-macos";
        else if (arch.equals("aarch64") || arch.equals("arm64")) resource = "jattach-linux-arm64";
        else if (arch.equals("amd64") || arch.equals("x86_64")) resource = "jattach-linux-x64";
        else throw new IOException("no bundled jattach for os.arch=" + arch + " (os.name=" + os + ") — only linux x64/arm64,"
            + " macOS and Windows x64 binaries are shipped. Use a JRE/JDK that ships the jdk.attach module so jattach isn't needed.");
        // Prefer java.io.tmpdir; if it is mounted noexec (or an ACL blocks exec), the exec PROBE below fails even though
        // the exec bit was set, so fall back to a private dir under user.home. File.canExecute() only reads the bit and
        // misses a noexec mount, so we actually run the binary once (no args -> prints usage, exits) to be sure.
        Path out = stage(Files.createTempDirectory("mcgraft-jattach-"), resource, outName);
        IOException tmpFailure = execFailure(out);
        if (tmpFailure != null) {
            Path home = Path.of(System.getProperty("user.home", "."));
            Path alt = stage(Files.createTempDirectory(home, ".lb-jattach-"), resource, outName);
            IOException homeFailure = execFailure(alt);
            if (homeFailure != null)
                throw new IOException("the extracted jattach binary (" + resource + ") could not be executed — java.io.tmpdir ("
                    + System.getProperty("java.io.tmpdir") + "): " + tmpFailure.getMessage() + "; " + home + " fallback: "
                    + homeFailure.getMessage() + ". " + (isFormatError(tmpFailure) || isFormatError(homeFailure)
                        ? "An exec-format error means the bundled binary does not match this machine (os.arch=" + arch
                          + ", os.name=" + os + ") — a different temp directory will not help; a matching build is needed."
                        : "Both paths reject execution (e.g. noexec mounts or a restrictive ACL). Use a JRE/JDK that ships "
                          + "the jdk.attach module so jattach isn't needed."));
            out = alt;
        }
        cachedBinary = out.toFile();
        return cachedBinary;
    }

    /** Extract + verify the vendored binary into {@code dir} (random name, owner-only 0700 on POSIX / per-user %TEMP%
     *  on Windows; SHA-256 is the real integrity guarantee). Registered for deletion on JVM exit. Throws on a
     *  copy/verify failure (a tamper is fatal, not a fall-back-worthy noexec condition). */
    private static Path stage(Path dir, String resource, String outName) throws IOException {
        dir.toFile().deleteOnExit();
        Path out = dir.resolve(outName);
        try (InputStream in = Jattach.class.getResourceAsStream("/jattach/" + resource)) {
            if (in == null) throw new FileNotFoundException("bundled jattach binary /jattach/" + resource + " is missing from the tool jar");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        verifySha256(out, SHA256.get(resource), resource);   // fail closed on a tampered/unknown binary before making it executable
        out.toFile().deleteOnExit();
        if (!out.toFile().setExecutable(true, true))          // don't silently ignore: a false here means the probe below will fail -> fallback
            System.err.println("[jattach] could not set the exec bit on " + out + " (restrictive FS/ACL); verifying by probing");
        return out;
    }

    /** Actually run the binary (no args -> usage + fast exit) to confirm the OS permits execution — catches a noexec
     *  mount / ACL that {@link java.io.File#canExecute()} (bit-only) misses. Returns null when it ran, else the failure
     *  (kept, not collapsed to a boolean, so the caller can tell EACCES from ENOEXEC — different causes, different fixes). */
    private static IOException execFailure(Path bin) {
        try {
            Process p = new ProcessBuilder(bin.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();   // drain the usage output so the child doesn't block
            p.waitFor();
            return null;
        } catch (IOException e) {
            return e;                             // EACCES on a noexec mount / unset exec bit; ENOEXEC on a wrong-arch binary
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;                          // it did start; interruption isn't an exec failure
        }
    }

    /** ENOEXEC — Linux "error=8, Exec format error", Windows ERROR_BAD_EXE_FORMAT (193). The JDK only exposes these as
     *  message text on the IOException, so match on it; a false negative just falls back to the generic noexec wording. */
    private static boolean isFormatError(IOException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("Exec format error") || m.contains("error=8,")
            || m.contains("error=193,") || m.contains("not a valid Win32 application");
    }
}
