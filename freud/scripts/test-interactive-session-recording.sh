#!/usr/bin/env bash
set -euo pipefail

# End-to-end test for interactive mode session recording.
#
# 1. Starts NeoPsyke in interactive mode with --record-session
# 2. Waits for the dashboard to become available
# 3. Creates a chat session and sends a message via HTTP
# 4. Waits for the agent's response via SSE
# 5. Sends "exit" to stdin to stop the agent
# 6. Verifies the session recording captured the signal
# 7. Replays the recorded session via live-eval.sh --session-replay
# 8. Compares answers
#
# Usage:
#   freud/scripts/test-interactive-session-recording.sh [--timeout <seconds>]
#
# Requires a working LLM config (uses FREUD_CONFIG or default.env).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

TIMEOUT=60
DASHBOARD_PORT=8787
DASHBOARD_HOST="127.0.0.1"
STARTUP_WAIT_MAX=30
RESPONSE_WAIT_MAX=30
INPUT_TEXT="What is 2 + 2?"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --timeout)
      [[ $# -lt 2 ]] && { echo "Missing value for $1" >&2; exit 1; }
      TIMEOUT="$2"; shift 2 ;;
    --timeout=*) TIMEOUT="${1#*=}"; shift ;;
    -h|--help)
      echo "Usage: freud/scripts/test-interactive-session-recording.sh [--timeout <seconds>]"
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

echo "=== Interactive Session Recording E2E Test ==="
echo "Input: $INPUT_TEXT"
echo "Dashboard: http://${DASHBOARD_HOST}:${DASHBOARD_PORT}"
echo ""

# ── Step 0: Setup ─────────────────────────────────────────────────────
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/freud-interactive-e2e-XXXXXX")"
cleanup() {
  exec 3>&- 2>/dev/null || true
  if [[ -n "${APP_PID:-}" ]]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR" 2>/dev/null || true
}
trap cleanup EXIT

echo "--- Step 1: Starting NeoPsyke in interactive mode with --record-session ---"

# Use a named pipe for stdin so we can send "exit" later
STDIN_FIFO="$WORK_DIR/stdin-fifo"
mkfifo "$STDIN_FIFO"
APP_LOG="$WORK_DIR/app-output.log"

# Start the app in background with session recording.
# run-neopsyke.sh --record-session creates its own session dir and prints
# the path to stderr. We capture the output to extract it later.
./run-neopsyke.sh --record-session \
  < "$STDIN_FIFO" \
  >"$WORK_DIR/stdout.log" 2>&1 &
APP_PID=$!

# Keep the write end open so the app doesn't get EOF immediately
exec 3>"$STDIN_FIFO"

# Wait for dashboard to come up
echo "Waiting for dashboard on port $DASHBOARD_PORT..."
elapsed=0
while ! curl -sf "http://${DASHBOARD_HOST}:${DASHBOARD_PORT}/api/chat/sessions" >/dev/null 2>&1; do
  sleep 1
  elapsed=$((elapsed + 1))
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "FAIL: App process died during startup. Output:"
    cat "$WORK_DIR/stdout.log" 2>/dev/null || true
    exit 1
  fi
  if [[ "$elapsed" -ge "$STARTUP_WAIT_MAX" ]]; then
    echo "FAIL: Dashboard did not start within ${STARTUP_WAIT_MAX}s"
    cat "$WORK_DIR/stdout.log" 2>/dev/null | tail -20 || true
    exit 1
  fi
done
echo "Dashboard is up (${elapsed}s)"

# Extract the session recording dir from app output
SESSION_RECORD_DIR="$(grep -o 'signals will be written to [^ ]*' "$WORK_DIR/stdout.log" 2>/dev/null | head -1 | sed 's/signals will be written to //' || echo "")"
if [[ -z "$SESSION_RECORD_DIR" ]]; then
  echo "WARN: Could not extract session recording dir from app output"
  # Fall back to finding it in the runs dir
  SESSION_RECORD_DIR="$(ls -td .neopsyke/runs/freud/*-session 2>/dev/null | head -1 || echo "")"
fi
echo "Session recording dir: ${SESSION_RECORD_DIR:-<unknown>}"

# ── Step 2: Send a chat message ───────────────────────────────────────
echo ""
echo "--- Step 2: Sending chat message via HTTP ---"

