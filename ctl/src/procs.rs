//! Discover running, attachable HotSpot JVMs on this host (and flag which are Minecraft). On Unix this reads `/proc`
//! and the `hsperfdata` markers HotSpot drops; on Windows it enumerates processes via `sysinfo`. No external `jps`/JDK.

#[derive(Clone)]
pub struct Jvm {
    pub pid: i32,
    /// Full command line, space-joined.
    pub cmdline: String,
    /// Best-effort "what is this" label: the launched main class or `-jar` name.
    pub main: String,
    /// Parsed Minecraft `--version <x>` if present.
    pub mc_version: Option<String>,
    pub is_minecraft: bool,
    /// Which mod loader this client is running, inferred from the command line.
    pub loader: Loader,
    /// Loader version, when a versioned loader jar is identifiable on the classpath.
    pub loader_version: Option<String>,
    /// `--gameDir`, i.e. where the payload's config/data will land.
    pub game_dir: Option<String>,
    /// The `java`/`javaw` binary that launched it — useful when several JDKs are installed.
    pub java_exe: Option<String>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Loader {
    Vanilla,
    Fabric,
    Quilt,
    NeoForge,
    Forge,
    Unknown,
}

impl Loader {
    pub fn label(self) -> &'static str {
        match self {
            Loader::Vanilla => "Vanilla",
            Loader::Fabric => "Fabric",
            Loader::Quilt => "Quilt",
            Loader::NeoForge => "NeoForge",
            Loader::Forge => "Forge",
            Loader::Unknown => "unknown",
        }
    }
    /// Whether mc-graft has a platform implementation for this loader.
    pub fn supported(self) -> bool {
        matches!(self, Loader::Vanilla | Loader::Fabric | Loader::NeoForge)
    }
}

/// All attachable JVMs, Minecraft ones first, then by pid.
pub fn list_jvms() -> Vec<Jvm> {
    let mut out = platform_list();
    out.sort_by(|a, b| b.is_minecraft.cmp(&a.is_minecraft).then(a.pid.cmp(&b.pid)));
    out
}

/// Resolve a user-supplied selector to a single pid: an explicit pid, or (if unique) the sole running Minecraft JVM.
pub fn resolve_target(selector: Option<&str>) -> Result<i32, String> {
    if let Some(s) = selector {
        return s.trim().parse::<i32>().map_err(|_| format!("not a pid: {s:?}"));
    }
    let mc: Vec<Jvm> = list_jvms().into_iter().filter(|j| j.is_minecraft).collect();
    match mc.len() {
        1 => Ok(mc[0].pid),
        0 => Err("no running Minecraft JVM found — start the client, or pass a pid explicitly".into()),
        _ => Err(format!(
            "{} Minecraft JVMs are running ({}) — pass a pid explicitly",
            mc.len(),
            mc.iter().map(|j| j.pid.to_string()).collect::<Vec<_>>().join(", ")
        )),
    }
}

/// Classify a JVM from its pid + argv (platform-independent).
fn classify(pid: i32, args: &[String]) -> Jvm {
    let cmdline = args.join(" ");
    let joined = cmdline.as_str();
    let is_minecraft = joined.contains("knot.KnotClient")
        || joined.contains("net.minecraft.client.main.Main")
        || joined.contains("net.fabricmc.loader")
        || joined.contains("cpw.mods.bootstraplauncher") // NeoForge/Forge
        || joined.contains("net.neoforged")
        || joined.contains("io.github.zekerzhayard") // ForgeWrapper
        || (joined.contains("minecraft") && joined.contains("-cp"));
    let main = mainish(args);
    let mc_version = flag_value(args, "--version");
    let (loader, loader_version) = detect_loader(joined);
    Jvm {
        pid,
        main,
        mc_version,
        is_minecraft,
        loader,
        loader_version,
        game_dir: flag_value(args, "--gameDir"),
        java_exe: args.first().cloned(),
        cmdline,
    }
}

