#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BIN="$ROOT_DIR/build/install/neopsyke/bin/neopsyke"
MEMORY_FAT_JAR="$ROOT_DIR/neopsyke-pgvector-memory/build/libs/neopsyke-pgvector-memory-0.1.0-all.jar"
LOG_LEVEL="${NEOPSYKE_LOG_LEVEL:-warning}"
LOG_LEVEL_EXPLICIT=0
LOG_LEVEL_FROM_ENV=0
EVAL_MODE=0
FREUD_LIVE_MODE=0
DISABLE_ID=0
GOALS_OVERRIDE=""
LOOP_DELAY_MS="${EGO_LOOP_DELAY_MS:-1000}"
LOG_DIR="${NEOPSYKE_LOG_DIR:-$ROOT_DIR/.neopsyke/logs}"
LOG_RETENTION="${NEOPSYKE_LOG_RETENTION:-30}"
AUTO_START_PGVECTOR="${NEOPSYKE_AUTO_START_PGVECTOR:-false}"
APP_ARGS=()

log_info() {
  if [[ "$FREUD_LIVE_MODE" -eq 1 ]]; then
    printf '%s\n' "$*" >&2
  else
    printf '%s\n' "$*"
  fi
}

log_error() {
  printf '%s\n' "$*" >&2
}

is_pgvector_running() {
  if ! command -v docker >/dev/null 2>&1; then
    return 1
  fi
  if [[ ! -f "$ROOT_DIR/docker-compose.yml" ]]; then
    return 1
  fi
  docker compose ps --status running --services 2>/dev/null | grep -qx "pgvector"
}

maybe_start_pgvector() {
  if [[ "$AUTO_START_PGVECTOR" != "true" ]]; then
    if ! is_pgvector_running; then
      log_info "Tip: pgvector is not running. Start it with: docker compose up -d pgvector"
    fi
    return
  fi
  if is_pgvector_running; then
    return
  fi
  if ! command -v docker >/dev/null 2>&1; then
    log_error "NEOPSYKE_AUTO_START_PGVECTOR=true but docker is not available in PATH."
    return
  fi
  if [[ ! -f "$ROOT_DIR/docker-compose.yml" ]]; then
    log_error "NEOPSYKE_AUTO_START_PGVECTOR=true but docker-compose.yml is missing at $ROOT_DIR."
    return
  fi
  log_info "Starting pgvector service via docker compose..."
  if ! docker compose up -d pgvector; then
    log_error "Failed to start pgvector automatically. Run: docker compose up -d pgvector"
  fi
}

if [[ -n "${NEOPSYKE_LOG_LEVEL:-}" ]]; then
  LOG_LEVEL_FROM_ENV=1
fi

if [[ -z "${NEOPSYKE_METRICS_DB:-}" ]]; then
  export NEOPSYKE_METRICS_DB="$ROOT_DIR/.neopsyke/metrics.db"
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    -l|--log-level)
      if [[ $# -lt 2 ]]; then
        log_error "Missing value for $1"
        exit 1
      fi
      LOG_LEVEL="$2"
      LOG_LEVEL_EXPLICIT=1
      shift 2
      ;;
    --log-level=*)
      LOG_LEVEL="${1#*=}"
      LOG_LEVEL_EXPLICIT=1
      shift
      ;;
    --loop-delay-ms)
      if [[ $# -lt 2 ]]; then
        log_error "Missing value for $1"
        exit 1
      fi
      LOOP_DELAY_MS="$2"
      shift 2
      ;;
    --loop-delay-ms=*)
      LOOP_DELAY_MS="${1#*=}"
      shift
      ;;
    --no-delay)
      LOOP_DELAY_MS="0"
      shift
      ;;
    --eval-reasoning-only)
      EVAL_MODE=1
      APP_ARGS+=("$1")
      shift
      ;;
    --eval-memory-live)
      EVAL_MODE=1
      APP_ARGS+=("$1")
      shift
      ;;
    --freud-live)
      EVAL_MODE=1
      FREUD_LIVE_MODE=1
      APP_ARGS+=("$1")
      shift
      ;;
    --freud-live-timeout)
      if [[ $# -lt 2 ]]; then
        log_error "Missing value for $1"
        exit 1
      fi
      APP_ARGS+=("$1" "$2")
      shift 2
      ;;
    --freud-live-timeout=*)
      APP_ARGS+=("--freud-live-timeout" "${1#*=}")
      shift
      ;;
    --no-id)
      DISABLE_ID=1
      shift
      ;;
    --goals)
      GOALS_OVERRIDE="true"
      shift
      ;;
    --no-goals)
      GOALS_OVERRIDE="false"
      shift
      ;;
    --clear-memory-all|--clear-memory-vector|--clear-memory-episodic|--clear-memory-lessons)
      APP_ARGS+=("$1")
      shift
      ;;
    -h|--help)
      cat <<'EOF'
