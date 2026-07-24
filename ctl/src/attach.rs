//! Minimal pure-Rust implementation of the HotSpot dynamic-attach protocol (Linux/macOS Unix-socket variant).
//!
//! The JVM exposes a control channel that lets an external process load a JVMTI agent into it at runtime. The channel
//! is an `AF_UNIX` socket the JVM creates on demand. If the socket does not exist yet, we create the well-known
//! trigger file the JVM's SIGQUIT handler looks for and send it SIGQUIT; the JVM then starts its AttachListener and
//! creates the socket (with the trigger file present it does this INSTEAD of dumping threads). We then speak the
//! text protocol: a version byte, a command, and up to three NUL-terminated arguments. To load an agent jar we use
//! the JVM's built-in `instrument` agent: `load instrument false "<jar>=<options>"`.
//!
//! No JNI, no bundled native binary — just std sockets + a `kill -QUIT`.

use std::fs;
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread::sleep;
use std::time::{Duration, Instant};

pub struct AttachResult {
    /// JVM command-completion status (first response line). 0 = the command was accepted.
    pub jvm_status: i32,
    /// The `instrument` agent's `Agent_OnAttach` return code, parsed from its "return code: N" output. 0 = agentmain OK.
    pub agent_rc: Option<i32>,
    /// The full raw response text (thread-dump-free; just the attach channel's reply).
    pub output: String,
}

/// The tmp directory the target JVM writes its attach socket into. HotSpot always uses the OS temp dir (`/tmp`), which
/// — for a process in another mount namespace (container) — we reach through `/proc/<pid>/root/tmp`. For a same-namespace
/// process this simply resolves to `/tmp`.
fn attach_dir(pid: i32) -> PathBuf {
    let ns = PathBuf::from(format!("/proc/{}/root/tmp", pid));
    if ns.is_dir() { ns } else { PathBuf::from("/tmp") }
}

fn send_sigquit(pid: i32) -> Result<(), String> {
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
    let dir = attach_dir(pid);
    let sock = dir.join(format!(".java_pid{pid}"));
    if sock.exists() {
        return Ok(sock);
    }
    // Create the attach trigger. The JVM's SIGQUIT handler checks its cwd first, then /tmp; create it where we can.
    let candidates = [
        PathBuf::from(format!("/proc/{pid}/cwd/.attach_pid{pid}")),
        dir.join(format!(".attach_pid{pid}")),
    ];
    let trigger = candidates
        .iter()
        .find(|c| fs::File::create(c).is_ok())
        .cloned()
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
    let _ = fs::remove_file(&trigger);
    r
}

/// Load `agent_jar` into the JVM `pid`, passing `agent_args` (the raw agent option string — already separator-joined).
pub fn load_agent(pid: i32, agent_jar: &Path, agent_args: &str) -> Result<AttachResult, String> {
    let jar = agent_jar
        .canonicalize()
        .map_err(|e| format!("agent jar {}: {e}", agent_jar.display()))?;
    let sock = ensure_socket(pid)?;
    let mut stream =
        UnixStream::connect(&sock).map_err(|e| format!("connect {}: {e}", sock.display()))?;
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
    stream
        .write_all(&req)
        .map_err(|e| format!("write attach request: {e}"))?;

    let mut buf = Vec::new();
    stream
        .read_to_end(&mut buf)
        .map_err(|e| format!("read attach response: {e}"))?;
    let output = String::from_utf8_lossy(&buf).into_owned();

    let jvm_status = output
        .lines()
        .next()
        .unwrap_or("")
        .trim()
        .parse::<i32>()
        .unwrap_or(-1);
    let agent_rc = output.lines().find_map(parse_return_code);

    Ok(AttachResult { jvm_status, agent_rc, output })
}

/// Parse a trailing signed integer out of a line that mentions "return code" (case-insensitive).
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
