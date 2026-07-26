#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------------------------------
# WORLD-JOIN test — phase P5 of docs/multi-payload.md.
#
# Everything tools/multi-payload-test.sh does, and then the part that had NEVER been done in this project:
# it ACTUALLY ENTERS A WORLD and exercises the co-hooked methods.
#
# Why this exists
# ---------------
# Across 20 archived session logs there were ZERO world joins. Every "verified end-to-end" run stopped at the title
# screen. A sidecar is *defined* at attach time but only *linked on first use*, and every co-hooked method
# (Camera.calculateFov / calculateHudFov / extractRenderState, MouseHandler.onScroll / turnPlayer) needs a world to
# run. So NoClassDefFoundError / IllegalAccessError / VerifyError / AbstractMethodError / duck-dispatch failures on
# those paths were structurally invisible to every test this repo ever had. `COMPOSITION` in multi-payload-test.sh
# proves both payloads' hooks are WOVEN into one method; it says nothing about whether that method ever EXECUTES.
#
# What it does
# ------------
#   1. starts ONE local Fabric (Knot) client, waits for the title screen
#   2. injects payload A, then (optionally) payload B into the SAME JVM
#   3. drives the client into a deterministic superflat creative world with xdotool
#   4. holds each payload's zoom key, then BOTH at once, and after each:
#        - screenshots the framebuffer  (evidence the RENDER LOOP executed the hook)
#        - attaches a read-only probe that invokes Camera.calculateFov / calculateHudFov on the LIVE camera and
#          prints the returned FOV     (evidence the published two-payload method body runs to completion)
#   5. pixel-diffs the screenshots against the un-zoomed baseline
#   6. scans the client log for linkage failures, over the in-world window only
#   7. POSITIVE CONTROL: deliberately raises a NoClassDefFoundError on the game thread and re-runs the same scanner.
#      A run that reports "no errors" without the control firing proves nothing, so the control is not optional.
#
# Usage:
#   tools/world-join-test.sh zoomify              # single-payload control run
#   tools/world-join-test.sh zoomify wurst        # two payloads in one JVM
#   tools/world-join-test.sh --no-pack wurst zoomify
#
# Exit status: 0 = every assertion passed. 1 = a test failure. 2 = environment/setup problem (not a test failure).
#
# ONE client at a time, -Xmx2G: this VM has no swap. Never run this next to a gradle build.
#
# Game-dir fixture (set once, by hand, in $GAME_DIR/options.txt):
#   pauseOnLostFocus:false                              — else a stray focus change opens the pause menu mid-measurement
#   key_key.saveToolbarActivator:key.keyboard.unknown   — vanilla binds C, which is also Zoomify's zoom key
#
# World setup (done once, by hand, and then reused): a Creative + Superflat + cheats world named $WORLD_NAME, the
# player teleported to a fixed pos+rotation (`/tp @s 0.5 -60 0.5 180 0`) facing a 51x21 bookshelf wall built at
# z=-30/-31, holding a diamond sword. The wall gives a high-frequency texture so an FOV change produces a large,
# unambiguous pixel delta; the sword makes calculateHudFov's effect visible. If the world is missing the run stops
# with an ENVIRONMENT FAILURE rather than inventing a weaker scene.
#
# Time/weather freezing is NOT baked into the save — the run issues those commands itself after joining and asserts
# SCENE_FROZEN, because they were once set with the pre-26.2 camelCase gamerule names and silently rejected.
# ---------------------------------------------------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- environment (override by exporting) ------------------------------------------------------------------------
MCGRAFT="${MCGRAFT:-$REPO/ctl/target/release/mcgraft}"
JAVA="${JAVA:-/usr/lib/jvm/graalvm-25/bin/java}"
MC_JAR="${MC_JAR:-$HOME/.gradle/caches/fabric-loom/26.2/minecraft-client.jar}"
MC_CP_FILE="${MC_CP_FILE:-/tmp/fabric-cp.txt}"
GAME_DIR="${GAME_DIR:-/tmp/bare-fabric-run/gamedir}"
ASSETS_DIR="${ASSETS_DIR:-$HOME/.gradle/caches/fabric-loom/assets}"
ASSET_INDEX="${ASSET_INDEX:-26.2-32}"
MC_VERSION="${MC_VERSION:-26.2}"
MC_DISPLAY="${MC_DISPLAY:-:99}"
XDG_RT="${XDG_RT:-/tmp/xrt-mc}"
AGENT_DIR="${AGENT_DIR:-/tmp/mcgraft-mp-agents}"
OUT_ROOT="${OUT_ROOT:-/tmp/mcgraft-world}"
PROBE_JAR="${PROBE_JAR:-$REPO/tools/worldprobe/worldprobe.jar}"
ERROR_JAR="${ERROR_JAR:-$REPO/tools/worldprobe/erroragent.jar}"
ATTACH_CP="${ATTACH_CP:-$REPO/tools/worldprobe}"      # dir holding Attach.class
WORLD_NAME="${WORLD_NAME:-P5Flat}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
INJECT_TIMEOUT="${INJECT_TIMEOUT:-300}"
SETTLE_QUIET="${SETTLE_QUIET:-20}"
JOIN_TIMEOUT="${JOIN_TIMEOUT:-180}"
ZOOM_SETTLE="${ZOOM_SETTLE:-3}"      # seconds to hold a zoom key before measuring (Zoomify animates in ~1s)
SKIP_POSITIVE_CONTROL="${SKIP_POSITIVE_CONTROL:-0}"