Usage: ./run-neopsyke.sh [--log-level LEVEL] [--loop-delay-ms MS|--no-delay] [--no-id] [--goals|--no-goals] [--clear-memory-*] [--] [app-args...]

Options:
  -l, --log-level LEVEL   SLF4J simple logger level (default: warning)
      --loop-delay-ms MS  Delay between interactive loop cycles (default: 1000)
      --no-delay          Alias for --loop-delay-ms 0
      --no-id             Disable the Id module (autonomous drives) for this run
      --goals             Enable the goals subsystem for this run
      --no-goals          Disable the goals subsystem for this run
  -h, --help              Show this help message

Memory clearing (applied before agent startup):
      --clear-memory-all         Clear ALL long-term memory (vector + episodic) before starting
      --clear-memory-vector      Clear vector/hippocampus memory before starting
      --clear-memory-episodic    Clear episodic logbook memory before starting
      --clear-memory-lessons     Clear lessons from vector memory before starting

Environment:
  NEOPSYKE_LLM_CONFIG_FILE   Optional path to LLM runtime YAML (default: ./llm-runtime.yaml)
  NEOPSYKE_AGENT_CONFIG_FILE Optional path to agent/app/eval runtime YAML (default: ./agent-runtime.yaml)
  GROQ_API_KEY            Required when a configured provider uses Groq
  MISTRAL_API_KEY         Required when a configured provider uses Mistral
  GOOGLE_API_KEY          Required when a configured provider uses Google
  OPENAI_API_KEY          Required when a configured provider uses OpenAI
  NEOPSYKE_MCP_CONFIG_FILE      Optional path to MCP runtime YAML for time/fetch (default: ./mcp-runtime.yaml)
  NEOPSYKE_MEMORY_CONFIG_FILE   Optional path to memory runtime YAML (default: ./memory-runtime.yaml)
  NEOPSYKE_LOG_LEVEL         Default log level if --log-level is not provided
  NEOPSYKE_LOG_DIR           Directory for run logs (default: .neopsyke/logs)
  NEOPSYKE_LOG_RETENTION     Number of run log files to keep (default: 30)
  NEOPSYKE_AUTO_START_PGVECTOR  When true, launcher runs 'docker compose up -d pgvector' if needed
  NEOPSYKE_MEMORY_MODE         Memory mode: off, default, external
  NEOPSYKE_MEMORY_DEFAULT_COMMAND  Optional override for managed default provider command
  NEOPSYKE_MEMORY_DEFAULT_BASE_URL Optional override for managed default provider base URL
  MEMORY_DEFAULT_NAMESPACE  Namespace for long-term memory provider reads/writes (launcher default: neopsyke)
  NEOPSYKE_EVENT_LOG_FILE    Optional path override for instrumentation sidecar JSONL
  NEOPSYKE_METRICS_DB        SQLite path for persisted local metrics
  EGO_LOOP_DELAY_MS       Delay between loop cycles in ms (default via launcher: 1000)
  NEOPSYKE_ID_CONFIG_FILE       Optional path to Id runtime YAML (default: ./id-runtime.yaml)
  NEOPSYKE_ID_ENABLED            Override Id module enabled state (true/false, overrides YAML)
  NEOPSYKE_GOALS_ENABLED         Override goals subsystem enabled state (true/false, launcher default: true)
  NEOPSYKE_LLM_CACHE_MODE       LLM response cache mode: record, replay, or off (default: off)
  NEOPSYKE_LLM_CACHE_FILE       Path to LLM cache JSONL file (required when cache mode is record or replay)
  NEOPSYKE_EVAL_TRANSPORT_DEBUG  Set to true to keep low-level LLM transport debug lines in eval mode
  NEOPSYKE_EVAL_MAX_RAW_RESPONSE_CHARS  Max chars stored per raw eval thought (default: unlimited)

