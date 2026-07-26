//! `mcgraft` — control CLI for mc-graft.
//!
//! Discovers running Minecraft JVMs and injects the mc-graft agent jar into a live one via the HotSpot attach protocol
//! (implemented in `attach.rs`, no JNI / no bundled native binary), then follows the agent's injection log.

mod attach;
mod build;
mod bytecode;
mod config;
mod gui;
mod modinfo;
mod pack;
mod patch;
mod procs;
mod store;

use clap::{Parser, Subcommand};
use std::io::{BufRead, BufReader, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::exit;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// The agent splits its option string on this byte (must match `mcgraft.rt.InjectionLogger.ARG_SEP`).
const ARG_SEP: char = '\u{1}';

#[derive(Parser)]
#[command(name = "mcgraft", version, about = "Control mc-graft: build agents, find Minecraft JVMs, and inject.")]
struct Cli {
    /// With no subcommand, the graphical UI opens.
    #[command(subcommand)]
    cmd: Option<Cmd>,
}

#[derive(Subcommand)]
enum Cmd {
    /// Open the graphical UI (also the default when run with no subcommand).
    Ui,
    /// List running (attachable) JVMs; Minecraft instances are marked.
    List {
        /// Print full details per JVM: loader version, game dir, java binary and the whole command line.
        #[arg(short, long)]
        verbose: bool,
    },
    /// Build the mc-graft agent jar for a payload mod (drives Gradle's agentJar task).
    Build(BuildCliArgs),
    /// Assemble an agent jar WITHOUT a JDK/Gradle, splicing a payload into the precompiled base jar.
    Pack(PackArgs),
    /// Inject the mc-graft agent into a running Minecraft JVM.
    Inject(InjectArgs),
    /// Ask a running JVM for its own system properties (authoritative loader/version info, straight from the process).
    ///
    /// This ATTACHES to the target — the same mechanism inject uses — so it is deliberately not part of `list`, which
    /// would otherwise attach to every JVM on the machine just to draw a table.
    Props {
        /// Target JVM pid.
        pid: String,
        /// Print every property rather than the mc-graft-relevant subset.
        #[arg(short, long)]
        all: bool,
    },
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
struct PackArgs {
    /// The Fabric mod jar to pack into an agent.
    #[arg(short, long)]
    payload: PathBuf,
    /// The precompiled base jar (Gradle `agentBase` output). Default: auto-detect under the repo's build/agent.
    #[arg(short, long, value_name = "JAR")]
    base: Option<PathBuf>,
    /// Optional config .properties (same keys as the agent's graft.properties overrides).
    #[arg(short, long, value_name = "FILE")]
    config: Option<PathBuf>,
    /// Output agent jar path (default: <payload-id>-agent.jar in cwd).
    #[arg(short, long, value_name = "FILE")]
    out: Option<PathBuf>,
    /// mc-graft repo root (used only to auto-detect the base jar; default: auto-detect from cwd/exe).
    #[arg(long, value_name = "DIR")]
    repo: Option<PathBuf>,
    /// Patch(es) to apply to the payload jar before packing (repeatable, applied in order). Each is a `.mcpatch`
    /// bundle (dir or zip with manifest.toml) or a bare `.jar`/`.zip` overlay.
    #[arg(long = "patch", value_name = "PATH")]
    patches: Vec<PathBuf>,
    /// Ignore each patch's [target] base guard for this run (sha256/mod-id checks).
    #[arg(long)]
    no_verify_base: bool,
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
        None | Some(Cmd::Ui) => {
            if let Err(e) = gui::run() {
                die(&e);
            }
        }
        Some(Cmd::List { verbose }) => cmd_list(verbose),
        Some(Cmd::Build(a)) => cmd_build(a),
        Some(Cmd::Pack(a)) => cmd_pack(a),
        Some(Cmd::Inject(a)) => cmd_inject(a),
        Some(Cmd::Props { pid, all }) => cmd_props(&pid, all),
        Some(Cmd::Logs { file, follow }) => {
            if let Err(e) = follow_log(&file, 0, follow, Instant::now() + Duration::from_secs(600)) {
                die(&e);
            }
        }
    }
}

fn cmd_list(verbose: bool) {
    let jvms = procs::list_jvms();
    if jvms.is_empty() {
        println!("No attachable JVMs found.");
        return;
    }
    if verbose {
        for j in &jvms {
            let dash = |o: &Option<String>| o.clone().unwrap_or_else(|| "—".into());
            let loader = match &j.loader_version {
                Some(v) => format!("{} {v}", j.loader.label()),
                None => j.loader.label().to_string(),
            };
            println!("PID {}", j.pid);
            println!("  minecraft   {}", if j.is_minecraft { "yes" } else { "not detected" });
            println!("  mc version  {}", dash(&j.mc_version));
            // Only warn about loader support for something we actually think is Minecraft; on an unrelated JVM
            // "unknown loader" is just noise.
            let unsupported = j.is_minecraft && !j.loader.supported();
            println!("  loader      {}{}", loader, if unsupported { "   (UNSUPPORTED by mc-graft)" } else { "" });
            println!("  main class  {}", j.main);
            println!("  game dir    {}", dash(&j.game_dir));
            println!("  java        {}", dash(&j.java_exe));
            println!("  cmdline     {}", j.cmdline);
            println!();
        }
        return;
    }
    println!("{:>8}  {:<3}  {:<12}  {:<10}  MAIN / COMMAND", "PID", "MC", "MC VER", "LOADER");
    for j in &jvms {
        let mark = if j.is_minecraft { "yes" } else { "" };
        let ver = j.mc_version.clone().unwrap_or_default();
        let label = if j.main == "?" { truncate(&j.cmdline, 80) } else { j.main.clone() };
        let loader = if j.is_minecraft { j.loader.label() } else { "" };
        println!("{:>8}  {:<3}  {:<12}  {:<10}  {}", j.pid, mark, ver, loader, label);
    }
    println!("\n(use `mcgraft list --verbose` for game dir, loader version and the full command line)");
}

/// The properties worth showing by default: what the JVM says about itself, rather than what its command line implies.
/// `java.class.path` in particular carries the loader jar *with its version*, which no cmdline heuristic can match.
const INTERESTING_PROPS: &[&str] = &[
    "java.version",
    "java.vendor",
    "java.home",
    "os.name",
    "os.arch",
    "user.dir",
    "sun.java.command",
    "java.class.path",
    "minecraft.launcher.brand",
    "minecraft.launcher.version",
    "fabric.gameVersion",
    "fabric.development",
];

fn cmd_props(pid: &str, all: bool) {
    let pid = procs::resolve_target(Some(pid)).unwrap_or_else(|e| die(&e));
    eprintln!("Attaching to PID {pid} to read its system properties…");
    let props = attach::system_properties(pid).unwrap_or_else(|e| die(&format!("read properties: {e}")));
    if props.is_empty() {
        die("the JVM returned no properties");
    }
    for (k, v) in &props {
        if all || INTERESTING_PROPS.contains(&k.as_str()) {
            println!("{k} = {v}");
        }
    }
    if !all {
        println!("\n({} properties total — use `mcgraft props {pid} --all` for the rest)", props.len());
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

fn cmd_pack(a: PackArgs) {
    if !a.payload.is_file() {
        die(&format!("payload jar not found: {}", a.payload.display()));
    }
    eprintln!("{}", version_line());

    // Resolve inputs that don't depend on the patched jar first, so a failure here can't leak a patched temp file.
    let base = a.base.clone().unwrap_or_else(|| {
        let repo = a
            .repo
            .clone()
            .map(Ok)
            .unwrap_or_else(|| build::find_repo_root(None))
            .unwrap_or_else(|e| die(&e));
        repo.join("build/agent/mc-graft-base.jar")
    });
    let cfg = match &a.config {
        Some(p) => {
            let text = std::fs::read_to_string(p).unwrap_or_else(|e| die(&format!("{}: {e}", p.display())));
            config::AgentConfig::from_properties(&text)
        }
        None => config::AgentConfig::default(),
    };

    // Apply any user patches to the payload jar — a merge on fabric.mod.json must be visible to everything below (mod id,
    // mixins, deps, entrypoints). `patched` is a NamedTempFile that deletes itself when it drops (all exit paths below).
    let patched = if a.patches.is_empty() {
        None
    } else {
        let opts = patch::Options { no_verify_base: a.no_verify_base };
        patch::apply_patches(&a.payload, &a.patches, &opts, |l| println!("{l}")).unwrap_or_else(|e| die(&e))
    };
    let payload: &Path = patched.as_ref().map(|t| t.path()).unwrap_or(&a.payload);

    let info = modinfo::read_jar(payload).unwrap_or_else(|e| die(&e));
    eprintln!("Payload: {}", info.summary());

    let out = a.out.clone().unwrap_or_else(|| PathBuf::from(format!("{}-agent.jar", info.id)));

    match pack::pack_agent(&base, payload, &info, &cfg, &out, |l| println!("{l}")) {
        Ok(()) => println!("\nAgent packed (JDK-free): {}", out.display()),
        Err(e) => die(&e),
    }
    drop(patched); // delete the patched temp jar (also happens on normal scope-exit)
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
    eprintln!("{}", version_line());
    // Tell the agent which jar it was attached from. It cannot work this out for itself: the engine lives in the
    // default package, so on a second attach into the same JVM the JVM resolves `Agent-Class` to jar #1's
    // already-loaded class, and both routes the engine would otherwise use — its own ProtectionDomain CodeSource and
    // a classpath `getResourceAsStream("graft.properties")` — then point at jar #1. Without this the second injection
    // silently runs with the FIRST payload's config.
    let mut parts: Vec<String> = vec![
        format!("logFile={}", log_path.display()),
        format!("agentJar={}", std::fs::canonicalize(&a.agent).unwrap_or_else(|_| a.agent.clone()).display()),
    ];
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

    // A non-zero agent_rc means agentmain threw inside the client. Report it and still follow the log (which carries
    // the stack trace), but exit non-zero so scripts and humans both see a failure rather than "delivered".
    let mut agent_failed = false;
    match attach::load_agent(pid, &a.agent, &agent_args) {
        Ok(r) => {
            if r.jvm_status != 0 {
                die(&format!(
                    "the JVM rejected the load command (status {}). Response:\n{}",
                    r.jvm_status, r.output.trim()
                ));
            }
            if r.agent_rc == 0 {
                eprintln!("Agent delivered (Agent_OnAttach returned 0).");
            } else {
                agent_failed = true;
                eprintln!(
                    "Agent load FAILED: agentmain reported {} inside the client. JVM response:\n{}",
                    r.agent_rc,
                    r.output.trim()
                );
            }
        }
        Err(e) => die(&format!("attach failed: {e}")),
    }

    if a.no_follow {
        eprintln!("Attached. Follow the log with:  mcgraft logs --follow {}", log_path.display());
    } else {
        eprintln!("--- injection log (Ctrl-C to stop) ---");
        let _ = follow_log(&log_path, start_off, true, Instant::now() + Duration::from_secs(300));
    }
    if agent_failed {
        std::process::exit(1);
    }
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
/// Has the agent finished, successfully or not?
///
/// Only genuinely FINAL lines belong here. This list used to be inherited from when the tool injected nothing but
/// LiquidBounce, and it was wrong in both directions at once:
///
///   - it did not contain the engine's own generic completion line, so a payload that never says LiquidBounce's words
///     (Wurst, say) left the GUI spinning long after the injection had finished, until the user pressed Stop;
///   - and "restored original state" / "has been successfully initialized" are MID-injection steps, so on a payload
///     that did emit them the tail stopped early and truncated the log before the final line was ever written.
///
/// Every agent built from this engine ends with `payload injection complete`; the failure lines below are the other
/// ways it can stop. A jar too old to emit any of them is bounded by the tail's own liveness check and deadline.
fn is_terminal_marker(l: &str) -> bool {
    l.contains("payload injection complete")
        || l.contains("re-injection refused")
        || l.contains("engine build mismatch")
        || l.contains("preflight failed")
        || l.contains("kick error")
        || l.contains("initialization incomplete")
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

/// One line identifying this binary, printed by every pack and inject.
///
/// A bug report is only as useful as the build it names. Until this existed, telling an old binary from a new one meant
/// recognising the phrasing of a log message — which is how a stale exe went unnoticed for an entire debugging session.
pub fn version_line() -> String {
    format!("mcgraft {} ({})", env!("CARGO_PKG_VERSION"), env!("MCGRAFT_COMMIT"))
}

/// The commit this binary was built from, for comparison against the base jar it packs against.
pub fn version_line_commit() -> &'static str {
    env!("MCGRAFT_COMMIT")
}

fn die(msg: &str) -> ! {
    eprintln!("error: {msg}");
    exit(1);
}