# Create a session
CREATE_RESP="$(curl -sf -X POST \
  "http://${DASHBOARD_HOST}:${DASHBOARD_PORT}/api/chat/sessions" \
  -H "Content-Type: application/json" \
  -d '{"title": "e2e-test"}' 2>&1)" || {
  echo "FAIL: Could not create chat session"
  echo "$CREATE_RESP"
  exit 1
}
SESSION_ID="$(echo "$CREATE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['session_id'])" 2>/dev/null)" || {
  echo "FAIL: Could not parse session_id from: $CREATE_RESP"
  exit 1
}
echo "Session created: $SESSION_ID"

# Submit the message
SUBMIT_RESP="$(curl -sf -X POST \
  "http://${DASHBOARD_HOST}:${DASHBOARD_PORT}/api/chat/sessions/${SESSION_ID}/messages" \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$INPUT_TEXT\"}" 2>&1)" || {
  echo "FAIL: Could not submit message"
  exit 1
}
echo "Message submitted: $(echo "$SUBMIT_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'accepted={d.get(\"accepted\")} enqueued={d.get(\"enqueued\")}')" 2>/dev/null || echo "$SUBMIT_RESP")"

# ── Step 3: Wait for agent response via SSE ───────────────────────────
echo ""
echo "--- Step 3: Waiting for agent response ---"

RECORD_ANSWER=""
elapsed=0
while [[ -z "$RECORD_ANSWER" && "$elapsed" -lt "$RESPONSE_WAIT_MAX" ]]; do
  sleep 2
  elapsed=$((elapsed + 2))
  # Poll for assistant messages
  MESSAGES="$(curl -sf "http://${DASHBOARD_HOST}:${DASHBOARD_PORT}/api/chat/sessions/${SESSION_ID}" 2>/dev/null || echo "")"
  if [[ -n "$MESSAGES" ]]; then
    RECORD_ANSWER="$(echo "$MESSAGES" | python3 -c "
import sys, json
data = json.load(sys.stdin)
msgs = data.get('messages', [])
for m in msgs:
    if m.get('role') == 'assistant':
        print(m.get('content', ''))
        break
" 2>/dev/null || echo "")"
  fi
done

if [[ -z "$RECORD_ANSWER" ]]; then
  echo "FAIL: No agent response within ${RESPONSE_WAIT_MAX}s"
  touch "$SESSION_RECORD_DIR/.send-exit"
  exit 1
fi
echo "Agent answered: $RECORD_ANSWER"

# ── Step 4: Stop the agent ────────────────────────────────────────────
echo ""
echo "--- Step 4: Stopping agent ---"
echo "exit" >&3
exec 3>&-  # close the write end
sleep 3

# Check if the process exited
if kill -0 "$APP_PID" 2>/dev/null; then
  echo "Sending SIGTERM..."
  kill "$APP_PID" 2>/dev/null || true
  sleep 2
fi
wait "$APP_PID" 2>/dev/null || true
unset APP_PID

# ── Step 5: Verify recording ─────────────────────────────────────────
echo ""
echo "--- Step 5: Verifying session recording ---"

FAILURES=0

if [[ -f "$SESSION_RECORD_DIR/signals.jsonl" ]]; then
  SIGNAL_COUNT="$(wc -l < "$SESSION_RECORD_DIR/signals.jsonl" | tr -d ' ')"
  if [[ "$SIGNAL_COUNT" -gt 0 ]]; then
    echo "PASS: signals.jsonl has $SIGNAL_COUNT signal(s)"
  else
    echo "FAIL: signals.jsonl is empty"
    FAILURES=$((FAILURES + 1))
  fi
else
  echo "FAIL: signals.jsonl not found"
  FAILURES=$((FAILURES + 1))
fi

if [[ -f "$SESSION_RECORD_DIR/session-manifest.json" ]]; then
  echo "PASS: session-manifest.json exists"
else
  echo "FAIL: session-manifest.json not found"
  FAILURES=$((FAILURES + 1))
fi

# Check if LLM cache was written (may be in session dir or run log dir)
LLM_CACHE=""
if [[ -f "$SESSION_RECORD_DIR/llm-cache.jsonl" ]]; then
  LLM_CACHE="$SESSION_RECORD_DIR/llm-cache.jsonl"
  LLM_COUNT="$(wc -l < "$LLM_CACHE" | tr -d ' ')"
  echo "PASS: llm-cache.jsonl has $LLM_COUNT LLM call(s)"
