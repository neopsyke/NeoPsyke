#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

INPUT_FILE=""
EXPECTED_FILE=""
CACHE_REPLAY_FILE=""
SESSION_REPLAY_DIR=""
RECORD_SESSION=false
CONFIG_PATH="${FREUD_CONFIG:-$REPO_ROOT/freud/config/default.env}"

if [[ -f "$CONFIG_PATH" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_PATH"
fi

if [[ -n "${NEOPSYKE_LLM_CONFIG_FILE:-}" ]]; then
  export NEOPSYKE_LLM_CONFIG_FILE
fi

TIMEOUT="${FREUD_LIVE_EVAL_TIMEOUT:-120}"
GOALS_OVERRIDE=""
RUN_ROOT="${FREUD_RUN_ROOT:-.neopsyke/runs/freud}"
GRADLE_USER_HOME_CFG="${FREUD_GRADLE_USER_HOME:-.freud/gradle-home}"
NEOPSYKE_CMD="${FREUD_LIVE_EVAL_NEOPSYKE_CMD:-$REPO_ROOT/run-neopsyke.sh}"
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
  if grep -Eq '^ego> ' "$raw_file" 2>/dev/null; then
    grep '^ego> ' "$raw_file" | tail -n 1
  else
    cat "$raw_file"
  fi
}

usage() {
  cat <<'EOF'
Usage: freud/legacy/scripts/live-eval.sh --input <file> [options]

Runs a single-input live eval against NeoPsyke with LLM response caching.
This is the primary Freud live entrypoint for one-off real-agent checks.

Options:
  --input <file>          Input file containing the user message (required)
  --expected <file>       Expected answer file for acceptance check
  --cache-replay <file>   JSONL cache file to replay (enables replay mode)
  --record-session        Record all signals for later replay via --session-replay
  --session-replay <dir>  Replay a recorded session directory
  --timeout <seconds>     Timeout for the live eval run (default: 120)
  --goals                 Enable the goals subsystem for this eval
  --no-goals              Disable the goals subsystem for this eval
  -h, --help              Show this help message

Environment:
  FREUD_LIVE_EVAL_TIMEOUT      Default timeout in seconds (default: 120)
  FREUD_RUN_ROOT               Run artifact root (default: .neopsyke/runs/freud)
  FREUD_RUN_RETENTION_DAYS     Delete run dirs older than this (default: 3)

Per-run isolation:
  Each run gets fully isolated persistent state inside its run directory:
    $RUN_DIR/state/logbook.db        (episodic memory)
    $RUN_DIR/state/metrics.db        (usage metrics)
    $RUN_DIR/state/action-control.db (action staging)
  pgvector uses a per-run namespace (freud-eval-{run-id}).
  Parallel runs are safe. User data is never touched.
  Run directories older than FREUD_RUN_RETENTION_DAYS are auto-deleted.

First run (no --cache-replay): records all LLM responses to a cache file.
Replay run (with --cache-replay): replays cached responses until divergence,
then uses real LLM for remaining calls.

Exit codes:
  0 = answer delivered (and accepted if --expected provided)
  1 = answer delivered but rejected / error
  2 = timeout
EOF
}

search_logs_with_fallback() {
  local regex="$1"
  shift
  local path
  for path in "$@"; do
    [[ -e "$path" ]] || continue
    if command -v rg >/dev/null 2>&1; then
      rg -n "$regex" "$path" 2>/dev/null || true
    else
      grep -ERn -- "$regex" "$path" 2>/dev/null || true
    fi
  done
}

prime_gradle_build_cache() {
  [[ -z "${GRADLE_USER_HOME:-}" ]] && return 0
  mkdir -p "$GRADLE_USER_HOME"

  # 1. Prime wrapper dists (fast copy if available locally)
  local local_dists="$GRADLE_USER_HOME/wrapper/dists"
  local home_dists="$HOME/.gradle/wrapper/dists"
  if ! compgen -G "$local_dists/gradle-*-bin/*" >/dev/null 2>&1; then
    if [[ -d "$home_dists" ]]; then
      mkdir -p "$local_dists"
      cp -R "$home_dists"/gradle-*-bin "$local_dists"/ 2>/dev/null || true
    fi
  fi

  # 2. Prime build plugins + dependencies (Kotlin plugin, etc.)
  local marker="$GRADLE_USER_HOME/.build-cache-primed"
  if [[ -f "$marker" ]]; then
    return 0
  fi
  echo "Priming isolated Gradle home with build plugins and dependencies..." >&2
  if GRADLE_USER_HOME="$GRADLE_USER_HOME" "$REPO_ROOT/gradlew" \
      --no-daemon --no-problems-report \
      compileKotlin compileTestKotlin >/dev/null 2>&1; then
    touch "$marker"
    echo "Isolated Gradle home primed successfully." >&2
  else
    echo "WARNING: Failed to prime Gradle build cache. First build may be slow or fail offline." >&2
  fi
}

update_symlink_pointer() {
  local target="$1"
  local link_path="$2"
  local tmp_link="${link_path}.tmp.$$.$RANDOM"
  ln -s "$target" "$tmp_link" 2>/dev/null || return 0
  mv -f "$tmp_link" "$link_path" 2>/dev/null || rm -f "$tmp_link"
}

write_pointer_file() {
  local value="$1"
  local file_path="$2"
  local tmp_file="${file_path}.tmp.$$.$RANDOM"
  printf '%s\n' "$value" >"$tmp_file"
  mv -f "$tmp_file" "$file_path"
}

write_local_freud_pointers() {
  local local_root="$REPO_ROOT/freud"
  mkdir -p "$local_root/logs"
  update_symlink_pointer "$RUN_DIR" "$local_root/latest"
  update_symlink_pointer "$RUN_DIR/logs" "$local_root/logs/latest"
  update_symlink_pointer "$RUN_DIR/artifacts" "$local_root/artifacts-latest"
  write_pointer_file "$RUN_DIR" "$local_root/latest-run.txt"
}

classify_failure_class() {
  local exit_code="$1"
  local stderr_file="$2"
  local app_log_file="$3"

  if [[ "$exit_code" -eq 2 ]]; then
    printf 'timeout'
    return 0
  fi

  local runtime_matches provider_matches
  runtime_matches="$(search_logs_with_fallback \
    'Gradle could not start your build|Could not create service of type FileLockContentionHandler|Operation not permitted|Permission denied|Address already in use|No such file or directory|command not found|not executable|Unable to start|failed to launch|bootstrap|sandbox' \
    "$stderr_file" "$app_log_file")"
  if [[ -n "$runtime_matches" ]]; then
    printf 'local_runtime_bootstrap_failure'
    return 0
  fi

  provider_matches="$(search_logs_with_fallback \
    '401|403|429|rate limit|quota|authentication|unauthorized|forbidden|api key|provider unavailable|model unavailable|service unavailable|bad gateway|upstream|provider error' \
    "$stderr_file" "$app_log_file")"
  if [[ -n "$provider_matches" ]]; then
    printf 'provider_model_failure'
    return 0
  fi

  printf 'live_eval_process_failure'
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
    --session-replay)
      [[ $# -lt 2 ]] && { log_error "Missing value for $1"; exit 1; }
      SESSION_REPLAY_DIR="$2"; shift 2 ;;
    --session-replay=*) SESSION_REPLAY_DIR="${1#*=}"; shift ;;
    --record-session) RECORD_SESSION=true; shift ;;
    --timeout)
      [[ $# -lt 2 ]] && { log_error "Missing value for $1"; exit 1; }
      TIMEOUT="$2"; shift 2 ;;
    --timeout=*) TIMEOUT="${1#*=}"; shift ;;
    --preserve-memory) PRESERVE_MEMORY="true"; shift ;;
    --goals) GOALS_OVERRIDE="true"; shift ;;
    --no-goals) GOALS_OVERRIDE="false"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) log_error "Unknown argument: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$INPUT_FILE" && -z "$SESSION_REPLAY_DIR" ]]; then
  log_error "Error: --input is required (unless using --session-replay)."
  usage
  exit 1
