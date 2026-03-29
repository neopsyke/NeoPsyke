#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$SCRIPT_DIR/bin"
BIN_PATH="$BIN_DIR/freud"
CACHE_DIR="${GOCACHE:-$SCRIPT_DIR/.cache/go-build}"

if ! command -v go >/dev/null 2>&1; then
  echo "bootstrap failed: missing 'go' in PATH" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "bootstrap failed: missing 'java' in PATH (JDK 21+ required)" >&2
  exit 1
fi

mkdir -p "$BIN_DIR"
mkdir -p "$CACHE_DIR"

(
  cd "$SCRIPT_DIR"
  GOCACHE="$CACHE_DIR" go build -o "$BIN_PATH" ./cli
)

echo "Built $BIN_PATH"
echo "Next steps:"
echo "  ./freud/bin/freud --help"
echo "  ./freud/bin/freud eval --live --input <file>"
echo "  ./freud/bin/freud run <feature-id>"
