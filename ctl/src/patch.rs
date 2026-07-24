//! Payload patches (v1): user-supplied edits applied to the mod jar BEFORE grafting, entirely at pack time in Rust.
//!
//! v1 scope (no JVM / no bytecode tooling): file **overlay** (add/replace), **delete**, **rename**, and JSON **merge**
//! (RFC 7396) on resource entries such as `fabric.mod.json`. Bytecode edits (`[[patch]]`), assembled `new/` classes, and
//! RFC-6902 `jsonpatch` are deferred to a later phase (they need the Krakatau toolchain / a JSON-Patch impl) and are
//! rejected here with a clear message rather than silently ignored.
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

#[derive(Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
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
            if e.is_dir() {
                map.insert(name.clone(), Entry::Dir);
            } else {
                let mut buf = Vec::with_capacity(e.size() as usize);
                e.read_to_end(&mut buf).map_err(|e| format!("read jar entry: {e}"))?;
                map.insert(name.clone(), Entry::File(buf));
            }
            order.push(name);
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

    // Cross-patch conflict ledger: path -> name of the patch that last wrote it. A second patch touching the same path
    // is a conflict resolved per that bundle's on-conflict policy.
    let mut written: HashMap<String, String> = HashMap::new();

    for p in patches {
        let name = p.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| p.display().to_string());
        log(&format!("applying patch: {name}"));
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
                jar.remove(&r.from);
                jar.put(&r.to, b);
                claim(written, &r.to, patch_name, man.apply.on_conflict, log)?;
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
                    claim(written, k, patch_name, man.apply.on_conflict, log)?;
                    let verb = if jar.file(k).is_some() { "replace" } else { "add" };
                    jar.put(k, m[k].clone());
                    log(&format!("  overlay {verb} {k}"));
                }
            }
            Source::Dir(_) => {
                for rel in src.list_under("") {
                    let bytes = src.read(&rel).unwrap_or_default();
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
        if mg.kind != "merge" {
            return Err(format!(
                "patch {patch_name}: merge kind '{}' is not supported yet (only RFC-7396 'merge'); 'jsonpatch' is deferred",
                mg.kind
            ));
        }
        let target = jar
            .file(&mg.path)
            .ok_or_else(|| format!("patch {patch_name}: merge target '{}' not found in payload", mg.path))?;
        let patch_doc = src
            .read(&mg.file)
            .ok_or_else(|| format!("patch {patch_name}: merge file '{}' not found in bundle", mg.file))?;
        let mut doc: Value = serde_json::from_slice(target)
            .map_err(|e| format!("patch {patch_name}: merge target '{}' is not valid JSON: {e}", mg.path))?;
        let overlay: Value = serde_json::from_slice(&patch_doc)
            .map_err(|e| format!("patch {patch_name}: merge file '{}' is not valid JSON: {e}", mg.file))?;
        json_merge(&mut doc, &overlay);
        let rendered = serde_json::to_vec_pretty(&doc).map_err(|e| format!("re-serialize {}: {e}", mg.path))?;
        claim(written, &mg.path, patch_name, man.apply.on_conflict, log)?;
        jar.put(&mg.path, rendered);
        log(&format!("  merge {} <- {}", mg.path, mg.file));
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
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let c = dir.join("krak2");
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
    // Keep a TempPath (not NamedTempFile): the file persists for the process (deleted on exit) but its writable fd is
    // CLOSED — Linux refuses to exec a file that still has an open writable descriptor (ETXTBSY).
    static EXTRACTED: OnceLock<Result<tempfile::TempPath, String>> = OnceLock::new();
    match EXTRACTED.get_or_init(|| {
        static BYTES: &[u8] = include_bytes!(env!("MCGRAFT_KRAK2_EMBEDDED"));
        let mut tmp = tempfile::Builder::new()
            .prefix("mcgraft-krak2-")
            .tempfile()
            .map_err(|e| format!("extract embedded krak2: {e}"))?;
        tmp.write_all(BYTES).map_err(|e| format!("extract embedded krak2: {e}"))?;
        tmp.flush().ok();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(tmp.path(), std::fs::Permissions::from_mode(0o755))
                .map_err(|e| format!("chmod embedded krak2: {e}"))?;
        }
        Ok(tmp.into_temp_path()) // closes the fd, keeps the file on disk
    }) {
        Ok(tp) => Ok(tp.to_path_buf()),
        Err(e) => Err(e.clone()),
    }
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
fn reject_unsafe_entry(name: &str) -> Result<(), String> {
    if name.starts_with('/') || name.contains('\\') || name.split('/').any(|c| c == "..") {
        return Err(format!("unsafe patch entry name '{name}' (absolute path or ..-traversal)"));
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
        if krak2_bin().is_err() {
            return; // krak2 not available in this environment — skip
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
        if krak2_bin().is_err() {
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
    }

    // --- ristretto ops (need krak2 to assemble the fixture class; skip cleanly otherwise) ---
    #[test]
    fn ops_redirect_invoke() {
        if krak2_bin().is_err() {
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
        if krak2_bin().is_err() {
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
        if krak2_bin().is_err() {
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

    // M2 regression: an op that matches nothing errors even when another op in the same patch matched.
    #[test]
    fn ops_every_op_must_match() {
        if krak2_bin().is_err() {
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