DO_PACK=1
[ "${1:-}" = "--no-pack" ] && { DO_PACK=0; shift; }

A_ID="${1:-zoomify}"
B_ID="${2:-}"

RUN_ID="$(date +%Y%m%d-%H%M%S)-${A_ID}${B_ID:+-then-$B_ID}"
OUT="$OUT_ROOT/$RUN_ID"
SHOTS="$OUT/shots"
mkdir -p "$SHOTS" "$AGENT_DIR"
HOST_LOG="$OUT/client.log"

export DISPLAY="$MC_DISPLAY"

# ---- payload registry -------------------------------------------------------------------------------------------
payload_jar() { case "$1" in
    zoomify) echo "${ZOOMIFY_JAR:-/tmp/zoomify-2.16.126.2.jar}" ;;
    wurst)   echo "${WURST_JAR:-/tmp/wurst/build/libs/Wurst-Client-v7.54-MC26.2.jar}" ;;
    liquidbounce) echo "${LIQUIDBOUNCE_JAR:-/tmp/lbpay.jar}" ;;
    *) echo "" ;; esac; }
payload_cfg() { case "$1" in
    zoomify) echo "${ZOOMIFY_CFG:-/tmp/zoomify.properties}" ;;
    wurst)   echo "${WURST_CFG:-/tmp/wurst.properties}" ;;
    liquidbounce) echo "${LIQUIDBOUNCE_CFG:-$REPO/examples/liquidbounce/bootstrap.properties}" ;;
    *) echo "" ;; esac; }
payload_marker() { case "$1" in
    zoomify) echo 'dev\.isxander\.zoomify\.Zoomify\.onInitializeClient' ;;
    wurst)   echo 'net\.wurstclient\.WurstInitializer\.onInitialize' ;;
    liquidbounce) echo 'liquidbounce initialization completed' ;;
    *) echo "" ;; esac; }
# The key each payload binds its zoom to. Both hook Camera.calculateFov(F)F, which is why this pair is the money shot.
#   zoomify: KeyMapping("zoomify.key.zoom", KEYSYM, 67 /* C */)  — read out of Zoomify.class, not guessed
#   wurst:   ZoomOtf keybind setting, default "key.keyboard.v"
payload_zoomkey() { case "$1" in zoomify) echo c ;; wurst) echo v ;; *) echo "" ;; esac; }

# ---- reporting --------------------------------------------------------------------------------------------------
FAILED=0
declare -a RESULTS=()
say()  { printf '%s\n' "$*"; }
hdr()  { printf '\n=== %s\n' "$*"; }
ok()   { RESULTS+=("PASS  $1  $2"); printf '  [PASS] %-24s %s\n' "$1" "$2"; }
bad()  { RESULTS+=("FAIL  $1  $2"); FAILED=1; printf '  [FAIL] %-24s %s\n' "$1" "$2"; }
skip() { RESULTS+=("SKIP  $1  $2"); printf '  [SKIP] %-24s %s\n' "$1" "$2"; }
note() { printf '  [note] %s\n' "$*"; }
envdie() { printf '\nENVIRONMENT FAILURE: %s\n' "$*" >&2; cleanup; exit 2; }

MC_PID=""
cleanup() { [ -n "$MC_PID" ] && kill -9 "$MC_PID" 2>/dev/null; pkill -9 -f jcef_helper 2>/dev/null; return 0; }
trap cleanup EXIT

# ---- linkage-failure scanner ------------------------------------------------------------------------------------
# The exact failure class this whole test exists to catch. Deliberately broad: a first-use link failure of a sidecar
# surfaces as one of these, on a game thread, with no mc-graft prefix anywhere near it.
LINK_PAT='NoClassDefFoundError|IllegalAccessError|VerifyError|AbstractMethodError|NoSuchMethodError|NoSuchFieldError|IncompatibleClassChangeError|ExceptionInInitializerError|LinkageError|ClassFormatError|UnsupportedClassVersionError|ClassCircularityError|ClassCastException|Minecraft Crash Report|A fatal error|Exception in thread|Negative time elapsed'
# scan_from <byte-offset> -> prints matching lines from HOST_LOG at/after that offset
scan_from() { tail -c "+$(( ${1:-1} ))" "$HOST_LOG" 2>/dev/null | grep -aE "$LINK_PAT" | grep -av 'Attach Listener'; }
logsize()   { wc -c < "$HOST_LOG" 2>/dev/null || echo 0; }

