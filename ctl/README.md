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

## How attach works

`inject` resolves the target's attach socket (`/tmp/.java_pid<pid>`), creating the trigger file and sending SIGQUIT if
the socket isn't up yet, then speaks the text protocol (`load instrument false "<jar>=<opts>"`). Agent options are the
same `\x01`-separated `key=value` string the agent expects (`logFile`, `gameDir`, `tolerateBaselineFailures`), so this
is a drop-in replacement for the Java `tool/` launcher.
