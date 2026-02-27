#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BIN="$ROOT_DIR/build/install/psyke/bin/psyke"
LOG_LEVEL="${PSYKE_LOG_LEVEL:-warning}"
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
    -h|--help)
      cat <<'EOF'
Usage: ./run-psyke.sh [--log-level LEVEL] [--] [app-args...]

Options:
  -l, --log-level LEVEL   SLF4J simple logger level (default: warning)
  -h, --help              Show this help message

Environment:
  MISTRAL_API_KEY         Required for model access
  PSYKE_LOG_LEVEL         Default log level if --log-level is not provided
  PSYKE_METRICS_DB        SQLite path for persisted local metrics
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
export JAVA_OPTS="${JAVA_OPTS:-} -Dorg.slf4j.simpleLogger.defaultLogLevel=${LOG_LEVEL}"

if [[ ! -x "$APP_BIN" ]]; then
  echo "Bootstrapping local app distribution (one-time)..."
  "$ROOT_DIR/gradlew" --no-problems-report installDist
fi

if [[ ${#APP_ARGS[@]} -gt 0 ]]; then
  exec "$APP_BIN" "${APP_ARGS[@]}"
else
  exec "$APP_BIN"
fi