# ---- preflight --------------------------------------------------------------------------------------------------
hdr "preflight"
[ -x "$MCGRAFT" ]    || envdie "mcgraft CLI not found/executable: $MCGRAFT"
[ -x "$JAVA" ]       || envdie "java not found: $JAVA"
[ -f "$MC_CP_FILE" ] || envdie "launch classpath file not found: $MC_CP_FILE"
[ -f "$MC_JAR" ]     || envdie "minecraft client jar not found: $MC_JAR"
[ -d "$GAME_DIR" ]   || envdie "game dir not found: $GAME_DIR"
# The probe/attach helpers are tiny single-file agents; build them on demand rather than checking in class files.
if [ ! -f "$PROBE_JAR" ] || [ ! -f "$ERROR_JAR" ] || [ ! -f "$ATTACH_CP/Attach.class" ]; then
    say "  building the probe agents in $REPO/tools/worldprobe"
    ( cd "$REPO/tools/worldprobe" \
      && "${JAVA%/java}/javac" -d . WorldProbe.java ErrorAgent.java Attach.java \
      && printf 'Agent-Class: WorldProbe\nCan-Retransform-Classes: true\n' > manifest.txt \
      && printf 'Agent-Class: ErrorAgent\n' > manifest-err.txt \
      && "${JAVA%/java}/jar" cfm worldprobe.jar manifest.txt WorldProbe*.class \
      && "${JAVA%/java}/jar" cfm erroragent.jar manifest-err.txt ErrorAgent*.class ) \
      > "$OUT/build-probes.log" 2>&1 || { cat "$OUT/build-probes.log"; envdie "could not build the probe agents"; }
fi
[ -f "$PROBE_JAR" ]  || envdie "world probe jar not built: $PROBE_JAR"
[ -f "$ATTACH_CP/Attach.class" ] || envdie "Attach.class not found in $ATTACH_CP"
[ -d "$GAME_DIR/saves/$WORLD_NAME" ] || envdie "test world '$WORLD_NAME' not found under $GAME_DIR/saves — see the header"
for t in xdotool scrot compare convert; do command -v $t >/dev/null || envdie "missing GUI tool: $t"; done
pgrep -x Xvfb >/dev/null 2>&1 || envdie "no Xvfb; DISPLAY=$MC_DISPLAY will not exist"
for id in "$A_ID" ${B_ID:+$B_ID}; do
    j="$(payload_jar "$id")"; c="$(payload_cfg "$id")"
    [ -n "$j" ] || envdie "unknown payload id '$id'"
    [ -f "$j" ] || envdie "payload jar for '$id' not found: $j"
    [ -f "$c" ] || envdie "payload config for '$id' not found: $c"
done
say "  repo=$REPO"
say "  output=$OUT"
say "  payloads: A=$A_ID${B_ID:+  B=$B_ID}"

# Keybind conflict, stripped so the two payloads' zoom keys stay independent variables.
# Zoomify binds zoom to C (KeyMapping("zoomify.key.zoom", KEYSYM, 67), read out of Zoomify.class).
# Wurst's own keybind system binds C to Fullbright by default. One xdotool keypress would then drive both.
# In the 2026-07-26 zoomify+wurst run this was measured harmless — Fullbright is a no-op in a noon superflat
# world, the released frame still came back to within 2398 px of the baseline — but it is still a confound.
# The file has to stay NON-EMPTY: Wurst rewrites the full default set over an empty one during init.
if [ -f "$GAME_DIR/wurst/keybinds.json" ]; then
    python3 - "$GAME_DIR/wurst/keybinds.json" <<'PY'
import json, sys
p = sys.argv[1]
try: d = json.load(open(p))
except Exception: d = {}
if d.pop("key.keyboard.c", None) is not None:
    if not d: d = {"key.keyboard.p": "fullbright"}   # keep it non-empty or Wurst restores the defaults
    json.dump(d, open(p, "w"), indent=2)
    print("  stripped wurst's key.keyboard.c -> fullbright binding (conflicts with zoomify's zoom key)")
PY
fi
[ -f "$GAME_DIR/wurst/enabled-hacks.json" ] && echo '[]' > "$GAME_DIR/wurst/enabled-hacks.json"

# ---- pack agents ------------------------------------------------------------------------------------------------
hdr "packing agents"
declare -A AGENT=()
for id in "$A_ID" ${B_ID:+$B_ID}; do
    jar="$AGENT_DIR/$id-agent.jar"
    if [ "$DO_PACK" = 1 ]; then
        "$MCGRAFT" pack --payload "$(payload_jar "$id")" --config "$(payload_cfg "$id")" --out "$jar" \
            > "$OUT/pack-$id.log" 2>&1 || { cat "$OUT/pack-$id.log"; envdie "pack failed for '$id'"; }
    fi
    [ -f "$jar" ] || envdie "agent jar missing for '$id': $jar (drop --no-pack?)"
    AGENT[$id]="$jar"
    say "  $id -> $jar ($(stat -c %s "$jar") bytes)"
done

# ---- start ONE client -------------------------------------------------------------------------------------------
hdr "starting client"
pkill -9 -f "knot.KnotClient" 2>/dev/null; pkill -9 -f jcef_helper 2>/dev/null; pkill -9 -f zenity 2>/dev/null; sleep 4
mkdir -p "$XDG_RT"
nohup env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE -u XAUTHORITY DISPLAY="$MC_DISPLAY" XDG_RUNTIME_DIR="$XDG_RT" \
    "$JAVA" -Xmx2G --enable-native-access=ALL-UNNAMED -Djava.awt.headless=false \
    -Dfabric.gameJarPath="$MC_JAR" \
    -cp "$(cat "$MC_CP_FILE")" net.fabricmc.loader.impl.launch.knot.KnotClient \
    --username Dev --version "$MC_VERSION" --gameDir "$GAME_DIR" --assetsDir "$ASSETS_DIR" --assetIndex "$ASSET_INDEX" \
    --accessToken 0 --uuid 00000000000000000000000000000001 --userType legacy > "$HOST_LOG" 2>&1 &
