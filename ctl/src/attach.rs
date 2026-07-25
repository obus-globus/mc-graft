//! HotSpot dynamic-attach.
//!
//! On Unix (Linux/macOS) this is a pure-Rust implementation of the AF_UNIX-socket attach protocol: the JVM exposes a
//! control channel as a Unix socket it creates on demand. If the socket does not exist yet, we create the well-known
//! trigger file the JVM's SIGQUIT handler looks for and send it SIGQUIT; the JVM then starts its AttachListener and
//! creates the socket. We then speak the text protocol (version byte, command, up to three NUL-terminated args) and
//! load the agent via the JVM's built-in `instrument` agent: `load instrument false "<jar>=<options>"`.
//!
//! On Windows the mechanism is entirely different (a named pipe + `CreateRemoteThread` into `jvm.dll`), so `load_agent`
//! there is a not-yet-implemented stub — `pack`/patch work everywhere; inject is Unix-only for now.

pub struct AttachResult {
    /// JVM command-completion status (first response line). 0 = the command was accepted.
    pub jvm_status: i32,
    /// The `instrument` agent's `Agent_OnAttach` outcome. 0 = agentmain OK; anything else = it failed and `output`
    /// carries the JVM's explanation. Always determined — see [`classify_agent_rc`].
    pub agent_rc: i32,
    /// The full raw response text (thread-dump-free; just the attach channel's reply).
    pub output: String,
}

/// Parse a trailing signed integer out of a line that mentions "return code" (case-insensitive). Shared by both paths.
fn parse_return_code(line: &str) -> Option<i32> {
    let low = line.to_ascii_lowercase();
    if !low.contains("return code") {
        return None;
    }
    let tail = &low[low.find("return code").unwrap()..];
    tail.split(|c: char| !(c.is_ascii_digit() || c == '-'))
        .rfind(|s| !s.is_empty() && *s != "-")
        .and_then(|s| s.parse::<i32>().ok())
}

/// Classify the `instrument` agent's outcome from the JVM's reply *body* (everything after the status line).
///
/// HotSpot reports a `load` result in three different shapes depending on JDK, and only one of them mentions a return
/// code. Mirrors jattach's `posix/jattach_hotspot.c`, which is the reference implementation vendored under
/// `thirdparty/`:
///
/// - `return code: N` — JDK 9..17
/// - a bare number    — JDK 8
/// - anything else    — JDK 21+ (i.e. what modern Minecraft runs on): `load` *always* reports 0 at the command level
///   and the body itself IS the error message. Treating this shape as "no return code reported" is why a failed
///   agentmain used to be announced as a successful injection.
///
/// An empty body means the command was accepted with nothing to report.
fn classify_agent_rc(body: &str) -> i32 {
    let Some(first) = body.lines().map(str::trim).find(|l| !l.is_empty()) else {
        return 0;
    };
    if let Some(rc) = parse_return_code(first) {
        return rc;
    }
    if let Ok(rc) = first.parse::<i32>() {
        return rc;
    }
    -1
}

#[cfg(unix)]
pub use unix_impl::load_agent;

#[cfg(unix)]
mod unix_impl {
    use super::AttachResult;
    use std::fs;
    use std::io::{Read, Write};
    use std::os::unix::net::UnixStream;
    use std::path::{Path, PathBuf};
    use std::process::Command;
    use std::thread::sleep;
    use std::time::{Duration, Instant};

    /// The tmp directory the target JVM writes its attach socket into. HotSpot uses the OS temp dir (`/tmp`), which —
    /// for a process in another mount namespace (container) — we reach through `/proc/<pid>/root/tmp`.
    fn attach_dir(pid: i32) -> PathBuf {
        let ns = PathBuf::from(format!("/proc/{}/root/tmp", pid));
        if ns.is_dir() { ns } else { PathBuf::from("/tmp") }
    }

