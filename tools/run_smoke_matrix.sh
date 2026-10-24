#!/usr/bin/env bash
# Runs the client smoke test (runClient -PsmokeTest) once per named run and keeps each result in
# run/smoketest-matrix/<run>.json (screenshots: run/screenshots/smoketest_[<variant>_][gui<N>_]*.png).
#   default                       the plain walkthrough (also resets every accessibility option)
#   keyboard|accessible|colorblind  -Pvariant=<name>: keyboard-only play, or presentation trials
#   visual                        all screens, Notebook tabs and JEI in every shipped language at GUI scale 4
#   gui1 .. gui4                  -PguiScale=<N>: in the 1280x960 window, GUI 1280x960 down to exactly 320x240
# With no arguments: default gui1 gui2 gui3 gui4. SMOKE_GRADLE_ARGS adds Gradle arguments to every run, for example
# SMOKE_GRADLE_ARGS=-PwithPotionMods for the four optional potion mods. Under Hyprland the tiling layout would choose
# the window size, so the test client's own window (found by its PID) is floated and sized; no other window is touched.
# Never run it while something else compiles the project: the client runs from build/.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QUIET=/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh
OUT="$ROOT/run/smoketest-matrix"
mkdir -p "$OUT"
RUNS="${*:-default gui1 gui2 gui3 gui4}"

float_client() {
    command -v hyprctl > /dev/null || return 0
    for _ in $(seq 1 300); do
        local pid
        pid="$(pgrep -f 'java.*immersive_brewing\.smoketest\.output' | head -n 1)"
        if [ -n "$pid" ] && hyprctl clients -j | python3 -c "import json,sys; sys.exit(0 if any(c['pid'] == $pid for c in json.load(sys.stdin)) else 1)"; then
            hyprctl dispatch "hl.dsp.window.fullscreen({ action = \"unset\", window = \"pid:$pid\" })" > /dev/null
            # Hyprland 0.55+ Lua dispatchers; the exact pid selector touches no other window.
            hyprctl dispatch "hl.dsp.window.float({ action = \"enable\", window = \"pid:$pid\" })" > /dev/null
            hyprctl dispatch "hl.dsp.window.resize({ x = 1280, y = 960, relative = false, window = \"pid:$pid\" })" > /dev/null
            return 0
        fi
        sleep 1
    done
}

for run in $RUNS; do
    case "$run" in
        default) props=() ;;
        keyboard|accessible|colorblind) props=("-Pvariant=$run") ;;
        visual) props=("-Pvariant=visual" "-PguiScale=4") ;;
        gui[1-4]) props=("-PguiScale=${run#gui}") ;;
        *) echo "unknown run: $run"; continue ;;
    esac
    rm -rf "$ROOT/run/smoketest"
    # shellcheck disable=SC2086 # SMOKE_GRADLE_ARGS is a list of words
    "$QUIET" "$ROOT" runClient -PsmokeTest "${props[@]}" ${SMOKE_GRADLE_ARGS:-} > "$OUT/$run.txt" 2>&1 &
    pid=$!
    float_client
    wait "$pid"
    if [ -f "$ROOT/run/smoketest/smoketest-result.json" ]; then
        cp "$ROOT/run/smoketest/smoketest-result.json" "$OUT/$run.json"
        [ -f "$ROOT/run/smoketest/coverage-report.txt" ] && cp "$ROOT/run/smoketest/coverage-report.txt" "$OUT/$run-coverage-report.txt"
    else
        echo '{"pass": false, "reason": "no result file"}' > "$OUT/$run.json"
    fi
    python3 -c "import json; d = json.load(open('$OUT/$run.json')); c = d.get('checks', {}); print('$run', d['pass'], d['reason'], c.get('framebuffer'), c.get('gui_scaled'))"
done
