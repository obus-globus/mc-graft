#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------------------------------
# Sequential two-payload injection test — phase P0 of docs/multi-payload.md.
#
# Starts ONE local Fabric (Knot) client, injects payload A, waits for it to finish, then injects payload B into the
# SAME running JVM, and reports a PASS/FAIL per assertion per payload plus exactly where it stopped.
#
# This exists BEFORE any fix, so later phases have a real pass/fail signal. Today it is expected to FAIL at payload B's
# ATTACH_ACCEPTED assertion (FullInjectAgent.everInjected refuses a second attach).
#
# SCOPE: this harness stops at the TITLE SCREEN. Its COMPOSITION assertion proves both payloads' hooks are WOVEN into
# one method; it proves nothing about whether that method ever EXECUTES, because every co-hooked method needs a world
# and a sidecar is only linked on first use. For that, run tools/world-join-test.sh, which actually enters a world.
#
# Usage:
#   tools/multi-payload-test.sh <payloadA> <payloadB>
#   tools/multi-payload-test.sh zoomify liquidbounce
#   tools/multi-payload-test.sh --no-pack wurst liquidbounce     # reuse already-packed agents from $AGENT_DIR
#
# Exit status: 0 only when every assertion for both payloads passes. 1 otherwise. 2 = environment/setup problem
# (client would not start, missing payload jar, ...) — an environment failure is NOT reported as a test failure.
#
# It runs exactly one client at a time and caps it at -Xmx2G: this VM has no swap.
# ---------------------------------------------------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- environment (override by exporting) ------------------------------------------------------------------------
MCGRAFT="${MCGRAFT:-$REPO/ctl/target/release/mcgraft}"
JAVA="${JAVA:-/usr/lib/jvm/graalvm-25/bin/java}"
MC_JAR="${MC_JAR:-$HOME/.gradle/caches/fabric-loom/26.2/minecraft-client.jar}"
MC_CP_FILE="${MC_CP_FILE:-/tmp/fabric-cp.txt}"          # ':'-joined launch classpath (loader + MC + libs)
GAME_DIR="${GAME_DIR:-/tmp/bare-fabric-run/gamedir}"    # its mods/ supplies fabric-api / fabric-language-kotlin / yacl
ASSETS_DIR="${ASSETS_DIR:-$HOME/.gradle/caches/fabric-loom/assets}"
ASSET_INDEX="${ASSET_INDEX:-26.2-32}"
MC_VERSION="${MC_VERSION:-26.2}"
MC_DISPLAY="${MC_DISPLAY:-:99}"                          # Xvfb; the client needs a real GL context
XDG_RT="${XDG_RT:-/tmp/xrt-mc}"
AGENT_DIR="${AGENT_DIR:-/tmp/mcgraft-mp-agents}"         # packed agent jars are cached here
OUT_ROOT="${OUT_ROOT:-/tmp/mcgraft-mp}"
PROBE_AGENT="${PROBE_AGENT:-}"                           # optional read-only agent jar; attached before/after each
PROBE_ATTACH="${PROBE_ATTACH:-}"                         # injection to dump JVM state. Needs a `java -cp DIR Attach`
                                                         # helper: set PROBE_ATTACH=<dir containing Attach.class>.
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"                      # seconds to reach the title screen
INJECT_TIMEOUT="${INJECT_TIMEOUT:-300}"                  # seconds to reach a terminal line in the injection log
SETTLE_QUIET="${SETTLE_QUIET:-20}"                       # seconds the host log must be unchanged before attaching

DO_PACK=1
[ "${1:-}" = "--no-pack" ] && { DO_PACK=0; shift; }

A_ID="${1:-zoomify}"
B_ID="${2:-liquidbounce}"

RUN_ID="$(date +%Y%m%d-%H%M%S)-$A_ID-then-$B_ID"
OUT="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT" "$AGENT_DIR"
HOST_LOG="$OUT/client.log"