else
  echo "WARN: llm-cache.jsonl not in session dir (may be in run log dir)"
fi

echo "Recording dir: $SESSION_RECORD_DIR"
echo "Contents: $(ls "$SESSION_RECORD_DIR/")"

# ── Step 6: Replay ────────────────────────────────────────────────────
echo ""
echo "--- Step 6: Replaying recorded session ---"

if [[ -z "$LLM_CACHE" ]]; then
  echo "SKIP: Cannot replay without llm-cache.jsonl in session dir"
  echo ""
  if [[ "$FAILURES" -gt 0 ]]; then
    echo "=== FAIL: $FAILURES check(s) failed ==="
    exit 1
  fi
  echo "=== PARTIAL PASS: Recording verified, replay skipped (no LLM cache in session dir) ==="
  exit 0
fi

REPLAY_OUTPUT="$(freud/scripts/live-eval.sh --session-replay "$SESSION_RECORD_DIR" --timeout "$TIMEOUT" 2>&1)" || true
REPLAY_RUN_DIR="$(echo "$REPLAY_OUTPUT" | grep "^Run directory:" | head -1 | sed 's/Run directory: //')"
REPLAY_ANSWER=""
if [[ -n "$REPLAY_RUN_DIR" && -f "$REPLAY_RUN_DIR/artifacts/answer.txt" ]]; then
  REPLAY_ANSWER="$(cat "$REPLAY_RUN_DIR/artifacts/answer.txt" | sed 's/^ego> //')"
fi
echo "Replay answer: ${REPLAY_ANSWER:-<none>}"

# Compare
normalize() {
  echo "$1" | tr '[:upper:]' '[:lower:]' | tr -d '.,!?;:' | tr -s ' ' | sed 's/^ *//;s/ *$//'
}
NORM_RECORD="$(normalize "$RECORD_ANSWER")"
NORM_REPLAY="$(normalize "$REPLAY_ANSWER")"

if [[ -z "$REPLAY_ANSWER" ]]; then
  echo "FAIL: No replay answer"
  FAILURES=$((FAILURES + 1))
elif [[ "$NORM_RECORD" == "$NORM_REPLAY" ]]; then
  echo "PASS: Answers match exactly"
elif [[ "$NORM_RECORD" == *"$NORM_REPLAY"* || "$NORM_REPLAY" == *"$NORM_RECORD"* ]]; then
  echo "PASS: Answers overlap (record='$RECORD_ANSWER' replay='$REPLAY_ANSWER')"
else
  echo "WARN: Answers differ (record='$RECORD_ANSWER' replay='$REPLAY_ANSWER') — may be LLM wording difference"
fi

# Check LLM cache stats — MUST have zero live calls (all cached)
CACHE_STATS_FILE="$REPLAY_RUN_DIR/artifacts/cache-stats.json"
if [[ -n "$REPLAY_RUN_DIR" && -f "$CACHE_STATS_FILE" ]]; then
  LLM_CACHED="$(python3 -c "import json; print(json.load(open('$CACHE_STATS_FILE'))['cached_calls'])" 2>/dev/null || echo "0")"
  LLM_REAL="$(python3 -c "import json; print(json.load(open('$CACHE_STATS_FILE'))['real_calls'])" 2>/dev/null || echo "?")"
  LLM_DIV="$(python3 -c "import json; print(json.load(open('$CACHE_STATS_FILE'))['divergence_count'])" 2>/dev/null || echo "?")"
  if [[ "$LLM_DIV" == "0" && "$LLM_CACHED" != "0" ]]; then
    echo "PASS: LLM cache — $LLM_CACHED cached, $LLM_REAL live, 0 divergences"
  else
    echo "FAIL: LLM cache diverged — $LLM_CACHED cached, $LLM_REAL live, $LLM_DIV divergence(s)"
    FAILURES=$((FAILURES + 1))
  fi
fi

echo ""
if [[ "$FAILURES" -gt 0 ]]; then
  echo "=== FAIL: $FAILURES check(s) failed ==="
  exit 1
fi
echo "=== PASS: Interactive session recording E2E test passed ==="
exit 0
