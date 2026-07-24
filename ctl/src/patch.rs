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
    // Reserved for a later phase; parsed so we can reject with a helpful message instead of "unknown field".
    #[serde(default)]
    patch: Vec<toml::Value>,
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

/// Apply `patches` (in order) to `payload`, writing the result to a temp jar and returning its path. Returns `Ok(None)`
/// when `patches` is empty (caller uses the original payload). The caller owns cleanup of the returned temp path.
pub fn apply_patches<F: FnMut(&str)>(
    payload: &Path,
    patches: &[PathBuf],
    opts: &Options,
    mut log: F,
) -> Result<Option<PathBuf>, String> {
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
        let src = open_source(p)?;
        apply_one(&mut jar, &src, &name, &base_sha, base_mod_id.as_deref(), opts, &mut written, &mut log)?;
    }

    let out_bytes = jar.write()?;
    let tmp = std::env::temp_dir().join(format!("mcgraft-patched-{}.jar", std::process::id()));
    std::fs::write(&tmp, &out_bytes).map_err(|e| format!("write patched jar {}: {e}", tmp.display()))?;
    log(&format!("patched payload → {} ({} KiB)", tmp.display(), out_bytes.len() / 1024));
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
    if !man.patch.is_empty() {
        return Err(format!(
            "patch {patch_name}: [[patch]] (bytecode edits) is not supported in this version — that phase (Krakatau) is deferred; \
             use overlay/delete/rename/merge for now"
        ));
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

    // Ordered pipeline: delete -> rename -> overlay -> merge. (new/ + patch are deferred.)
    if src.read("new").is_some() || !src.list_under("new").is_empty() {
        log(&format!("  note: patch {patch_name} has a new/ directory — assembling classes is deferred to a later phase; ignoring"));
    }

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
        claim(written, &rel, patch_name, man.apply.on_conflict, log)?;
        let verb = if jar.file(&rel).is_some() { "replace" } else { "add" };
        jar.put(&rel, bytes);
        log(&format!("  overlay {verb} {rel}"));
    }
    // A bare overlay (no manifest): overlay ALL entries of the zip.
    if manifest_bytes.is_none() {
        if let Source::Zip(m) = src {
            let mut keys: Vec<&String> = m.keys().collect();
            keys.sort();
            for k in keys {
                claim(written, k, patch_name, man.apply.on_conflict, log)?;
                let verb = if jar.file(k).is_some() { "replace" } else { "add" };
                jar.put(k, m[k].clone());
                log(&format!("  overlay {verb} {k}"));
            }
        }
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

    Ok(())
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

    #[test]
    fn rejects_deferred_bytecode_and_bad_sha() {
        let base = jar_with(&[("fabric.mod.json", br#"{"id":"demo"}"#)]);
        let base_sha = hex(&Sha256::digest(&base));
        let opts = Options { no_verify_base: false };
        let mut log = |_: &str| {};
        // [[patch]] bytecode section is rejected (deferred phase 2)
        let mut z: HashMap<String, Vec<u8>> = HashMap::new();
        z.insert("manifest.toml".into(), b"[bundle]\nformat=1\n[[patch]]\nclass=\"x\"\nformat=\"krakatau-diff\"\nfile=\"x\"\n".to_vec());
        let mut jar = Jar::read(&base).unwrap();
        assert!(apply_one(&mut jar, &Source::Zip(z.clone()), "p", &base_sha, None, &opts, &mut HashMap::new(), &mut log).is_err());
        // sha256 base guard mismatch hard-fails
        let mut z2: HashMap<String, Vec<u8>> = HashMap::new();
        z2.insert("manifest.toml".into(), b"[bundle]\nformat=1\n[target]\nsha256=\"deadbeef\"\n".to_vec());
        assert!(apply_one(&mut jar, &Source::Zip(z2.clone()), "p", &base_sha, None, &opts, &mut HashMap::new(), &mut log).is_err());
        // ...but --no-verify-base overrides it
        let opts_off = Options { no_verify_base: true };
        assert!(apply_one(&mut jar, &Source::Zip(z2), "p", &base_sha, None, &opts_off, &mut HashMap::new(), &mut log).is_ok());
    }
}