# ---- payload registry -------------------------------------------------------------------------------------------
# Each payload: the mod jar, its mc-graft config, and the agent-log line that proves its entrypoint/bootstrap actually
# RAN. That marker is evidence the payload initialised — it is NOT evidence the feature works on screen.
#
# NOTE on liquidbounce: /tmp/lbpay.jar is a THIN LiquidBounce jar — it carries no bundled runtime libs, and
# `mcgraft pack` only resolves the two coordinates in payload.dependencies (fabric-api, fabric-language-kotlin).
# LB's own init then dies with `NoClassDefFoundError: net/ccbluex/liquidbounce/authlib/account/MinecraftAccount`
# (and `org/graalvm/polyglot/Engine`), so the readiness probe never passes.
#
# This note used to add "it is fine as payload B (which is refused long before that matters)". MEASURED FALSE once
# P3 admitted a second injection (2026-07-26): as payload B, LB injects, converts 145 targets and bootstraps, then
# dies at exactly the same NoClassDefFoundError — the readiness probe times out, so its target retransforms are
# never published. /tmp/lbpay.jar is unusable in EITHER slot here. That is a jar-contents/environment limit and has
# nothing to do with sequential injection.
#
# To use LB here at all, point MC_CP_FILE at a launch classpath that already carries LB's ~140 bundled libs
# (e.g. /tmp/lbi-cp.txt), or pack from a fat LB jar. Otherwise use `wurst`: it is a fat jar that completes, and it
# overlaps zoomify on Camera.calculateFov / calculateHudFov / extractRenderState, so the pair still exercises the
# co-hooked-method regression that P3 exists for.
payload_jar() { case "$1" in
    zoomify)      echo "${ZOOMIFY_JAR:-/tmp/zoomify-2.16.126.2.jar}" ;;
    liquidbounce) echo "${LIQUIDBOUNCE_JAR:-/tmp/lbpay.jar}" ;;
    wurst)        echo "${WURST_JAR:-/tmp/wurst/build/libs/Wurst-Client-v7.54-MC26.2.jar}" ;;
    *) echo "" ;; esac; }
payload_cfg() { case "$1" in
    zoomify)      echo "${ZOOMIFY_CFG:-/tmp/zoomify.properties}" ;;
    liquidbounce) echo "${LIQUIDBOUNCE_CFG:-$REPO/examples/liquidbounce/bootstrap.properties}" ;;
    wurst)        echo "${WURST_CFG:-/tmp/wurst.properties}" ;;
    *) echo "" ;; esac; }
payload_marker() { case "$1" in
    zoomify)      echo 'dev\.isxander\.zoomify\.Zoomify\.onInitializeClient' ;;
    liquidbounce) echo 'liquidbounce initialization completed' ;;
    wurst)        echo 'net\.wurstclient\.WurstInitializer\.onInitialize' ;;
    *) echo "" ;; esac; }

# ---- reporting --------------------------------------------------------------------------------------------------
FAILED=0
declare -a RESULTS=()
say()  { printf '%s\n' "$*"; }
hdr()  { printf '\n=== %s\n' "$*"; }
ok()   { RESULTS+=("PASS  $1  $2"); printf '  [PASS] %-22s %s\n' "$1" "$2"; }
bad()  { RESULTS+=("FAIL  $1  $2"); FAILED=1; printf '  [FAIL] %-22s %s\n' "$1" "$2"; }
skip() { RESULTS+=("SKIP  $1  $2"); printf '  [SKIP] %-22s %s\n' "$1" "$2"; }
envdie() { printf '\nENVIRONMENT FAILURE: %s\n' "$*" >&2; cleanup; exit 2; }

MC_PID=""
cleanup() { [ -n "$MC_PID" ] && kill -9 "$MC_PID" 2>/dev/null; pkill -9 -f jcef_helper 2>/dev/null; return 0; }
trap cleanup EXIT

