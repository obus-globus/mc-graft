//! JDK-free agent assembly. Given the precompiled, payload-independent base jar (Gradle `agentBase`), splice in a
//! payload mod: derive its `graft.properties` from `fabric.mod.json`, its `mixin-targets.txt` from the mod's `@Mixin`
//! annotations (parsed in-process with cafebabe), resolve any `payload.dependencies` from Maven over HTTP, and rezip.
//! No JDK, no Gradle at pack time — only when the base itself is built (once).

use crate::config::AgentConfig;
use crate::modinfo::ModInfo;
use std::collections::BTreeSet;
use std::io::{Read, Write};
use std::path::Path;

/// Locate the precompiled base jar (Gradle `agentBase` output) across the places a JDK-free install might keep it:
/// the repo's `build/agent/`, next to the running executable, or `~/.config/mcgraft/`.
pub fn find_base_jar(repo: Option<&Path>) -> Option<std::path::PathBuf> {
    let mut candidates: Vec<std::path::PathBuf> = Vec::new();
    if let Some(r) = repo {
        candidates.push(r.join("build/agent/mc-graft-base.jar"));
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("mc-graft-base.jar"));
        }
    }
    if let Some(cfg) = std::env::var_os("XDG_CONFIG_HOME")
        .map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".config")))
    {
        candidates.push(cfg.join("mcgraft").join("mc-graft-base.jar"));
    }
    candidates.into_iter().find(|p| p.is_file())
}

/// Build a full agent jar at `out` from `base` + `payload`. Streams progress to `log`.
pub fn pack_agent<F: FnMut(&str)>(
    base: &Path,
    payload: &Path,
    info: &ModInfo,
    cfg: &AgentConfig,
    out: &Path,
    mut log: F,
) -> Result<(), String> {
    if !base.is_file() {
        return Err(format!(
            "agent base not found: {} — build it once with `./gradlew agentBase` (needs a JDK); after that, packing is JDK-free",
            base.display()
        ));
    }
    // Resolve declared Maven dependencies first (their bytes feed dependency-mixin scanning below).
    let coords: Vec<String> = cfg
        .deps
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    let mut libs: Vec<(String, Vec<u8>)> = Vec::new();
    for c in &coords {
        log(&format!("resolving dependency {c} …"));
        let (name, bytes) = resolve_dep(c)?;
        log(&format!("  fetched {name} ({} KiB)", bytes.len() / 1024));
        libs.push((name, bytes));
    }

    log("deriving mixin targets from @Mixin annotations…");
    let mut targets: BTreeSet<String> = derive_mixin_targets(payload, &info.mixins)?.into_iter().collect();
    log(&format!("  {} payload target class(es)", targets.len()));

    // Optionally also apply the DEPENDENCY mods' own mixins (fabric-api etc.) — needed for mods that rely on
    // fabric-api's runtime hooks (custom-payload networking, etc.) that a late-attached fabric-api never installed.
    let mut dep_configs: Vec<String> = Vec::new();
    let dep_filter: Vec<String> = cfg
        .apply_dep_mixins
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    if !dep_filter.is_empty() {
        log(&format!("scanning dependency mods for mixin configs (filter: {})…", cfg.apply_dep_mixins.trim()));
        let before = targets.len();
        let (dcfgs, dtargets) = dep_mixins(payload, &libs, &dep_filter);
        dep_configs = dcfgs;
        targets.extend(dtargets);
        log(&format!(
            "  {} dependency mixin config(s), +{} target(s) (total {})",
            dep_configs.len(),
            targets.len() - before,
            targets.len()
        ));
    }
    let targets_txt = targets.into_iter().collect::<Vec<_>>().join("\n") + "\n";

    log("deriving graft.properties…");
    let props = graft_properties(payload, info, cfg, &dep_configs, &mut log);

    log("assembling agent jar…");
    assemble(base, payload, &props, &targets_txt, &libs, out)?;
    log(&format!("packed {}", out.display()));
    Ok(())
}

