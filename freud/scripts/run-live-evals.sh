#!/usr/bin/env bash
# Run all live eval inputs and report aggregate pass/fail.
# Used as a Freud pipeline step: exit 0 = pass, exit 2 = fail.
#
# Requires: --lane <lane> passed via FREUD_EVAL_LANE env var or first arg.
# Example:
#   FREUD_EVAL_LANE=low-llm ./freud/bin/run-live-evals.sh
#   ./freud/bin/run-live-evals.sh low-llm

set -euo pipefail

LANE="${FREUD_LANE:-${1:-}}"
if [ -z "$LANE" ]; then
    echo "error: lane required. Set FREUD_LANE or pass as first arg." >&2
    exit 2
fi

FREUD_BIN="./freud/bin/freud"
PASS=0
FAIL=0
TOTAL=0
RESULTS=()

run_eval() {
    local input="$1"
    local extra_flags="$2"
    local expect_timeout="${3:-false}"
    local name
    name=$(basename "$input" .txt)
    TOTAL=$((TOTAL + 1))

    echo "--- [$TOTAL] $name ---"

    local output run_dir verdict
    output=$($FREUD_BIN eval --live --lane "$LANE" $extra_flags --input "$input" 2>&1 || true)
    run_dir=$(echo "$output" | grep "Run directory:" | head -1 | sed 's/Run directory: //')
    verdict=$(echo "$output" | grep "\[freud\] verdict=" | tail -1 | sed 's/.*verdict=\([^ ]*\).*/\1/')

    if [ "$verdict" = "pass" ]; then
        echo "  PASS ($name)"
        PASS=$((PASS + 1))
        RESULTS+=("PASS  $name")
    elif [ "$verdict" = "timeout" ] && [ "$expect_timeout" = "true" ]; then
        # For goal evals, timeout is expected (approval blocks). Verify the
        # pipeline completed up to staging by checking telemetry events.
        local pipeline_ok=true
        if [ -n "$run_dir" ] && [ -f "$run_dir/logs/events.jsonl" ]; then
            grep -q "plan_refinement_completed" "$run_dir/logs/events.jsonl" 2>/dev/null || pipeline_ok=false
            grep -q "action_staged" "$run_dir/logs/events.jsonl" 2>/dev/null || pipeline_ok=false
        else
            pipeline_ok=false
        fi

        if [ "$pipeline_ok" = "true" ]; then
            echo "  PASS ($name) [timeout expected: plan refined + action staged]"
            PASS=$((PASS + 1))
            RESULTS+=("PASS  $name (timeout-expected)")
        else
            echo "  FAIL ($name) [timeout but pipeline incomplete]"
            FAIL=$((FAIL + 1))
            RESULTS+=("FAIL  $name (timeout-incomplete)")
        fi
    else
        echo "  FAIL ($name) [verdict=$verdict]"
        FAIL=$((FAIL + 1))
        RESULTS+=("FAIL  $name ($verdict)")
    fi
}

echo "=== Live Eval Pack (lane=$LANE) ==="
echo ""

# Cognitive-runtime evals (no goals)
for f in freud/evals/cognitive-runtime/phase-*.txt; do
    [ -f "$f" ] || continue
    run_eval "$f" "" false
done

# Plan-refinement evals (goals enabled, timeout expected)
for f in freud/evals/plan-refinement/*.txt; do
    [ -f "$f" ] || continue
    run_eval "$f" "--goals" true
done

echo ""
echo "=== Results: $PASS/$TOTAL passed, $FAIL failed ==="
for r in "${RESULTS[@]}"; do
    echo "  $r"
done

if [ "$FAIL" -gt 0 ]; then
    exit 2
fi
exit 0
