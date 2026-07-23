//! Build the mc-graft agent jar for a given payload by driving the repo's Gradle `agentJar` task. This is the same
//! command a user would run by hand (`./gradlew agentJar -Ppayload=...`), wrapped so the CLI/TUI can build + inject in
//! one flow. Output is streamed line-by-line to `on_line`; the produced jar path is parsed from the task's own log
//! line (`mc-graft agent -> <path>`).

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

pub struct BuildRequest {
    pub payload: PathBuf,
    pub bootstrap_config: Option<PathBuf>,
    pub mixin_targets: Option<PathBuf>,
    pub payload_deps: Option<String>,
    pub agent_name: Option<String>,
}

/// Locate the mc-graft repo root: the nearest ancestor of `start` (or the exe dir) that has `gradlew` and `engine/`.
pub fn find_repo_root(start: Option<&Path>) -> Result<PathBuf, String> {
    let mut roots: Vec<PathBuf> = Vec::new();
    if let Some(s) = start {
        roots.push(s.to_path_buf());
    }
    if let Ok(cwd) = std::env::current_dir() {
        roots.push(cwd);
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(p) = exe.parent() {
            roots.push(p.to_path_buf());
        }
    }
    for base in roots {
        let mut dir: Option<&Path> = Some(base.as_path());
        while let Some(d) = dir {
            if d.join("gradlew").is_file() && d.join("engine").is_dir() {
                return Ok(d.to_path_buf());
            }
            dir = d.parent();
        }
    }
    Err("could not locate the mc-graft repo root (a dir with gradlew + engine/); pass --repo".into())
}

/// Run the agent build. Returns the path to the produced agent jar.
pub fn build_agent<F: FnMut(&str)>(
    repo: &Path,
    req: &BuildRequest,
    mut on_line: F,
) -> Result<PathBuf, String> {
    if !req.payload.is_file() {
        return Err(format!("payload jar not found: {}", req.payload.display()));
    }
    let payload = req
        .payload
        .canonicalize()
        .map_err(|e| format!("payload {}: {e}", req.payload.display()))?;

    // NB: no -q — the task reports the produced jar via logger.lifecycle, which -q would suppress.
    let mut args: Vec<String> = vec![
        "agentJar".into(),
        "--console=plain".into(),
        format!("-Ppayload={}", payload.display()),
    ];
    if let Some(c) = &req.bootstrap_config {
        let c = c.canonicalize().map_err(|e| format!("bootstrapConfig {}: {e}", c.display()))?;
        args.push(format!("-PbootstrapConfig={}", c.display()));
    }
    if let Some(m) = &req.mixin_targets {
        let m = m.canonicalize().map_err(|e| format!("mixinTargets {}: {e}", m.display()))?;
        args.push(format!("-PmixinTargets={}", m.display()));
    }
    if let Some(d) = &req.payload_deps {
        args.push(format!("-PpayloadDeps={d}"));
    }
    if let Some(n) = &req.agent_name {
        args.push(format!("-PagentName={n}"));
    }

    on_line(&format!("$ ./gradlew {}", args.join(" ")));
    let mut child = Command::new("./gradlew")
        .args(&args)
        .current_dir(repo)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("failed to launch ./gradlew in {}: {e}", repo.display()))?;

    let mut produced: Option<PathBuf> = None;
    // Drain stderr on a thread so a full pipe can't deadlock; parse stdout on this thread.
    let stderr = child.stderr.take();
    let err_handle = stderr.map(|s| {
        std::thread::spawn(move || {
            let mut lines = Vec::new();
            for l in BufReader::new(s).lines().map_while(Result::ok) {
                lines.push(l);
            }
            lines
        })
    });
    if let Some(out) = child.stdout.take() {
        for line in BufReader::new(out).lines().map_while(Result::ok) {
            on_line(&line);
            if let Some(rest) = line.split("mc-graft agent ->").nth(1) {
                // "mc-graft agent -> /abs/path.jar (payload: X.jar)"
                let path = rest.trim().split(" (").next().unwrap_or("").trim();
                if !path.is_empty() {
                    produced = Some(PathBuf::from(path));
                }
            }
        }
    }
    let status = child.wait().map_err(|e| format!("gradle wait: {e}"))?;
    let err_lines = err_handle.and_then(|h| h.join().ok()).unwrap_or_default();
    if !status.success() {
        for l in &err_lines {
            on_line(l);
        }
        return Err(format!(
            "gradle build failed (exit {}). Last errors:\n{}",
            status.code().unwrap_or(-1),
            err_lines.iter().rev().take(8).cloned().collect::<Vec<_>>().into_iter().rev().collect::<Vec<_>>().join("\n")
        ));
    }
    produced.ok_or_else(|| {
        "build reported success but no agent path was parsed (expected a 'mc-graft agent -> <path>' line)".into()
    })
}
