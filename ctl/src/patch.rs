//! Payload patches (v1): user-supplied edits applied to the mod jar BEFORE grafting, entirely at pack time in Rust.
//!
//! Scope: file **overlay** (add/replace), **delete**, **rename**, JSON **merge** (RFC 7396) and **jsonpatch**
//! (RFC 6902) on resource entries such as `fabric.mod.json`, whole-class Krakatau assembly (`new/`, bare `.j`), and
//! in-place bytecode edits via the `ops` DSL (`[[patch]] format = "ops"`, see [`crate::bytecode`]). Only the
//! `krakatau-diff` `[[patch]]` format remains deferred; it is parsed and rejected with a clear message rather than
//! silently ignored.
//!
//! Patches are applied to the payload jar's contents and produce a rewritten temp jar; everything downstream (mixin
//! target derivation, graft.properties, dependency scan) then reads the PATCHED jar, so a `[[merge]]` on `fabric.mod.json`
//! correctly moves the pipeline's inputs.
//!
//! Input modes (auto-detected per `--patch`):
//!   * bundle  — a directory or `.mcpatch` zip containing `manifest.toml`
//!   * overlay — a bare `.jar`/`.zip` with no manifest: every entry is overlaid

use serde::Deserialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

// ----------------------------------------------------------------------------------------------- manifest schema

#[derive(Deserialize, Default)]
#[serde(deny_unknown_fields)]
struct Manifest {
    #[serde(default)]
    bundle: BundleMeta,
    #[serde(default)]
    apply: ApplyCfg,
    #[serde(default)]
    target: TargetGuard,
    #[serde(default)]
    delete: Vec<DeleteOp>,
    #[serde(default)]
    rename: Vec<RenameOp>,
    #[serde(default)]
    merge: Vec<MergeOp>,
    #[serde(default)]
    patch: Vec<PatchEntry>,
}

