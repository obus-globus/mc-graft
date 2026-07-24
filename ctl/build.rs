//! Optionally embed helper binaries into `mcgraft` so patch/inject features work with no external tool:
//!
//! - `MCGRAFT_BUNDLE_KRAK2=/path/to/krak2` — Krakatau assembler (for `.j` / `new/` patches)
//! - `MCGRAFT_BUNDLE_JATTACH=/path/to/jattach` — jattach (Windows inject)
//!
//! Each, when set to a real file, is copied into OUT_DIR and `include_bytes!`d; without it, the tool falls back to the
//! matching env var / `PATH` at runtime. The embedded binary must match the build target's platform.

use std::path::Path;

fn main() {
    embed("MCGRAFT_BUNDLE_KRAK2", "krak2.bin", "MCGRAFT_KRAK2_EMBEDDED", "embed_krak2");
    embed("MCGRAFT_BUNDLE_JATTACH", "jattach.bin", "MCGRAFT_JATTACH_EMBEDDED", "embed_jattach");
}

fn embed(env_var: &str, out_name: &str, embed_env: &str, cfg: &str) {
    println!("cargo:rerun-if-env-changed={env_var}");
    println!("cargo:rustc-check-cfg=cfg({cfg})"); // declare the custom cfg so no unexpected_cfg warning

    if let Ok(src) = std::env::var(env_var) {
        let src = Path::new(&src);
        if src.is_file() {
            let out = std::env::var("OUT_DIR").expect("OUT_DIR");
            let dst = Path::new(&out).join(out_name);
            std::fs::copy(src, &dst).unwrap_or_else(|e| panic!("copy {env_var} into OUT_DIR: {e}"));
            println!("cargo:rustc-env={embed_env}={}", dst.display());
            println!("cargo:rustc-cfg={cfg}");
            println!("cargo:rerun-if-changed={}", src.display());
        } else {
            println!("cargo:warning={env_var} is set but not a file: {}", src.display());
        }
    }
}