fn graft_properties<F: FnMut(&str)>(
    payload: &Path,
    info: &ModInfo,
    cfg: &AgentConfig,
    dep_configs: &[String],
    log: &mut F,
) -> String {
    // Bundled (jar-in-jar) dependency mods have their own Fabric entrypoints that set up state the payload needs (e.g.
    // xaerolib's XaeroLib.INSTANCE). Fabric would run them as normal mods; a late-attach must too, and BEFORE the
    // payload's — so prepend them to the entrypoint lists (deps first).
    let (jij_main, jij_client) = jij_entrypoints(payload);
    if !jij_main.is_empty() || !jij_client.is_empty() {
        log(&format!("  bundled dependency-mod entrypoints: main={jij_main:?} client={jij_client:?}"));
    }
    let main_eps: Vec<String> = jij_main.iter().chain(info.entry_main.iter()).cloned().collect();
    let client_eps: Vec<String> = jij_client.iter().chain(info.entry_client.iter()).cloned().collect();

    let mut s = String::new();
    s.push_str(&format!("# generated by mcgraft (JDK-free packer) for {}\n", info.id));
    s.push_str(&format!("payload.id={}\n", info.id));
    s.push_str(&format!("payload.accessWidener={}\n", info.access_widener.clone().unwrap_or_default()));
    s.push_str(&format!("payload.mixins={}\n", info.mixins.join(",")));
    if !dep_configs.is_empty() {
        s.push_str(&format!("payload.mixins.deps={}\n", dep_configs.join(",")));
    }
    s.push_str(&format!("payload.entrypoints.main={}\n", main_eps.join(",")));
    s.push_str(&format!("payload.entrypoints.client={}\n", client_eps.join(",")));
    let over = cfg.to_properties();
    if !over.is_empty() {
        s.push_str("# --- config ---\n");
        s.push_str(&over);
    }
    s
}

/// Collect the Fabric entrypoints (main, client) of the payload's bundled jar-in-jar dependency mods, in bundle order.
/// These are library mods (e.g. xaerolib) the payload ships inside `META-INF/jars/`; their initializers must run so the
/// payload can use them. fabric-api (a Maven `payload.dependencies` coordinate, staged separately) is intentionally not
/// scanned here — its modules initialize via their own mixins/lazily and some assume pre-startup state.
fn jij_entrypoints(payload: &Path) -> (Vec<String>, Vec<String>) {
    let mut main = Vec::new();
    let mut client = Vec::new();
    let Ok(file) = std::fs::File::open(payload) else { return (main, client) };
    let Ok(mut zip) = zip::ZipArchive::new(file) else { return (main, client) };
    let Some(fmj) = read_zip_string(&mut zip, "fabric.mod.json") else { return (main, client) };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&fmj) else { return (main, client) };
    let jar_paths: Vec<String> = v["jars"]
        .as_array()
        .map(|a| a.iter().filter_map(|j| j["file"].as_str().map(String::from)).collect())
        .unwrap_or_default();
    for jp in jar_paths {
        let Some(bytes) = read_zip_bytes(&mut zip, &jp) else { continue };
        let Ok(mut nz) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { continue };
        let Some(nj) = read_zip_string(&mut nz, "fabric.mod.json") else { continue };
        let Ok(nv) = serde_json::from_str::<serde_json::Value>(&nj) else { continue };
        for (key, out) in [("main", &mut main), ("client", &mut client)] {
            if let Some(a) = nv["entrypoints"][key].as_array() {
                for e in a {
                    // an entrypoint is either a bare "class.Name" or an object { "value": "...", "adapter": "..." }
                    let ep = e.as_str().map(String::from).or_else(|| e["value"].as_str().map(String::from));
                    if let Some(ep) = ep {
                        if !out.contains(&ep) {
                            out.push(ep);
                        }
                    }
                }
            }
        }
    }
    (main, client)
}

/// Read each mixin config JSON from the payload jar, then parse each declared mixin class's `@Mixin` to collect targets.
fn derive_mixin_targets(payload: &Path, configs: &[String]) -> Result<Vec<String>, String> {
    let file = std::fs::File::open(payload).map_err(|e| format!("open {}: {e}", payload.display()))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| format!("payload not a jar: {e}"))?;
    let mut out: BTreeSet<String> = BTreeSet::new();
    for cfg in configs {
        let Some(json) = read_zip_string(&mut zip, cfg) else { continue };
        targets_from_config(&mut zip, &json, &mut out);
    }
    Ok(out.into_iter().collect())
}

/// Given a mixin config JSON and the jar it lives in, add every `@Mixin` target (internal name) to `out`. The mixin
/// classes named by the config's mixins/client/server arrays are read from the same jar.
fn targets_from_config<R: Read + std::io::Seek>(zip: &mut zip::ZipArchive<R>, json: &str, out: &mut BTreeSet<String>) {
    let Ok(v) = serde_json::from_str::<serde_json::Value>(json) else { return };
    let Some(pkg) = v["package"].as_str().map(|p| p.replace('.', "/")) else { return };
    let mut classes: Vec<String> = Vec::new();
    for key in ["mixins", "client", "server"] {
        if let Some(a) = v[key].as_array() {
            for m in a {
                if let Some(s) = m.as_str() {
                    classes.push(s.to_string());
                }
            }
        }
    }
    for mn in classes {
        let path = format!("{}/{}.class", pkg, mn.replace('.', "/"));
        let Some(bytes) = read_zip_bytes(zip, &path) else { continue };
        for t in mixin_targets_of(&bytes) {
            out.insert(t);
        }
    }
}

