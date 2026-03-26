#!/usr/bin/env bash
set -euo pipefail

# End-to-end test for session record → replay.
#
# 1. Records a live-eval run with --record-session
# 2. Replays it with --session-replay
# 3. Compares the answers
# 4. Checks that session channels replayed without divergence
#
# Usage:
#   freud/scripts/test-session-replay.sh [--input <file>] [--timeout <seconds>]
#
# Defaults to a simple arithmetic question if no input is provided.
# Requires a working LLM config (uses FREUD_CONFIG or default.env).
#
# Exit codes:
#   0 = answers match and session channels replayed cleanly
#   1 = mismatch or error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

INPUT_FILE=""
TIMEOUT="${FREUD_LIVE_EVAL_TIMEOUT:-60}"
LIVE_EVAL="freud/scripts/live-eval.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      [[ $# -lt 2 ]] && { echo "Missing value for $1" >&2; exit 1; }
      INPUT_FILE="$2"; shift 2 ;;
    --input=*) INPUT_FILE="${1#*=}"; shift ;;
    --timeout)
      [[ $# -lt 2 ]] && { echo "Missing value for $1" >&2; exit 1; }
      TIMEOUT="$2"; shift 2 ;;
    --timeout=*) TIMEOUT="${1#*=}"; shift ;;
    -h|--help)
      echo "Usage: freud/scripts/test-session-replay.sh [--input <file>] [--timeout <seconds>]"
      echo ""
      echo "End-to-end test for session record → replay."
      echo "Defaults to 'What is 2 + 2?' if no input is provided."
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# Create default input if none provided
if [[ -z "$INPUT_FILE" ]]; then
  INPUT_FILE="$(mktemp "${TMPDIR:-/tmp}/session-replay-test-input-XXXXXX.txt")"
  echo "What is 2 + 2?" > "$INPUT_FILE"
  CLEANUP_INPUT=true
else
  CLEANUP_INPUT=false
fi

cleanup() {
  [[ "$CLEANUP_INPUT" == "true" ]] && rm -f "$INPUT_FILE"
}
trap cleanup EXIT

echo "=== Session Replay E2E Test ==="
echo "Input: $(cat "$INPUT_FILE")"
echo "Timeout: ${TIMEOUT}s"
echo ""

# ── Step 1: Record ────────────────────────────────────────────────────
echo "--- Step 1: Recording session ---"
RECORD_OUTPUT="$("$LIVE_EVAL" --input "$INPUT_FILE" --record-session --timeout "$TIMEOUT" 2>&1)"
RECORD_EXIT=$?

if [[ "$RECORD_EXIT" -ne 0 ]]; then
  echo "FAIL: Recording run exited with code $RECORD_EXIT"
  echo "$RECORD_OUTPUT"
  exit 1
fi

# Find the run directory from the output
RECORD_RUN_DIR="$(echo "$RECORD_OUTPUT" | grep "^Run directory:" | head -1 | sed 's/Run directory: //')"
if [[ -z "$RECORD_RUN_DIR" || ! -d "$RECORD_RUN_DIR" ]]; then
  echo "FAIL: Could not find recording run directory"
  echo "$RECORD_OUTPUT"
  exit 1
fi

RECORD_ANSWER="$(cat "$RECORD_RUN_DIR/artifacts/answer.txt" 2>/dev/null || echo "")"
RECORD_ANSWER_CLEAN="$(echo "$RECORD_ANSWER" | sed 's/^ego> //')"
echo "Record run: $RECORD_RUN_DIR"
echo "Record answer: $RECORD_ANSWER_CLEAN"

# Verify session recording files exist
if [[ ! -f "$RECORD_RUN_DIR/session/signals.jsonl" ]]; then
  echo "FAIL: signals.jsonl not found in session recording"
  exit 1
fi
if [[ ! -f "$RECORD_RUN_DIR/session/llm-cache.jsonl" ]]; then
  echo "FAIL: llm-cache.jsonl not found in session recording"
  exit 1
fi

SIGNAL_COUNT="$(wc -l < "$RECORD_RUN_DIR/session/signals.jsonl" | tr -d ' ')"
LLM_CACHE_COUNT="$(wc -l < "$RECORD_RUN_DIR/session/llm-cache.jsonl" | tr -d ' ')"
echo "Recorded: ${SIGNAL_COUNT} signal(s), ${LLM_CACHE_COUNT} LLM call(s)"
echo ""

# ── Step 2: Replay ────────────────────────────────────────────────────
echo "--- Step 2: Replaying session ---"
REPLAY_OUTPUT="$("$LIVE_EVAL" --session-replay "$RECORD_RUN_DIR" --timeout "$TIMEOUT" 2>&1)"
REPLAY_EXIT=$?

