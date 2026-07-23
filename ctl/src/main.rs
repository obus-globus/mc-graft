//! `mcgraft` — control CLI for mc-graft.
//!
//! Discovers running Minecraft JVMs and injects the mc-graft agent jar into a live one via the HotSpot attach protocol
//! (implemented in `attach.rs`, no JNI / no bundled native binary), then follows the agent's injection log.

mod attach;
mod build;
mod procs;

use clap::{Parser, Subcommand};
use std::io::{BufRead, BufReader, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::exit;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// The agent splits its option string on this byte (must match `mcgraft.rt.InjectionLogger.ARG_SEP`).
const ARG_SEP: char = '\u{1}';

#[derive(Parser)]
#[command(name = "mcgraft", version, about = "Control mc-graft: find Minecraft JVMs and inject the agent.")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// List running (attachable) JVMs; Minecraft instances are marked.
    List,
    /// Build the mc-graft agent jar for a payload mod (drives Gradle's agentJar task).
    Build(BuildCliArgs),
    /// Inject the mc-graft agent into a running Minecraft JVM.
    Inject(InjectArgs),
    /// Follow an existing injection log file.
    Logs {
        /// Path to the injection log written by a previous inject.
        file: PathBuf,
        /// Keep following (tail -f); otherwise print what's there and exit.
        #[arg(short, long)]
        follow: bool,
    },
}

#[derive(clap::Args)]
struct BuildCliArgs {
    /// The Fabric mod jar to build an agent for.
    #[arg(short, long)]
    payload: PathBuf,
    /// Optional bootstrap/override .properties (init override for entrypoint-less mods, e.g. LiquidBounce).
    #[arg(short, long, value_name = "FILE")]
    config: Option<PathBuf>,
    /// Optional override of the auto-derived mixin target list.
    #[arg(long, value_name = "FILE")]
    mixin_targets: Option<PathBuf>,
    /// Optional external dependency mods to bundle (comma-separated Maven coordinates).
    #[arg(long, value_name = "COORDS")]
    deps: Option<String>,
    /// Output agent jar name (default: mc-graft-agent.jar).
    #[arg(short, long, value_name = "NAME")]
    name: Option<String>,
    /// mc-graft repo root (default: auto-detect from cwd/exe).
    #[arg(long, value_name = "DIR")]
    repo: Option<PathBuf>,
}

#[derive(clap::Args)]
struct InjectArgs {
    /// Target JVM pid. Omit to auto-select the sole running Minecraft JVM.
    pid: Option<String>,
    /// The agent jar to load (e.g. build/agent/mc-graft-agent.jar).
    #[arg(short, long)]
    agent: PathBuf,
    /// Data/game directory to hand the payload. Default: the client's real game dir (agent resolves it).
    #[arg(short, long, value_name = "DIR")]
    game_dir: Option<String>,
    /// Use the client's working directory as the game dir (shortcut for --game-dir .).
    #[arg(long, conflicts_with = "game_dir")]
    here: bool,
    /// On modded loaders, skip an other-mod mixin target that fails instead of aborting the whole inject.
    #[arg(long)]
    tolerate_failures: bool,
    /// Write the injection log here (default: a temp file that is then followed).
    #[arg(short, long, value_name = "FILE")]
    log: Option<PathBuf>,
    /// Do not follow the injection log after attaching.
    #[arg(long)]
    no_follow: bool,
}

fn main() {
    match Cli::parse().cmd {
        Cmd::List => cmd_list(),
        Cmd::Build(a) => cmd_build(a),
        Cmd::Inject(a) => cmd_inject(a),
        Cmd::Logs { file, follow } => {
            if let Err(e) = follow_log(&file, 0, follow, Instant::now() + Duration::from_secs(600)) {
                die(&e);
            }
        }
    }
}

fn cmd_list() {
    let jvms = procs::list_jvms();
    if jvms.is_empty() {
        println!("No attachable JVMs found.");
        return;
    }
    println!("{:>8}  {:<3}  {:<12}  {}", "PID", "MC", "MC VER", "MAIN / COMMAND");
    for j in &jvms {
        let mark = if j.is_minecraft { "yes" } else { "" };
        let ver = j.mc_version.clone().unwrap_or_default();
        let label = if j.main == "?" { truncate(&j.cmdline, 90) } else { j.main.clone() };
        println!("{:>8}  {:<3}  {:<12}  {}", j.pid, mark, ver, label);
    }
}

fn cmd_build(a: BuildCliArgs) {
    let repo = a
        .repo
        .clone()
        .map(Ok)
        .unwrap_or_else(|| build::find_repo_root(None))
        .unwrap_or_else(|e| die(&e));
    eprintln!("Building agent in {} ...", repo.display());
    let req = build::BuildRequest {
        payload: a.payload,
        bootstrap_config: a.config,
        mixin_targets: a.mixin_targets,
        payload_deps: a.deps,
        agent_name: a.name,
    };
    match build::build_agent(&repo, &req, |l| println!("{l}")) {
        Ok(jar) => println!("\nAgent built: {}", jar.display()),
        Err(e) => die(&e),
    }
}