/// Infer the mod loader (and its version, when a versioned loader jar is on the classpath) from the command line.
///
/// Order matters: a NeoForge/Forge launch also mentions `minecraft`, and Quilt ships a Fabric compatibility layer whose
/// jars mention `fabric`, so the more specific markers are tested first.
fn detect_loader(joined: &str) -> (Loader, Option<String>) {
    let low = joined.to_ascii_lowercase();
    let loader = if low.contains("org.quiltmc.loader") || low.contains("quilt-loader") {
        Loader::Quilt
    } else if low.contains("net.neoforged") || low.contains("neoforge") {
        Loader::NeoForge
    } else if low.contains("net.minecraftforge") || low.contains("forgewrapper") || low.contains("minecraftforge") {
        Loader::Forge
    } else if low.contains("net.fabricmc.loader") || low.contains("knot.knotclient") || low.contains("fabric-loader") {
        Loader::Fabric
    } else if low.contains("net.minecraft.client.main.main") {
        Loader::Vanilla
    } else {
        Loader::Unknown
    };
    let stem = match loader {
        Loader::Fabric => "fabric-loader-",
        Loader::Quilt => "quilt-loader-",
        Loader::NeoForge => "neoforge-",
        Loader::Forge => "forge-",
        _ => return (loader, None),
    };
    // e.g. ".../fabric-loader-0.18.3.jar" -> "0.18.3"
    let version = low.find(stem).map(|i| &joined[i + stem.len()..]).and_then(|rest| {
        let end = rest.find(".jar").unwrap_or(rest.len());
        let v = rest[..end].trim();
        (!v.is_empty() && v.starts_with(|c: char| c.is_ascii_digit())).then(|| v.to_string())
    });
    (loader, version)
}

/// The launched entry point: `-jar X` target, else the main class (first non-`-flag`, non-`java` arg after options).
/// JVM options that take their value as a SEPARATE following argument. Anything here must consume the next arg, or
/// that value gets mistaken for the main class — `--add-opens java.base/java.lang=ALL-UNNAMED` is on essentially every
/// modded launch, and it used to make the process list read `java.base/java.lang=ALL-UNNAMED`.
const VALUE_FLAGS: &[&str] = &[
    "-cp",
    "-classpath",
    "--class-path",
    "-p",
    "--module-path",
    "--upgrade-module-path",
    "--add-opens",
    "--add-exports",
    "--add-reads",
    "--add-modules",
    "--patch-module",
    "--limit-modules",
    "-m",
    "--module",
    "--source",
];

/// Whether `s` looks like a Java class name, as opposed to a module directive, a path, or a `key=value`. Dots are
/// optional so a default-package main class (`Idle`) still resolves; what this rejects is `=`, `/` and `\`, which
/// between them cover option values (`java.base/java.lang=ALL-UNNAMED`) and bare paths.
fn looks_like_class_name(s: &str) -> bool {
    !s.is_empty()
        && !s.contains('=')
        && !s.contains('/')
        && !s.contains('\\')
        && s.split('.').all(|seg| {
            !seg.is_empty()
                && seg.starts_with(|c: char| c.is_ascii_alphabetic() || c == '_' || c == '$')
                && seg.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '$')
        })
}

pub(crate) fn mainish(args: &[String]) -> String {
    if let Some(i) = args.iter().position(|a| a == "-jar") {
        if let Some(j) = args.get(i + 1) {
            return j.clone();
        }
    }
    // Known entry points win outright — including the launcher wrappers, which otherwise fall through to the
    // heuristic. Prism/MultiMC start their own EntryPoint and only reach the real client main class later.
    for known in [
        "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "net.minecraft.client.main.Main",
        "cpw.mods.bootstraplauncher.BootstrapLauncher",
        "org.prismlauncher.EntryPoint",
        "org.multimc.EntryPoint",
        "io.github.zekerzhayard.forgewrapper.installer.Main",
    ] {
        if args.iter().any(|a| a == known) {
            return known.to_string();
        }
    }
    let mut skip_next = false;
    for a in args.iter().skip(1) {
        if skip_next {
            skip_next = false;
            continue;
        }
        if VALUE_FLAGS.contains(&a.as_str()) {
            skip_next = true;
            continue;
        }
        if a.starts_with('-') {
            continue;
        }
        // Only accept something that actually looks like a class name; an option value that slipped through (or a
        // bare path) is not a main class, and reporting it is worse than reporting nothing.
        if looks_like_class_name(a) {
            return a.clone();
        }
    }
    "?".into()
}

fn flag_value(args: &[String], flag: &str) -> Option<String> {
    args.iter().position(|a| a == flag).and_then(|i| args.get(i + 1).cloned())
}

// ----------------------------------------------------------------------------------------------- platform discovery