/// An in-place edit to an existing class. `format = "ops"` (the structured DSL) is supported; `krakatau-diff` is
/// deferred. `class` is an internal name ("com/example/Foo").
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PatchEntry {
    class: String,
    #[serde(default = "default_patch_format")]
    format: String,
    #[serde(default)]
    op: Vec<OpSpec>,
    // Only meaningful for the (deferred) `krakatau-diff` format; accepted here so such a manifest parses and reaches the
    // friendly "deferred" message rather than an opaque unknown-field error.
    #[serde(default)]
    #[allow(dead_code)]
    file: Option<String>,
}
fn default_patch_format() -> String {
    "ops".to_string()
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct OpSpec {
    action: String,
    #[serde(rename = "in", default)]
    in_method: Option<String>, // "name(desc)" — restrict to one method; absent = all
    #[serde(default)]
    from: Option<toml::Value>,
    #[serde(default)]
    to: Option<toml::Value>,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
struct BundleMeta {
    #[serde(default)]
    name: String,
    #[serde(default)]
    format: u32,
}

// Container-level `default` (not just on Manifest::apply, which only fires when the whole table is absent) so a
// PARTIAL [apply] table parses. Otherwise `[apply]\non-conflict = "patch-wins"` dies with "missing field
// verify-target" — and the natural way out of that error is to write `verify-target = false`, i.e. the failure mode
// nudges users into disabling the base guard.
#[derive(Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields, default)]
struct ApplyCfg {
    on_conflict: OnConflict,
    verify_target: bool,
}
impl Default for ApplyCfg {
    fn default() -> Self {
        ApplyCfg { on_conflict: OnConflict::Error, verify_target: true }
    }
}

#[derive(Deserialize, Clone, Copy, PartialEq)]
#[serde(rename_all = "kebab-case")]
enum OnConflict {
    Error,
    OverlayWins,
    PatchWins,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
struct TargetGuard {
    #[serde(rename = "mod")]
    mod_id: Option<String>,
    sha256: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DeleteOp {
    path: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RenameOp {
    from: String,
    to: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct MergeOp {
    path: String,
    file: String,
    #[serde(default = "default_merge_kind")]
    kind: String,
}
fn default_merge_kind() -> String {
    "merge".to_string()
}

// ----------------------------------------------------------------------------------------------- jar model

enum Entry {
    Dir,
    File(Vec<u8>),
}

/// In-memory jar: ordered entries, so a rewrite preserves the original layout as closely as possible.
struct Jar {
    order: Vec<String>,
    map: HashMap<String, Entry>,
}

impl Jar {
    fn read(bytes: &[u8]) -> Result<Jar, String> {
        let mut zip = zip::ZipArchive::new(Cursor::new(bytes.to_vec()))
            .map_err(|e| format!("payload is not a valid jar/zip: {e}"))?;
        let mut order = Vec::new();
        let mut map = HashMap::new();
        for i in 0..zip.len() {
            let mut e = zip.by_index(i).map_err(|e| format!("read jar entry: {e}"))?;
            let name = e.name().to_string();
            let duplicate = map.contains_key(&name); // O(1); scanning `order` would be quadratic on a real mod jar
            if e.is_dir() {
                map.insert(name.clone(), Entry::Dir);
            } else {
                // with_capacity from the DECLARED uncompressed size would let a crafted jar claim 4 GiB and make us
                // allocate it before reading a byte; cap the hint and let the Vec grow honestly if it's really that big.
                let mut buf = Vec::with_capacity((e.size() as usize).min(1 << 22));
                e.read_to_end(&mut buf).map_err(|e| format!("read jar entry: {e}"))?;
                map.insert(name.clone(), Entry::File(buf));
            }
            // Duplicate entry names are legal ZIP and do occur in shaded/repacked mod jars. `map` keeps the last, but
            // pushing the name twice would make Jar::write call start_file twice and fail with a bare "Duplicate
            // filename" that names the entry and not the cause. Keep first-seen order, write once.
            if !duplicate {
                order.push(name);
            }
        }
        Ok(Jar { order, map })
    }

    fn write(&self) -> Result<Vec<u8>, String> {
        let mut out = Vec::new();
        {
            let mut zw = zip::ZipWriter::new(Cursor::new(&mut out));
            let opts = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);
            for name in &self.order {
                match self.map.get(name) {
                    Some(Entry::Dir) => zw.add_directory(name, opts).map_err(|e| format!("write dir {name}: {e}"))?,
                    Some(Entry::File(b)) => {
                        zw.start_file(name, opts).map_err(|e| format!("write {name}: {e}"))?;
                        zw.write_all(b).map_err(|e| format!("write {name}: {e}"))?;
                    }
                    None => {} // removed by a delete op; skip
                }
            }
            zw.finish().map_err(|e| format!("finalize patched jar: {e}"))?;
        }
        Ok(out)
    }

    fn file(&self, path: &str) -> Option<&[u8]> {
        match self.map.get(path) {
            Some(Entry::File(b)) => Some(b),
            _ => None,
        }
    }

    /// Add or replace a file entry (preserving position if it already exists, else appending).
    fn put(&mut self, path: &str, bytes: Vec<u8>) {
        if !self.map.contains_key(path) {
            self.order.push(path.to_string());
        }
        self.map.insert(path.to_string(), Entry::File(bytes));
    }

    fn remove(&mut self, path: &str) -> bool {
        let existed = self.map.remove(path).is_some();
        self.order.retain(|n| n != path);
        existed
    }
}

// ----------------------------------------------------------------------------------------------- bundle source

/// A patch's backing store — a directory tree or the entries of a zip — presented uniformly.
enum Source {
    Dir(PathBuf),
    Zip(HashMap<String, Vec<u8>>),
}

impl Source {
    fn read(&self, rel: &str) -> Option<Vec<u8>> {
        // Sanitise here rather than per-caller: most callers pass a fixed or list_under-derived path, but a few take a
        // free-form string straight from the untrusted manifest (`[[merge]] file`). Path::join REPLACES the root when
        // given an absolute component, so `file = "/home/you/.ssh/id_rsa"` would read that file and merge it into the
        // shipped jar; `../..` would escape the bundle. Only the Dir arm can traverse (Zip is a HashMap lookup), but
        // the check is cheap and belongs on every path.
        if reject_unsafe_entry(rel).is_err() {
            return None;
        }
        match self {
            Source::Dir(root) => std::fs::read(root.join(rel)).ok(),
            Source::Zip(m) => m.get(rel).cloned(),
        }
    }

    /// Relative paths of the FILES under `prefix/` (recursively), prefix stripped.
    fn list_under(&self, prefix: &str) -> Vec<String> {
        let mut out = Vec::new();
        match self {
            Source::Dir(root) => walk_dir(&root.join(prefix), &mut |rel| out.push(rel), root.join(prefix).as_path()),
            Source::Zip(m) => {
                let p = format!("{}/", prefix.trim_end_matches('/'));
                for k in m.keys() {
                    if let Some(rest) = k.strip_prefix(&p) {
                        if !rest.is_empty() && !k.ends_with('/') {
                            out.push(rest.to_string());
                        }
                    }
                }
            }
        }
        out.sort();
        out
    }
}

fn walk_dir(dir: &Path, f: &mut dyn FnMut(String), base: &Path) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            walk_dir(&p, f, base);
        } else if let Ok(rel) = p.strip_prefix(base) {
            f(rel.to_string_lossy().replace('\\', "/"));
        }
    }
}

// ----------------------------------------------------------------------------------------------- entry point

pub struct Options {
    /// CLI `--no-verify-base`: force the base guard off for every patch this run (overrides each bundle's setting).
    pub no_verify_base: bool,
}

/// Apply `patches` (in order) to `payload`, writing the result to a private temp jar. Returns the `NamedTempFile`
/// (whose `.path()` the caller feeds to the pack pipeline) — it deletes itself when dropped. `Ok(None)` when `patches`
/// is empty (caller uses the original payload).
pub fn apply_patches<F: FnMut(&str)>(
    payload: &Path,
    patches: &[PathBuf],
    opts: &Options,
    mut log: F,
) -> Result<Option<tempfile::NamedTempFile>, String> {
    if patches.is_empty() {
        return Ok(None);
    }
    let payload_bytes = std::fs::read(payload).map_err(|e| format!("{}: {e}", payload.display()))?;
    let base_sha = hex(&Sha256::digest(&payload_bytes));
    let base_mod_id = read_mod_id(&payload_bytes);
    let mut jar = Jar::read(&payload_bytes)?;

    // Patching rewrites entries, so the per-entry digests in META-INF/MANIFEST.MF no longer match while the signature
    // files survive — the jar looks signed but fails verification. Say so once, rather than letting it surface as an
    // opaque SecurityException later.
    if jar.order.iter().any(|n| {
        let up = n.to_ascii_uppercase();
        up.starts_with("META-INF/") && (up.ends_with(".SF") || up.ends_with(".RSA") || up.ends_with(".DSA") || up.ends_with(".EC"))
    }) {
        log("  WARNING: payload jar is signed — patching invalidates its signature (signature files are left in place)");
    }

    // Cross-patch conflict ledger: path -> name of the patch that last wrote it. A second patch touching the same path
    // is a conflict resolved per that bundle's on-conflict policy.
    let mut written: HashMap<String, String> = HashMap::new();

    for (i, p) in patches.iter().enumerate() {
        // The ledger identifies a patch by this string, and `claim` only reports a conflict when it CHANGES. Using the
        // bare file name meant `--patch a/fix.mcpatch --patch b/fix.mcpatch` compared equal, so two unrelated bundles
        // could clobber each other with conflict detection effectively off. Prefix the index to keep it unique.
        let label = p.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| p.display().to_string());
        let name = format!("#{} {}", i + 1, label);
        log(&format!("applying patch: {label}"));
        // Bare Krakatau: a single .j file assembles straight into the jar (add/replace by declared class name).
        if p.is_file() && is_j_file(p) {
            let jb = std::fs::read(p).map_err(|e| format!("{}: {e}", p.display()))?;
            apply_assembled(&mut jar, &jb, &name, OnConflict::Error, &mut written, &mut log)?;
            continue;
        }
        let src = open_source(p)?;
        apply_one(&mut jar, &src, &name, &base_sha, base_mod_id.as_deref(), opts, &mut written, &mut log)?;
    }

    let out_bytes = jar.write()?;
    let mut tmp = tempfile::Builder::new()
        .prefix("mcgraft-patched-")
        .suffix(".jar")
        .tempfile()
        .map_err(|e| format!("create temp jar: {e}"))?;
    tmp.write_all(&out_bytes).map_err(|e| format!("write patched jar: {e}"))?;
    tmp.flush().ok();
    log(&format!("patched payload → {} ({} KiB)", tmp.path().display(), out_bytes.len() / 1024));
    Ok(Some(tmp))
}

fn open_source(p: &Path) -> Result<Source, String> {
    if p.is_dir() {
        return Ok(Source::Dir(p.to_path_buf()));
    }
    // A file: read as a zip (either an .mcpatch bundle, or a bare overlay jar/zip).
    let bytes = std::fs::read(p).map_err(|e| format!("{}: {e}", p.display()))?;
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|e| format!("{}: not a directory or zip ({e})", p.display()))?;
    let mut m = HashMap::new();
    for i in 0..zip.len() {
        let mut e = zip.by_index(i).map_err(|e| format!("read patch entry: {e}"))?;
        if e.is_dir() {
            continue;
        }
        let n = e.name().to_string();
        let mut b = Vec::new();
        e.read_to_end(&mut b).map_err(|e| format!("read patch entry: {e}"))?;
        m.insert(n, b);
    }
    Ok(Source::Zip(m))
}