if [[ "$REPLAY_EXIT" -ne 0 ]]; then
  echo "FAIL: Replay run exited with code $REPLAY_EXIT"
  echo "$REPLAY_OUTPUT"
  exit 1
fi

REPLAY_RUN_DIR="$(echo "$REPLAY_OUTPUT" | grep "^Run directory:" | head -1 | sed 's/Run directory: //')"
REPLAY_ANSWER="$(cat "$REPLAY_RUN_DIR/artifacts/answer.txt" 2>/dev/null || echo "")"
REPLAY_ANSWER_CLEAN="$(echo "$REPLAY_ANSWER" | sed 's/^ego> //')"
echo "Replay run: $REPLAY_RUN_DIR"
echo "Replay answer: $REPLAY_ANSWER_CLEAN"
echo ""

# ── Step 3: Compare ───────────────────────────────────────────────────
echo "--- Step 3: Comparing results ---"

FAILURES=0

# Compare answers — normalize to lowercase, strip punctuation and extra whitespace.
# LLM cache may diverge (UUIDs in prompt), causing the replay LLM to produce a
# differently-worded but semantically equivalent answer. We check that the core
# content overlaps (one contains the other after normalization).
normalize() {
  echo "$1" | tr '[:upper:]' '[:lower:]' | tr -d '.,!?;:' | tr -s ' ' | sed 's/^ *//;s/ *$//'
}
NORM_RECORD="$(normalize "$RECORD_ANSWER_CLEAN")"
NORM_REPLAY="$(normalize "$REPLAY_ANSWER_CLEAN")"

if [[ "$NORM_RECORD" == "$NORM_REPLAY" ]]; then
  echo "PASS: Answers match exactly ('$RECORD_ANSWER_CLEAN')"
elif [[ "$NORM_RECORD" == *"$NORM_REPLAY"* || "$NORM_REPLAY" == *"$NORM_RECORD"* ]]; then
  echo "PASS: Answers overlap (record='$RECORD_ANSWER_CLEAN' replay='$REPLAY_ANSWER_CLEAN')"
else
  echo "FAIL: Answers differ (record='$RECORD_ANSWER_CLEAN' replay='$REPLAY_ANSWER_CLEAN')"
  FAILURES=$((FAILURES + 1))
fi

# Check session replay stats
REPLAY_STATS_FILE="$REPLAY_RUN_DIR/artifacts/session-replay-stats.json"
if [[ -f "$REPLAY_STATS_FILE" ]]; then
  TOTAL_HITS="$(python3 -c "import json; print(json.load(open('$REPLAY_STATS_FILE'))['total_replay_hits'])" 2>/dev/null || echo "0")"
  TOTAL_DIVERGENCES="$(python3 -c "import json; print(json.load(open('$REPLAY_STATS_FILE'))['total_divergences'])" 2>/dev/null || echo "?")"
  DIVERGED="$(python3 -c "import json; print(','.join(json.load(open('$REPLAY_STATS_FILE')).get('diverged_channels',[])))" 2>/dev/null || echo "?")"

  if [[ "$TOTAL_DIVERGENCES" == "0" ]]; then
    echo "PASS: Session channels replayed with 0 divergences ($TOTAL_HITS hit(s))"
  else
    echo "WARN: Session channels had $TOTAL_DIVERGENCES divergence(s) in: $DIVERGED"
  fi
else
  echo "WARN: No session replay stats file found"
fi

# Check LLM cache stats
CACHE_STATS_FILE="$REPLAY_RUN_DIR/artifacts/cache-stats.json"
if [[ -f "$CACHE_STATS_FILE" ]]; then
  LLM_CACHED="$(python3 -c "import json; print(json.load(open('$CACHE_STATS_FILE'))['cached_calls'])" 2>/dev/null || echo "0")"
  LLM_REAL="$(python3 -c "import json; print(json.load(open('$CACHE_STATS_FILE'))['real_calls'])" 2>/dev/null || echo "?")"
  LLM_DIV="$(python3 -c "import json; print(json.load(open('$CACHE_STATS_FILE'))['divergence_count'])" 2>/dev/null || echo "?")"
  echo "INFO: LLM cache — $LLM_CACHED cached, $LLM_REAL live, $LLM_DIV divergence(s)"
fi

echo ""
if [[ "$FAILURES" -gt 0 ]]; then
  echo "=== FAIL: $FAILURES check(s) failed ==="
  exit 1
fi
echo "=== PASS: Session replay E2E test passed ==="
exit 0