#[cfg(unix)]
fn platform_list() -> Vec<Jvm> {
    use std::fs;

    fn hsperf_pids() -> Vec<i32> {
        let mut v = Vec::new();
        if let Ok(tmp) = fs::read_dir("/tmp") {
            for e in tmp.flatten() {
                let name = e.file_name();
                let name = name.to_string_lossy();
                if !name.starts_with("hsperfdata_") {
                    continue;
                }
                if let Ok(inner) = fs::read_dir(e.path()) {
                    for f in inner.flatten() {
                        if let Ok(pid) = f.file_name().to_string_lossy().parse::<i32>() {
                            v.push(pid);
                        }
                    }
                }
            }
        }
        v
    }
    fn proc_pids() -> Vec<i32> {
        fs::read_dir("/proc")
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|e| e.file_name().to_string_lossy().parse::<i32>().ok())
            .collect()
    }
    fn comm(pid: i32) -> Option<String> {
        fs::read_to_string(format!("/proc/{pid}/comm")).ok().map(|s| s.trim().to_string())
    }
    fn cmdline_args(pid: i32) -> Option<Vec<String>> {
        let raw = fs::read(format!("/proc/{pid}/cmdline")).ok()?;
        if raw.is_empty() {
            return None;
        }
        Some(
            raw.split(|b| *b == 0)
                .filter(|s| !s.is_empty())
                .map(|s| String::from_utf8_lossy(s).into_owned())
                .collect(),
        )
    }

    fn is_jvm(pid: i32) -> bool {
        comm(pid).as_deref() == Some("java")
            || fs::read_to_string(format!("/proc/{pid}/maps")).is_ok_and(|m| m.contains("libjvm.so"))
    }

    // hsperfdata files outlive the JVM that wrote them (a SIGKILLed VM never cleans up), so a recycled pid would
    // otherwise be listed as an attachable JVM — and injecting into it SIGQUITs an unrelated process. Confirm every
    // candidate is actually a live JVM, whichever way it was discovered.
    let mut pids: Vec<i32> = hsperf_pids();
    // Also include any /proc entry whose comm is exactly "java" (covers VMs started with -XX:-UsePerfData).
    for pid in proc_pids() {
        if !pids.contains(&pid) && comm(pid).as_deref() == Some("java") {
            pids.push(pid);
        }
    }
    pids.into_iter()
        .filter_map(|pid| {
            // cmdline_args reads /proc/<pid>/cmdline and returns None if the process is gone, so it doubles as the
            // liveness check — no separate /proc/<pid> existence test is needed (it could never fire after this).
            let args = cmdline_args(pid)?;
            if !is_jvm(pid) {
                return None;
            }
            Some(classify(pid, &args))
        })
        .collect()
}

#[cfg(windows)]
fn platform_list() -> Vec<Jvm> {
    use sysinfo::System;
    let sys = System::new_all();
    sys.processes()
        .values()
        .filter_map(|p| {
            let name = p.name().to_string_lossy().to_ascii_lowercase();
            if name != "java.exe" && name != "javaw.exe" {
                return None;
            }
            let args: Vec<String> = p.cmd().iter().map(|s| s.to_string_lossy().into_owned()).collect();
            if args.is_empty() {
                return None;
            }
            Some(classify(i32::try_from(p.pid().as_u32()).unwrap_or(-1), &args))
        })
        .collect()
}

/// Cheap liveness check for a single pid — used to stop tailing an injection log when the client exits, rather than
/// sitting on a fixed timeout. Deliberately not `list_jvms()`: that walks every process and would be absurd in a poll.
#[cfg(unix)]
pub fn is_alive(pid: i32) -> bool {
    std::path::Path::new(&format!("/proc/{pid}")).exists()
}

#[cfg(windows)]
pub fn is_alive(pid: i32) -> bool {
    use sysinfo::{Pid, ProcessRefreshKind, ProcessesToUpdate, System};
    let Ok(raw) = u32::try_from(pid) else { return false };
    let target = Pid::from_u32(raw);
    let mut sys = System::new();
    sys.refresh_processes_specifics(ProcessesToUpdate::Some(&[target]), true, ProcessRefreshKind::nothing());
    sys.process(target).is_some()
}

#[cfg(not(any(unix, windows)))]
pub fn is_alive(_pid: i32) -> bool {
    true
}