MC_PID=$!
say "  pid=$MC_PID  log=$HOST_LOG"

deadline=$((SECONDS + BOOT_TIMEOUT))
until grep -qiE "Backend library|Reloading ResourceManager" "$HOST_LOG" 2>/dev/null; do
    sleep 5
    kill -0 "$MC_PID" 2>/dev/null || envdie "client exited during startup; see $HOST_LOG"
    [ $SECONDS -lt $deadline ] || envdie "client did not reach the title screen in ${BOOT_TIMEOUT}s; see $HOST_LOG"
done
prev=""; stable=0; need=$(( SETTLE_QUIET / 5 ))
while [ $stable -lt $need ]; do
    sleep 5
    kill -0 "$MC_PID" 2>/dev/null || envdie "client exited while settling; see $HOST_LOG"
    cur=$(logsize); if [ "$cur" = "$prev" ]; then stable=$((stable+1)); else stable=0; fi; prev=$cur
done
WID="$(xdotool search --name "Minecraft" 2>/dev/null | head -1)"
[ -n "$WID" ] || envdie "no Minecraft window on $MC_DISPLAY"
read -r WX WY WW WH < <(xwininfo -id "$WID" | awk '
    /Absolute upper-left X/{x=$4} /Absolute upper-left Y/{y=$4} /^  Width:/{w=$2} /^  Height:/{h=$2}
    END{print x, y, w, h}')
say "  client idle at the menu (log size $prev), window $WID at ${WX},${WY} ${WW}x${WH}"
xdotool windowactivate "$WID"; sleep 1

# shot <name> — full-display scrot, plus a crop of just the game viewport (no title bar) for pixel maths.
#
# NOTE: this deliberately never moves the mouse. Once in a world Minecraft grabs the pointer
# (GLFW_CURSOR_DISABLED), so ANY xdotool mousemove is delivered as look input and turns the camera —
# which would swamp the FOV pixel delta with a completely different view. Cursor parking happens only
# while a menu is up, via park_cursor.
IN_GAME=0
park_cursor() { [ "$IN_GAME" = 1 ] && return 0; xdotool mousemove $((WX + 100)) $((WY + WH - 60)) >/dev/null 2>&1; }
shot() {
    park_cursor
    sleep 1
    scrot -o "$SHOTS/$1.png"
    convert "$SHOTS/$1.png" -crop "${WW}x${WH}+${WX}+${WY}" +repage "$SHOTS/$1-view.png"
}
# probe <name> — attach the read-only WorldProbe and dump its report
probe() {
    "$JAVA" -cp "$ATTACH_CP" Attach "$MC_PID" "$PROBE_JAR" "$OUT/probe-$1.txt" > "$OUT/probe-$1.attach.log" 2>&1
}
# fov <probe-name> — pull the calculateFov number out of a probe dump
fov()    { grep -m1 -oE 'calculateFov\(1\.0f\) = [0-9.E-]+'    "$OUT/probe-$1.txt" 2>/dev/null | grep -oE '[0-9.E-]+$'; }
hudfov() { grep -m1 -oE 'calculateHudFov\(1\.0f\) = [0-9.E-]+' "$OUT/probe-$1.txt" 2>/dev/null | grep -oE '[0-9.E-]+$'; }
sens()   { grep -m1 -oE 'sensitivity = [0-9.E-]+'              "$OUT/probe-$1.txt" 2>/dev/null | grep -oE '[0-9.E-]+$'; }
# pxdiff <a> <b> — count differing pixels between two viewport crops.
#
# -fuzz 5% is load-bearing, not cosmetic. This client renders through Mesa llvmpipe, and two frames of the
# IDENTICAL scene differ in ~217k of 409,920 pixels by a channel step or two. Without the fuzz the
# released-zoom frame scores 217216 against a held-zoom score of 381693 — a 1.8x separation that proves
# nothing. With it: 1527 vs 307165, a 200x separation. Measured, both ways, on the zoomify control run.
pxdiff() { compare -metric AE -fuzz 5% "$SHOTS/$1-view.png" "$SHOTS/$2-view.png" null: 2>&1 | grep -oE '^[0-9]+' | head -1; }

shot 00-title
probe 00-title
say "  baseline probe (title, pre-injection): calculateFov=$(fov 00-title)  sidecars=$(grep -m1 -oE 'count=[0-9]+' "$OUT/probe-00-title.txt")"

# ---- inject + assert --------------------------------------------------------------------------------------------
inject_and_assert() {
    local slot="$1" id="$2"
    local log="$OUT/inject-$slot-$id.log" cli="$OUT/cli-$slot-$id.log"
    local marker; marker="$(payload_marker "$id")"

    hdr "payload $slot: $id"
    kill -0 "$MC_PID" 2>/dev/null || { bad "$slot/CLIENT_ALIVE" "client was already dead before this injection"; return; }

    "$MCGRAFT" inject --agent "${AGENT[$id]}" --log "$log" --no-follow "$MC_PID" > "$cli" 2>&1
    local rc=$?
    say "  mcgraft inject exit=$rc  (cli: $cli)"

    local d=$((SECONDS + INJECT_TIMEOUT))
    while [ $SECONDS -lt $d ]; do
        [ -f "$log" ] && grep -qE "payload injection complete|re-injection refused|\[ERROR\]" "$log" && break
        kill -0 "$MC_PID" 2>/dev/null || break
        sleep 3
    done
    sleep 3

    if [ ! -s "$log" ]; then
        bad "$slot/ATTACH_ACCEPTED" "no injection log was written at all — the agent never ran"; sed 's/^/        /' "$cli"; return
    fi
    if grep -q "re-injection refused" "$log"; then
        bad "$slot/ATTACH_ACCEPTED" "REFUSED: $(grep -m1 're-injection refused' "$log")"
    elif [ "$rc" != 0 ]; then
        bad "$slot/ATTACH_ACCEPTED" "mcgraft inject exited $rc"
    else ok "$slot/ATTACH_ACCEPTED" "agent ran"; fi

    local loaded; loaded="$(grep -m1 -oE "payload '[^']+'" "$log" | head -1)"
    if [ "$loaded" = "payload '$id'" ]; then ok "$slot/PAYLOAD_IDENTITY" "$loaded"
    else bad "$slot/PAYLOAD_IDENTITY" "asked for '$id', agent loaded ${loaded:-<nothing>}"; fi

    if grep -q "payload injection complete" "$log"; then ok "$slot/INJECT_COMPLETE" "'payload injection complete'"
    else bad "$slot/INJECT_COMPLETE" "never completed; last line: $(tail -1 "$log")"; fi

    local errs; errs="$(grep -c "\[ERROR\]" "$log")"
    if [ "$errs" = 0 ]; then ok "$slot/NO_AGENT_ERRORS" "0 [ERROR] lines"
    else bad "$slot/NO_AGENT_ERRORS" "$errs [ERROR]; first: $(grep -m1 '\[ERROR\]' "$log")"; fi

    if [ -n "$marker" ] && grep -qE "$marker" "$log"; then ok "$slot/BOOTSTRAP_RAN" "entrypoint ran"
    else bad "$slot/BOOTSTRAP_RAN" "no line matching /$marker/"; fi
}

