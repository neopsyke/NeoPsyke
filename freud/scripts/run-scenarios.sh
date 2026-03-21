#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/scripts/run-scenarios.sh [--file <scenario_manifest>] [--dry-run]

Scenario formats:
  JSON (preferred): {"scenarios":[{"id":"...","selector":"...","description":"..."}]}
  TSV (legacy): id<TAB>selector<TAB>description
EOF
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradle_user_home="${FREUD_GRADLE_USER_HOME:-}"

prime_gradle_wrapper_cache() {
  [[ -z "$gradle_user_home" ]] && return 0
  local local_dists="$gradle_user_home/wrapper/dists"
  local home_dists="$HOME/.gradle/wrapper/dists"
  if compgen -G "$local_dists/gradle-*-bin/*" >/dev/null; then
    return 0
  fi
  if [[ -d "$home_dists" ]]; then
    mkdir -p "$local_dists"
    cp -R "$home_dists"/gradle-*-bin "$local_dists"/ 2>/dev/null || true
  fi
}

prime_gradle_wrapper_cache

scenario_file="$repo_root/freud/scenarios/v1/neopsyke-agent-scenarios.json"
dry_run="false"

# Keep full workspace debug dumps enabled in direct scenario runs.
export EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED="true"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --file)
      scenario_file="${2:-}"
      if [[ -z "$scenario_file" ]]; then
        echo "--file requires a value."
        exit 1
      fi
      shift 2
      ;;
    --dry-run)
      dry_run="true"
      shift
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ ! -f "$scenario_file" ]]; then
  echo "Scenario file does not exist: $scenario_file"
  exit 1
fi

total=0
passed=0
failed=0

run_scenario() {
  local id="$1"
  local selector="$2"
  local description="$3"

  [[ -z "${id:-}" || -z "${selector:-}" ]] && return 0

  total=$((total + 1))
  echo "scenario_id=$id selector=$selector"
  if [[ "$dry_run" == "true" ]]; then
    echo "status=dry_run description=$description"
    passed=$((passed + 1))
    return 0
  fi

  local exit_code
  set +e
  if [[ -n "$gradle_user_home" ]]; then
    (
      cd "$repo_root"
      GRADLE_USER_HOME="$gradle_user_home" ./gradlew :test --tests "$selector" --console=plain
    )
  else
    (
      cd "$repo_root"
      ./gradlew :test --tests "$selector" --console=plain
    )
  fi
  exit_code=$?
  set -e

  if [[ $exit_code -eq 0 ]]; then
    echo "status=pass description=$description"
    passed=$((passed + 1))
  else
    echo "status=fail description=$description"
    failed=$((failed + 1))
  fi
}

if [[ "$scenario_file" == *.json ]]; then
  if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to read JSON scenario manifests."
    exit 1
  fi
  while IFS=$'\t' read -r id selector description; do
    run_scenario "$id" "$selector" "$description"
  done < <(
    jq -r '
      (.scenarios // .)
      | if type == "array" then . else [] end
      | .[]
      | if type == "object" then [(.id // ""), (.selector // ""), (.description // "")] | @tsv else empty end
    ' "$scenario_file"
  )
else
  while IFS=$'\t' read -r id selector description; do
    [[ -z "${id:-}" ]] && continue
    if [[ "$id" == \#* ]]; then
      continue
    fi
    run_scenario "$id" "$selector" "$description"
  done <"$scenario_file"
fi

echo "scenarios_total=$total scenarios_passed=$passed scenarios_failed=$failed"
if [[ "$failed" -gt 0 ]]; then
  exit 2
fi
