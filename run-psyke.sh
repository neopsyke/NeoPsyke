#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BIN="$ROOT_DIR/build/install/psyke/bin/psyke"
LOG_LEVEL="${PSYKE_LOG_LEVEL:-warning}"
LOOP_DELAY_MS="${EGO_LOOP_DELAY_MS:-1000}"
LOG_DIR="${PSYKE_LOG_DIR:-$ROOT_DIR/.psyke/logs}"
LOG_RETENTION="${PSYKE_LOG_RETENTION:-30}"
APP_ARGS=()

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
      shift 2
      ;;
    --log-level=*)
      LOG_LEVEL="${1#*=}"
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
    -h|--help)
      cat <<'EOF'
Usage: ./run-psyke.sh [--log-level LEVEL] [--loop-delay-ms MS|--no-delay] [--] [app-args...]

Options:
  -l, --log-level LEVEL   SLF4J simple logger level (default: warning)
      --loop-delay-ms MS  Delay between interactive loop cycles (default: 1000)
      --no-delay          Alias for --loop-delay-ms 0
  -h, --help              Show this help message

Environment:
  MISTRAL_API_KEY         Required for model access
  PSYKE_LOG_LEVEL         Default log level if --log-level is not provided
  PSYKE_LOG_DIR           Directory for run logs (default: .psyke/logs)
  PSYKE_LOG_RETENTION     Number of run log files to keep (default: 30)
  PSYKE_METRICS_DB        SQLite path for persisted local metrics
  EGO_LOOP_DELAY_MS       Delay between loop cycles in ms (default via launcher: 1000)
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

if ! [[ "${LOG_RETENTION}" =~ ^[0-9]+$ ]] || [[ "${LOG_RETENTION}" -lt 1 ]]; then
  LOG_RETENTION="30"
fi

mkdir -p "$LOG_DIR/runs"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
RUN_LOG_FILE="$LOG_DIR/runs/$RUN_ID.log"
RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
touch "$RUN_LOG_FILE"
ln -sfn "runs/$RUN_ID.log" "$LOG_DIR/latest.log"
cat >"$LOG_DIR/latest-run.env" <<EOF
PSYKE_LOG_RUN_ID=$RUN_ID
PSYKE_LOG_FILE=$RUN_LOG_FILE
PSYKE_LOG_STARTED_AT=$RUN_STARTED_AT
EOF

retained=0
while IFS= read -r old_log; do
  retained=$((retained + 1))
  if [[ "$retained" -gt "$LOG_RETENTION" ]]; then
    rm -f "$old_log"
  fi
done < <(ls -1t "$LOG_DIR"/runs/*.log 2>/dev/null || true)

export EGO_LOOP_DELAY_MS="${LOOP_DELAY_MS}"
export PSYKE_LOG_FILE="$RUN_LOG_FILE"
export JAVA_OPTS="${JAVA_OPTS:-} -Dorg.slf4j.simpleLogger.defaultLogLevel=${LOG_LEVEL} -Dorg.slf4j.simpleLogger.logFile=${RUN_LOG_FILE} -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=yyyy-MM-dd_HH:mm:ss.SSSZ"

if [[ ! -x "$APP_BIN" ]]; then
  echo "Bootstrapping local app distribution (one-time)..."
  "$ROOT_DIR/gradlew" --no-problems-report installDist
fi

echo "Psyke logs for this run: $RUN_LOG_FILE"
echo "Latest run log pointer: $LOG_DIR/latest.log"

if [[ ${#APP_ARGS[@]} -gt 0 ]]; then
  exec "$APP_BIN" "${APP_ARGS[@]}"
else
  exec "$APP_BIN"
fi