inject_and_assert A "$A_ID"
[ -n "$B_ID" ] && inject_and_assert B "$B_ID"

# COMPOSITION — both payloads' hooks WOVEN into one method. (Not evidence either executed; that is what follows.)
if [ -n "$B_ID" ]; then
    hdr "composition (from the bytes actually published)"
    B_LOG="$OUT/inject-B-$B_ID.log"
    COMPO="$(grep -m1 "composition: .*co-hooked methods:" "$B_LOG" 2>/dev/null)"
    A_KEY="$(printf '%s' "$A_ID" | tr -c 'A-Za-z0-9_' '_')"
    B_KEY="$(printf '%s' "$B_ID" | tr -c 'A-Za-z0-9_' '_')"
    if [ -n "$COMPO" ] && printf '%s' "$COMPO" | grep -q "$A_KEY" && printf '%s' "$COMPO" | grep -q "$B_KEY"; then
        ok "COMPOSITION" "$(printf '%s' "$COMPO" | sed 's/^\[mc-graft\] \[INFO\] //' | cut -c1-150)"
    else
        bad "COMPOSITION" "no co-hooked method naming both '$A_KEY' and '$B_KEY'"
    fi
    grep -q "composition INCOMPLETE" "$B_LOG" 2>/dev/null \
        && bad "COMPOSITION_COMPLETE" "$(grep -m1 -o 'composition INCOMPLETE.*' "$B_LOG" | cut -c1-160)" \
        || ok "COMPOSITION_COMPLETE" "no target failed to take the second payload's hooks"
fi

POST_INJECT_OFF=$(( $(logsize) + 1 ))
shot 01-title-injected

# ---- JOIN THE WORLD ---------------------------------------------------------------------------------------------
# This is the step no run in this repo's history had ever taken.
hdr "joining world '$WORLD_NAME'"
kill -0 "$MC_PID" 2>/dev/null || envdie "client died before the world join"
xdotool windowactivate "$WID"; sleep 1
xdotool mousemove $((WX + 426)) $((WY + 235)); sleep 0.4; xdotool click 1; sleep 4    # Singleplayer
xdotool type --delay 60 "$WORLD_NAME"; sleep 2                                        # search box is auto-focused
shot 02-world-list
xdotool mousemove $((WX + 349)) $((WY + 136)); sleep 0.4; xdotool click --repeat 2 --delay 120 1

d=$((SECONDS + JOIN_TIMEOUT)); joined=0
while [ $SECONDS -lt $d ]; do
    grep -q "Time elapsed" "$HOST_LOG" 2>/dev/null && { joined=1; break; }
    kill -0 "$MC_PID" 2>/dev/null || break
    sleep 3
done
sleep 15   # let chunks build and the render settle
IN_GAME=1  # from here on the pointer is grabbed by the game: no mousemove, ever (see shot())

# Freeze the scene. Without this the daylight cycle keeps running and every screenshot after the first is
# darker than the one before: MEASURED on a zero-payload control run, `getSkyDarken` 2 -> 11 over 150s and
# 358057 of 409920 pixels changed with NOTHING injected. That drift is the same order as the zoom signal,
# so it would be attributed to a payload. Set every run rather than baked into the save, so the fixture
# cannot silently rot.
#
# The rule names are snake_case in 26.2 (`advance_time`, not `doDaylightCycle`) — the camelCase forms are
# rejected with "Incorrect argument for command", silently, since chat errors do not reach the log. That is
# exactly how the drift went unnoticed. ctrl+a before typing clears whatever autocomplete left behind.
mc_cmd() {
    xdotool key t; sleep 1.0
    xdotool key ctrl+a; sleep 0.3
    xdotool type --delay 30 "$1"; sleep 0.8
    xdotool key Return; sleep 1.5
}
mc_cmd "/gamerule advance_time false"
mc_cmd "/gamerule advance_weather false"
mc_cmd "/time set 6000"
mc_cmd "/weather clear"
mc_cmd "/kill @e[type=!player]"
sleep 12   # let the chat lines fade out, or they would differ between the baseline and later frames

