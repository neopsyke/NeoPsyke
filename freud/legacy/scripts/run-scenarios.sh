#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/legacy/scripts/run-scenarios.sh [--file <scenario_manifest>] [--dry-run]

Scenario formats:
  JSON (preferred): {"scenarios":[{"id":"...","selector":"...","description":"..."}]}
  TSV (legacy): id<TAB>selector<TAB>description
EOF
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradle_user_home="${FREUD_GRADLE_USER_HOME:-}"

prime_gradle_build_cache() {
  [[ -z "$gradle_user_home" ]] && return 0
  mkdir -p "$gradle_user_home"

  # 1. Prime wrapper dists (fast copy if available locally)
  local local_dists="$gradle_user_home/wrapper/dists"
  local home_dists="$HOME/.gradle/wrapper/dists"
  if ! compgen -G "$local_dists/gradle-*-bin/*" >/dev/null 2>&1; then
    if [[ -d "$home_dists" ]]; then
      mkdir -p "$local_dists"
      cp -R "$home_dists"/gradle-*-bin "$local_dists"/ 2>/dev/null || true
    fi
  fi

  # 2. Prime build plugins + dependencies (Kotlin plugin, etc.)
  local marker="$gradle_user_home/.build-cache-primed"
  if [[ -f "$marker" ]]; then
    return 0
  fi
  echo "Priming isolated Gradle home with build plugins and dependencies..." >&2
  if GRADLE_USER_HOME="$gradle_user_home" "$repo_root/gradlew" \
      --no-daemon --no-problems-report \
      compileKotlin compileTestKotlin >/dev/null 2>&1; then
    touch "$marker"
    echo "Isolated Gradle home primed successfully." >&2
  else
    echo "WARNING: Failed to prime Gradle build cache. First build may be slow or fail offline." >&2
  fi
}

prime_gradle_build_cache

scenario_file="$repo_root/freud/scenarios/v1/neopsyke-agent-scenarios.json"
dry_run="false"
validated_manifest="$(mktemp)"
cleanup() {
  rm -f "$validated_manifest"
}
trap cleanup EXIT

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

selector_exists_in_tests() {
  local selector="$1"
  local class_part method_part class_short
  if [[ "$selector" == *"*"* ]]; then
    return 1
  fi
  class_part="${selector%.*}"
  method_part="${selector##*.}"
  class_short="${class_part##*.}"

  if [[ -z "$class_short" || -z "$method_part" || "$class_short" == "$method_part" ]]; then
    return 1
  fi

  local candidate_files file
  set +e
  candidate_files="$(rg -l --glob '*.kt' "fun[[:space:]]+${method_part}[[:space:]]*\\(" "$repo_root/src/test/kotlin" 2>/dev/null)"
  set -e
  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    if rg -q "(class|object)[[:space:]]+${class_short}\\b" "$file" 2>/dev/null; then
      return 0
    fi
  done < <(printf '%s\n' "$candidate_files")
  return 1
}

load_manifest_to_tsv() {
  if [[ "$scenario_file" == *.json ]]; then
    if ! command -v jq >/dev/null 2>&1; then
      echo "jq is required to read JSON scenario manifests."
      exit 1
    fi
    jq -r '
      (.scenarios // .)
      | if type == "array" then . else [] end
      | .[]
      | if type == "object" then [(.id // ""), (.selector // ""), (.description // "")] | @tsv else empty end
    ' "$scenario_file"
  else
    cat "$scenario_file"
  fi
}

validate_manifest_selectors() {
  local invalid_count=0
  while IFS=$'\t' read -r id selector description; do
    [[ -z "${id:-}" ]] && continue
    if [[ "$id" == \#* ]]; then
      continue
    fi
    if ! selector_exists_in_tests "$selector"; then
      echo "stale selector: scenario_id=$id selector=$selector does not match a test under src/test/kotlin"
      invalid_count=$((invalid_count + 1))
      continue
    fi
    printf '%s\t%s\t%s\n' "$id" "$selector" "$description" >>"$validated_manifest"
  done < <(load_manifest_to_tsv)

  if [[ "$invalid_count" -gt 0 ]]; then
    echo "scenario manifest validation failed: $invalid_count stale selector(s) found."
    exit 1
  fi
}

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

validate_manifest_selectors

while IFS=$'\t' read -r id selector description; do
  [[ -z "${id:-}" ]] && continue
  run_scenario "$id" "$selector" "$description"
done <"$validated_manifest"

echo "scenarios_total=$total scenarios_passed=$passed scenarios_failed=$failed"
if [[ "$failed" -gt 0 ]]; then
  exit 2
fi
