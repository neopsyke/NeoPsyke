#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${MEMORY_PROVIDER_HTTP_PORT:-7841}"
HOST="${MEMORY_PROVIDER_HTTP_HOST:-127.0.0.1}"
JAR_PATH="${MEMORY_PROVIDER_JAR_PATH:-}"

if [[ -z "$JAR_PATH" ]]; then
  JAR_PATH="$(find "$ROOT_DIR" -maxdepth 3 -type f -name 'neopsyke-pgvector-memory-*-all.jar' | sort | tail -n 1)"
fi

if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
  echo "Provider fat jar not found. Build it first with ./gradlew fatJar or set MEMORY_PROVIDER_JAR_PATH." >&2
  exit 1
fi

export PGVECTOR_BOOTSTRAP_MODE="${PGVECTOR_BOOTSTRAP_MODE:-auto}"

exec java -jar "$JAR_PATH" --transport=http --host="$HOST" --port="$PORT"
