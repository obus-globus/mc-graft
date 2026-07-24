# Payload Patches — design

Let a user supply patches that the **packer** applies to the payload mod jar *before* grafting, so a mod can be
tweaked (behavior, resources, metadata, extra/replaced classes) without its build environment. Patching happens at
**pack time, in Rust** — the JDK-free packer stays JDK-free.

Scope: patches operate on the **contents of the payload jar** (the mod's own classes + resources). They do **not** target
Minecraft/loader classes — those don't exist at pack time; they're transformed at attach time by the engine's mixin
pipeline (a separate mechanism). **A patch that needs to change MC behavior must be expressed as a mixin added to the
payload** (drop a mixin class in via `overlay/`/`new/` and add its config to `fabric.mod.json` via a merge). This is the
first limitation users hit, so it's stated up front.

## Status

**Shipped — JVM-free layer (`zip` crate + inline merge, no external tools):** file **overlay** (add/replace),
**delete**, **rename**, and JSON **merge** (RFC 7396). Covers metadata edits, resource swaps, config tweaks, file
removal — the bulk of real demand — with near-zero fragility.

**Shipped — whole-class Krakatau assembly (needs `krak2`):** a **bare `.j`** file and a bundle **`new/`** directory of
`.j` files are assembled (via `krak2 asm`) and added/replaced by their declared class name. This is robust: you author a
*complete* class in Krakatau assembly and it's compiled outright — there is **no diff and no round-trip dependency**, so
the fragility below does not apply. `krak2` is located via `MCGRAFT_KRAK2` or `PATH`, and is only invoked when a patch
actually contains `.j` — overlay/JSON-only patches never need it.

**Shipped — in-place bytecode `[[patch]]` via the `ops` DSL (`ristretto_classfile`):** structured, base-robust edits to
an existing class — `replace_const` (swap an int/String constant) and `redirect_invoke` (retarget a call, descriptor
preserved). Each op is a 1→1 instruction replacement; ristretto stores branch/exception/line/StackMapTable offsets as
*logical instruction offsets* and recomputes all byte offsets + frame deltas on write, so replacements are safe at any
size — no round-trip fragility. (Verified end-to-end: an `ops` patch that changed a constant produced a class that loads
under `-Xverify:all` and returns the new value.) No `krak2` needed for `ops`.

**Deferred to phase 2:** the `krakatau-diff` `[[patch]]` format (the base-fragile *diff*-of-disassembly path — `ops`
supersedes it for most needs) and RFC-6902 `jsonpatch`. Both are parsed and **rejected with a clear message** rather than
silently ignored. See "Phase 2 risks" for why the *diff* approach is held back.

## Input modes

The packer accepts repeatable `--patch <path>`; each is auto-detected and applied in argument order:

1. **Bare Krakatau** — a `.j` file or a directory of `.j` files (no `manifest.toml`). Whole-class assembly.
2. **Bare overlay** — a `.jar`/`.zip` with no `manifest.toml`: every entry is overlaid (add/replace) onto the payload.
3. **Bundle** — a directory or `.mcpatch` (zip) containing a `manifest.toml` (full feature set below).

```
mcgraft pack --payload mod.jar --patch no-watermark.mcpatch --patch hotfix.j -o agent.jar
```

The GUI exposes a reorderable "Patches" list mapping to `--patch` order.

### Bare Krakatau semantics

Each `.j` is assembled with `krak2`. The class identity comes from the `.class` directive **inside** the file, not the
filename:

```
.class public final com/example/mod/HudRenderer   ; decides the jar path
.super java/lang/Object
...
```

- declared class already in the payload → **replace** that entry
- not present → **add** it

Clean whole-class replacement at the bytecode level; no diffs, no manifest.

## Bundle layout

```
no-watermark.mcpatch/            # a directory, or the same tree zipped as .mcpatch
  manifest.toml                  # source of truth
  overlay/                       # verbatim add/replace, keyed by path
    assets/mymod/icon.png
    mymod.mixins.json
  new/                           # *.j -> assembled, add/replace by declared class name
    com/example/mod/ExtraFeature.j
  patch/                         # in-place bytecode edits to EXISTING classes
    HudRenderer.j.diff           # unified diff of the krak2 disassembly
  meta/
    fabric.mod.merge.json        # JSON Merge Patch payload
```

`overlay/` and `new/` are auto-discovered by convention. Operations that need parameters (delete, rename, merge,
bytecode patch) are declared in the manifest.

## manifest.toml

```toml
[bundle]
name    = "no-watermark"
format  = 1                       # manifest schema version

[apply]
on-conflict   = "error"           # error | overlay-wins | patch-wins
verify-target = true              # base guard on/off (see below); default true
# default order (overridable); structural first, content next, edits last:
order = ["delete", "rename", "overlay", "new", "merge", "patch"]

# --- base guard (only enforced when verify-target = true) ---
[target]
mod    = "xaerominimap"           # must match payload fabric.mod.json id -> WARN on mismatch
sha256 = "9f86d0…"                # exact base-jar hash               -> HARD FAIL on mismatch (when present)

# --- file-level ops ---
[[delete]]
path = "assets/mymod/oldicon.png"

[[rename]]
from = "assets/mymod/a.json"
to   = "assets/mymod/b.json"

[[merge]]
path = "fabric.mod.json"
file = "meta/fabric.mod.merge.json"
kind = "merge"                    # merge (RFC 7386) | jsonpatch (RFC 6902)

# --- bytecode patch (in-place edit of an EXISTING class) ---
[[patch]]
class  = "com/example/mod/HudRenderer"
format = "krakatau-diff"          # krakatau-diff (v1). `ops` (structured DSL) is RESERVED for a later phase.
file   = "patch/HudRenderer.j.diff"
```

A `krakatau-diff` patch applies as: **disassemble** the original class → **apply the unified diff** → **reassemble**.
Being a line diff over disassembly, it is sensitive to the exact base class — hence the base guard.

## Apply order & conflicts

Per bundle (then bundles in CLI order), a fixed default pipeline:

`delete` → `rename` → `overlay` → `new` → `merge` → `patch`

Structural changes first, new content next, in-place edits last, so bytecode patches see the final class set
(including overlaid/new classes). 

Conflict model (v1): a conflict is **two different patches writing the same jar path**. It's tracked with a
path→patch ledger and resolved by that bundle's `on-conflict` (default `error`; `overlay-wins`/`patch-wins` allow
last-write-wins with a note). Within one bundle, an overlay *replacing* an original payload entry is normal, not a
conflict. `rename` errors if the source is missing or the target already exists; `merge` errors if the target entry is
missing (you can't merge into nothing). **Phase-2 caveat:** once `[[patch]]` exists, "overlay a class then bytecode-patch
it" makes the diff base the *overlaid* class, not the original — the author almost certainly diffed against the
original. That silent-corruption trap is called out in "Phase 2 risks" and must be handled when bytecode patching lands.

## Base guard

Patches — especially `krakatau-diff` — are base-sensitive. With `verify-target = true` (default):

- `[target].mod` present and mismatched → **warn** (patch built for a different mod; likely a mistake).
- `[target].sha256` present and mismatched → **hard fail** (exact base required; a diff against the wrong bytes
  corrupts silently).

Turn the whole check off with `verify-target = false` in the manifest, or `--no-verify-base` on the CLI (overrides all
bundles for the run). Off by choice, never off by default.

## Validation

**v1** has no bytecode edits, so the only structural risk is malformed JSON — merges are parsed, applied, and
re-serialized, which guarantees the result is well-formed JSON. The patched jar is written and re-read (a round-trip),
then flows through the normal pack pipeline (mixin-target derivation, dependency scan, graft.properties) unchanged. A
`[[merge]]` on `fabric.mod.json` therefore moves the pipeline's inputs by design.

**Phase 2** will re-verify every added/modified `.class` with `ristretto_classfile` (constant-pool integrity, stack
maps, descriptors). Important framing (and a project rule — see CLAUDE.md): **that verify only rejects *malformed*
bytecode; it does NOT prove the patch is *correct*.** A diff that cleanly applies to the wrong instruction, or a class
that verifies but misbehaves at runtime, passes structural verification. Pack-time verify is a lint, not a guarantee —
the real backstop is the engine's runtime `LateAttachVerifier` (fail-closed) that every payload class already passes
through at attach. Patched classes get that same runtime check.

## Krakatau integration (phase 2)

`krakatau2` (`krak2`) is `2.0.0-alpha` and **binary-only** (no `[lib]` target), so we won't depend on it as a crate.
Plan: prefer a `krak2` on `PATH`, with an **embedded binary** (via `include_bytes!`, per target platform, extracted to
a tempdir on first use) as an opt-in convenience — and gate the whole bytecode-patch feature behind a build flag so users
who only want overlay/JSON never carry an alpha JVM tool or hit exec pitfalls (tmpfs `noexec`, AV quarantine, exec bit).
The pinned `krak2` version is recorded so disassembly-format drift is detectable.

## Reserved: structured `ops` DSL (later)

The `format = "ops"` value on a `[[patch]]` is reserved for a future base-robust, semantic patch DSL
(`replace_const` / `remove_call` / `redirect_invoke` / `insert_before` / …) built on `ristretto_classfile`. Given the
phase-2 risks below, `ops` may end up preferable to `krakatau-diff` as the *primary* bytecode format.

## Phase 2 risks (must-solve before bytecode patching ships)

- **`krakatau-diff` base fragility (the big one).** krak2's *default* disassembly does **not** round-trip — it may
  reorder constant-pool entries, so the disassembly text of the same class differs between runs/versions. A unified diff
  over default-mode output is non-deterministic and won't reliably apply. Mitigation: **require krak2's `--roundtrip`
  mode** on both the authoring and applying sides — but roundtrip output is verbose/low-readability, which undercuts the
  "human-readable diff" rationale. This is the strongest argument for the `ops` DSL instead.
- **krak2-version drift is a separate axis** from base-jar drift. The `[target].sha256` guard catches the base jar; it
  does nothing for krak2 output-format changes. Add a per-patch krak2-version guard (hard-fail on mismatch).
- **Java-version coverage.** krak2 targets the Java 19 bytecode spec and doesn't reject newer class-file versions — so a
  Java 21+ payload won't hard-fail but may be *silently mishandled* on reassemble, producing a class that still
  structurally verifies but is subtly wrong. Document and test this.
- **Overlay-then-patch diff base** (see Conflict model) — define the base as the post-overlay class and warn loudly, or
  forbid patching an overlaid class.

## Implementation phases

1. ✅ `--patch` plumbing + input-mode detection (bundle dir/zip, bare overlay zip, bare `.j`) + manifest parser.
2. ✅ File ops: overlay, delete, rename, JSON merge (RFC 7396) + base guard + tests + wire into the pack pipeline.
3. ✅ GUI "Patches" list (Quick-pack mode).
4. ✅ `krak2` (PATH / `MCGRAFT_KRAK2`) wrapper; whole-class assembly — bare `.j` + bundle `new/`.
5. ✅ In-place bytecode `[[patch]] format = "ops"` on `ristretto_classfile` — `replace_const`, `redirect_invoke`.
6. ⏳ More `ops` (`insert_before`/`remove_call`/`set_return` — need frame-index adjustment); `ristretto` post-verify;
      RFC-6902 `jsonpatch`; ship `krak2` self-contained (embed via `include_bytes!` behind a feature).

## Open questions (phase 2)

- **Diff application** — strict exact-context match (fuzz 0) is required; silent fuzzy application on bytecode is
  dangerous. Combined with the roundtrip requirement above.
- **`new/` name/path mismatch** — the `.class` directive wins; warn on mismatch.
- **Per-patch vs per-run guards** — make `--no-verify-base` / `on-conflict` per-patch rather than global for the run.