POST_JOIN_OFF=$(( $(logsize) + 1 ))

probe 10-inworld-base
shot  10-inworld-base
IN_WORLD="$(grep -m1 -oE 'IN_WORLD = (true|false)' "$OUT/probe-10-inworld-base.txt")"
if [ "$IN_WORLD" = "IN_WORLD = true" ]; then
    ok "WORLD_JOINED" "$(grep -m1 'player pos' "$OUT/probe-10-inworld-base.txt" | sed 's/^ *//')  screen=$(grep -m1 'mc.screen' "$OUT/probe-10-inworld-base.txt" | sed 's/.*= //')"
else
    bad "WORLD_JOINED" "probe says ${IN_WORLD:-<no probe output>} (server-side join marker seen: $joined)"
fi

SIDECARS="$(grep -m1 -A1 '$$Sidecar\$ classes defined' "$OUT/probe-10-inworld-base.txt" | grep -oE 'count=[0-9]+')"
say "  sidecars defined in the JVM: $SIDECARS"

FOV_BASE="$(fov 10-inworld-base)"; HUD_BASE="$(hudfov 10-inworld-base)"; SENS_BASE="$(sens 10-inworld-base)"
if [ -n "$FOV_BASE" ]; then
    ok "COHOOKED_EXECUTES" "Camera.calculateFov(1.0f) returned $FOV_BASE on the live camera — the published body ran to completion (no VerifyError/NoClassDefFoundError/IllegalAccessError)"
else
    bad "COHOOKED_EXECUTES" "calculateFov did not return a value; see $OUT/probe-10-inworld-base.txt"
fi
say "  baseline: fov=$FOV_BASE hudFov=$HUD_BASE sensitivity=$SENS_BASE"

# ---- zoom measurements ------------------------------------------------------------------------------------------
# hold_measure <label> <key...> — hold the given keys, screenshot + probe, release.
hold_measure() {
    local label="$1"; shift
    xdotool windowactivate "$WID"; sleep 0.5
    for k in "$@"; do xdotool keydown "$k"; done
    sleep "$ZOOM_SETTLE"
    shot  "$label"
    probe "$label"
    for k in "$@"; do xdotool keyup "$k"; done
    sleep 2
}
# hold_scroll_measure <label> <scrolls> <key...> — same, but scrolls the wheel while the key is held.
# Both payloads route scroll-while-zooming through MouseHandler.onScroll(JDD)V, the OTHER co-hooked
# target class, so this covers it too.
hold_scroll_measure() {
    local label="$1" n="$2"; shift 2
    xdotool windowactivate "$WID"; sleep 0.5
    for k in "$@"; do xdotool keydown "$k"; done
    sleep "$ZOOM_SETTLE"
    for _ in $(seq 1 "$n"); do xdotool click 4; sleep 0.25; done   # button 4 = wheel up
    sleep "$ZOOM_SETTLE"
    shot  "$label"
    probe "$label"
    for k in "$@"; do xdotool keyup "$k"; done
    sleep 2
}

hdr "zoom measurements (each payload alone, then both at once)"
declare -A FOVS=() DIFFS=()
FOVS[base]="$FOV_BASE"

