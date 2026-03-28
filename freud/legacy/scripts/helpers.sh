#!/usr/bin/env bash
# Shared Bash helper functions used by feature-loop.sh and tested via BATS.
# The data-processing scripts (triage, summarize, context-pack, telemetry) have
# been migrated to Python under freud/legacy/py/. These helpers remain because
# feature-loop.sh still uses them directly.

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

tsv_escape() {
  local value="$1"
  value="${value//$'\n'/ }"
  value="${value//$'\r'/ }"
  value="${value//$'\t'/ }"
  printf '%s' "$value"
}

extract_json_string() {
  local file="$1"
  local key="$2"
  [[ ! -f "$file" ]] && return 0
  sed -nE "s/^[[:space:]]*\"$key\"[[:space:]]*:[[:space:]]*\"(.*)\"[[:space:]]*,?[[:space:]]*$/\\1/p" "$file" | head -n 1
}

extract_json_number() {
  local file="$1"
  local key="$2"
  [[ ! -f "$file" ]] && return 0
  sed -nE "s/^[[:space:]]*\"$key\"[[:space:]]*:[[:space:]]*([0-9]+)[[:space:]]*,?[[:space:]]*$/\\1/p" "$file" | head -n 1
}

pct() {
  local num="$1"
  local den="$2"
  if [[ "$den" -eq 0 ]]; then
    printf '0.00'
    return
  fi
  awk -v n="$num" -v d="$den" 'BEGIN { printf "%.2f", (n * 100.0) / d }'
}