#[allow(clippy::too_many_arguments)]
fn apply_one<F: FnMut(&str)>(
    jar: &mut Jar,
    src: &Source,
    patch_name: &str,
    base_sha: &str,
    base_mod_id: Option<&str>,
    opts: &Options,
    written: &mut HashMap<String, String>,
    log: &mut F,
) -> Result<(), String> {
    // Detect bundle (has manifest.toml) vs bare overlay (no manifest -> overlay every entry).
    let manifest_bytes = src.read("manifest.toml");
    let man: Manifest = match &manifest_bytes {
        Some(b) => {
            let text = std::str::from_utf8(b).map_err(|_| "manifest.toml is not valid UTF-8".to_string())?;
            toml::from_str(text).map_err(|e| format!("manifest.toml: {e}"))?
        }
        None => Manifest::default(), // bare overlay
    };

    if man.bundle.format > 1 {
        return Err(format!("patch {patch_name}: manifest format {} is newer than this packer supports (1)", man.bundle.format));
    }
    if !man.bundle.name.is_empty() {
        log(&format!("  bundle: {}", man.bundle.name));
    }

    // Base guard (skipped for a bare overlay, which has no [target]).
    if manifest_bytes.is_some() && man.apply.verify_target && !opts.no_verify_base {
        if let Some(want) = &man.target.sha256 {
            if !want.eq_ignore_ascii_case(base_sha) {
                return Err(format!(
                    "patch {patch_name}: base-jar sha256 mismatch (manifest wants {want}, payload is {base_sha}); \
                     this patch was built against a different jar. Re-target it, or pass --no-verify-base to override"
                ));
            }
        }
        if let Some(want) = &man.target.mod_id {
            match base_mod_id {
                Some(got) if got == want => {}
                Some(got) => log(&format!("  WARNING: patch {patch_name} targets mod '{want}' but payload is '{got}'")),
                None => log(&format!("  WARNING: patch {patch_name} targets mod '{want}' but payload has no fabric.mod.json id")),
            }
        }
    }

    // Ordered pipeline: delete -> rename -> overlay -> new (assemble) -> merge.
    for d in &man.delete {
        // Deleting an entry another patch wrote destroys its contribution just as surely as overwriting it, so it
        // must go through the same ledger — otherwise "patch A overlays x.png, patch B deletes it" is silent.
        claim(written, &d.path, patch_name, man.apply.on_conflict, log)?;
        if jar.remove(&d.path) {
            log(&format!("  delete {}", d.path));
        } else {
            log(&format!("  delete {} (not present — skipped)", d.path));
        }
    }

    for r in &man.rename {
        let bytes = jar.file(&r.from).map(|b| b.to_vec());
        match bytes {
            None => return Err(format!("patch {patch_name}: rename source '{}' not found in payload", r.from)),
            Some(_) if jar.file(&r.to).is_some() => {
                return Err(format!("patch {patch_name}: rename target '{}' already exists", r.to));
            }
            Some(b) => {
                reject_unsafe_entry(&r.to)?;
                // Claim BOTH ends: the source disappears (destroying any earlier patch's write to it) and the target
                // appears. Claiming only the target let "A writes a.json, B renames a.json -> b.json" pass silently.
                claim(written, &r.from, patch_name, man.apply.on_conflict, log)?;
                claim(written, &r.to, patch_name, man.apply.on_conflict, log)?;
                jar.remove(&r.from);
                jar.put(&r.to, b);
                log(&format!("  rename {} -> {}", r.from, r.to));
            }
        }
    }

    // overlay/: every file under it replaces (or adds) the same jar path.
    for rel in src.list_under("overlay") {
        let Some(bytes) = src.read(&format!("overlay/{rel}")) else { continue };
        reject_unsafe_entry(&rel)?;
        claim(written, &rel, patch_name, man.apply.on_conflict, log)?;
        let verb = if jar.file(&rel).is_some() { "replace" } else { "add" };
        jar.put(&rel, bytes);
        log(&format!("  overlay {verb} {rel}"));
    }
    // A bare overlay (no manifest): overlay ALL entries. For a zip, entries go in verbatim; for a directory, a loose
    // `.j` file is assembled (bare Krakatau) and everything else is overlaid.
    if manifest_bytes.is_none() {
        match src {
            Source::Zip(m) => {
                let mut keys: Vec<&String> = m.keys().collect();
                keys.sort();
                for k in keys {
                    reject_unsafe_entry(k)?;
                    claim(written, k, patch_name, man.apply.on_conflict, log)?;
                    let verb = if jar.file(k).is_some() { "replace" } else { "add" };
                    jar.put(k, m[k].clone());
                    log(&format!("  overlay {verb} {k}"));
                }
            }
            Source::Dir(_) => {
                for rel in src.list_under("") {
                    let bytes = src.read(&rel).unwrap_or_default();
                    reject_unsafe_entry(&rel)?;
                    if rel.ends_with(".j") {
                        apply_assembled(jar, &bytes, patch_name, man.apply.on_conflict, written, log)?;
                    } else {
                        claim(written, &rel, patch_name, man.apply.on_conflict, log)?;
                        let verb = if jar.file(&rel).is_some() { "replace" } else { "add" };
                        jar.put(&rel, bytes);
                        log(&format!("  overlay {verb} {rel}"));
                    }
                }
            }
        }
    }

    // new/: assemble each Krakatau .j into its declared class(es) and add/replace them.
    for rel in src.list_under("new") {
        if !rel.ends_with(".j") {
            continue;
        }
        let jb = src
            .read(&format!("new/{rel}"))
            .ok_or_else(|| format!("patch {patch_name}: cannot read new/{rel}"))?;
        apply_assembled(jar, &jb, patch_name, man.apply.on_conflict, written, log)?;
    }

    for mg in &man.merge {
        let target = jar
            .file(&mg.path)
            .ok_or_else(|| format!("patch {patch_name}: merge target '{}' not found in payload", mg.path))?;
        let patch_doc = src
            .read(&mg.file)
            .ok_or_else(|| format!("patch {patch_name}: merge file '{}' not found in bundle", mg.file))?;
        let mut doc: Value = serde_json::from_slice(target)
            .map_err(|e| format!("patch {patch_name}: merge target '{}' is not valid JSON: {e}", mg.path))?;
        match mg.kind.as_str() {
            "merge" => {
                // RFC 7396 JSON Merge Patch.
                let overlay: Value = serde_json::from_slice(&patch_doc)
                    .map_err(|e| format!("patch {patch_name}: merge file '{}' is not valid JSON: {e}", mg.file))?;
                json_merge(&mut doc, &overlay);
            }
            "jsonpatch" => {
                // RFC 6902 JSON Patch (an array of add/remove/replace/move/copy/test ops).
                let p: json_patch::Patch = serde_json::from_slice(&patch_doc).map_err(|e| {
                    format!("patch {patch_name}: jsonpatch file '{}' is not a valid RFC-6902 patch: {e}", mg.file)
                })?;
                json_patch::patch(&mut doc, &p)
                    .map_err(|e| format!("patch {patch_name}: applying jsonpatch '{}': {e}", mg.file))?;
            }
            other => return Err(format!("patch {patch_name}: merge kind '{other}' unknown (expected \"merge\" or \"jsonpatch\")")),
        }
        let rendered = serde_json::to_vec_pretty(&doc).map_err(|e| format!("re-serialize {}: {e}", mg.path))?;
        claim(written, &mg.path, patch_name, man.apply.on_conflict, log)?;
        jar.put(&mg.path, rendered);
        log(&format!("  {} {} <- {}", mg.kind, mg.path, mg.file));
    }

    // [[patch]]: in-place bytecode edits on an existing class (last, so it sees any overlaid/new class).
    for pe in &man.patch {
        match pe.format.as_str() {
            "ops" => {
                let path = format!("{}.class", pe.class);
                let cur = jar
                    .file(&path)
                    .ok_or_else(|| format!("patch {patch_name}: [[patch]] class '{}' not found in payload", pe.class))?
                    .to_vec();
                let ops = parse_ops(&pe.op, patch_name)?;
                let new_bytes = crate::bytecode::apply_ops(&cur, &ops)
                    .map_err(|e| format!("patch {patch_name}: {} ops: {e}", pe.class))?;
                claim(written, &path, patch_name, man.apply.on_conflict, log)?;
                jar.put(&path, new_bytes);
                log(&format!("  patch(ops) {} — {} op(s)", pe.class, pe.op.len()));
            }
            "krakatau-diff" => {
                return Err(format!(
                    "patch {patch_name}: [[patch]] format 'krakatau-diff' (bytecode diff) is deferred — use format = \"ops\""
                ));
            }
            other => return Err(format!("patch {patch_name}: unknown [[patch]] format '{other}' (expected \"ops\")")),
        }
    }

    Ok(())
}