Freud live eval mode (forwarded to app):
  --freud-live                Run single-input live eval (reads stdin, writes answer to stdout)
  --freud-live-timeout N      Timeout in seconds for freud-live mode (default: 120)

Eval mode (forwarded to app):
  --eval-reasoning-only           Run deterministic reasoning self-eval (no tools/actions)
                                 (defaults launcher log level to trace unless overridden)
  --eval-reasoning-mode MODE      Eval mode: logic (default) or model
  --eval-memory-live              Run live memory eval (real LLM + real long-term memory provider)
  --eval-stage ID                 Label this eval run (default: UTC date, e.g. 2026-02-28)
  --eval-reasoning-max-attempts N Max retries per reasoning task (default: 4)
  --eval-reasoning-tasks id1,id2  Run only selected reasoning task ids
  --eval-memory-max-attempts N    Max retries per long-term memory assessment task (default: 2)
  --eval-memory-tasks id1,id2     Run only selected memory eval task ids
EOF
      exit 0
      ;;
    --)
      shift
      APP_ARGS+=("$@")
      break
      ;;
    *)
      APP_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "${LOG_LEVEL}" ]]; then
  LOG_LEVEL="warning"
fi

if [[ "$EVAL_MODE" -eq 1 && "$LOG_LEVEL_EXPLICIT" -eq 0 && "$LOG_LEVEL_FROM_ENV" -eq 0 ]]; then
  LOG_LEVEL="trace"
fi

if ! [[ "${LOG_RETENTION}" =~ ^[0-9]+$ ]] || [[ "${LOG_RETENTION}" -lt 1 ]]; then
  LOG_RETENTION="30"
fi

mkdir -p "$LOG_DIR/runs"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
RUN_LOG_FILE="$LOG_DIR/runs/$RUN_ID.log"
RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ -n "${NEOPSYKE_EVENT_LOG_FILE:-}" ]]; then
  RUN_EVENT_FILE="${NEOPSYKE_EVENT_LOG_FILE}"
else
  RUN_EVENT_FILE="$LOG_DIR/runs/$RUN_ID.events.jsonl"