fn cmd_inject(a: InjectArgs) {
    if !a.agent.is_file() {
        die(&format!("agent jar not found: {}", a.agent.display()));
    }
    let pid = procs::resolve_target(a.pid.as_deref()).unwrap_or_else(|e| die(&e));

    // Confirm the target looks like a JVM we can attach to, and warn (not fail) if it isn't flagged as Minecraft.
    let jvm = procs::list_jvms().into_iter().find(|j| j.pid == pid);
    match &jvm {
        Some(j) if j.is_minecraft => eprintln!("Target PID {pid}: {} (Minecraft{})", j.main, ver_suffix(j)),
        Some(j) => eprintln!("Target PID {pid}: {} — not detected as Minecraft; attaching anyway.", j.main),
        None => eprintln!("Warning: PID {pid} is not in the attachable-JVM list; attaching anyway."),
    }

    // Build the agent option string (key=value pairs, ARG_SEP-joined).
    let log_path = a.log.clone().unwrap_or_else(|| default_log(pid));
    let mut parts: Vec<String> = vec![format!("logFile={}", log_path.display())];
    if a.here {
        parts.push("gameDir=.".into());
    } else if let Some(dir) = &a.game_dir {
        parts.push(format!("gameDir={dir}"));
    }
    if a.tolerate_failures {
        parts.push("tolerateBaselineFailures=true".into());
    }
    let agent_args = parts.join(&ARG_SEP.to_string());

    eprintln!("Injecting {} -> PID {pid}", a.agent.display());
    eprintln!("Injection log: {}", log_path.display());
    let start_off = std::fs::metadata(&log_path).map(|m| m.len()).unwrap_or(0);

    match attach::load_agent(pid, &a.agent, &agent_args) {
        Ok(r) => {
            if r.jvm_status != 0 {
                die(&format!(
                    "the JVM rejected the load command (status {}). Response:\n{}",
                    r.jvm_status, r.output.trim()
                ));
            }
            match r.agent_rc {
                Some(0) => eprintln!("Agent delivered (Agent_OnAttach returned 0)."),
                Some(rc) => eprintln!(
                    "Agent delivered but Agent_OnAttach returned {rc} — agentmain failed inside the client; see the log."
                ),
                None => eprintln!("Agent load delivered (no return code reported — watch the log to confirm)."),
            }
        }
        Err(e) => die(&format!("attach failed: {e}")),
    }

    if a.no_follow {
        eprintln!("Attached. Follow the log with:  mcgraft logs --follow {}", log_path.display());
        return;
    }
    eprintln!("--- injection log (Ctrl-C to stop) ---");
    let _ = follow_log(&log_path, start_off, true, Instant::now() + Duration::from_secs(300));
}

/// Print the log from `start_off`, optionally following. Stops on a terminal marker or `deadline` when following.
fn follow_log(file: &Path, start_off: u64, follow: bool, deadline: Instant) -> Result<(), String> {
    // Wait briefly for the file to appear (the agent creates it a moment after attach).
    let appear = Instant::now() + Duration::from_secs(10);
    while !file.exists() && Instant::now() < appear {
        std::thread::sleep(Duration::from_millis(100));
    }
    let f = std::fs::File::open(file).map_err(|e| format!("open {}: {e}", file.display()))?;
    let mut reader = BufReader::new(f);
    reader.seek(SeekFrom::Start(start_off)).ok();
    let stdout = std::io::stdout();
    let mut line = String::new();
    loop {
        line.clear();
        let n = reader.read_line(&mut line).map_err(|e| format!("read: {e}"))?;
        if n == 0 {
            if !follow || Instant::now() > deadline {
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(150));
            continue;
        }
        let clean = strip_ansi(line.trim_end());
        {
            let mut h = stdout.lock();
            let _ = writeln!(h, "{clean}");
        }
        if follow && is_terminal_marker(&clean) {
            return Ok(());
        }
    }
}

/// Log lines that mean "injection finished" (success or failure) so `--follow` can stop instead of hanging.
fn is_terminal_marker(l: &str) -> bool {
    l.contains("initialization completed")
        || l.contains("has been successfully initialized")
        || l.contains("restored original state")
        || l.contains("initialization incomplete")
        || l.contains("kick error")
        || l.contains("preflight failed")
        || l.contains("re-injection refused")
        || l.contains("=== UNINJECT complete")
}

fn default_log(pid: i32) -> PathBuf {
    let t = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    std::env::temp_dir().join(format!("mcgraft-inject-{pid}-{t}.log"))
}

fn ver_suffix(j: &procs::Jvm) -> String {
    j.mc_version.as_ref().map(|v| format!(" {v}")).unwrap_or_default()
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        format!("{}…", s.chars().take(n).collect::<String>())
    }
}

/// Strip ANSI SGR escape sequences (the client may colorize stdout; the log file can carry them through).
fn strip_ansi(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            // ESC [ ... <final byte 0x40-0x7E>
            if chars.peek() == Some(&'[') {
                chars.next();
                while let Some(&d) = chars.peek() {
                    chars.next();
                    if ('@'..='~').contains(&d) {
                        break;
                    }
                }
            }
            continue;
        }
        out.push(c);
    }
    out
}

fn die(msg: &str) -> ! {
    eprintln!("error: {msg}");
    exit(1);
}
