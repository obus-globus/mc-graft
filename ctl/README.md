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
  click *Build agent*. It drives Gradle and fills in the produced agent jar.
- **② Inject**: agent jar (auto-filled by the build), data-dir mode, "tolerate other-mod mixin failures", then
  *Inject → PID*. The injection log streams live into the Log pane.

## CLI

```bash
mcgraft list                                   # attachable JVMs; MC flagged + version

mcgraft build -p LiquidBounce.jar \            # build an agent (drives ./gradlew agentJar)
      -c examples/liquidbounce/bootstrap.properties -n lb-agent.jar

mcgraft inject -a build/agent/lb-agent.jar     # auto-selects the sole MC JVM, attaches, follows the log
mcgraft inject 12345 -a agent.jar --here       # explicit pid; use the client's working dir as the data dir
mcgraft logs --follow /tmp/mcgraft-inject-…    # tail an existing injection log
```

`mcgraft <cmd> --help` for all flags.

## How attach works

`inject` resolves the target's attach socket (`/tmp/.java_pid<pid>`), creating the trigger file and sending SIGQUIT if
the socket isn't up yet, then speaks the text protocol (`load instrument false "<jar>=<opts>"`). Agent options are the
same `\x01`-separated `key=value` string the agent expects (`logFile`, `gameDir`, `tolerateBaselineFailures`), so this
is a drop-in replacement for the Java `tool/` launcher.
