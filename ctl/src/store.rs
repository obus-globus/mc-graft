//! User-saved presets, persisted to `~/.config/mcgraft/presets.json`: named build configs (an `AgentConfig`) and named
//! inject configs (data-dir mode + options). Loaded at startup, saved whenever a preset is added or removed.

use crate::config::AgentConfig;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Eq, Default, Debug)]
pub enum GameDir {
    #[default]
    ClientDir,
    WorkingDir,
    Custom,
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct InjectConfig {
    pub game_dir: GameDir,
    pub custom_dir: String,
    pub tolerate: bool,
    /// Optional saved agent jar path (blank = leave whatever's selected).
    pub agent_jar: String,
}

#[derive(Serialize, Deserialize, Default)]
#[serde(default)]
pub struct Store {
    pub build: BTreeMap<String, AgentConfig>,
    pub inject: BTreeMap<String, InjectConfig>,
}

fn store_path() -> PathBuf {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".config")
        });
    base.join("mcgraft").join("presets.json")
}

impl Store {
    pub fn load() -> Store {
        std::fs::read_to_string(store_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    pub fn save(&self) -> Result<(), String> {
        let p = store_path();
        if let Some(d) = p.parent() {
            std::fs::create_dir_all(d).map_err(|e| format!("{}: {e}", d.display()))?;
        }
        let json = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(&p, json).map_err(|e| format!("{}: {e}", p.display()))
    }
}