/// Translate manifest `[[patch.op]]` entries into `bytecode::Op`s.
fn parse_ops(specs: &[OpSpec], patch_name: &str) -> Result<Vec<crate::bytecode::Op>, String> {
    use crate::bytecode::{Op, OpKind};
    let mut out = Vec::new();
    for s in specs {
        let in_method = match &s.in_method {
            Some(sig) => Some(split_method_sig(sig).ok_or_else(|| format!("patch {patch_name}: bad `in` method sig '{sig}' (expected name(desc))"))?),
            None => None,
        };
        let kind = match s.action.as_str() {
            "replace_const" => {
                let from = s.from.as_ref().ok_or_else(|| format!("patch {patch_name}: replace_const needs `from`"))?;
                let to = s.to.as_ref().ok_or_else(|| format!("patch {patch_name}: replace_const needs `to`"))?;
                match (from, to) {
                    (toml::Value::Integer(a), toml::Value::Integer(b)) => OpKind::ReplaceConstInt {
                        from: i32::try_from(*a).map_err(|_| format!("patch {patch_name}: replace_const int out of range"))?,
                        to: i32::try_from(*b).map_err(|_| format!("patch {patch_name}: replace_const int out of range"))?,
                    },
                    (toml::Value::String(a), toml::Value::String(b)) => OpKind::ReplaceConstStr { from: a.clone(), to: b.clone() },
                    _ => return Err(format!("patch {patch_name}: replace_const `from`/`to` must both be int or both string")),
                }
            }
            "redirect_invoke" => {
                let from = s.from.as_ref().and_then(|v| v.as_str()).ok_or_else(|| format!("patch {patch_name}: redirect_invoke `from` must be \"owner.name(desc)\""))?;
                let to = s.to.as_ref().and_then(|v| v.as_str()).ok_or_else(|| format!("patch {patch_name}: redirect_invoke `to` must be \"owner.name\""))?;
                let (o, n, d) = split_owner_name_desc(from).ok_or_else(|| format!("patch {patch_name}: bad redirect_invoke `from` '{from}'"))?;
                let (o2, n2) = split_owner_name(to).ok_or_else(|| format!("patch {patch_name}: bad redirect_invoke `to` '{to}'"))?;
                OpKind::RedirectInvoke { from: (o, n, d), to: (o2, n2) }
            }
            "set_return" => {
                if in_method.is_none() {
                    return Err(format!("patch {patch_name}: set_return requires `in` (the method whose body to replace)"));
                }
                let value = match &s.to {
                    None => None,
                    Some(toml::Value::Integer(i)) => Some(crate::bytecode::RetVal::Int(*i)),
                    Some(toml::Value::Float(f)) => Some(crate::bytecode::RetVal::Float(*f)),
                    Some(toml::Value::Boolean(b)) => Some(crate::bytecode::RetVal::Int(i64::from(*b))),
                    Some(toml::Value::String(s)) if s == "null" => Some(crate::bytecode::RetVal::Null),
                    Some(toml::Value::String(s)) => Some(crate::bytecode::RetVal::Str(s.clone())),
                    Some(_) => return Err(format!("patch {patch_name}: set_return `to` must be int/float/bool/string/\"null\"")),
                };
                OpKind::SetReturn { value }
            }
            other => return Err(format!("patch {patch_name}: unknown op action '{other}' (expected replace_const | redirect_invoke | set_return)")),
        };
        out.push(Op { in_method, kind });
    }
    Ok(out)
}

/// "name(desc)" -> (name, "(desc)").
fn split_method_sig(s: &str) -> Option<(String, String)> {
    let p = s.find('(')?;
    Some((s[..p].to_string(), s[p..].to_string()))
}
/// "owner/pkg/Class.name(desc)" -> (owner, name, "(desc)").
fn split_owner_name_desc(s: &str) -> Option<(String, String, String)> {
    let p = s.find('(')?;
    let (lhs, desc) = (&s[..p], &s[p..]);
    let dot = lhs.rfind('.')?;
    Some((lhs[..dot].to_string(), lhs[dot + 1..].to_string(), desc.to_string()))
}
/// "owner/pkg/Class.name" -> (owner, name).
fn split_owner_name(s: &str) -> Option<(String, String)> {
    let dot = s.rfind('.')?;
    Some((s[..dot].to_string(), s[dot + 1..].to_string()))
}

/// Record that `patch_name` wrote `path`; enforce the cross-patch conflict policy if another patch already wrote it.
fn claim<F: FnMut(&str)>(
    written: &mut HashMap<String, String>,
    path: &str,
    patch_name: &str,
    policy: OnConflict,
    log: &mut F,
) -> Result<(), String> {
    if let Some(prev) = written.get(path) {
        if prev != patch_name {
            match policy {
                OnConflict::Error => {
                    return Err(format!(
                        "conflict: '{path}' written by both '{prev}' and '{patch_name}' (set [apply].on-conflict to overlay-wins/patch-wins to allow)"
                    ));
                }
                _ => log(&format!("  note: '{path}' also written by '{prev}' — last write wins")),
            }
        }
    }
    written.insert(path.to_string(), patch_name.to_string());
    Ok(())
}

// ----------------------------------------------------------------------------------------------- helpers

/// RFC 7396 JSON Merge Patch.
fn json_merge(target: &mut Value, patch: &Value) {
    match patch {
        Value::Object(pm) => {
            if !target.is_object() {
                *target = Value::Object(serde_json::Map::new());
            }
            let tm = target.as_object_mut().unwrap();
            for (k, v) in pm {
                if v.is_null() {
                    tm.remove(k);
                } else {
                    json_merge(tm.entry(k.clone()).or_insert(Value::Null), v);
                }
            }
        }
        _ => *target = patch.clone(),
    }
}

// ----------------------------------------------------------------------------------------------- Krakatau assembly

/// Locate the `krak2` binary: `MCGRAFT_KRAK2` override, else the embedded copy (if this build bundled one), else `krak2`
/// on `PATH`. Only called when a patch actually contains Krakatau assembly, so overlay/JSON/ops patches never need it.
fn krak2_bin() -> Result<PathBuf, String> {
    if let Some(p) = std::env::var_os("MCGRAFT_KRAK2") {
        let p = PathBuf::from(p);
        return if p.is_file() { Ok(p) } else { Err(format!("MCGRAFT_KRAK2 is not a file: {}", p.display())) };
    }
    #[cfg(embed_krak2)]
    if let Ok(p) = embedded_krak2() {
        return Ok(p);
    }
    let exe = if cfg!(windows) { "krak2.exe" } else { "krak2" };
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let c = dir.join(exe);
            if c.is_file() {
                return Ok(c);
            }
        }
    }
    Err("krak2 not found — put it on PATH, set MCGRAFT_KRAK2, or build with MCGRAFT_BUNDLE_KRAK2 to embed it \
         (required to assemble Krakatau .j patches; overlay/JSON/ops patches don't need it)"
        .into())
}