for id in "$A_ID" ${B_ID:+$B_ID}; do
    k="$(payload_zoomkey "$id")"; [ -n "$k" ] || { skip "ZOOM_$id" "no zoom key known for '$id'"; continue; }
    hold_measure "20-zoom-$id" "$k"
    f="$(fov "20-zoom-$id")"; FOVS[$id]="$f"
    dp="$(pxdiff 10-inworld-base "20-zoom-$id")"; DIFFS[$id]="$dp"
    if [ -n "$f" ] && awk "BEGIN{exit !($f < $FOV_BASE - 0.5)}"; then
        ok "ZOOM_FOV_$id" "key '$k' held: calculateFov $FOV_BASE -> $f  (hudFov $HUD_BASE -> $(hudfov "20-zoom-$id"))"
    else
        bad "ZOOM_FOV_$id" "key '$k' held but calculateFov stayed at ${f:-<none>} (baseline $FOV_BASE)"
    fi
    if [ -n "$dp" ] && [ "$dp" -gt 50000 ]; then
        ok "ZOOM_PIXELS_$id" "$dp differing pixels vs the un-zoomed baseline — the RENDER LOOP used the hooked FOV"
    else
        bad "ZOOM_PIXELS_$id" "only ${dp:-?} differing pixels; the frame did not visibly change"
    fi
    # release check
    shot  "21-release-$id"
    probe "21-release-$id"
    fr="$(fov "21-release-$id")"
    dr="$(pxdiff 10-inworld-base "21-release-$id")"
    if [ -n "$fr" ] && awk "BEGIN{exit !($fr > $FOV_BASE - 0.5)}"; then
        ok "ZOOM_RELEASE_$id" "released: calculateFov back to $fr"
    else
        bad "ZOOM_RELEASE_$id" "after release calculateFov is $fr, not back at $FOV_BASE"
    fi
    # The released frame must return to the baseline frame, not merely differ from the zoomed one — this is
    # the control that says the zoom delta above is the zoom and not drift.
    if [ -n "$dr" ] && [ -n "$dp" ] && [ "$dp" -gt 0 ] && [ "$dr" -lt $(( dp / 10 )) ]; then
        ok "ZOOM_RELEASE_PIXELS_$id" "released frame is back at the baseline: $dr differing pixels vs $dp while held"
    else
        bad "ZOOM_RELEASE_PIXELS_$id" "released frame still differs from the baseline by $dr px (held: $dp)"
    fi

    # MouseHandler.onScroll(JDD)V — the other co-hooked target class. Both payloads deepen the zoom on
    # a wheel event while the zoom key is held, so a further FOV drop is evidence that method executed.
    hold_scroll_measure "22-scroll-$id" 5 "$k"
    fs="$(fov "22-scroll-$id")"
    if [ -n "$fs" ] && [ -n "$f" ] && awk "BEGIN{exit !($fs < $f - 0.2)}"; then
        ok "SCROLL_ONSCROLL_$id" "wheel-up x5 while zooming: calculateFov $f -> $fs — MouseHandler.onScroll executed"
    else
        bad "SCROLL_ONSCROLL_$id" "wheel-up while zooming did not deepen the zoom (held=$f scrolled=${fs:-<none>})"
    fi
    xdotool keyup "$k" 2>/dev/null; sleep 2
done

# ---- THE MONEY SHOT: both payloads' hooks active on the SAME method, at once ------------------------------------
if [ -n "$B_ID" ]; then
    hdr "both payloads zooming at once on Camera.calculateFov(F)F"
    KA="$(payload_zoomkey "$A_ID")"; KB="$(payload_zoomkey "$B_ID")"
    if [ -n "$KA" ] && [ -n "$KB" ]; then
        hold_measure "30-zoom-both" "$KA" "$KB"
        FBOTH="$(fov 30-zoom-both)"; FOVS[both]="$FBOTH"
        DBOTH="$(pxdiff 10-inworld-base 30-zoom-both)"
        FA="${FOVS[$A_ID]:-}"; FB="${FOVS[$B_ID]:-}"
        say "  fov: base=$FOV_BASE  $A_ID=$FA  $B_ID=$FB  both=$FBOTH"
        if [ -n "$FBOTH" ] && [ -n "$FA" ] && [ -n "$FB" ] \
           && awk "BEGIN{exit !($FBOTH < $FA - 0.5 && $FBOTH < $FB - 0.5)}"; then
            # both hooks composing multiplicatively => base * (FA/base) * (FB/base)
            EXP="$(awk "BEGIN{printf \"%.4f\", $FA*$FB/$FOV_BASE}")"
            ok "BOTH_HOOKS_FIRE" "both keys held -> $FBOTH, strictly below either alone ($FA / $FB). Multiplicative composition predicts $EXP."
        else
            bad "BOTH_HOOKS_FIRE" "both keys held -> ${FBOTH:-<none>}; not below both single-payload values ($FA / $FB) — one hook is being lost"
        fi
        if [ -n "$DBOTH" ] && [ "$DBOTH" -gt 50000 ]; then
            ok "BOTH_PIXELS" "$DBOTH differing pixels vs baseline"
        else
            bad "BOTH_PIXELS" "only ${DBOTH:-?} differing pixels"
        fi
        shot 31-release-both; probe 31-release-both
        FRB="$(fov 31-release-both)"; DRB="$(pxdiff 10-inworld-base 31-release-both)"
        if [ -n "$FRB" ] && awk "BEGIN{exit !($FRB > $FOV_BASE - 0.5)}" && [ -n "$DRB" ] && [ "$DRB" -lt $(( DBOTH / 10 )) ]; then
            ok "BOTH_RELEASE" "both keys released: calculateFov back to $FRB, frame back to baseline ($DRB px vs $DBOTH while held)"
        else
            bad "BOTH_RELEASE" "after releasing both: calculateFov=$FRB (baseline $FOV_BASE), $DRB px still differ (held $DBOTH)"
        fi
    else
        skip "BOTH_HOOKS_FIRE" "one of the payloads has no zoom key"
    fi
fi

# ---- Wurst's sensitivity side effect: proof the GAME LOOP ran the hook, not the probe ---------------------------
# ZoomOtf.changeFovBasedOnZoom is reachable ONLY from Wurst's Camera.calculateFov / calculateHudFov mixins, and it
# writes Options.sensitivity. So a changed sensitivity is game-loop evidence, independent of the probe's own call.
if [ "$A_ID" = wurst ] || [ "$B_ID" = wurst ]; then
    SW="$(sens 20-zoom-wurst)"; SR="$(sens 21-release-wurst)"
    if [ -n "$SW" ] && [ -n "$SENS_BASE" ] && awk "BEGIN{exit !($SW < $SENS_BASE - 0.001)}"; then
        ok "WURST_SIDE_EFFECT" "Options.sensitivity $SENS_BASE -> $SW while zooming, $SR after release — ZoomOtf.changeFovBasedOnZoom executed"
    else
        bad "WURST_SIDE_EFFECT" "sensitivity did not move (base=$SENS_BASE zoom=$SW release=$SR)"
    fi