    /// Refuse to signal anything that isn't demonstrably a live JVM.
    ///
    /// SIGQUIT's default disposition is terminate-and-core-dump, so a mistyped pid doesn't fail harmlessly — it kills
    /// an unrelated process. `kill(2)` also reads pid 0 as "every process in my process group" (which includes us and
    /// our shell) and negative pids as a process group, and `--` lets those reach us from the CLI. jattach checks
    /// existence + ownership before signalling for exactly this reason; mirror that.
    fn verify_is_jvm(pid: i32) -> Result<(), String> {
        if pid <= 0 {
            return Err(format!("refusing to attach to pid {pid}: pid must be positive (0 and negatives signal groups)"));
        }
        let proc = PathBuf::from(format!("/proc/{pid}"));
        if !proc.is_dir() {
            return Err(format!("no such process: {pid}"));
        }
        // `comm` is the truncated executable name; a JVM launched via a wrapper may not be "java", so fall back to
        // looking for libjvm in the mapped libraries, which no non-JVM process has.
        let comm = fs::read_to_string(proc.join("comm")).unwrap_or_default();
        if comm.trim() == "java" {
            return Ok(());
        }
        let maps = fs::read_to_string(proc.join("maps")).unwrap_or_default();
        if maps.contains("libjvm.so") || maps.contains("libjvm.dylib") {
            return Ok(());
        }
        Err(format!(
            "pid {pid} does not look like a JVM (comm={:?}, no libjvm mapped) — refusing to send SIGQUIT, which would \
             terminate it",
            comm.trim()
        ))
    }

    /// The attach socket must be a real socket owned by us before we speak to it.
    fn validate_socket(sock: &Path) -> Result<(), String> {
        use std::os::unix::fs::{FileTypeExt, MetadataExt};
        // symlink_metadata, not metadata: a symlink planted here must be rejected, not followed.
        let md = fs::symlink_metadata(sock).map_err(|e| format!("stat {}: {e}", sock.display()))?;
        if !md.file_type().is_socket() {
            return Err(format!(
                "{} exists but is not a socket — refusing to use it (a file planted at this well-known path would \
                 receive our attach request and could fake a success reply)",
                sock.display()
            ));
        }
        // Our own euid without libc (this crate forbids unsafe): /proc/self is owned by it. Where /proc is absent the
        // check degrades to a no-op, which is why the socket-type check above is the load-bearing one.
        let me = fs::metadata("/proc/self").map(|m| m.uid()).unwrap_or(md.uid());
        if md.uid() != me {
            return Err(format!("{} is owned by uid {}, not {me} — refusing to attach", sock.display(), md.uid()));
        }
        Ok(())
    }

    fn send_sigquit(pid: i32) -> Result<(), String> {
        verify_is_jvm(pid)?;
        let st = Command::new("kill")
            .args(["-s", "QUIT", &pid.to_string()])
            .status()
            .map_err(|e| format!("could not run kill(1) to signal the JVM: {e}"))?;
        if !st.success() {
            return Err(format!("kill -QUIT {pid} failed (is the pid alive and owned by you?)"));
        }
        Ok(())
    }

    /// Ensure the JVM's attach socket exists; return its path. Creates+SIGQUITs if needed.
    fn ensure_socket(pid: i32) -> Result<PathBuf, String> {
        verify_is_jvm(pid)?;
        let dir = attach_dir(pid);
        let sock = dir.join(format!(".java_pid{pid}"));
        // Require an actual socket owned by us. A regular file or a symlink planted at this well-known path by another
        // local user would otherwise receive our attach request (which carries the agent jar path and game dir) and
        // could reply with a fabricated success. jattach does both checks; so do we.
        if sock.exists() {
            return validate_socket(&sock).map(|()| sock);
        }
        // Create the attach trigger. The JVM's SIGQUIT handler checks its cwd first, then /tmp; create it in that order.
        // Use create_new so a concurrent attach's trigger is never truncated: if the file already exists we reuse it and
        // leave cleanup to whoever created it (owned = false).
        let candidates = [
            PathBuf::from(format!("/proc/{pid}/cwd/.attach_pid{pid}")),
            dir.join(format!(".attach_pid{pid}")),
        ];
        let (trigger, owned) = candidates
            .iter()
            .find_map(|c| create_trigger(c))
            .ok_or_else(|| "could not create the attach trigger file (permission denied?)".to_string())?;

        let r = (|| {
            send_sigquit(pid)?;
            let deadline = Instant::now() + Duration::from_secs(6);
            while Instant::now() < deadline {
                if sock.exists() {
                    return Ok(sock.clone());
                }
                sleep(Duration::from_millis(120));
            }
            Err(format!(
                "JVM {pid} did not create its attach socket within 6s — is it a HotSpot JVM with attach enabled \
                 (not launched with -XX:+DisableAttachMechanism)?"
            ))
        })();
        if owned {
            let _ = fs::remove_file(&trigger);
        }
        r
    }