fi

if [[ -n "$INPUT_FILE" && ! -f "$INPUT_FILE" ]]; then
  log_error "Error: input file not found: $INPUT_FILE"
  exit 1
fi

if [[ -n "$CACHE_REPLAY_FILE" && ! -f "$CACHE_REPLAY_FILE" ]]; then
  log_error "Error: cache replay file not found: $CACHE_REPLAY_FILE"
  exit 1
fi

if [[ -n "$SESSION_REPLAY_DIR" && ! -d "$SESSION_REPLAY_DIR" ]]; then
  log_error "Error: session replay directory not found: $SESSION_REPLAY_DIR"
  exit 1
fi

# Resolve session replay dir: accept either the session/ subdir or the parent run dir.
if [[ -n "$SESSION_REPLAY_DIR" ]]; then
  SESSION_REPLAY_DIR="$(cd "$SESSION_REPLAY_DIR" && pwd)"
  if [[ -d "$SESSION_REPLAY_DIR/session" && -f "$SESSION_REPLAY_DIR/session/signals.jsonl" ]]; then
    SESSION_REPLAY_DIR="$SESSION_REPLAY_DIR/session"
  fi
fi

# --session-replay implies --cache-replay from the same session directory
if [[ -n "$SESSION_REPLAY_DIR" && -z "$CACHE_REPLAY_FILE" ]]; then
  SESSION_LLM_CACHE="$SESSION_REPLAY_DIR/llm-cache.jsonl"
  if [[ -f "$SESSION_LLM_CACHE" ]]; then
    CACHE_REPLAY_FILE="$SESSION_LLM_CACHE"
  fi
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
  prime_gradle_build_cache
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