/// Discover the mixin configs (and their `@Mixin` targets) of the payload's DEPENDENCY mods, so a late-attached
/// fabric-api (and any bundled library mod) gets its own mixins applied to the already-loaded MC classes it would
/// normally patch at startup. Scans the payload's bundled JiJ mods and every resolved Maven dependency jar, recursing
/// into their own bundled JiJ modules (fabric-api ships its modules that way). Server-only configs are skipped.
/// Returns (config resource names, target internal names).
fn dep_mixins(payload: &Path, dep_jars: &[(String, Vec<u8>)], filter: &[String]) -> (Vec<String>, BTreeSet<String>) {
    let mut configs: Vec<String> = Vec::new();
    let mut targets: BTreeSet<String> = BTreeSet::new();
    // the payload's own bundled JiJ mods (e.g. xaerolib)
    if let Ok(bytes) = std::fs::read(payload) {
        for nested in jij_jars(&bytes) {
            scan_jar_mixins(&nested, filter, &mut configs, &mut targets, 0);
        }
    }
    // resolved Maven dependency jars (e.g. fabric-api, which bundles its modules as JiJ)
    for (_, bytes) in dep_jars {
        scan_jar_mixins(bytes, filter, &mut configs, &mut targets, 0);
    }
    (configs, targets)
}

/// True if a config resource name passes the filter: `["*"]` matches everything, otherwise the name must contain one
/// of the (case-insensitive) substring tokens (e.g. "networking").
fn config_included(name: &str, filter: &[String]) -> bool {
    filter.iter().any(|t| t == "*") || {
        let lower = name.to_ascii_lowercase();
        filter.iter().any(|t| lower.contains(&t.to_ascii_lowercase()))
    }
}

/// Return the raw bytes of every `META-INF/jars/*.jar` bundled in `jar_bytes` (one level).
fn jij_jars(jar_bytes: &[u8]) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    let Ok(mut zip) = zip::ZipArchive::new(std::io::Cursor::new(jar_bytes.to_vec())) else { return out };
    let names: Vec<String> = (0..zip.len())
        .filter_map(|i| zip.by_index(i).ok().map(|e| e.name().to_string()))
        .filter(|n| n.starts_with("META-INF/jars/") && n.ends_with(".jar"))
        .collect();
    for n in names {
        if let Some(b) = read_zip_bytes(&mut zip, &n) {
            out.push(b);
        }
    }
    out
}

/// Scan one dependency jar for mixin configs it declares (from fabric.mod.json `mixins`, non-server) + their targets,
/// then recurse into its bundled JiJ modules. Bounded recursion depth guards against pathological nesting.
fn scan_jar_mixins(jar_bytes: &[u8], filter: &[String], configs: &mut Vec<String>, targets: &mut BTreeSet<String>, depth: u32) {
    if depth > 4 {
        return;
    }
    let Ok(mut zip) = zip::ZipArchive::new(std::io::Cursor::new(jar_bytes.to_vec())) else { return };
    if let Some(fmj) = read_zip_string(&mut zip, "fabric.mod.json") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&fmj) {
            if let Some(arr) = v["mixins"].as_array() {
                for m in arr {
                    // a mixins entry is a bare "cfg.mixins.json" or { "config": "...", "environment": "client|common|server|*" }
                    let name = m.as_str().map(String::from).or_else(|| {
                        let env = m["environment"].as_str().unwrap_or("*");
                        (env != "server").then(|| m["config"].as_str().map(String::from)).flatten()
                    });
                    if let Some(name) = name {
                        if !config_included(&name, filter) {
                            continue;
                        }
                        if !configs.contains(&name) {
                            configs.push(name.clone());
                        }
                        if let Some(json) = read_zip_string(&mut zip, &name) {
                            targets_from_config(&mut zip, &json, targets);
                        }
                    }
                }
            }
        }
    }
    for nested in jij_jars(jar_bytes) {
        scan_jar_mixins(&nested, filter, configs, targets, depth + 1);
    }
}

/// Extract `@Mixin(value=…, targets=…)` targets (as internal names) from a mixin class's bytecode.
fn mixin_targets_of(bytes: &[u8]) -> Vec<String> {
    use cafebabe::attributes::{AnnotationElementValue as V, AttributeData};
    let Ok(class) = cafebabe::parse_class(bytes) else { return Vec::new() };
    let mut targets = Vec::new();
    for attr in &class.attributes {
        let anns = match &attr.data {
            AttributeData::RuntimeInvisibleAnnotations(a) | AttributeData::RuntimeVisibleAnnotations(a) => a,
            _ => continue,
        };
        for ann in anns {
            if ann.type_descriptor.to_string() != "Lorg/spongepowered/asm/mixin/Mixin;" {
                continue;
            }
            for el in &ann.elements {
                match (el.name.as_ref(), &el.value) {
                    ("value", V::ArrayValue(items)) => {
                        for it in items {
                            if let V::ClassLiteral { class_name } = it {
                                targets.push(desc_to_internal(class_name));
                            }
                        }
                    }
                    ("targets", V::ArrayValue(items)) => {
                        for it in items {
                            if let V::StringConstant(s) = it {
                                targets.push(s.replace('.', "/"));
                            }
                        }
                    }
                    _ => {}
                }
            }
        }
    }
    targets
}