# probe <label> — attach the read-only probe agent and save its dump. No-op unless PROBE_AGENT is set.
# The probe has its OWN Agent-Class, so the everInjected guard does not apply to it.
probe() {
    [ -n "$PROBE_AGENT" ] && [ -n "$PROBE_ATTACH" ] || return 0
    local out="$OUT/probe-$1.txt"
    "$JAVA" -cp "$PROBE_ATTACH" Attach "$MC_PID" "$PROBE_AGENT" "$out" > "$OUT/probe-$1.attach.log" 2>&1
    say "  probe[$1] -> $out ($(grep -c . "$out" 2>/dev/null || echo 0) lines)"
}

# ---- preflight --------------------------------------------------------------------------------------------------
hdr "preflight"
[ -x "$MCGRAFT" ]     || envdie "mcgraft CLI not found/executable: $MCGRAFT"
[ -x "$JAVA" ]        || envdie "java not found: $JAVA"
[ -f "$MC_CP_FILE" ]  || envdie "launch classpath file not found: $MC_CP_FILE"
[ -f "$MC_JAR" ]      || envdie "minecraft client jar not found: $MC_JAR"
[ -d "$GAME_DIR" ]    || envdie "game dir not found: $GAME_DIR"
pgrep -x Xvfb >/dev/null 2>&1 || say "  warning: no Xvfb process found; DISPLAY=$MC_DISPLAY may not exist"
for id in "$A_ID" "$B_ID"; do
    j="$(payload_jar "$id")"; c="$(payload_cfg "$id")"
    [ -n "$j" ] || envdie "unknown payload id '$id' (known: zoomify, liquidbounce, wurst)"
    [ -f "$j" ] || envdie "payload jar for '$id' not found: $j"
    [ -f "$c" ] || envdie "payload config for '$id' not found: $c"
done
say "  repo=$REPO"
say "  output=$OUT"

# ---- pack agents ------------------------------------------------------------------------------------------------
hdr "packing agents"
declare -A AGENT=()
for id in "$A_ID" "$B_ID"; do
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
pkill -9 -f "knot.KnotClient" 2>/dev/null; pkill -9 -f jcef_helper 2>/dev/null; sleep 4
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
# Attach only once the client is idle at the menu, so no startup resource reload is still in flight.
prev=""; stable=0; need=$(( SETTLE_QUIET / 5 ))
while [ $stable -lt $need ]; do
    sleep 5
    kill -0 "$MC_PID" 2>/dev/null || envdie "client exited while settling; see $HOST_LOG"
    cur=$(wc -c < "$HOST_LOG")
    if [ "$cur" = "$prev" ]; then stable=$((stable+1)); else stable=0; fi
    prev=$cur
done
say "  client idle at the menu (log size $prev)"