/// Extract the build-time-embedded `krak2` to a private temp file once and return its path. The `NamedTempFile` is kept
/// alive for the process lifetime in the `OnceLock` (so the path stays valid to exec) and is created with an
/// unpredictable mkstemp name + O_EXCL, avoiding the predictable-temp-file hazard.
#[cfg(embed_krak2)]
fn embedded_krak2() -> Result<PathBuf, String> {
    use std::sync::OnceLock;
    // Content-addressed cache rather than a per-run temp file. A TempPath held in a `static` is NEVER deleted — Rust
    // does not run destructors for statics at exit — so the old approach leaked another ~4 MB 0755 binary into /tmp on
    // every run that assembled a .j. Keying the name by content hash means one file per krak2 build, reused across
    // runs, and re-verified each time so a tampered or truncated cache entry is rewritten rather than executed.
    static EXTRACTED: OnceLock<Result<PathBuf, String>> = OnceLock::new();
    match EXTRACTED.get_or_init(|| {
        static BYTES: &[u8] = include_bytes!(env!("MCGRAFT_KRAK2_EMBEDDED"));
        let want = Sha256::digest(BYTES);
        let name = format!("krak2-{:x}{}", want, if cfg!(windows) { ".exe" } else { "" });
        let dir = cache_dir().join("mcgraft");
        std::fs::create_dir_all(&dir).map_err(|e| format!("create cache dir {}: {e}", dir.display()))?;
        let dst = dir.join(&name);

        // Reuse only if the cached bytes still hash to what we embed.
        if std::fs::read(&dst).is_ok_and(|got| Sha256::digest(&got) == want) {
            return Ok(dst);
        }
        // Write via a temp file in the SAME directory, then rename: rename is atomic, and it also closes the writable
        // fd before anything execs the result (Linux returns ETXTBSY for a file with an open writable descriptor).
        let mut tmp = tempfile::Builder::new()
            .prefix(".krak2-")
            .tempfile_in(&dir)
            .map_err(|e| format!("extract embedded krak2: {e}"))?;
        tmp.write_all(BYTES).map_err(|e| format!("extract embedded krak2: {e}"))?;
        tmp.flush().ok();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(tmp.path(), std::fs::Permissions::from_mode(0o755))
                .map_err(|e| format!("chmod embedded krak2: {e}"))?;
        }
        tmp.into_temp_path()
            .persist(&dst)
            .map_err(|e| format!("install embedded krak2 at {}: {e}", dst.display()))?;
        Ok(dst)
    }) {
        Ok(p) => Ok(p.clone()),
        Err(e) => Err(e.clone()),
    }
}

/// Per-user cache directory, honouring `MCGRAFT_CACHE_DIR` then the platform convention, falling back to the temp dir.
#[cfg(embed_krak2)]
fn cache_dir() -> PathBuf {
    if let Some(d) = std::env::var_os("MCGRAFT_CACHE_DIR") {
        return PathBuf::from(d);
    }
    #[cfg(windows)]
    let base = std::env::var_os("LOCALAPPDATA").map(PathBuf::from);
    #[cfg(not(windows))]
    let base = std::env::var_os("XDG_CACHE_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".cache")));
    base.unwrap_or_else(std::env::temp_dir)
}

/// Assemble one Krakatau `.j` source into its class(es); returns `(internal-name, class-bytes)` per class. A `.j` may
/// declare more than one class; each is placed by its declared name (the `.class` directive), not any filename.
fn assemble_j(j_bytes: &[u8]) -> Result<Vec<(String, Vec<u8>)>, String> {
    let bin = krak2_bin()?;
    // A private temp dir (mkdtemp: unpredictable name, not attacker-guessable) holding the .j input and .jar output;
    // it and its contents are removed when `dir` drops.
    let dir = tempfile::Builder::new().prefix("mcgraft-krak-").tempdir().map_err(|e| format!("temp dir: {e}"))?;
    let in_j = dir.path().join("in.j");
    let out_jar = dir.path().join("out.jar");
    std::fs::write(&in_j, j_bytes).map_err(|e| format!("write temp .j: {e}"))?;
    let run = Command::new(&bin).arg("asm").arg("--out").arg(&out_jar).arg(&in_j).output().map_err(|e| format!("run krak2: {e}"))?;
    if !run.status.success() {
        return Err(format!("krak2 asm failed: {}", String::from_utf8_lossy(&run.stderr).trim()));
    }
    let jar_bytes = std::fs::read(&out_jar).map_err(|e| format!("read krak2 output: {e}"))?;
    let mut zip = zip::ZipArchive::new(Cursor::new(jar_bytes)).map_err(|e| format!("krak2 output not a jar: {e}"))?;
    let mut classes = Vec::new();
    for i in 0..zip.len() {
        let mut e = zip.by_index(i).map_err(|e| format!("read krak2 output: {e}"))?;
        let name = e.name().to_string();
        if let Some(internal) = name.strip_suffix(".class") {
            let mut b = Vec::new();
            e.read_to_end(&mut b).map_err(|e| format!("read class: {e}"))?;
            classes.push((internal.to_string(), b));
        }
    }
    if classes.is_empty() {
        return Err("krak2 produced no classes from the .j".into());
    }
    Ok(classes)
}

/// Assemble `j_bytes` and add/replace each declared class in the jar (add-or-replace by internal name).
fn apply_assembled<F: FnMut(&str)>(
    jar: &mut Jar,
    j_bytes: &[u8],
    patch_name: &str,
    policy: OnConflict,
    written: &mut HashMap<String, String>,
    log: &mut F,
) -> Result<(), String> {
    for (internal, bytes) in assemble_j(j_bytes)? {
        let path = format!("{internal}.class");
        reject_unsafe_entry(&path)?;
        claim(written, &path, patch_name, policy, log)?;
        let verb = if jar.file(&path).is_some() { "replace" } else { "add" };
        jar.put(&path, bytes);
        log(&format!("  assemble {verb} {path}"));
    }
    Ok(())
}

/// Reject a jar entry name that is absolute or contains a `..`/backslash component. Entry names flow from patch content
/// (overlay paths, rename targets, a `.j`'s declared class), so keep them well-formed jar paths defensively.
/// Reject any entry name from an untrusted bundle that isn't a plain relative path.
///
/// Covers: absolute paths (unix and UNC), `\` (a Windows separator that `split('/')` would not see as a component),
/// `..` traversal, `.` components, a Windows drive prefix like `C:foo` (relative to that drive's cwd, not ours), empty
/// names, and NUL/control bytes (which truncate or corrupt paths in downstream C consumers).
fn reject_unsafe_entry(name: &str) -> Result<(), String> {
    let bad = name.is_empty()
        || name.starts_with('/')
        || name.contains('\\')
        || name.chars().any(|c| c.is_control())
        || name.split('/').any(|c| c == ".." || c == ".")
        || name.split('/').next().is_some_and(|c| c.len() >= 2 && c.as_bytes()[1] == b':');
    if bad {
        return Err(format!(
            "unsafe patch entry name '{name}' (must be a plain relative path: no absolute/drive prefix, \
             '..', '.', backslash or control characters)"
        ));
    }
    Ok(())
}

fn is_j_file(p: &Path) -> bool {
    p.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("j"))
}

fn read_mod_id(jar_bytes: &[u8]) -> Option<String> {
    let mut zip = zip::ZipArchive::new(Cursor::new(jar_bytes.to_vec())).ok()?;
    let mut e = zip.by_name("fabric.mod.json").ok()?;
    let mut s = String::new();
    e.read_to_string(&mut s).ok()?;
    let v: Value = serde_json::from_str(&s).ok()?;
    v.get("id")?.as_str().map(|s| s.to_string())
}

fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Gate for the krak2-dependent tests: skip locally when the optional tool is absent, but HARD-FAIL when
    /// `MCGRAFT_REQUIRE_KRAK2` is set (CI does). A bare `return` in a `#[test]` is reported as PASSED, not ignored —
    /// which is exactly how the entire bytecode-op surface once sat untested behind a green check.
    fn skip_without_krak2() -> bool {
        match krak2_bin() {
            Ok(_) => false,
            Err(e) => {
                assert!(
                    std::env::var_os("MCGRAFT_REQUIRE_KRAK2").is_none(),
                    "MCGRAFT_REQUIRE_KRAK2 is set but krak2 could not be found: {e}"
                );
                true
            }
        }
    }

    fn jar_with(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut zw = zip::ZipWriter::new(Cursor::new(&mut out));
            let o = zip::write::SimpleFileOptions::default();
            for (n, b) in entries {
                zw.start_file(*n, o).unwrap();
                zw.write_all(b).unwrap();
            }
            zw.finish().unwrap();
        }
        out
    }

    #[test]
    fn merge_rfc7396() {
        let mut doc: Value = serde_json::json!({"id":"m","depends":{"a":"1"},"drop":1});
        let patch: Value = serde_json::json!({"depends":{"b":"2"},"drop":null});
        json_merge(&mut doc, &patch);
        assert_eq!(doc, serde_json::json!({"id":"m","depends":{"a":"1","b":"2"}}));
    }

    #[test]
    fn overlay_delete_rename_roundtrip() {
        let mut jar = Jar::read(&jar_with(&[("a.txt", b"A"), ("b.txt", b"B"), ("c/d.txt", b"D")])).unwrap();
        jar.put("a.txt", b"A2".to_vec()); // replace
        jar.put("new.txt", b"N".to_vec()); // add
        assert!(jar.remove("b.txt"));
        let bytes = jar.write().unwrap();
        let rt = Jar::read(&bytes).unwrap();
        assert_eq!(rt.file("a.txt"), Some(&b"A2"[..]));
        assert_eq!(rt.file("new.txt"), Some(&b"N"[..]));
        assert!(rt.file("b.txt").is_none());
        assert_eq!(rt.file("c/d.txt"), Some(&b"D"[..]));
    }

    #[test]
    fn conflict_errors_by_default() {
        let mut w = HashMap::new();
        let mut log = |_: &str| {};
        claim(&mut w, "x", "p1", OnConflict::Error, &mut log).unwrap();
        assert!(claim(&mut w, "x", "p2", OnConflict::Error, &mut log).is_err());
        assert!(claim(&mut w, "x", "p3", OnConflict::PatchWins, &mut log).is_ok());
    }

    // Exercises the full apply_one pipeline over an in-memory zip bundle (the .mcpatch-zip path) — manifest parse,
    // base guard, delete, overlay, and JSON merge — without touching the filesystem.
    #[test]
    fn zip_bundle_pipeline() {
        let base = jar_with(&[
            ("fabric.mod.json", br#"{"id":"demo","depends":{"minecraft":"*"}}"#),
            ("keep.txt", b"k"),
            ("gone.txt", b"g"),
        ]);
        let base_sha = hex(&Sha256::digest(&base));
        let opts = Options { no_verify_base: false };
        let mut log = |_: &str| {};

        let mut z: HashMap<String, Vec<u8>> = HashMap::new();
        z.insert(
            "manifest.toml".into(),
            b"[bundle]\nformat = 1\n[target]\nmod = \"demo\"\n[[delete]]\npath = \"gone.txt\"\n[[merge]]\npath = \"fabric.mod.json\"\nfile = \"m.json\"\n".to_vec(),
        );
        z.insert("overlay/new.txt".into(), b"N".to_vec());
        z.insert("m.json".into(), br#"{"depends":{"fabric-api":"*"}}"#.to_vec());

        let mut jar = Jar::read(&base).unwrap();
        let mut written = HashMap::new();
        apply_one(&mut jar, &Source::Zip(z), "b.mcpatch", &base_sha, Some("demo"), &opts, &mut written, &mut log).unwrap();

        assert!(jar.file("gone.txt").is_none(), "delete");
        assert_eq!(jar.file("new.txt"), Some(&b"N"[..]), "overlay add");
        assert_eq!(jar.file("keep.txt"), Some(&b"k"[..]), "untouched");
        let fmj: Value = serde_json::from_slice(jar.file("fabric.mod.json").unwrap()).unwrap();
        assert_eq!(fmj["depends"]["fabric-api"], "*", "merge added");
        assert_eq!(fmj["depends"]["minecraft"], "*", "merge preserved");
    }

    // RFC-6902 jsonpatch via [[merge]] kind = "jsonpatch".
    #[test]
    fn jsonpatch_rfc6902() {
        let base = jar_with(&[("fabric.mod.json", br#"{"id":"demo","depends":{"minecraft":"*"}}"#)]);
        let base_sha = hex(&Sha256::digest(&base));
        let mut z: HashMap<String, Vec<u8>> = HashMap::new();
        z.insert("manifest.toml".into(), b"[bundle]\nformat=1\n[[merge]]\npath=\"fabric.mod.json\"\nfile=\"p.json\"\nkind=\"jsonpatch\"\n".to_vec());
        z.insert("p.json".into(), br#"[{"op":"add","path":"/depends/fabric-api","value":"*"},{"op":"replace","path":"/id","value":"demo2"}]"#.to_vec());
        let mut jar = Jar::read(&base).unwrap();
        let opts = Options { no_verify_base: false };
        let mut log = |_: &str| {};
        apply_one(&mut jar, &Source::Zip(z), "p", &base_sha, None, &opts, &mut HashMap::new(), &mut log).unwrap();
        let fmj: Value = serde_json::from_slice(jar.file("fabric.mod.json").unwrap()).unwrap();
        assert_eq!(fmj["id"], "demo2", "replace op");
        assert_eq!(fmj["depends"]["fabric-api"], "*", "add op");
        assert_eq!(fmj["depends"]["minecraft"], "*", "untouched");
    }

    #[test]
    fn bare_overlay_zip() {
        let base = jar_with(&[("a.txt", b"old"), ("b.txt", b"b")]);
        let base_sha = hex(&Sha256::digest(&base));
        let opts = Options { no_verify_base: false };
        let mut log = |_: &str| {};
        let mut z: HashMap<String, Vec<u8>> = HashMap::new(); // no manifest -> bare overlay: every entry overlaid
        z.insert("a.txt".into(), b"new".to_vec());
        z.insert("c.txt".into(), b"c".to_vec());
        let mut jar = Jar::read(&base).unwrap();
        let mut written = HashMap::new();
        apply_one(&mut jar, &Source::Zip(z), "bare.zip", &base_sha, None, &opts, &mut written, &mut log).unwrap();
        assert_eq!(jar.file("a.txt"), Some(&b"new"[..]), "overlay replace");
        assert_eq!(jar.file("c.txt"), Some(&b"c"[..]), "overlay add");
        assert_eq!(jar.file("b.txt"), Some(&b"b"[..]), "untouched");
    }

    // Requires krak2 (MCGRAFT_KRAK2 or on PATH); skips cleanly when the optional tool isn't present.
    #[test]
    fn krakatau_assemble() {
        if skip_without_krak2() {
            return;
        }
        let j = b".class public com/demo/Demo\n.super java/lang/Object\n\
                  .method public static answer : ()I\n.code stack 1 locals 0\nbipush 42\nireturn\n.end code\n.end method\n";
        let classes = assemble_j(j).unwrap();
        assert_eq!(classes.len(), 1);
        assert_eq!(classes[0].0, "com/demo/Demo");
        assert_eq!(&classes[0].1[0..4], &[0xCA, 0xFE, 0xBA, 0xBE], "valid class magic");
    }

    // In-place bytecode op: replace_const 64 -> 256, verified behaviorally (needs krak2; skips otherwise).
    #[test]
    fn ops_replace_const() {
        if skip_without_krak2() {
            return;
        }
        use crate::bytecode::{apply_ops, Op, OpKind};
        let j = b".class public com/demo/Calc\n.super java/lang/Object\n\
                  .method public static getMax : ()I\n.code stack 1 locals 0\nbipush 64\nireturn\n.end code\n.end method\n";
        let cls = assemble_j(j).unwrap().remove(0).1;
        let patched = apply_ops(&cls, &[Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 64, to: 256 } }]).unwrap();
        assert!(ristretto_classfile::ClassFile::from_bytes(&patched).is_ok(), "patched class re-parses");
        // 64 no longer present (no site to match) ...
        assert!(apply_ops(&patched, &[Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 64, to: 1 } }]).is_err());
        // ... and 256 is now present.
        assert!(apply_ops(&patched, &[Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 256, to: 1 } }]).is_ok());
    }

    #[test]
    fn rejects_deferred_bytecode_and_bad_sha() {
        let base = jar_with(&[("fabric.mod.json", br#"{"id":"demo"}"#)]);
        let base_sha = hex(&Sha256::digest(&base));
        let opts = Options { no_verify_base: false };
        let mut log = |_: &str| {};
        // [[patch]] krakatau-diff parses (has a `file` field) and reaches the friendly "deferred" message, not an
        // opaque unknown-field TOML error.
        let mut z: HashMap<String, Vec<u8>> = HashMap::new();
        z.insert("manifest.toml".into(), b"[bundle]\nformat=1\n[[patch]]\nclass=\"com/x/Y\"\nformat=\"krakatau-diff\"\nfile=\"x.diff\"\n".to_vec());
        let mut jar = Jar::read(&base).unwrap();
        let err = apply_one(&mut jar, &Source::Zip(z.clone()), "p", &base_sha, None, &opts, &mut HashMap::new(), &mut log).unwrap_err();
        assert!(err.contains("krakatau-diff"), "should hit the friendly deferred message, got: {err}");
        // sha256 base guard mismatch hard-fails
        let mut z2: HashMap<String, Vec<u8>> = HashMap::new();
        z2.insert("manifest.toml".into(), b"[bundle]\nformat=1\n[target]\nsha256=\"deadbeef\"\n".to_vec());
        assert!(apply_one(&mut jar, &Source::Zip(z2.clone()), "p", &base_sha, None, &opts, &mut HashMap::new(), &mut log).is_err());
        // ...but --no-verify-base overrides it
        let opts_off = Options { no_verify_base: true };
        assert!(apply_one(&mut jar, &Source::Zip(z2), "p", &base_sha, None, &opts_off, &mut HashMap::new(), &mut log).is_ok());
    }

    // A .mcpatch DIRECTORY bundle (Source::Dir + list_under) — previously only the zip path was tested.
    #[test]
    fn dir_bundle_pipeline() {
        let base = jar_with(&[("fabric.mod.json", br#"{"id":"demo"}"#), ("keep.txt", b"k")]);
        let base_sha = hex(&Sha256::digest(&base));
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("manifest.toml"), b"[bundle]\nformat = 1\n").unwrap();
        std::fs::create_dir_all(dir.path().join("overlay/assets/demo")).unwrap();
        std::fs::write(dir.path().join("overlay/assets/demo/x.txt"), b"X").unwrap();
        let mut jar = Jar::read(&base).unwrap();
        let opts = Options { no_verify_base: false };
        let mut log = |_: &str| {};
        apply_one(&mut jar, &Source::Dir(dir.path().to_path_buf()), "d", &base_sha, Some("demo"), &opts, &mut HashMap::new(), &mut log).unwrap();
        assert_eq!(jar.file("assets/demo/x.txt"), Some(&b"X"[..]), "dir overlay applied");
        assert_eq!(jar.file("keep.txt"), Some(&b"k"[..]), "untouched");
    }

    #[test]
    fn unsafe_entry_names_rejected() {
        assert!(reject_unsafe_entry("assets/ok.txt").is_ok());
        assert!(reject_unsafe_entry("/etc/passwd").is_err());
        assert!(reject_unsafe_entry("../../escape").is_err());
        assert!(reject_unsafe_entry("a/../../b").is_err());
        // hardened set: these all reached the output jar (or the filesystem, via Source::Dir) before
        assert!(reject_unsafe_entry("").is_err(), "empty name");
        assert!(reject_unsafe_entry("C:/windows/x").is_err(), "drive-absolute");
        assert!(reject_unsafe_entry("C:x").is_err(), "drive-relative");
        assert!(reject_unsafe_entry("..\\..\\escape").is_err(), "windows separator");
        assert!(reject_unsafe_entry("a\0b").is_err(), "NUL byte");
        assert!(reject_unsafe_entry("./a").is_err(), "'.' component");
    }

    /// P2 regression: `Source::read` must not escape the bundle even when handed a free-form manifest path.
    #[test]
    fn source_read_refuses_traversal() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("inside.json"), b"{}").unwrap();
        let src = Source::Dir(dir.path().to_path_buf());
        assert!(src.read("inside.json").is_some(), "ordinary relative read still works");
        // Path::join REPLACES the root on an absolute component, so this used to read the real file.
        assert!(src.read("/etc/hostname").is_none(), "absolute path must not escape");
        assert!(src.read("../../../etc/hostname").is_none(), "..-traversal must not escape");
    }

    /// P3 regression: a PARTIAL [apply] table must parse, filling the rest from defaults.
    #[test]
    fn partial_apply_table_parses() {
        let man: Manifest = toml::from_str("[apply]\non-conflict = \"patch-wins\"\n").expect("partial [apply] parses");
        assert!(man.apply.on_conflict == OnConflict::PatchWins);
        assert!(man.apply.verify_target, "unspecified verify-target must stay ON, not silently off");
    }

    /// A modded launch command line must not report an option value as the main class.
    #[test]
    fn main_class_detection_survives_modded_cmdlines() {
        use crate::procs::mainish;
        let split = |s: &str| s.split_whitespace().map(str::to_string).collect::<Vec<_>>();

        // The reported case: --add-opens takes a separate value that doesn't start with '-'.
        let prism = split(
            "java -Xmx4G --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
             -Djava.library.path=C:\\natives -cp C:\\libs\\fabric-loader.jar org.prismlauncher.EntryPoint",
        );
        assert_eq!(mainish(&prism), "org.prismlauncher.EntryPoint");

        // Plain Fabric launch still resolves to the known entry point.
        let fabric = split(
            "java -Xmx2G -cp /libs/a.jar net.fabricmc.loader.impl.launch.knot.KnotClient --username Dev --version 26.2",
        );
        assert_eq!(mainish(&fabric), "net.fabricmc.loader.impl.launch.knot.KnotClient");

        // An unknown main class is still found...
        assert_eq!(mainish(&split("java -Xmx1G -cp /x.jar com.example.Main --flag")), "com.example.Main");
        // ...but a stray option value is NOT reported as one.
        assert_eq!(mainish(&split("java --add-modules java.se.ee -jar")), "?");
    }

    /// P6 regression: delete and rename-source go through the ledger, and two bundles with the same FILE NAME are
    /// still distinct patches.
    #[test]
    fn ledger_covers_delete_and_distinguishes_same_named_patches() {
        let mut w = HashMap::new();
        let mut log = |_: &str| {};
        claim(&mut w, "assets/x.png", "#1 fix.mcpatch", OnConflict::Error, &mut log).unwrap();
        // a delete by a DIFFERENT patch that happens to share a file name must still conflict
        assert!(claim(&mut w, "assets/x.png", "#2 fix.mcpatch", OnConflict::Error, &mut log).is_err());
    }

    // --- ristretto ops (need krak2 to assemble the fixture class; skip cleanly otherwise) ---
    #[test]
    fn ops_redirect_invoke() {
        if skip_without_krak2() {
            return;
        }
        use crate::bytecode::{apply_ops, Op, OpKind};
        let j = b".class public com/demo/T\n.super java/lang/Object\n\
                  .method public static now : ()J\n.code stack 2 locals 0\n\
                  invokestatic Method java/lang/System currentTimeMillis ()J\nlreturn\n.end code\n.end method\n";
        let cls = assemble_j(j).unwrap().remove(0).1;
        let red = Op {
            in_method: None,
            kind: OpKind::RedirectInvoke {
                from: ("java/lang/System".into(), "currentTimeMillis".into(), "()J".into()),
                to: ("com/demo/Clock".into(), "now".into()),
            },
        };
        let patched = apply_ops(&cls, std::slice::from_ref(&red)).unwrap();
        assert!(ristretto_classfile::ClassFile::from_bytes(&patched).is_ok());
        // the original call site is gone, so re-applying the same redirect now matches nothing -> error
        assert!(apply_ops(&patched, std::slice::from_ref(&red)).is_err());
    }

    #[test]
    fn ops_replace_const_str() {
        if skip_without_krak2() {
            return;
        }
        use crate::bytecode::{apply_ops, Op, OpKind};
        let j = b".class public com/demo/S\n.super java/lang/Object\n\
                  .method public static tag : ()Ljava/lang/String;\n.code stack 1 locals 0\nldc 'old'\nareturn\n.end code\n.end method\n";
        let cls = assemble_j(j).unwrap().remove(0).1;
        let op = |a: &str, b: &str| Op { in_method: None, kind: OpKind::ReplaceConstStr { from: a.into(), to: b.into() } };
        let patched = apply_ops(&cls, &[op("old", "new")]).unwrap();
        assert!(ristretto_classfile::ClassFile::from_bytes(&patched).is_ok());
        assert!(apply_ops(&patched, &[op("old", "x")]).is_err(), "'old' gone");
        assert!(apply_ops(&patched, &[op("new", "x")]).is_ok(), "'new' present");
    }

    #[test]
    fn ops_set_return() {
        if skip_without_krak2() {
            return;
        }
        use crate::bytecode::{apply_ops, Op, OpKind, RetVal};
        let j = b".class public com/demo/R\n.super java/lang/Object\n\
                  .method public static getMax : ()I\n.code stack 1 locals 0\nbipush 64\nireturn\n.end code\n.end method\n";
        let cls = assemble_j(j).unwrap().remove(0).1;
        let op = Op {
            in_method: Some(("getMax".into(), "()I".into())),
            kind: OpKind::SetReturn { value: Some(RetVal::Int(999)) },
        };
        let patched = apply_ops(&cls, std::slice::from_ref(&op)).unwrap();
        assert!(ristretto_classfile::ClassFile::from_bytes(&patched).is_ok(), "re-parses");
        // the whole body was replaced, so the old constant 64 is gone
        assert!(apply_ops(&patched, &[Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 64, to: 1 } }]).is_err());
        // but the new body returns 999
        assert!(apply_ops(&patched, &[Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 999, to: 1 } }]).is_ok());
    }

    /// C1 regression: a size-GROWING replacement must not corrupt the StackMapTable.
    ///
    /// Every other fixture here is a branch-free method with no frames, which is exactly why this bug class survived.
    /// This one puts the first frame at byte offset 63 — the largest delta a compact `SameFrame` can encode — with an
    /// `iconst_1` (1 byte) ahead of it that the op widens to `sipush 300` (3 bytes). Before the fix the frame was
    /// re-emitted as `SameFrame { frame_type: 65 }`, which is out of range for that form and re-parses as a
    /// `SameLocals1StackItemFrame`, desynchronizing the rest of the table; HotSpot rejects the class with
    /// `ClassFormatError: StackMapTable format error: access beyond the end of attribute`.
    #[test]
    fn ops_growing_replacement_keeps_stackmap_valid() {
        if skip_without_krak2() {
            return;
        }
        use crate::bytecode::{apply_ops, Op, OpKind};
        use ristretto_classfile::attributes::{Attribute, StackFrame};

        let mut j = String::from(
            ".version 52 0\n.class public com/demo/F\n.super java/lang/Object\n\
             .method public static m : ()V\n.code stack 2 locals 0\niconst_0\nifne L63\niconst_1\npop\n",
        );
        j.push_str(&"nop\n".repeat(57)); // pad so L63 lands at byte offset exactly 63
        j.push_str("L63:\n.stack same\nreturn\n.end code\n.end method\n");
        let cls = assemble_j(j.as_bytes()).unwrap().remove(0).1;

        let frames_of = |bytes: &[u8]| -> Vec<StackFrame> {
            let cf = ristretto_classfile::ClassFile::from_bytes(bytes).expect("class re-parses");
            cf.methods
                .iter()
                .flat_map(|m| &m.attributes)
                .filter_map(|a| match a {
                    Attribute::Code { attributes, .. } => Some(attributes),
                    _ => None,
                })
                .flatten()
                .filter_map(|a| match a {
                    Attribute::StackMapTable { frames, .. } => Some(frames.clone()),
                    _ => None,
                })
                .flatten()
                .collect()
        };

        // Pin the fixture. ristretto reports deltas as *logical instruction* offsets, so this is 61 instructions
        // (iconst_0, ifne, iconst_1, pop, 57×nop) even though the on-disk byte offset is 63. If someone retunes the
        // padding, this fails and the byte arithmetic below must be re-derived — otherwise the test would quietly
        // stop covering the boundary it exists to cover.
        let before = frames_of(&cls);
        assert_eq!(before.len(), 1, "fixture must carry exactly one stack-map frame");
        assert_eq!(before[0].offset_delta(), 61, "fixture drifted off the 63-byte boundary");

        let op = Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 1, to: 300 } };
        let patched = apply_ops(&cls, std::slice::from_ref(&op)).unwrap();

        // Byte offset 63 -> 65, which no longer fits a compact SameFrame (0..=63). ristretto never promotes frames on
        // its own, so seeing the extended form proves our promotion ran. Under the bug it emitted `SameFrame { 65 }`,
        // and re-reading that lands in the 64..=127 window -> it comes back as SameLocals1StackItemFrame with a
        // bogus stack, which is precisely the table desync HotSpot rejects.
        let frames = frames_of(&patched);
        assert_eq!(frames.len(), 1, "one frame expected after patching");
        assert!(
            matches!(frames[0], StackFrame::SameFrameExtended { .. }),
            "expected promotion to SameFrameExtended; got {:?} (a SameLocals1StackItemFrame here means the \
             compact-form overflow is back)",
            frames[0]
        );
    }

    // M2 regression: an op that matches nothing errors even when another op in the same patch matched.
    #[test]
    fn ops_every_op_must_match() {
        if skip_without_krak2() {
            return;
        }
        use crate::bytecode::{apply_ops, Op, OpKind};
        let j = b".class public com/demo/C\n.super java/lang/Object\n\
                  .method public static m : ()I\n.code stack 1 locals 0\nbipush 64\nireturn\n.end code\n.end method\n";
        let cls = assemble_j(j).unwrap().remove(0).1;
        let ops = [
            Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 64, to: 256 } }, // matches
            Op { in_method: None, kind: OpKind::ReplaceConstInt { from: 99, to: 1 } },   // matches nothing
        ];
        assert!(apply_ops(&cls, &ops).is_err(), "an op matching nothing must fail even if another matched");
    }
}