    /// Create an attach trigger at `path`, returning `(path, owned)`. `owned` is true when we created the file, false
    /// when it already existed (a concurrent attach — reuse it). `None` when not writable. Never truncates.
    fn create_trigger(path: &Path) -> Option<(PathBuf, bool)> {
        match fs::OpenOptions::new().write(true).create_new(true).open(path) {
            Ok(_) => Some((path.to_path_buf(), true)),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => Some((path.to_path_buf(), false)),
            Err(_) => None,
        }
    }

    /// Load `agent_jar` into the JVM `pid`, passing `agent_args` (the raw agent option string — already joined).
    pub fn load_agent(pid: i32, agent_jar: &Path, agent_args: &str) -> Result<AttachResult, String> {
        let jar = agent_jar
            .canonicalize()
            .map_err(|e| format!("agent jar {}: {e}", agent_jar.display()))?;
        let sock = ensure_socket(pid)?;
        let mut stream = match UnixStream::connect(&sock) {
            Ok(s) => s,
            // A JVM killed with SIGKILL leaves its socket file behind; when the pid is recycled, connect() gets
            // ECONNREFUSED forever because ensure_socket short-circuits on mere existence. Drop the corpse and let the
            // trigger+SIGQUIT path recreate it, rather than leaving attach permanently broken with no hint.
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionRefused => {
                let _ = fs::remove_file(&sock);
                let sock = ensure_socket(pid)?;
                UnixStream::connect(&sock).map_err(|e| format!("connect {}: {e}", sock.display()))?
            }
            Err(e) => return Err(format!("connect {}: {e}", sock.display())),
        };
        stream.set_read_timeout(Some(Duration::from_secs(120))).ok();

        // The instrument agent's option string is "<absolute jar path>[=<args>]".
        let option = if agent_args.is_empty() {
            jar.display().to_string()
        } else {
            format!("{}={}", jar.display(), agent_args)
        };
        // Request framing: version, then command + up to 3 args, each NUL-terminated.
        let mut req: Vec<u8> = Vec::new();
        req.extend_from_slice(b"1\0");
        for part in ["load", "instrument", "false", option.as_str()] {
            req.extend_from_slice(part.as_bytes());
            req.push(0);
        }
        stream.write_all(&req).map_err(|e| format!("write attach request: {e}"))?;

        let mut buf = Vec::new();
        stream.read_to_end(&mut buf).map_err(|e| format!("read attach response: {e}"))?;
        let output = String::from_utf8_lossy(&buf).into_owned();

        // Line 1 is the command status; everything after it is the body the agent outcome is derived from.
        let mut lines = output.lines();
        let jvm_status = lines.next().unwrap_or("").trim().parse::<i32>().unwrap_or(-1);
        let agent_rc = super::classify_agent_rc(&lines.collect::<Vec<_>>().join("\n"));

        Ok(AttachResult { jvm_status, agent_rc, output })
    }
}

