# mcgraft — control app for mc-graft

A single Rust tool that covers the whole mc-graft lifecycle: **build** an agent for a mod, **discover** running
Minecraft JVMs, and **inject** the agent into a live one — as a **CLI** and a native **GUI**.

The attach path is a pure-Rust implementation of the HotSpot dynamic-attach protocol (Unix socket + SIGQUIT trigger):
no JNI, no bundled `jattach` binary, and it needs no JDK on the machine running it.

## Build

```bash
cd ctl
cargo build --release      # -> target/release/mcgraft
```

The GUI links egui/eframe (X11 + Wayland). A headless build (CLI only) works too; the GUI just won't open without a
display.

## GUI

```bash
mcgraft            # no subcommand -> opens the GUI
mcgraft ui
```

- **Running JVMs** (left): live list; Minecraft instances are marked and auto-selected. Click to pick a target.
- **① Build agent**: pick a payload mod jar (+ optional bootstrap `.properties`, agent name, extra Maven deps),
  click *Build agent*. Pick a **Build method**: *Gradle (JDK)* drives `./gradlew agentJar` (needs a JDK + the repo),
  or *Quick pack (no JDK)* splices the payload into the precompiled base jar entirely in-process (see below).
- **② Inject**: agent jar (auto-filled by the build), data-dir mode, "tolerate other-mod mixin failures", then
  *Inject → PID*. The injection log streams live into the Log pane.

## CLI

```bash
mcgraft list                                   # attachable JVMs; MC flagged + version

mcgraft build -p LiquidBounce.jar \            # build an agent (drives ./gradlew agentJar)
      -c examples/liquidbounce/bootstrap.properties -n lb-agent.jar

mcgraft pack -p LiquidBounce.jar \             # JDK-free build (no Gradle): splice into the precompiled base jar
      -c examples/liquidbounce/bootstrap.properties -o lb-agent.jar

mcgraft inject -a build/agent/lb-agent.jar     # auto-selects the sole MC JVM, attaches, follows the log
mcgraft inject 12345 -a agent.jar --here       # explicit pid; use the client's working dir as the data dir
mcgraft logs --follow /tmp/mcgraft-inject-…    # tail an existing injection log
```

`mcgraft <cmd> --help` for all flags.

## JDK-free packing (`pack` / GUI "Quick pack")

`build` needs a JDK to run Gradle. `pack` does not: it assembles the agent jar in-process from a **precompiled,
payload-independent base jar** (`mc-graft-base.jar`, built once with `./gradlew agentBase`). For a given payload it:

- derives `graft.properties` from the mod's `fabric.mod.json` (+ any config `.properties`),
- derives `mixin-targets.txt` by parsing the mod's `@Mixin` annotations directly (via the `cafebabe` class-file reader),
- resolves `payload.dependencies` from Maven over HTTP and stages them under `agent-libs/`,
- rezips base + payload + those into a full agent jar.

The result is byte-for-byte structurally identical to the Gradle-built agent (same base classes, mixin-target set,
manifest, and bundled deps). The base jar is looked up under `<repo>/build/agent/`, next to the `mcgraft` executable,
or `~/.config/mcgraft/`, so a standalone install (binary + base jar, no JDK, no repo) can build agents out of the box.

## Payload patches (`pack --patch`)

`pack` can apply user patches to the payload jar *before* packing — repeatable `--patch <path>`, applied in order
(the GUI's *Quick pack* mode has an equivalent "Patches" list):

```
mcgraft pack -p yourmod.jar --patch tweaks.mcpatch --patch overlay.zip -o agent.jar
```

Each `--patch` is a `.mcpatch` bundle (a directory or zip with a `manifest.toml`), a bare `.jar`/`.zip` overlay, or a
bare Krakatau `.j` file. Supported (all JDK-free):

- **overlay** (add/replace entries), **`[[delete]]`**, **`[[rename]]`**, and **`[[merge]]`** (RFC-7396 JSON merge, e.g.
  editing `fabric.mod.json`) — no external tools.
- **Whole-class Krakatau assembly** — a bare `.j` file, or a bundle `new/` directory of `.j` files, assembled and
  added/replaced by declared class name. Uses `krak2`: released binaries **embed it** (built with
  `MCGRAFT_BUNDLE_KRAK2=/path/to/krak2`); otherwise it's found via `MCGRAFT_KRAK2` or `PATH`. Only needed when a patch
  actually contains `.j`.
- **In-place bytecode `[[patch]] format = "ops"`** — structured edits to an existing class via `ristretto_classfile`:
  `replace_const` (swap an int/String constant) and `redirect_invoke` (retarget a call). Base-robust (1→1 instruction
  replacement; offsets + stack-map frames recomputed on write), no `krak2` needed.

Patches apply before mod-info derivation, so a merge on `fabric.mod.json` correctly changes the derived
id/mixins/deps/entrypoints. A `[target]` guard warns on mod-id mismatch and hard-fails on `sha256` mismatch (override
with `--no-verify-base`). Deferred to phase 2: the `krakatau-diff` `[[patch]]` format and RFC-6902 `jsonpatch` (both
rejected with a message for now). See [`../docs/patches.md`](../docs/patches.md) for the full format; example bundle at
[`../examples/patches/demo.mcpatch`](../examples/patches/demo.mcpatch).

## How attach works

`inject` resolves the target's attach socket (`/tmp/.java_pid<pid>`), creating the trigger file and sending SIGQUIT if
the socket isn't up yet, then speaks the text protocol (`load instrument false "<jar>=<opts>"`). Agent options are the
same `\x01`-separated `key=value` string the agent expects (`logFile`, `gameDir`, `tolerateBaselineFailures`), so this
is a drop-in replacement for the Java `tool/` launcher.
