//! The full mc-graft agent bootstrap configuration (everything the engine's `graft.properties` supports beyond what's
//! auto-derived from `fabric.mod.json`). Modeled as structured fields so the UI can expose every option, load presets
//! from the bundled `examples/*/bootstrap.properties`, and serialize back to a `.properties` file the build consumes.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct AgentConfig {
    // packaging / dependencies
    pub deps: String,               // payload.dependencies (comma-separated Maven coords)
    pub apply_dep_mixins: String,   // bootstrap.applyDependencyMixins: "" off, "*" all, else config-name substring filter
    pub neoforge_mixins: String,    // payload.mixins.neoforge
    pub packages: String,           // payload.packages
    // registry / reload lifecycle
    pub reload_resources: bool,     // bootstrap.reloadResources
    pub thaw_registries: String,    // bootstrap.thawRegistries
    pub register_mod_container: bool, // bootstrap.registerModContainer
    pub reopen_key_bindings: bool,  // bootstrap.reopenKeyBindings
    pub bridge_payload_codecs: bool, // bootstrap.bridgePayloadCodecs
    pub game_dir_property: String,  // bootstrap.gameDirProperty
    pub join_gate_screen: String,   // bootstrap.joinGate.screen
    // event bootstrap (for mods with no entrypoint)
    pub evt_manager: String,        // bootstrap.event.manager
    pub evt_manager_instance: String,
    pub evt_base: String,           // bootstrap.event.base
    pub evt_start: String,          // bootstrap.event.start
    pub evt_start_instance: String,
    pub evt_call: String,           // bootstrap.event.call
    // readiness probe (async event bootstrap)
    pub ready_class: String,        // bootstrap.ready.class
    pub ready_instance: String,
    pub ready_method: String,
}

impl AgentConfig {
    /// True when nothing is configured (build can skip -PbootstrapConfig entirely).
    pub fn is_empty(&self) -> bool {
        *self == AgentConfig::default()
    }

    pub fn from_properties(text: &str) -> Self {
        let mut c = AgentConfig::default();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
                continue;
            }
            let Some((k, v)) = line.split_once('=') else { continue };
            let (k, v) = (k.trim(), v.trim().to_string());
            match k {
                "payload.dependencies" => c.deps = v,
                "bootstrap.applyDependencyMixins" => c.apply_dep_mixins = v,
                "payload.mixins.neoforge" => c.neoforge_mixins = v,
                "payload.packages" => c.packages = v,
                "bootstrap.reloadResources" => c.reload_resources = v.eq_ignore_ascii_case("true"),
                "bootstrap.thawRegistries" => c.thaw_registries = v,
                "bootstrap.registerModContainer" => c.register_mod_container = v.eq_ignore_ascii_case("true"),
                "bootstrap.reopenKeyBindings" => c.reopen_key_bindings = v.eq_ignore_ascii_case("true"),
                "bootstrap.bridgePayloadCodecs" => c.bridge_payload_codecs = v.eq_ignore_ascii_case("true"),
                "bootstrap.gameDirProperty" => c.game_dir_property = v,
                "bootstrap.joinGate.screen" => c.join_gate_screen = v,
                "bootstrap.event.manager" => c.evt_manager = v,
                "bootstrap.event.managerInstance" => c.evt_manager_instance = v,
                "bootstrap.event.base" => c.evt_base = v,
                "bootstrap.event.start" => c.evt_start = v,
                "bootstrap.event.startInstance" => c.evt_start_instance = v,
                "bootstrap.event.call" => c.evt_call = v,
                "bootstrap.ready.class" => c.ready_class = v,
                "bootstrap.ready.instance" => c.ready_instance = v,
                "bootstrap.ready.method" => c.ready_method = v,
                _ => {}
            }
        }
        c
    }

    pub fn to_properties(&self) -> String {
        fn kv(o: &mut String, k: &str, v: &str) {
            if !v.trim().is_empty() {
                o.push_str(k);
                o.push('=');
                o.push_str(v.trim());
                o.push('\n');
            }
        }
        let mut o = String::new();
        kv(&mut o, "payload.dependencies", &self.deps);
        kv(&mut o, "bootstrap.applyDependencyMixins", &self.apply_dep_mixins);
        kv(&mut o, "payload.mixins.neoforge", &self.neoforge_mixins);
        kv(&mut o, "payload.packages", &self.packages);
        if self.reload_resources {
            o.push_str("bootstrap.reloadResources=true\n");
        }
        kv(&mut o, "bootstrap.thawRegistries", &self.thaw_registries);
        if self.register_mod_container {
            o.push_str("bootstrap.registerModContainer=true\n");
        }
        if self.reopen_key_bindings {
            o.push_str("bootstrap.reopenKeyBindings=true\n");
        }
        if self.bridge_payload_codecs {
            o.push_str("bootstrap.bridgePayloadCodecs=true\n");
        }
        kv(&mut o, "bootstrap.gameDirProperty", &self.game_dir_property);
        kv(&mut o, "bootstrap.joinGate.screen", &self.join_gate_screen);
        kv(&mut o, "bootstrap.event.manager", &self.evt_manager);
        kv(&mut o, "bootstrap.event.managerInstance", &self.evt_manager_instance);
        kv(&mut o, "bootstrap.event.base", &self.evt_base);
        kv(&mut o, "bootstrap.event.start", &self.evt_start);
        kv(&mut o, "bootstrap.event.startInstance", &self.evt_start_instance);
        kv(&mut o, "bootstrap.event.call", &self.evt_call);
        kv(&mut o, "bootstrap.ready.class", &self.ready_class);
        kv(&mut o, "bootstrap.ready.instance", &self.ready_instance);
        kv(&mut o, "bootstrap.ready.method", &self.ready_method);
        o
    }
}

/// A named configuration preset (from a bundled `examples/<name>/bootstrap.properties`).
pub struct Preset {
    pub name: String,
    pub path: PathBuf,
}

/// Discover the bundled example configs as presets, sorted by name.
pub fn discover_presets(repo: &Path) -> Vec<Preset> {
    let mut out = Vec::new();
    let examples = repo.join("examples");
    if let Ok(rd) = std::fs::read_dir(&examples) {
        for e in rd.flatten() {
            let p = e.path().join("bootstrap.properties");
            if p.is_file() {
                out.push(Preset { name: e.file_name().to_string_lossy().into_owned(), path: p });
            }
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}
