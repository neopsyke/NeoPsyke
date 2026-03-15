#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

INPUT_FILE=""
EXPECTED_FILE=""
CACHE_REPLAY_FILE=""
CONFIG_PATH="${FREUD_CONFIG:-$REPO_ROOT/freud/config/default.env}"

if [[ -f "$CONFIG_PATH" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_PATH"
fi

if [[ -n "${PSYKE_LLM_CONFIG_FILE:-}" ]]; then
  export PSYKE_LLM_CONFIG_FILE
fi

TIMEOUT="${FREUD_LIVE_EVAL_TIMEOUT:-120}"
RUN_ROOT="${FREUD_RUN_ROOT:-.psyke/runs/freud}"
GRADLE_USER_HOME_CFG="${FREUD_GRADLE_USER_HOME:-.freud/gradle-home}"
PSYKE_CMD="${FREUD_LIVE_EVAL_PSYKE_CMD:-$REPO_ROOT/run-psyke.sh}"
RUN_DIR_OVERRIDE="${FREUD_LIVE_EVAL_RUN_DIR:-}"
PRESERVE_MEMORY="${FREUD_LIVE_EVAL_PRESERVE_MEMORY:-false}"

log_info() { printf '%s\n' "$*"; }
log_error() { printf '%s\n' "$*" >&2; }
normalize_answer() {
  local raw="$1"
  printf '%s' "$raw" \
    | sed -E 's/^ego> //g' \
    | tr '[:upper:]' '[:lower:]' \
    | tr '\n' ' ' \
    | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//'
}
extract_answer_line() {
  local raw_file="$1"
  if [[ ! -f "$raw_file" ]]; then
    return 0
  fi
  if rg -n '^ego> ' "$raw_file" >/dev/null 2>&1; then
    grep '^ego> ' "$raw_file" | tail -n 1
  else
    cat "$raw_file"
  fi
}

usage() {
  cat <<'EOF'
Usage: freud/scripts/live-eval.sh --input <file> [options]

Runs a single-input live eval against Psyke with LLM response caching.

Options:
  --input <file>          Input file containing the user message (required)
  --expected <file>       Expected answer file for acceptance check
  --cache-replay <file>   JSONL cache file to replay (enables replay mode)
  --timeout <seconds>     Timeout for the live eval run (default: 120)
  --preserve-memory       Do not clear Freud-isolated memory before the run
  -h, --help              Show this help message

Environment:
  FREUD_LIVE_EVAL_TIMEOUT   Default timeout in seconds (default: 120)
  FREUD_RUN_ROOT            Run artifact root (default: .psyke/runs/freud)

Memory isolation:
  Uses namespace "freud-eval" (pgvector), .psyke/freud-logbook.db (episodic),
  and .psyke/freud-metrics.db (usage metrics).
  By default, all freud memory is cleared before each run (--clear-memory-all).
  Use --preserve-memory when an eval sequence intentionally depends on prior
  isolated freud memory within the same namespace/DBs.
  User memory (namespace "psyke", .psyke/logbook.db, .psyke/metrics.db) is never touched.

First run (no --cache-replay): records all LLM responses to a cache file.
Replay run (with --cache-replay): replays cached responses until divergence,
then uses real LLM for remaining calls.

Exit codes:
  0 = answer delivered (and accepted if --expected provided)
  1 = answer delivered but rejected / error
  2 = timeout
EOF
}

prime_gradle_wrapper_cache() {
  [[ -z "${GRADLE_USER_HOME:-}" ]] && return 0
  local local_dists="$GRADLE_USER_HOME/wrapper/dists"
  local home_dists="$HOME/.gradle/wrapper/dists"
  if compgen -G "$local_dists/gradle-*-bin/*" >/dev/null; then
    return 0
  fi
  if [[ -d "$home_dists" ]]; then
    mkdir -p "$local_dists"
    cp -R "$home_dists"/gradle-*-bin "$local_dists"/ 2>/dev/null || true
  fi
}

write_local_freud_pointers() {
  local local_root="$REPO_ROOT/freud"
  mkdir -p "$local_root/logs"
  ln -sfn "$RUN_DIR" "$local_root/latest"
  ln -sfn "$RUN_DIR/logs" "$local_root/logs/latest"
  ln -sfn "$RUN_DIR/artifacts" "$local_root/artifacts-latest"
  printf '%s\n' "$RUN_DIR" >"$local_root/latest-run.txt"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      [[ $# -lt 2 ]] && { log_error "Missing value for $1"; exit 1; }
      INPUT_FILE="$2"; shift 2 ;;
    --input=*) INPUT_FILE="${1#*=}"; shift ;;
    --expected)
      [[ $# -lt 2 ]] && { log_error "Missing value for $1"; exit 1; }
      EXPECTED_FILE="$2"; shift 2 ;;
    --expected=*) EXPECTED_FILE="${1#*=}"; shift ;;
    --cache-replay)
      [[ $# -lt 2 ]] && { log_error "Missing value for $1"; exit 1; }
      CACHE_REPLAY_FILE="$2"; shift 2 ;;
    --cache-replay=*) CACHE_REPLAY_FILE="${1#*=}"; shift ;;
    --timeout)
      [[ $# -lt 2 ]] && { log_error "Missing value for $1"; exit 1; }
      TIMEOUT="$2"; shift 2 ;;
    --timeout=*) TIMEOUT="${1#*=}"; shift ;;
    --preserve-memory) PRESERVE_MEMORY="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) log_error "Unknown argument: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$INPUT_FILE" ]]; then
  log_error "Error: --input is required."
  usage
  exit 1
fi

if [[ ! -f "$INPUT_FILE" ]]; then
  log_error "Error: input file not found: $INPUT_FILE"
  exit 1
fi

if [[ -n "$CACHE_REPLAY_FILE" && ! -f "$CACHE_REPLAY_FILE" ]]; then
  log_error "Error: cache replay file not found: $CACHE_REPLAY_FILE"
  exit 1
fi

# Resolve paths from config/env the same way feature-loop.sh does.
if [[ "$RUN_ROOT" = /* ]]; then
  RUN_ROOT_ABS="$RUN_ROOT"
else
  RUN_ROOT_ABS="$REPO_ROOT/$RUN_ROOT"
fi

if [[ -n "$GRADLE_USER_HOME_CFG" ]]; then
  if [[ "$GRADLE_USER_HOME_CFG" = /* ]]; then
    GRADLE_USER_HOME="$GRADLE_USER_HOME_CFG"
  else
    GRADLE_USER_HOME="$REPO_ROOT/$GRADLE_USER_HOME_CFG"
  fi
  mkdir -p "$GRADLE_USER_HOME"
  export GRADLE_USER_HOME
  prime_gradle_wrapper_cache
fi

mkdir -p "$RUN_ROOT_ABS"
if [[ -n "$RUN_DIR_OVERRIDE" ]]; then
  if [[ "$RUN_DIR_OVERRIDE" = /* ]]; then
    RUN_DIR="$RUN_DIR_OVERRIDE"
  else
    RUN_DIR="$REPO_ROOT/$RUN_DIR_OVERRIDE"
  fi
else
  TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  RUN_DIR="$(mktemp -d "$RUN_ROOT_ABS/${TIMESTAMP}-live-eval-XXXXXX")"
fi
mkdir -p "$RUN_DIR/logs" "$RUN_DIR/artifacts"
export FREUD_RUN_DIR="$RUN_DIR"
export FREUD_ARTIFACT_DIR="$RUN_DIR/artifacts"
write_local_freud_pointers

# Determine cache mode
if [[ -n "$CACHE_REPLAY_FILE" ]]; then
  CACHE_MODE="replay"
  CACHE_FILE="$(cd "$(dirname "$CACHE_REPLAY_FILE")" && pwd)/$(basename "$CACHE_REPLAY_FILE")"
else
  CACHE_MODE="record"
  CACHE_FILE="$RUN_DIR/artifacts/llm-cache.jsonl"
fi

# Copy input to artifacts for reference
cp "$INPUT_FILE" "$RUN_DIR/artifacts/input.txt"

log_info "=== Freud Live Eval ==="
log_info "Run directory: $RUN_DIR"
log_info "Cache mode: $CACHE_MODE"
log_info "Cache file: $CACHE_FILE"
log_info "Timeout: ${TIMEOUT}s"
log_info "Preserve memory: ${PRESERVE_MEMORY}"
if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
  log_info "Gradle user home: ${GRADLE_USER_HOME}"
fi
log_info ""

# Set environment for the run
export PSYKE_LLM_CACHE_MODE="$CACHE_MODE"
export PSYKE_LLM_CACHE_FILE="$CACHE_FILE"
export PSYKE_LOG_FILE="$RUN_DIR/logs/psyke.log"
export PSYKE_EVENT_LOG_FILE="$RUN_DIR/logs/events.jsonl"
export EGO_TASK_WORKSPACE_DEBUG_CAPTURE_ENABLED="true"
export MEMORY_DEFAULT_NAMESPACE="freud-eval"
export PSYKE_LOGBOOK_DB_PATH="$REPO_ROOT/.psyke/freud-logbook.db"
export PSYKE_METRICS_DB="$REPO_ROOT/.psyke/freud-metrics.db"

# Run Psyke in freud-live mode
RUN_START="$(date +%s)"
clear_memory_arg="--clear-memory-all"
RAW_STDOUT_FILE="$RUN_DIR/logs/stdout.log"
if [[ "$PRESERVE_MEMORY" == "true" ]]; then
  clear_memory_arg=""
fi
set +e
cat "$INPUT_FILE" \
  | "$PSYKE_CMD" --freud-live --freud-live-timeout "$TIMEOUT" ${clear_memory_arg:+"$clear_memory_arg"} --no-id \
  2>"$RUN_DIR/logs/stderr.log" \
  | tee "$RAW_STDOUT_FILE"
EXIT_CODE="${PIPESTATUS[1]:-$?}"
set -e
ANSWER_OUTPUT="$(extract_answer_line "$RAW_STDOUT_FILE")"
printf '%s\n' "$ANSWER_OUTPUT" >"$RUN_DIR/artifacts/answer.txt"
if [[ -n "$ANSWER_OUTPUT" ]]; then
  printf '%s\n' "$ANSWER_OUTPUT"
fi
RUN_END="$(date +%s)"
DURATION=$((RUN_END - RUN_START))

log_info ""
log_info "Exit code: $EXIT_CODE (duration: ${DURATION}s)"

# Write verdict
VERDICT="unknown"
VERDICT_DETAIL=""

if [[ "$EXIT_CODE" -eq 0 ]]; then
  if [[ -n "$EXPECTED_FILE" && -f "$EXPECTED_FILE" ]]; then
    EXPECTED_CONTENT="$(cat "$EXPECTED_FILE")"
    ANSWER_CONTENT="$(cat "$RUN_DIR/artifacts/answer.txt" 2>/dev/null || echo "")"
    NORMALIZED_EXPECTED="$(normalize_answer "$EXPECTED_CONTENT")"
    NORMALIZED_ANSWER="$(normalize_answer "$ANSWER_CONTENT")"
    if [[ "$NORMALIZED_ANSWER" == "$NORMALIZED_EXPECTED" ]]; then
      VERDICT="pass"
      VERDICT_DETAIL="Answer matched expected content after normalization."
    else
      VERDICT="fail"
      VERDICT_DETAIL="Answer did not match expected content after normalization."
      EXIT_CODE=1
    fi
  else
    VERDICT="pass"
    VERDICT_DETAIL="Answer delivered (no expected file for comparison)."
  fi
elif [[ "$EXIT_CODE" -eq 2 ]]; then
  VERDICT="timeout"
  VERDICT_DETAIL="Timed out after ${TIMEOUT}s."
else
  VERDICT="error"
  VERDICT_DETAIL="Process exited with code $EXIT_CODE."
fi

cat >"$RUN_DIR/artifacts/verdict.json" <<VJSON
{
  "verdict": "$VERDICT",
  "detail": "$VERDICT_DETAIL",
  "exit_code": $EXIT_CODE,
  "duration_seconds": $DURATION,
  "cache_mode": "$CACHE_MODE",
  "cache_file": "$CACHE_FILE",
  "timeout_seconds": $TIMEOUT,
  "preserve_memory": $([[ "$PRESERVE_MEMORY" == "true" ]] && echo "true" || echo "false"),
  "run_dir": "$RUN_DIR",
  "artifacts_dir": "$RUN_DIR/artifacts",
  "logs_dir": "$RUN_DIR/logs",
  "answer_file": "$RUN_DIR/artifacts/answer.txt",
  "input_file": "$RUN_DIR/artifacts/input.txt"
}
VJSON

# Run triage if logs exist
if [[ -d "$RUN_DIR/logs" ]]; then
  log_info "Running triage..."
  PYTHONPATH="$REPO_ROOT" python3 -m freud.py.triage "$RUN_DIR" 2>/dev/null || true
fi

# Run cache telemetry if event log exists
if [[ -f "$RUN_DIR/logs/events.jsonl" ]]; then
  log_info "Analyzing cache telemetry..."
  PYTHONPATH="$REPO_ROOT" python3 -m freud.py.telemetry.llm_cache "$RUN_DIR/logs/events.jsonl" \
    > "$RUN_DIR/artifacts/cache-stats.json" 2>/dev/null || true
fi

# Run summarize
if [[ -f "$RUN_DIR/artifacts/verdict.json" ]]; then
  log_info "Generating summary..."
  PYTHONPATH="$REPO_ROOT" python3 -m freud.py.summarize "$RUN_DIR" 2>/dev/null || true
fi

# Update latest symlink
ln -sfn "$RUN_DIR" "$RUN_ROOT_ABS/latest"

# Print summary
log_info ""
log_info "=== Result ==="
log_info "Verdict: $VERDICT"
log_info "Detail: $VERDICT_DETAIL"
log_info "Duration: ${DURATION}s"
log_info "Artifacts: $RUN_DIR/artifacts/"
if [[ -f "$RUN_DIR/artifacts/cache-stats.json" ]]; then
  log_info "Cache stats: $(cat "$RUN_DIR/artifacts/cache-stats.json")"
fi
log_info ""

exit "$EXIT_CODE"
