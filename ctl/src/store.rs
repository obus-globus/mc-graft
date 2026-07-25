//! User-saved presets, persisted per-user (`~/.config/mcgraft/presets.json`, `%APPDATA%\mcgraft\presets.json` on
//! Windows): named build configs (an `AgentConfig`) and named inject configs (data-dir mode + options). Loaded at
//! startup, saved whenever a preset is added or removed.

use crate::config::AgentConfig;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::Path;
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

/// Per-user preset file, or `None` when no config directory can be determined.
///
/// Windows sets neither `XDG_CONFIG_HOME` nor `HOME`, and `unwrap_or_default()` turned that into an empty string — so
/// presets went to the RELATIVE `.config\mcgraft\presets.json`: a permission error under `C:\Program Files`, and
/// otherwise a different file per launch directory, so saved presets silently vanished on the next run. Returning None
/// lets `save` say so instead of writing somewhere arbitrary.
fn store_path() -> Option<PathBuf> {
    let base = std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from).or_else(|| {
        #[cfg(windows)]
        {
            std::env::var_os("APPDATA").map(PathBuf::from)
        }
        #[cfg(not(windows))]
        {
            std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config"))
        }
    })?;
    Some(base.join("mcgraft").join("presets.json"))
}

impl Store {
    pub fn load() -> Store {
        store_path()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    pub fn save(&self) -> Result<(), String> {
        let p = store_path().ok_or("no config directory (set XDG_CONFIG_HOME, or APPDATA on Windows)")?;
        if let Some(d) = p.parent() {
            std::fs::create_dir_all(d).map_err(|e| format!("{}: {e}", d.display()))?;
        }
        let json = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        // Write-then-rename: a crash or a full disk mid-write would otherwise leave a truncated presets.json, which
        // `load` silently treats as "no presets" and the next save then overwrites for good.
        let dir = p.parent().unwrap_or_else(|| Path::new("."));
        let mut tmp = tempfile::Builder::new()
            .prefix(".presets-")
            .tempfile_in(dir)
            .map_err(|e| format!("{}: {e}", dir.display()))?;
        std::io::Write::write_all(&mut tmp, json.as_bytes()).map_err(|e| format!("{}: {e}", p.display()))?;
        tmp.persist(&p).map_err(|e| format!("{}: {e}", p.display()))?;
        Ok(())
    }
}