/// Windows attach via `jattach` (a mature native tool that performs the named-pipe + `CreateRemoteThread` dance that
/// differs from the Unix socket protocol). We shell out to it rather than hand-rolling the unsafe FFI. `jattach` is
/// found via `MCGRAFT_JATTACH` or `jattach.exe` on `PATH`.
///
/// NOTE: this path is implemented and compiles/lints on Windows, but is not yet verified end-to-end against a running
/// Windows Minecraft — treat it as experimental until confirmed on a real Windows host.
#[cfg(windows)]
pub fn load_agent(pid: i32, agent_jar: &std::path::Path, agent_args: &str) -> Result<AttachResult, String> {
    let jar = agent_jar
        .canonicalize()
        .map_err(|e| format!("agent jar {}: {e}", agent_jar.display()))?;
    // `canonicalize` on Windows yields an extended-length path (`\\?\C:\...`). That string is handed to the JPLIS
    // `instrument` agent, which ends at `new JarFile(path)`; JDK 9+ resolves that through `java.nio`, which rejects
    // '?' in a path outright (`InvalidPathException`). Keep canonicalize for its existence check, drop the prefix.
    let jar = {
        let s = jar.display().to_string();
        s.strip_prefix(r"\\?\").map(str::to_string).unwrap_or(s)
    };
    let bin = jattach_bin()?;
    let option = if agent_args.is_empty() { jar.clone() } else { format!("{jar}={agent_args}") };
    // jattach <pid> load instrument false "<jar>[=<args>]" — the JVM's response is relayed to stdout.
    let out = std::process::Command::new(&bin)
        .arg(pid.to_string())
        .arg("load")
        .arg("instrument")
        .arg("false")
        .arg(&option)
        .output()
        .map_err(|e| format!("run jattach: {e}"))?;
    let stdout = String::from_utf8_lossy(&out.stdout).into_owned();
    let combined = format!("{stdout}\n{}", String::from_utf8_lossy(&out.stderr));
    if !out.status.success() {
        return Err(format!("jattach failed (exit {:?}): {}", out.status.code(), combined.trim()));
    }
    // jattach prints `JVM response code = ` with NO trailing newline and then dumps the JVM's raw reply, so the status
    // digit is glued onto that label line and the reply body follows on subsequent lines. Anchor on the marker rather
    // than hunting for "the first numeric line", which would otherwise pick up a number out of the body. Unknown
    // shape => -1 (failure), never 0: defaulting to success is how a failed load gets reported as a good one.
    const MARKER: &str = "JVM response code = ";
    let (jvm_status, body) = match stdout.find(MARKER) {
        Some(i) => {
            let mut rest = stdout[i + MARKER.len()..].lines();
            let status = rest.next().unwrap_or("").trim().parse::<i32>().unwrap_or(-1);
            (status, rest.collect::<Vec<_>>().join("\n"))
        }
        None => (-1, stdout.clone()),
    };
    let agent_rc = classify_agent_rc(&body);
    Ok(AttachResult { jvm_status, agent_rc, output: combined })
}

/// Locate jattach: `MCGRAFT_JATTACH` override, else the embedded copy (if this build bundled one), else `jattach.exe`
/// on `PATH`.
#[cfg(windows)]
fn jattach_bin() -> Result<std::path::PathBuf, String> {
    if let Some(p) = std::env::var_os("MCGRAFT_JATTACH") {
        let p = std::path::PathBuf::from(p);
        return if p.is_file() { Ok(p) } else { Err(format!("MCGRAFT_JATTACH is not a file: {}", p.display())) };
    }
    #[cfg(embed_jattach)]
    if let Ok(p) = embedded_jattach() {
        return Ok(p);
    }
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let c = dir.join("jattach.exe");
            if c.is_file() {
                return Ok(c);
            }
        }
    }
    Err("jattach not found — Windows inject needs jattach.exe on PATH, MCGRAFT_JATTACH set, or a build that embeds it \
         (from https://github.com/jattach/jattach). Windows attach is experimental / not yet verified end-to-end."
        .into())
}

/// Extract the build-time-embedded jattach.exe to a private temp file once and return its path (kept alive for the
/// process via the OnceLock; unpredictable mkstemp name).
#[cfg(all(windows, embed_jattach))]
fn embedded_jattach() -> Result<std::path::PathBuf, String> {
    use std::io::Write;
    use std::sync::OnceLock;
    static EXTRACTED: OnceLock<Result<tempfile::TempPath, String>> = OnceLock::new();
    match EXTRACTED.get_or_init(|| {
        static BYTES: &[u8] = include_bytes!(env!("MCGRAFT_JATTACH_EMBEDDED"));
        let mut tmp = tempfile::Builder::new()
            .prefix("mcgraft-jattach-")
            .suffix(".exe")
            .tempfile()
            .map_err(|e| format!("extract embedded jattach: {e}"))?;
        tmp.write_all(BYTES).map_err(|e| format!("extract embedded jattach: {e}"))?;
        tmp.flush().ok();
        Ok(tmp.into_temp_path())
    }) {
        Ok(tp) => Ok(tp.to_path_buf()),
        Err(e) => Err(e.clone()),
    }
}

/// Non-Unix, non-Windows (unlikely) — inject unsupported.
#[cfg(not(any(unix, windows)))]
pub fn load_agent(_pid: i32, _agent_jar: &std::path::Path, _agent_args: &str) -> Result<AttachResult, String> {
    Err("inject is not supported on this platform".into())
}