# ---- inject + assert --------------------------------------------------------------------------------------------
# inject_and_assert <slot A|B> <payload id>
inject_and_assert() {
    local slot="$1" id="$2"
    local log="$OUT/inject-$slot-$id.log" cli="$OUT/cli-$slot-$id.log"
    local marker; marker="$(payload_marker "$id")"

    hdr "payload $slot: $id"
    kill -0 "$MC_PID" 2>/dev/null || { bad "$slot/CLIENT_ALIVE" "client was already dead before this injection"; return; }
    probe "before-$slot-$id"

    "$MCGRAFT" inject --agent "${AGENT[$id]}" --log "$log" --no-follow "$MC_PID" > "$cli" 2>&1
    local rc=$?
    say "  mcgraft inject exit=$rc  (cli: $cli)"

    # Wait for the agent to reach a terminal line (or die trying).
    local d=$((SECONDS + INJECT_TIMEOUT))
    while [ $SECONDS -lt $d ]; do
        [ -f "$log" ] && grep -qE "payload injection complete|re-injection refused|\[ERROR\]" "$log" && break
        kill -0 "$MC_PID" 2>/dev/null || break
        sleep 3
    done
    sleep 3   # let any trailing lines land

    if [ ! -s "$log" ]; then
        bad "$slot/ATTACH_ACCEPTED" "no injection log was written at all — the agent never ran. CLI said:"
        sed 's/^/        /' "$cli"
        return
    fi

    # A1 — attach accepted (the agent did not refuse us outright)
    local refusal
    refusal="$(grep -m1 "re-injection refused" "$log")"
    if [ -n "$refusal" ]; then
        bad "$slot/ATTACH_ACCEPTED" "REFUSED by FullInjectAgent.everInjected"
        say  "        $refusal"
    elif [ "$rc" != 0 ]; then
        bad "$slot/ATTACH_ACCEPTED" "mcgraft inject exited $rc"
        grep -m3 -E "Agent load FAILED|attach failed" "$cli" | sed 's/^/        /'
    else
        ok "$slot/ATTACH_ACCEPTED" "agent ran"
    fi

    # A2 — the payload the agent actually loaded is the one we asked for (guards the shared-Agent-Class hazard)
    local loaded
    loaded="$(grep -m1 -oE "payload '[^']+'" "$log" | head -1)"
    if [ -z "$loaded" ]; then
        bad "$slot/PAYLOAD_IDENTITY" "the agent never announced a payload id"
    elif [ "$loaded" = "payload '$id'" ]; then
        ok "$slot/PAYLOAD_IDENTITY" "$loaded"
    else
        bad "$slot/PAYLOAD_IDENTITY" "asked for '$id' but the agent loaded $loaded"
    fi

    # A3 — injection reached completion
    if grep -q "payload injection complete" "$log"; then
        ok "$slot/INJECT_COMPLETE" "'payload injection complete'"
    else
        bad "$slot/INJECT_COMPLETE" "never reached 'payload injection complete'; last line:"
        tail -1 "$log" | sed 's/^/        /'
    fi

    # A4 — no agent-side errors
    local errs; errs="$(grep -c "\[ERROR\]" "$log")"
    if [ "$errs" = 0 ]; then ok "$slot/NO_AGENT_ERRORS" "0 [ERROR] lines"
    else bad "$slot/NO_AGENT_ERRORS" "$errs [ERROR] line(s), first:"; grep -m1 "\[ERROR\]" "$log" | sed 's/^/        /'; fi

    # A5 — the payload's own entrypoint/bootstrap ran (log evidence that it initialised; NOT proof the feature works)
    if [ -n "$marker" ] && grep -qE "$marker" "$log"; then
        ok "$slot/BOOTSTRAP_RAN" "$(grep -m1 -E "$marker" "$log" | sed 's/^\[mc-graft\] *//')"
    else
        bad "$slot/BOOTSTRAP_RAN" "no line matching /$marker/ in $log"
    fi

    # A6 — the agent's own verification report is clean
    local rep; rep="$(grep -m1 -oE "verification report: .*" "$log" | sed 's/^verification report: //')"
    if [ -z "$rep" ] || [ ! -f "$rep" ]; then
        bad "$slot/VERIFY_REPORT" "no verification report was written"
    else
        local status nerr
        status="$(grep -oE '"status":"[a-z]+"' "$rep" | head -1)"
        nerr="$(grep -oE '"errors":[0-9]+' "$rep" | head -1)"
        if [ "$status" = '"status":"ok"' ] && [ "$nerr" = '"errors":0' ]; then
            ok "$slot/VERIFY_REPORT" "$status $nerr ($rep)"
        else
            bad "$slot/VERIFY_REPORT" "$status $nerr ($rep)"
        fi
    fi

    # A7 — NO TARGET WENT INERT.
    #
    # The one outcome that used to be indistinguishable from success. An INERT target is one the payload asked to hook
    # that ends up carrying NONE of its hooks for the rest of the session: a retransform JVMTI refused (a class whose
    # <clinit> had already failed is in the erroneous state and answers JVMTI_ERROR_INVALID_CLASS), a conversion that
    # could not be published over the live class, a WHOLE-staged target that loaded inside the attach window, a sidecar
    # that could not be defined. Every route to it was a [WARN] and/or a SOFT report entry, so NO_AGENT_ERRORS passed
    # and the run read as PASS with a dead hook in it (docs/liquidbounce-mixin-audit.md H3, VERIFIED by execution).
    #
    # Checked in BOTH channels on purpose, because they fail differently: the log grep catches an inert target the
    # verifier never recorded, and the report count catches one recorded after the last log line this harness waited
    # for. (The report path is memoized by the engine, so `grep -m1` above really is the final report — it used to be a
    # fresh temp file per issue, i.e. a snapshot from the injection's FIRST issue.)
    #
    # NOT counted here, and it must stay that way: `mixin target not present in this installation`. Nothing was asked
    # for and nothing was lost — LiquidBounce skips 6 Sodium/Lithium/ViaVersion targets on every single run.
    local inert_log inert_rep
    inert_log="$(grep -cE "INERT TARGET" "$log")"
    inert_rep=""
    [ -n "$rep" ] && [ -f "$rep" ] && inert_rep="$(grep -oE '"inertTargets":[0-9]+' "$rep" | head -1 | cut -d: -f2)"
    if [ "$inert_log" != 0 ]; then
        bad "$slot/NO_INERT_TARGETS" "$inert_log log line(s) report an inert target:"
        grep -m2 -E "INERT TARGET" "$log" | cut -c1-220 | sed 's/^/        /'
    elif [ -z "$inert_rep" ]; then
        bad "$slot/NO_INERT_TARGETS" "no inertTargets count in the verification report (engine predates it?): ${rep:-<no report>}"
    elif [ "$inert_rep" != 0 ]; then
        bad "$slot/NO_INERT_TARGETS" "the report records $inert_rep inert target(s) that no log line named: $rep"
        grep -oE '"inert":\[.*' "$rep" | cut -c1-300 | sed 's/^/        /'
    else
        ok "$slot/NO_INERT_TARGETS" "0 inert targets (injection log and verification report agree)"
    fi

    # A8 — cross-payload contamination: did this run mention a mixin config that is not this payload's?
    # (the wurst.mixins.json question — see docs/multi-payload.md §1.6)
    local foreign
    foreign="$(grep -oE '[a-z0-9_.-]+\.mixins\.json' "$log" "$HOST_LOG" 2>/dev/null | sed 's/^[^:]*://' | sort -u \
               | grep -v "^$id" | grep -viE "^(fabric|mixin)" | tr '\n' ' ')"
    if [ -z "$foreign" ]; then ok "$slot/NO_FOREIGN_MIXIN_CFG" "only $id's own mixin config is referenced"
    else say "  [note] $slot/NO_FOREIGN_MIXIN_CFG  other mixin configs referenced: $foreign"; fi

    probe "after-$slot-$id"
}

