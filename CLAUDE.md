# mc-graft

Grafts a pre-built Fabric mod into an already-running Minecraft (vanilla, Fabric or NeoForge) through a JDK dynamic
agent, converting each mixin into a retransform-safe form. It is the late-attach engine from our LiquidBounce fork,
extracted and made mod-agnostic. [README.md](README.md) explains how it works, every build option and every
`graft.properties` key; read it first.

## Where things are

- `engine/` converter, verifier, loader platforms, agent · `mixinservice/` standalone Sponge-Mixin service for bare
  vanilla (package `vspike`) · `tool/` Java attach launcher (its own gradle build) · `ctl/` Rust CLI + GUI covering
  build, JVM discovery and inject (see `ctl/README.md`) · `examples/<mod>/` per-mod configs and patches
- `tools/` test harnesses: `multi-payload-test.sh`, `world-join-test.sh`; `worldprobe/` holds the probe agents
- `docs/` design notes (multi-payload, patches, LiquidBounce mixin audit) · `TODO.md` open work
- Test payload jars live outside the repo in `../mc-graft-fixtures/`
- Gitignored, so local only: `notes/` (session notes, reviews, Windows test guide), `logs/`, `thirdparty/`
- Pre-extraction history: branches `feat/vanilla-agent` → `feat/late-attach-injector` / `feat/late-attach-uninject` →
  `feat/converter-dropped-iface-types` (+ `feat/labymod-late-attach`) on `obus-globus/LiquidBounce`, checked out in
  `../liquidbounce-and-stuff/liquidbounce neoforge/LiquidBounce/`

## Build

- Agent: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew agentJar -Ppayload=<mod.jar>` → `build/agent/`.
  JDK 25 is required because Sponge-Mixin derives its compatibility level from the agent's bytecode version.
- ctl: `cd ctl && ~/.cargo/bin/cargo build --release` (cargo is not on PATH).

## Traps

- No Minecraft is on the build classpath; all game, mod and loader access is by string and reflection at run time.
  A green build says nothing about compatibility with a target version.
- Sidecars are defined at attach time but linked on first use, so reaching the title screen proves little. A hook is
  only exercised once a world is joined (`tools/world-join-test.sh`).
- Windows inject (via jattach) has never been verified end to end.
- `bootstrap.reloadResources` should stay off unless a mod truly defers init to a reload listener; the README explains
  why the reload damages state under late attach.