# Session recording: create session dir inside the run dir.
# Routes the LLM cache into the session dir so --session-replay finds everything.
SESSION_RECORD_DIR=""
if [[ "$RECORD_SESSION" == "true" ]]; then
  SESSION_RECORD_DIR="$RUN_DIR/session"
  mkdir -p "$SESSION_RECORD_DIR"
  export NEOPSYKE_SESSION_RECORDING_MODE="record"
  export NEOPSYKE_SESSION_RECORDING_DIR="$SESSION_RECORD_DIR"
fi

# Determine cache mode
if [[ -n "$CACHE_REPLAY_FILE" ]]; then
  CACHE_MODE="replay"
  CACHE_FILE="$(cd "$(dirname "$CACHE_REPLAY_FILE")" && pwd)/$(basename "$CACHE_REPLAY_FILE")"
elif [[ -n "$SESSION_RECORD_DIR" ]]; then
  CACHE_MODE="record"
  CACHE_FILE="$SESSION_RECORD_DIR/llm-cache.jsonl"
else
  CACHE_MODE="record"
  CACHE_FILE="$RUN_DIR/artifacts/llm-cache.jsonl"
fi

# Copy input to artifacts for reference
if [[ -n "$INPUT_FILE" ]]; then
  cp "$INPUT_FILE" "$RUN_DIR/artifacts/input.txt"
fi

# Set environment for the run
export NEOPSYKE_LLM_CACHE_MODE="$CACHE_MODE"
export NEOPSYKE_LLM_CACHE_FILE="$CACHE_FILE"

