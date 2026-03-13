#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT_DIR"

./gradlew -p poc/freudian-psyke run --args='--mode ablation --config config/poc.yaml --ablation-output build/ablation-summary.json'

printf '\nAblation report: %s\n' "$ROOT_DIR/poc/freudian-psyke/build/ablation-summary.json"
