#!/usr/bin/env bash
# Utility to extract and source individual functions from monolithic Freud scripts.
# Prevents execution of the script body while making functions available for testing.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

# Extract function bodies from a script file and eval them in the current shell.
# Usage: source_functions_from <script_path> <func_name> [func_name ...]
source_functions_from() {
  local script="$1"
  shift
  local func_names=("$@")

  for fn in "${func_names[@]}"; do
    local body
    body="$(awk -v fn="$fn" '
      $0 ~ "^" fn "\\(\\)" { found=1; brace=0 }
      found {
        for (i=1; i<=length($0); i++) {
          c = substr($0, i, 1)
          if (c == "{") brace++
          if (c == "}") brace--
        }
        buf = buf $0 "\n"
        if (brace == 0 && found) { print buf; exit }
      }
    ' "$script")"
    if [[ -n "$body" ]]; then
      eval "$body"
    fi
  done
}

# Source json_escape and tsv_escape from helpers.sh
source_feature_loop_helpers() {
  source_functions_from "$REPO_ROOT/freud/scripts/helpers.sh" \
    json_escape tsv_escape
}

# Source extract_json_string and extract_json_number from helpers.sh
source_extract_helpers() {
  source_functions_from "$REPO_ROOT/freud/scripts/helpers.sh" \
    extract_json_string extract_json_number
}

# Source step_is_active and the all_steps_ordered array from feature-loop.sh
source_step_filtering() {
  all_steps_ordered=(
    preflight_compile
    targeted_tests
    full_tests
    scenario_pack
    reasoning_eval_logic
    reasoning_eval_model
    memory_live_smoke
  )
  source_functions_from "$REPO_ROOT/freud/scripts/feature-loop.sh" step_is_active
}