if [[ -n "$SESSION_REPLAY_DIR" ]]; then
  export NEOPSYKE_SESSION_RECORDING_MODE="replay"
  export NEOPSYKE_SESSION_RECORDING_DIR="$SESSION_REPLAY_DIR"
  # Adopt runtime config from the recording so the replay environment matches.
  RECORDING_CONTEXT_FILE="$SESSION_REPLAY_DIR/recording-context.json"
  if [[ -f "$RECORDING_CONTEXT_FILE" ]]; then
    RECORDED_GOALS="$(python3 -c "import json; print(json.load(open('$RECORDING_CONTEXT_FILE')).get('goals_enabled', False))" 2>/dev/null || echo "")"
    if [[ "$RECORDED_GOALS" == "True" ]]; then
      export NEOPSYKE_GOALS_ENABLED="true"
    elif [[ "$RECORDED_GOALS" == "False" ]]; then
      export NEOPSYKE_GOALS_ENABLED="false"
    fi
  fi
fi
export NEOPSYKE_LOG_FILE="$RUN_DIR/logs/neopsyke.log"
export NEOPSYKE_EVENT_LOG_FILE="$RUN_DIR/logs/events.jsonl"
export EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED="true"

# Per-run isolated state: all persistent data lives inside the run directory.
# This ensures parallel runs never interfere and user data is never touched.
RUN_SHORT_ID="$(basename "$RUN_DIR")"
PGVECTOR_NAMESPACE="freud-eval-${RUN_SHORT_ID}"
mkdir -p "$RUN_DIR/state"
export MEMORY_DEFAULT_NAMESPACE="$PGVECTOR_NAMESPACE"
export NEOPSYKE_LOGBOOK_DB_PATH="$RUN_DIR/state/logbook.db"
export NEOPSYKE_METRICS_DB="$RUN_DIR/state/metrics.db"
export NEOPSYKE_ACTION_CONTROL_DB_PATH="$RUN_DIR/state/action-control.db"
export NEOPSYKE_GOALS_WORKSPACE_ROOT="${NEOPSYKE_GOALS_WORKSPACE_ROOT:-$RUN_DIR/artifacts/goals}"
# Record namespace for cleanup
printf '%s\n' "$PGVECTOR_NAMESPACE" > "$RUN_DIR/state/pgvector-namespace.txt"

log_info "=== Freud Live Eval ==="
log_info "Run directory: $RUN_DIR"
log_info "Cache mode: $CACHE_MODE"
log_info "Cache file: $CACHE_FILE"
log_info "Timeout: ${TIMEOUT}s"
log_info "Per-run namespace: ${PGVECTOR_NAMESPACE}"
if [[ -n "$SESSION_RECORD_DIR" ]]; then
  log_info "Session recording: $SESSION_RECORD_DIR"
fi
if [[ -n "$SESSION_REPLAY_DIR" ]]; then
  log_info "Session replay: $SESSION_REPLAY_DIR"
fi
if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
  log_info "Gradle user home: ${GRADLE_USER_HOME}"
fi
log_info ""

if [[ -n "$GOALS_OVERRIDE" ]]; then
  export NEOPSYKE_GOALS_ENABLED="$GOALS_OVERRIDE"
fi

# Run NeoPsyke in freud-live mode
RUN_START="$(date +%s)"
RAW_STDOUT_FILE="$RUN_DIR/logs/stdout.log"
set +e
if [[ -n "$SESSION_REPLAY_DIR" && -z "$INPUT_FILE" ]]; then
  # Session replay mode: no stdin input needed — signals come from the recording
  "$NEOPSYKE_CMD" --freud-live --freud-live-timeout "$TIMEOUT" --no-id \
    </dev/null \
    2>"$RUN_DIR/logs/stderr.log" \
    | tee "$RAW_STDOUT_FILE"
else
  cat "$INPUT_FILE" \
    | "$NEOPSYKE_CMD" --freud-live --freud-live-timeout "$TIMEOUT" --no-id \
    2>"$RUN_DIR/logs/stderr.log" \
    | tee "$RAW_STDOUT_FILE"