fi
touch "$RUN_LOG_FILE"
ln -sfn "runs/$RUN_ID.log" "$LOG_DIR/latest.log"
touch "$RUN_EVENT_FILE"
if [[ "$RUN_EVENT_FILE" == "$LOG_DIR"/runs/* ]]; then
  ln -sfn "${RUN_EVENT_FILE#$LOG_DIR/}" "$LOG_DIR/latest-events.jsonl"
else
  ln -sfn "$RUN_EVENT_FILE" "$LOG_DIR/latest-events.jsonl"
fi
cat >"$LOG_DIR/latest-run.env" <<EOF
NEOPSYKE_LOG_RUN_ID=$RUN_ID
NEOPSYKE_LOG_FILE=$RUN_LOG_FILE
NEOPSYKE_EVENT_LOG_FILE=$RUN_EVENT_FILE
NEOPSYKE_LOG_STARTED_AT=$RUN_STARTED_AT
EOF

retained=0
while IFS= read -r old_log; do
  retained=$((retained + 1))
  if [[ "$retained" -gt "$LOG_RETENTION" ]]; then
    rm -f "$old_log"
    rm -f "${old_log%.log}.events.jsonl"
  fi
done < <(ls -1t "$LOG_DIR"/runs/*.log 2>/dev/null || true)

export EGO_LOOP_DELAY_MS="${LOOP_DELAY_MS}"
export NEOPSYKE_LOG_FILE="$RUN_LOG_FILE"
export NEOPSYKE_EVENT_LOG_FILE="$RUN_EVENT_FILE"
export MEMORY_DEFAULT_NAMESPACE="${MEMORY_DEFAULT_NAMESPACE:-neopsyke}"
export EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED="true"

if [[ "$DISABLE_ID" -eq 1 ]]; then
  export NEOPSYKE_ID_ENABLED="false"
fi

if [[ -n "$GOALS_OVERRIDE" ]]; then
  EFFECTIVE_GOALS_ENABLED="$GOALS_OVERRIDE"
elif [[ -n "${NEOPSYKE_GOALS_ENABLED:-}" ]]; then
  EFFECTIVE_GOALS_ENABLED="$NEOPSYKE_GOALS_ENABLED"
else
  EFFECTIVE_GOALS_ENABLED="true"
fi
export NEOPSYKE_GOALS_ENABLED="$EFFECTIVE_GOALS_ENABLED"

effective_goals_enabled_normalized="$(printf '%s' "$EFFECTIVE_GOALS_ENABLED" | tr '[:upper:]' '[:lower:]')"
if [[ "$effective_goals_enabled_normalized" != "true" ]]; then
  log_info "Warning: goals subsystem is disabled for this run. Use --goals or set NEOPSYKE_GOALS_ENABLED=true to enable persistent goal creation."
fi

JAVA_OPTS_APPEND=" -Dorg.slf4j.simpleLogger.defaultLogLevel=${LOG_LEVEL} -Dorg.slf4j.simpleLogger.logFile=${RUN_LOG_FILE} -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=yyyy-MM-dd_HH:mm:ss.SSSZ"
if [[ "$EVAL_MODE" -eq 1 ]] && [[ "${NEOPSYKE_EVAL_TRANSPORT_DEBUG:-false}" != "true" ]]; then
  JAVA_OPTS_APPEND="${JAVA_OPTS_APPEND} -Dorg.slf4j.simpleLogger.log.ai.neopsyke.llm.MistralChatClient=warn -Dorg.slf4j.simpleLogger.log.ai.neopsyke.llm.GroqChatClient=warn"
fi
export JAVA_OPTS="${JAVA_OPTS:-}${JAVA_OPTS_APPEND}"

NEEDS_BUILD=0
if [[ ! -x "$APP_BIN" ]]; then
  NEEDS_BUILD=1
elif [[ -n "$(find \
  "$ROOT_DIR/src/main" \
  "$ROOT_DIR/src/test" \
  "$ROOT_DIR/build.gradle.kts" \
  "$ROOT_DIR/settings.gradle.kts" \
  "$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties" \
  -type f -newer "$APP_BIN" -print -quit 2>/dev/null)" ]]; then
  NEEDS_BUILD=1
fi

NEEDS_MEMORY_FAT_JAR=0
if [[ ! -f "$MEMORY_FAT_JAR" ]]; then
  NEEDS_MEMORY_FAT_JAR=1
elif [[ -n "$(find \
  "$ROOT_DIR/neopsyke-pgvector-memory/src/main" \
  "$ROOT_DIR/neopsyke-pgvector-memory/build.gradle.kts" \
  -type f -newer "$MEMORY_FAT_JAR" -print -quit 2>/dev/null)" ]]; then
  NEEDS_MEMORY_FAT_JAR=1
fi

if [[ "$NEEDS_BUILD" -eq 1 ]]; then
  log_info "Building local app distribution..."
  "$ROOT_DIR/gradlew" --no-daemon --no-problems-report installDist
fi

if [[ "$NEEDS_MEMORY_FAT_JAR" -eq 1 ]]; then
  log_info "Building memory provider fat jar..."
  "$ROOT_DIR/gradlew" --no-daemon --no-problems-report :neopsyke-pgvector-memory:fatJar
fi

maybe_start_pgvector

log_info "NeoPsyke logs for this run: $RUN_LOG_FILE"
log_info "NeoPsyke event sidecar for this run: $RUN_EVENT_FILE"
log_info "Latest run log pointer: $LOG_DIR/latest.log"

if [[ ${#APP_ARGS[@]} -gt 0 ]]; then
  exec "$APP_BIN" "${APP_ARGS[@]}"
else
  exec "$APP_BIN"
fi