inject_and_assert A "$A_ID"
inject_and_assert B "$B_ID"

# ---- post-state -------------------------------------------------------------------------------------------------
hdr "post-state (both payloads should be live in one JVM)"
if kill -0 "$MC_PID" 2>/dev/null; then ok "CLIENT_ALIVE" "pid $MC_PID still running after both injections"
else bad "CLIENT_ALIVE" "client died; tail of $HOST_LOG:"; tail -5 "$HOST_LOG" | sed 's/^/        /'; fi

# COMPOSITION — the assertion P3 exists for, and the one that used to break SILENTLY (docs/multi-payload.md §1.2).
# The agent reads back the bytes it actually published and reports, per target, which payloads' sidecars they call
# into; `co-hooked methods:` names a single Minecraft method carrying hooks from more than one payload. Anything less
# than that is not evidence: two payloads can both "inject successfully" while the second silently erases the first
# on every target they share. Read from payload B's log — it is B's activation that composes them.
B_LOG="$OUT/inject-B-$B_ID.log"
COMPO="$(grep -m1 "composition: .*co-hooked methods:" "$B_LOG" 2>/dev/null)"
if [ -z "$COMPO" ]; then
    if grep -q "composition: 0 target(s)" "$B_LOG" 2>/dev/null; then
        bad "COMPOSITION" "no target carries hooks from both payloads — they may not overlap, or B erased A"
    else
        bad "COMPOSITION" "the agent published no composition report; sequential composition is unproven"
    fi