fi
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
FAILURE_CLASS=""

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
      FAILURE_CLASS="live_eval_scoring_failure"
      EXIT_CODE=1
    fi
  else
    VERDICT="pass"
    VERDICT_DETAIL="Answer delivered (no expected file for comparison)."
  fi
elif [[ "$EXIT_CODE" -eq 2 ]]; then
  VERDICT="timeout"
  VERDICT_DETAIL="Timed out after ${TIMEOUT}s."
  FAILURE_CLASS="timeout"
else
  VERDICT="error"
  VERDICT_DETAIL="Process exited with code $EXIT_CODE."
  FAILURE_CLASS="$(classify_failure_class "$EXIT_CODE" "$RUN_DIR/logs/stderr.log" "$RUN_DIR/logs/neopsyke.log")"
fi

cat >"$RUN_DIR/artifacts/verdict.json" <<VJSON
{
  "verdict": "$VERDICT",
  "detail": "$VERDICT_DETAIL",
  "failure_class": "$FAILURE_CLASS",
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

# Run session replay telemetry if event log exists and session replay was used
if [[ -n "$SESSION_REPLAY_DIR" && -f "$RUN_DIR/logs/events.jsonl" ]]; then
  log_info "Analyzing session replay telemetry..."
  PYTHONPATH="$REPO_ROOT" python3 -m freud.py.telemetry.session_replay "$RUN_DIR/logs/events.jsonl" \
    > "$RUN_DIR/artifacts/session-replay-stats.json" 2>/dev/null || true
fi

# Run summarize
if [[ -f "$RUN_DIR/artifacts/verdict.json" ]]; then
  log_info "Generating summary..."
  PYTHONPATH="$REPO_ROOT" python3 -m freud.py.summarize "$RUN_DIR" 2>/dev/null || true
fi

# Update convenience pointer for the most recently completed live eval.
update_symlink_pointer "$RUN_DIR" "$RUN_ROOT_ABS/latest"
write_pointer_file "$RUN_DIR" "$RUN_ROOT_ABS/latest-run.txt"

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
if [[ -f "$RUN_DIR/artifacts/session-replay-stats.json" ]]; then
  log_info "Session replay stats: $(cat "$RUN_DIR/artifacts/session-replay-stats.json")"
fi
log_info ""

# ── Age-based run retention cleanup ──────────────────────────────────
# Delete run directories (and their per-run state) older than the
# configured retention period. pgvector namespaces recorded in
# state/pgvector-namespace.txt are cleaned up best-effort via the
# memory provider HTTP API.
RETENTION_DAYS="${FREUD_RUN_RETENTION_DAYS:-3}"
if [[ "$RETENTION_DAYS" -gt 0 ]]; then
  deleted_count=0
  while IFS= read -r old_run_dir; do
    [[ -d "$old_run_dir" ]] || continue
    # Best-effort pgvector namespace cleanup
    ns_file="$old_run_dir/state/pgvector-namespace.txt"
    if [[ -f "$ns_file" ]]; then
      old_ns="$(cat "$ns_file" 2>/dev/null)"
      if [[ -n "$old_ns" ]]; then
        # Try to reset via the memory provider HTTP API (non-blocking, best-effort)
        MEMORY_BASE_URL="${NEOPSYKE_MEMORY_DEFAULT_BASE_URL:-http://localhost:6333}"
        curl -sf -X DELETE "${MEMORY_BASE_URL}/collections/${old_ns}" >/dev/null 2>&1 || true
      fi
    fi
    rm -rf "$old_run_dir"
    deleted_count=$((deleted_count + 1))
  done < <(find "$RUN_ROOT_ABS" -maxdepth 1 -type d -name '*-live-eval-*' -mtime +"$RETENTION_DAYS" 2>/dev/null || true)
  if [[ "$deleted_count" -gt 0 ]]; then
    log_info "Retention cleanup: deleted $deleted_count run(s) older than ${RETENTION_DAYS} day(s)"
  fi
fi

exit "$EXIT_CODE"
