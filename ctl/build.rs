//! Optionally embed the `krak2` binary (Krakatau assembler) into the `mcgraft` binary so the Krakatau `.j` / `new/`
//! patch features work with no external tool. Opt-in: build with `MCGRAFT_BUNDLE_KRAK2=/path/to/krak2` and the binary
//! is copied into OUT_DIR and `include_bytes!`d (see patch.rs). Without it, the packer falls back to `MCGRAFT_KRAK2` /
//! `PATH` at runtime. The embedded binary must match the build target's platform.

use std::path::Path;

fn main() {
    println!("cargo:rerun-if-env-changed=MCGRAFT_BUNDLE_KRAK2");
    println!("cargo:rustc-check-cfg=cfg(embed_krak2)"); // declare the custom cfg so no unexpected_cfg warning

    if let Ok(src) = std::env::var("MCGRAFT_BUNDLE_KRAK2") {
        let src = Path::new(&src);
        if src.is_file() {
            let out = std::env::var("OUT_DIR").expect("OUT_DIR");
            let dst = Path::new(&out).join("krak2.bin");
            std::fs::copy(src, &dst).expect("copy krak2 binary into OUT_DIR");
            println!("cargo:rustc-env=MCGRAFT_KRAK2_EMBEDDED={}", dst.display());
            println!("cargo:rustc-cfg=embed_krak2");
            println!("cargo:rerun-if-changed={}", src.display());
        } else {
            println!("cargo:warning=MCGRAFT_BUNDLE_KRAK2 is set but not a file: {}", src.display());
        }
    }
}
