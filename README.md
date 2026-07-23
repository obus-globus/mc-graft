# mc-graft

Graft a **pre-built Fabric mod** into an **already-running Minecraft** — vanilla, Fabric (Knot), or NeoForge — through a
JDK dynamic agent. No Fabric loader required on the target; the mod's mixins are applied to classes that are already
loaded, using a retransform-safe conversion of each mixin.

This is the late-attach injection engine extracted from a LiquidBounce fork and made **mod-agnostic**: the engine
carries no knowledge of any specific mod. Point the build at a mod jar and it derives what it needs from that mod's
`fabric.mod.json`.

## How it works (one paragraph)

`java.lang.instrument` forbids *schema* changes to already-loaded classes (you cannot add fields, methods, or
interfaces via `retransformClasses`). A mixin routinely does all three. The converter therefore splits each mixin's
output class `X` into a **schema-legal `target'`** (same shape as the original) plus an **external sidecar** that holds
the added methods (as statics taking the receiver as arg 0) and a per-instance `$State` object (a
`WeakIdentityHashMap`) that holds the added fields. Added interfaces are dropped and their dispatch is emulated. A
fail-closed verifier gates every conversion; nothing is published until the whole batch verifies. Loader differences
(vanilla / Knot / FML) live behind a small `LoaderPlatform` SPI.

## Build an agent

```bash
# JDK 25 required (agent is compiled to Java 25 bytecode; Sponge-Mixin derives its CompatibilityLevel from it).
export JAVA_HOME=/path/to/jdk-25

# Minimal: everything else is derived from the mod jar.
./gradlew agentJar -Ppayload=/path/to/mod.jar

# Full options:
./gradlew agentJar \
  -Ppayload=/path/to/mod.jar \                    # the Fabric mod to inject (required)
  -PpayloadLibs=/dir/of/its/runtime/libs \         # optional: the mod's own dependency jars
  -PbootstrapConfig=/path/to/override.properties \ # optional: init override for entrypoint-less mods
  -PmixinTargets=/path/to/targets.txt \            # optional: override the auto-derived target list
  -PagentName=mymod-agent.jar
# -> build/agent/mymod-agent.jar
```

The build reads the payload's `fabric.mod.json` and emits `graft.properties` into the agent (mixin configs,
access-widener, mod id, client/main entrypoints), and **auto-derives the converter's target-class list** from the
`@Mixin(...)` annotations of the classes those mixin configs declare. So **grafting a different mod is just a different
`-Ppayload`** — no target list to maintain. `-PmixinTargets` is only for the rare mod whose targets need refmap
remapping the build can't resolve.

## Attach it

The agent is dynamic-attach only (`Agent-Class`, no `Premain-Class`). Attach to a running client's PID:

```bash
java -cp . Attach <pid> mymod-agent.jar "logFile=/tmp/inject.log"
# (see tool/ for a Swing VM-picker + attach + live log tail launcher)
```

## Payload configuration

`graft.properties` (auto-derived from `fabric.mod.json`; override file appended verbatim):

```
payload.id                 mod id
payload.accessWidener      access-widener resource name ("" if none)
payload.mixins             comma-separated mixin config json names
payload.entrypoints.main   ModInitializer classes (fabric "main")
payload.entrypoints.client ClientModInitializer classes (fabric "client")
payload.mixins.neoforge    optional NeoForge-specific mixin config set
payload.dependencies       external dependency mods to stage, as Maven coordinates (see below)
payload.packages           optional: internal-name prefixes the payload's own classes live under (scopes the caller
                           preflight); empty falls back to the exact payload class set — needed only for fat jars
```

Optional bootstrap keys (all default to off, so a plain entrypoint mod needs none):

```
bootstrap.reloadResources    trigger a resource reload on attach to drive a mod that defers init to a reload listener
bootstrap.thawRegistries     built-in registries the mod writes into during init (e.g. minecraft:sound_event); held
                             writable across the reload and restored after — comma-separated ids
bootstrap.gameDirProperty    system property the mod reads for its data dir (e.g. LiquidBounce reads vspike.gameDir)
bootstrap.joinGate.screen    MC screen internal name whose multiplayer-connect is deferred until the mod reports ready
                             (e.g. net/minecraft/client/gui/screens/ConnectScreen); omit to disable the join gate
```

The AccessWidener namespace is read from the AW file's own header, and the sidecar relocation package (NeoForge only)
defaults to `mcgraft/sc/` (`-Dmcgraft.sidecarPackage=` to override) — neither is hardcoded to any mod.

**Dependencies.** Late-attach bypasses the Fabric loader, which is what normally unpacks a mod's bundled
(jar-in-jar) dependencies and its declared dependency mods. mc-graft handles both:

- the agent **recursively stages jar-in-jar** — the payload's own bundled libraries (and any staged dependency mod's
  nested libraries, e.g. `fabric-language-kotlin` bundling the Kotlin stdlib) are extracted and put on the loader
  automatically; and
- **`payload.dependencies`** (in the `-PbootstrapConfig` file, comma-separated Maven coordinates — use
  `maven.modrinth:<slug>:<version>` for Modrinth mods) declares *external* dependency mods; the build resolves them
  from Maven Central / FabricMC / Modrinth and bundles them so the agent can stage them on a bare instance.
  `-PpayloadDeps=<coords>` does the same from the command line, and `-PpayloadLibs=<dir>` bundles local jars directly.

**Bootstrap.** By default the agent initializes the mod by calling its declared Fabric entrypoints
(`onInitialize` / `onInitializeClient`) — the standard path, and all a normal mod needs. A mod with **no entrypoint**
that initializes via a mixin-fired event (e.g. LiquidBounce) supplies an override describing that event; post-attach
its startup trigger has already passed, so the agent dispatches it manually and (optionally) waits on a readiness
probe before publishing the retransforms. See `examples/liquidbounce/bootstrap.properties`.

## Examples

- `examples/wurst/` — Wurst 7 (a normal `ModInitializer` mod): just `-Ppayload=Wurst-*.jar`, everything else derived.
- `examples/liquidbounce/` — LiquidBounce (no entrypoint; event-driven): add `-PbootstrapConfig=examples/liquidbounce/bootstrap.properties`.

## Status

- **LiquidBounce → clean vanilla MC 26.2**: full inject, config auto-derived from `fabric.mod.json`, **0 verification
  issues**. Validated end-to-end.
- Coverage/limitations of the converter itself (inherited-method overrides, etc.) are inherent to retransform-based
  injection — the verifier reports anything it cannot safely apply rather than silently degrading.

## Layout

```
engine/        converter, verifier, loader platforms, agent, mcgraft.rt runtime helpers
mixinservice/  standalone Sponge-Mixin service (used on bare vanilla)
tool/          attach launcher / UI (standalone gradle build)
examples/      per-mod config (LB, Wurst)
```

Build needs only ASM + Sponge-Mixin + MixinExtras (resolved from Maven Central + FabricMC) — **no Minecraft on the
build classpath**. Every Minecraft/mod/loader interaction is by string + reflection at run time.
