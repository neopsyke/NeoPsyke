#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BIN="$ROOT_DIR/build/install/psyke/bin/psyke"
LOG_LEVEL="${PSYKE_LOG_LEVEL:-warning}"
LOG_LEVEL_EXPLICIT=0
LOG_LEVEL_FROM_ENV=0
EVAL_MODE=0
LOOP_DELAY_MS="${EGO_LOOP_DELAY_MS:-1000}"
LOG_DIR="${PSYKE_LOG_DIR:-$ROOT_DIR/.psyke/logs}"
LOG_RETENTION="${PSYKE_LOG_RETENTION:-30}"
APP_ARGS=()

if [[ -n "${PSYKE_LOG_LEVEL:-}" ]]; then
  LOG_LEVEL_FROM_ENV=1
fi

if [[ -z "${PSYKE_METRICS_DB:-}" ]]; then
  export PSYKE_METRICS_DB="$ROOT_DIR/.psyke/metrics.db"
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    -l|--log-level)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for $1" >&2
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
        echo "Missing value for $1" >&2
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
    -h|--help)
      cat <<'EOF'
Usage: ./run-psyke.sh [--log-level LEVEL] [--loop-delay-ms MS|--no-delay] [--] [app-args...]

Options:
  -l, --log-level LEVEL   SLF4J simple logger level (default: warning)
      --loop-delay-ms MS  Delay between interactive loop cycles (default: 1000)
      --no-delay          Alias for --loop-delay-ms 0
  -h, --help              Show this help message

Environment:
  PSYKE_LLM_CONFIG_FILE   Optional path to LLM runtime YAML (default: ./llm-runtime.yaml)
  LLM_PROVIDER            Optional env override for YAML provider: groq or mistral
  GROQ_API_KEY            Required when provider=groq
  MISTRAL_API_KEY         Required when provider=mistral
  LLM_API_KEY             Optional generic API key fallback for the selected provider
  PSYKE_MCP_CONFIG_FILE   Optional path to MCP runtime YAML (default: ./mcp-runtime.yaml)
  MCP_MEMORY_SERVER_CMD   Optional override for memory command (required only if YAML memory command is not set)
  PSYKE_LOG_LEVEL         Default log level if --log-level is not provided
  PSYKE_LOG_DIR           Directory for run logs (default: .psyke/logs)
  PSYKE_LOG_RETENTION     Number of run log files to keep (default: 30)
  PSYKE_EVENT_LOG_FILE    Optional path override for instrumentation sidecar JSONL
  PSYKE_METRICS_DB        SQLite path for persisted local metrics
  EGO_LOOP_DELAY_MS       Delay between loop cycles in ms (default via launcher: 1000)
  PSYKE_EVAL_TRANSPORT_DEBUG  Set to true to keep low-level LLM transport debug lines in eval mode
  PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS  Max chars stored per raw eval thought (default: unlimited)

Eval mode (forwarded to app):
  --eval-reasoning-only           Run deterministic reasoning self-eval (no tools/actions)
                                 (defaults launcher log level to trace unless overridden)
  --eval-reasoning-mode MODE      Eval mode: logic (default) or model
  --eval-memory-live              Run live memory eval (real LLM + real MCP memory)
  --eval-stage ID                 Label this eval run for history comparison
  --eval-reasoning-max-attempts N Max retries per reasoning task (default: 4)
  --eval-reasoning-tasks id1,id2  Restrict reasoning eval to selected tasks
  --eval-memory-max-attempts N    Max retries per long-term memory assessment task (default: 2)
  --eval-memory-tasks id1,id2     Restrict memory eval to selected tasks
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
if [[ -n "${PSYKE_EVENT_LOG_FILE:-}" ]]; then
  RUN_EVENT_FILE="${PSYKE_EVENT_LOG_FILE}"
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
PSYKE_LOG_RUN_ID=$RUN_ID
PSYKE_LOG_FILE=$RUN_LOG_FILE
PSYKE_EVENT_LOG_FILE=$RUN_EVENT_FILE
PSYKE_LOG_STARTED_AT=$RUN_STARTED_AT
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
export PSYKE_LOG_FILE="$RUN_LOG_FILE"
export PSYKE_EVENT_LOG_FILE="$RUN_EVENT_FILE"

JAVA_OPTS_APPEND=" -Dorg.slf4j.simpleLogger.defaultLogLevel=${LOG_LEVEL} -Dorg.slf4j.simpleLogger.logFile=${RUN_LOG_FILE} -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=yyyy-MM-dd_HH:mm:ss.SSSZ"
if [[ "$EVAL_MODE" -eq 1 ]] && [[ "${PSYKE_EVAL_TRANSPORT_DEBUG:-false}" != "true" ]]; then
  JAVA_OPTS_APPEND="${JAVA_OPTS_APPEND} -Dorg.slf4j.simpleLogger.log.psyke.llm.MistralChatClient=warn -Dorg.slf4j.simpleLogger.log.psyke.llm.GroqChatClient=warn"
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

if [[ "$NEEDS_BUILD" -eq 1 ]]; then
  echo "Building local app distribution..."
  "$ROOT_DIR/gradlew" --no-problems-report installDist
fi

echo "Psyke logs for this run: $RUN_LOG_FILE"
echo "Psyke event sidecar for this run: $RUN_EVENT_FILE"
echo "Latest run log pointer: $LOG_DIR/latest.log"

if [[ ${#APP_ARGS[@]} -gt 0 ]]; then
  exec "$APP_BIN" "${APP_ARGS[@]}"
else
  exec "$APP_BIN"
fi