fi

# ---- log scan over the in-world window --------------------------------------------------------------------------
# ---- scene stability: did anything OTHER than the payloads change the frame during the run? ---------------------
hdr "scene stability"
skyd() { grep -m1 -oE 'level\.getSkyDarken = [0-9]+' "$OUT/probe-$1.txt" 2>/dev/null | grep -oE '[0-9]+$'; }
LAST_PROBE="31-release-both"; [ -n "$B_ID" ] || LAST_PROBE="21-release-$A_ID"
[ -f "$OUT/probe-$LAST_PROBE.txt" ] || LAST_PROBE="21-release-${B_ID:-$A_ID}"
SD0="$(skyd 10-inworld-base)"; SD1="$(skyd "$LAST_PROBE")"
if [ -n "$SD0" ] && [ "$SD0" = "$SD1" ]; then
    ok "SCENE_FROZEN" "level.getSkyDarken stayed $SD0 from the baseline frame to $LAST_PROBE — the pixel deltas above are the zoom, not the daylight cycle"
else
    bad "SCENE_FROZEN" "level.getSkyDarken moved ${SD0:-?} -> ${SD1:-?} during the run; every pixel delta above is contaminated by world lighting drift"
fi

hdr "linkage-failure scan (in-world window only)"
INWORLD_HITS="$(scan_from "$POST_JOIN_OFF")"
POSTINJ_HITS="$(scan_from "$POST_INJECT_OFF")"
printf '%s' "$POSTINJ_HITS" > "$OUT/scan-post-inject.txt"
printf '%s' "$INWORLD_HITS" > "$OUT/scan-in-world.txt"
if [ -z "$INWORLD_HITS" ]; then
    ok "NO_INWORLD_LINK_FAILURE" "0 lines matching the linkage-failure pattern after the world join"
else
    bad "NO_INWORLD_LINK_FAILURE" "$(printf '%s' "$INWORLD_HITS" | wc -l) line(s):"
    printf '%s\n' "$INWORLD_HITS" | head -5 | sed 's/^/        /'
fi
if kill -0 "$MC_PID" 2>/dev/null; then ok "CLIENT_ALIVE" "pid $MC_PID still running after the whole in-world run"
else bad "CLIENT_ALIVE" "client died; tail: $(tail -3 "$HOST_LOG" | tr '\n' ' ')"; fi

# ---- POSITIVE CONTROL -------------------------------------------------------------------------------------------
# Prove the scanner above is not simply blind. This kills the client, so it is last.
if [ "$SKIP_POSITIVE_CONTROL" = 1 ]; then
    skip "SCANNER_POSITIVE_CONTROL" "SKIP_POSITIVE_CONTROL=1"
elif [ ! -f "$ERROR_JAR" ]; then
    bad "SCANNER_POSITIVE_CONTROL" "erroragent.jar not built — the clean scan above is unverified and means nothing"
else
    hdr "POSITIVE CONTROL — deliberately raise a NoClassDefFoundError on the game thread"
    CTRL_OFF=$(( $(logsize) + 1 ))
    "$JAVA" -cp "$ATTACH_CP" Attach "$MC_PID" "$ERROR_JAR" x > "$OUT/erroragent.log" 2>&1
    for _ in $(seq 1 20); do [ -n "$(scan_from "$CTRL_OFF")" ] && break; sleep 2; done
    CTRL_HITS="$(scan_from "$CTRL_OFF")"
    printf '%s' "$CTRL_HITS" > "$OUT/scan-positive-control.txt"
    if printf '%s' "$CTRL_HITS" | grep -q MCGRAFT_P5_POSITIVE_CONTROL; then
        ok "SCANNER_POSITIVE_CONTROL" "the scanner caught the induced error, so its clean verdict above is meaningful"
        say "        $(printf '%s' "$CTRL_HITS" | grep -m1 MCGRAFT_P5_POSITIVE_CONTROL | cut -c1-160)"
    else
        bad "SCANNER_POSITIVE_CONTROL" "induced a NoClassDefFoundError on the game thread and the scanner did NOT see it — every 'no errors' verdict in this run is worthless"
    fi
fi

# ---- summary ----------------------------------------------------------------------------------------------------
hdr "measured FOV (Camera.calculateFov(1.0f), live camera, published bytes)"
for k in base "$A_ID" ${B_ID:+$B_ID} ${B_ID:+both}; do
    printf '  %-12s %s\n' "$k" "${FOVS[$k]:-n/a}"
done
hdr "pixel delta vs the un-zoomed in-world baseline"
for k in "$A_ID" ${B_ID:+$B_ID}; do printf '  %-12s %s\n' "$k" "${DIFFS[$k]:-n/a}"; done
[ -n "$B_ID" ] && printf '  %-12s %s\n' both "${DBOTH:-n/a}"

hdr "summary  ($A_ID${B_ID:+ then $B_ID})"
printf '%s\n' "${RESULTS[@]}"
if [ "$FAILED" = 0 ]; then hdr "RESULT: PASS"; else hdr "RESULT: FAIL — see the first [FAIL] above"; fi
say "artifacts:   $OUT"
say "screenshots: $SHOTS"
exit $FAILED