else
    # Every co-hooked line must name BOTH payload scope keys, else only one payload is really on that method.
    A_KEY="$(printf '%s' "$A_ID" | tr -c 'A-Za-z0-9_' '_')"
    B_KEY="$(printf '%s' "$B_ID" | tr -c 'A-Za-z0-9_' '_')"
    if printf '%s' "$COMPO" | grep -q "$A_KEY" && printf '%s' "$COMPO" | grep -q "$B_KEY"; then
        ok "COMPOSITION" "$(printf '%s' "$COMPO" | sed 's/^\[mc-graft\] \[INFO\] //' | cut -c1-160)"
    else
        bad "COMPOSITION" "a co-hooked method was reported but it does not name both '$A_KEY' and '$B_KEY':"
        printf '%s\n' "$COMPO" | sed 's/^/        /'
    fi
fi
if grep -q "composition INCOMPLETE" "$B_LOG" 2>/dev/null; then
    bad "COMPOSITION_COMPLETE" "$(grep -m1 -o 'composition INCOMPLETE.*' "$B_LOG" | cut -c1-200)"
else
    ok "COMPOSITION_COMPLETE" "no target failed to take the second payload's hooks"
fi

# Payload A must not have been torn down by B. This only checks for a new hard failure in the client log after B
# attached — it is NOT a functional check that A's hooks still fire (that needs a real client; see P5).
# `Exception in thread "Attach Listener"` is deliberately excluded: that is how a REFUSED attach surfaces (agentmain
# threw), and it is already reported by ATTACH_ACCEPTED. Counting it here would double-report one event as a crash.
if grep -iE "A fatal error|Minecraft has crashed|Exception in thread" "$HOST_LOG" | grep -qvi "Attach Listener"; then
    bad "NO_CLIENT_FAULT" "the client log contains a fatal error on a game thread:"
    grep -iE "A fatal error|Minecraft has crashed|Exception in thread" "$HOST_LOG" | grep -vi "Attach Listener" | head -1 | sed 's/^/        /'
else
    ok "NO_CLIENT_FAULT" "no fatal error on a game thread"
fi
# Inert targets, re-checked across BOTH logs after both injections. The per-slot assertion cannot see a target that
# goes inert later — including one of payload A's that only dies while payload B is being composed onto it.
LATE_INERT="$(grep -hE "INERT TARGET" "$OUT"/inject-*.log 2>/dev/null)"
if [ -n "$LATE_INERT" ]; then
    bad "NO_LATE_INERT_TARGETS" "$(printf '%s\n' "$LATE_INERT" | wc -l) inert-target line(s) across both injection logs:"
    printf '%s\n' "$LATE_INERT" | head -5 | cut -c1-220 | sed 's/^/        /'
else
    ok "NO_LATE_INERT_TARGETS" "no target went inert in either injection"
fi

if grep -q 'Exception in thread "Attach Listener"' "$HOST_LOG"; then
    say "  [note] the client logged an Attach Listener exception — that is a refused/failed agentmain, see above"
fi

hdr "summary  ($A_ID then $B_ID)"
printf '%s\n' "${RESULTS[@]}"
if [ "$FAILED" = 0 ]; then
    hdr "RESULT: PASS — both payloads injected into one JVM"
else
    hdr "RESULT: FAIL — see the first [FAIL] above for where it stopped"
fi
say "artifacts: $OUT"
exit $FAILED