/// "Lnet/minecraft/Foo;" -> "net/minecraft/Foo".
fn desc_to_internal(d: &str) -> String {
    d.trim_start_matches('L').trim_end_matches(';').to_string()
}

fn read_zip_bytes<R: Read + std::io::Seek>(zip: &mut zip::ZipArchive<R>, name: &str) -> Option<Vec<u8>> {
    let mut e = zip.by_name(name).ok()?;
    let mut buf = Vec::new();
    e.read_to_end(&mut buf).ok()?;
    Some(buf)
}
fn read_zip_string<R: Read + std::io::Seek>(zip: &mut zip::ZipArchive<R>, name: &str) -> Option<String> {
    read_zip_bytes(zip, name).and_then(|b| String::from_utf8(b).ok())
}

/// Resolve a Maven coordinate `group:artifact:version` (or `maven.modrinth:slug:version`) to (filename, jar bytes) by
/// trying the repos mc-graft's build uses. Non-transitive (just the declared coordinate), like the Gradle build.
fn resolve_dep(coord: &str) -> Result<(String, Vec<u8>), String> {
    let parts: Vec<&str> = coord.split(':').collect();
    if parts.len() < 3 {
        return Err(format!("bad Maven coordinate (need group:artifact:version): {coord}"));
    }
    let (group, artifact, version) = (parts[0], parts[1], parts[2]);
    let filename = format!("{artifact}-{version}.jar");
    let rel = format!("{}/{}/{}/{}", group.replace('.', "/"), artifact, version, filename);
    let repos = [
        "https://repo1.maven.org/maven2",
        "https://maven.fabricmc.net",
        "https://api.modrinth.com/maven",
        "https://repo.spongepowered.org/maven",
    ];
    let mut last = String::new();
    for r in repos {
        let url = format!("{r}/{rel}");
        match ureq::get(&url).call() {
            Ok(resp) => {
                let mut buf = Vec::new();
                resp.into_reader().read_to_end(&mut buf).map_err(|e| format!("download {url}: {e}"))?;
                if !buf.is_empty() {
                    return Ok((filename, buf));
                }
            }
            Err(e) => last = format!("{url}: {e}"),
        }
    }
    Err(format!("could not resolve {coord} from any known repo (last: {last})"))
}

fn assemble(
    base: &Path,
    payload: &Path,
    props: &str,
    targets: &str,
    libs: &[(String, Vec<u8>)],
    out: &Path,
) -> Result<(), String> {
    let base_file = std::fs::File::open(base).map_err(|e| format!("open base {}: {e}", base.display()))?;
    let mut zin = zip::ZipArchive::new(base_file).map_err(|e| format!("base not a jar: {e}"))?;
    let out_file = std::fs::File::create(out).map_err(|e| format!("create {}: {e}", out.display()))?;
    let mut zout = zip::ZipWriter::new(out_file);
    let opts: zip::write::SimpleFileOptions =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);

    for i in 0..zin.len() {
        let mut e = zin.by_index(i).map_err(|err| format!("read base entry {i}: {err}"))?;
        let name = e.name().to_string();
        if name.ends_with('/') {
            zout.add_directory(name, opts).map_err(|err| err.to_string())?;
            continue;
        }
        let mut buf = Vec::new();
        e.read_to_end(&mut buf).map_err(|err| err.to_string())?;
        zout.start_file(name, opts).map_err(|err| err.to_string())?;
        zout.write_all(&buf).map_err(|err| err.to_string())?;
    }

    let mut add = |name: &str, bytes: &[u8]| -> Result<(), String> {
        zout.start_file(name, opts).map_err(|e| e.to_string())?;
        zout.write_all(bytes).map_err(|e| e.to_string())
    };
    add("graft.properties", props.as_bytes())?;
    add("mixin-targets.txt", targets.as_bytes())?;
    let payload_bytes = std::fs::read(payload).map_err(|e| format!("read payload {}: {e}", payload.display()))?;
    add("agent-libs/payload.jar", &payload_bytes)?;
    for (n, b) in libs {
        add(&format!("agent-libs/{n}"), b)?;
    }
    zout.finish().map_err(|e| e.to_string())?;
    Ok(())
}
