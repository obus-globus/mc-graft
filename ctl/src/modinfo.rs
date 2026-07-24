//! Read a Fabric mod jar's `fabric.mod.json` to surface what it is and what it targets (mod id/name/version, the MC
//! and loader versions it depends on, its access-widener, mixin configs, and entrypoints). Used to show info when a
//! jar is dragged into the UI and to pre-fill the build.

use serde_json::Value;
use std::io::Read;
use std::path::Path;

#[derive(Clone, Default)]
pub struct ModInfo {
    pub id: String,
    pub name: String,
    pub version: String,
    pub mc: Option<String>,
    pub loader: Option<String>,
    pub access_widener: Option<String>,
    pub mixins: Vec<String>,
    pub entry_main: Vec<String>,
    pub entry_client: Vec<String>,
    /// Other declared `depends` (id -> version range), for display.
    pub other_deps: Vec<(String, String)>,
}

impl ModInfo {
    /// A mod with no entrypoints initializes some other way (e.g. a mixin-fired event) and needs a bootstrap override.
    pub fn needs_bootstrap_config(&self) -> bool {
        self.entry_main.is_empty() && self.entry_client.is_empty()
    }

    /// A compact one-liner for the window/status.
    pub fn summary(&self) -> String {
        let mc = self.mc.clone().unwrap_or_else(|| "?".into());
        format!("{} {}  ·  MC {}", self.name, self.version, mc)
    }
}

pub fn read_jar(path: &Path) -> Result<ModInfo, String> {
    let file = std::fs::File::open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| format!("not a jar/zip: {e}"))?;
    let mut s = String::new();
    zip.by_name("fabric.mod.json")
        .map_err(|_| "no fabric.mod.json — not a Fabric mod jar?".to_string())?
        .read_to_string(&mut s)
        .map_err(|e| format!("read fabric.mod.json: {e}"))?;
    let v: Value = serde_json::from_str(&s).map_err(|e| format!("parse fabric.mod.json: {e}"))?;

    let id = v["id"].as_str().unwrap_or("").to_string();
    let name = v["name"].as_str().filter(|s| !s.is_empty()).unwrap_or(&id).to_string();
    let version = v["version"].as_str().unwrap_or("").to_string();
    let access_widener = v["accessWidener"].as_str().map(|s| s.to_string());

    let mut mc = None;
    let mut loader = None;
    let mut other_deps = Vec::new();
    if let Some(dep) = v["depends"].as_object() {
        for (k, val) in dep {
            let range = dep_range(val);
            match k.as_str() {
                "minecraft" => mc = Some(range),
                "fabricloader" | "fabric-loader" => loader = Some(range),
                "java" => {}
                _ => other_deps.push((k.clone(), range)),
            }
        }
    }

    Ok(ModInfo {
        id,
        name,
        version,
        mc,
        loader,
        access_widener,
        mixins: mixin_configs(&v),
        entry_main: entrypoints(&v, "main"),
        entry_client: entrypoints(&v, "client"),
        other_deps,
    })
}

fn dep_range(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        Value::Array(a) => a.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(" | "),
        _ => "*".to_string(), // unexpected shape (fabric.mod.json deps are string|array) -> show "any" rather than raw JSON
    }
}

/// `mixins` may be strings or `{ "config": "x.json", ... }` objects.
fn mixin_configs(v: &Value) -> Vec<String> {
    v["mixins"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|m| match m {
                    Value::String(s) => Some(s.clone()),
                    Value::Object(_) => m["config"].as_str().map(|s| s.to_string()),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default()
}

/// entrypoints[key] entries may be strings or `{ "value": "Class", "adapter": ... }`.
fn entrypoints(v: &Value, key: &str) -> Vec<String> {
    v["entrypoints"][key]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|e| match e {
                    Value::String(s) => Some(s.clone()),
                    Value::Object(_) => e["value"].as_str().map(|s| s.to_string()),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default()
}
