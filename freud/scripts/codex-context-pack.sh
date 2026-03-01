#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/scripts/codex-context-pack.sh <run_dir>
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

run_dir="$1"
if [[ ! -d "$run_dir" ]]; then
  echo "Run directory does not exist: $run_dir"
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
artifact_dir="$run_dir/artifacts"
summary_json="$artifact_dir/summary.json"
steps_file="$artifact_dir/steps.tsv"
step_index_file="$artifact_dir/step-index.tsv"
anomalies_md="$artifact_dir/anomalies.md"
trail_file="$artifact_dir/trail.jsonl"
trail_index_file="$artifact_dir/trail-index.tsv"
run_config_file="$artifact_dir/run-config.json"
step_meta_dir="$artifact_dir/step-meta"
model_summary_json="$artifact_dir/model-summary.json"
model_summary_md="$artifact_dir/model-summary.md"
model_summary_attempts_tsv="$artifact_dir/model-summary-attempts.tsv"
model_summary_metrics_json="$artifact_dir/model-summary-metrics.json"
freud_metrics_json="$artifact_dir/freud-metrics.json"
compact_summary_md="$artifact_dir/summary-compact.md"
run_index_md="$artifact_dir/run-index.md"
context_md="$artifact_dir/codex-context.md"

extract_json_string() {
  local file="$1"
  local key="$2"
  if [[ ! -f "$file" ]]; then
    return 0
  fi
  sed -nE "s/^[[:space:]]*\"$key\"[[:space:]]*:[[:space:]]*\"(.*)\"[[:space:]]*,?[[:space:]]*$/\\1/p" "$file" | head -n 1
}

extract_json_number() {
  local file="$1"
  local key="$2"
  if [[ ! -f "$file" ]]; then
    return 0
  fi
  sed -nE "s/^[[:space:]]*\"$key\"[[:space:]]*:[[:space:]]*([0-9]+)[[:space:]]*,?[[:space:]]*$/\\1/p" "$file" | head -n 1
}

feature_id="$(extract_json_string "$summary_json" "feature_id")"
run_id="$(extract_json_string "$summary_json" "run_id")"
status="$(extract_json_string "$summary_json" "status")"
mode="$(extract_json_string "$summary_json" "mode")"
steps_failed="$(extract_json_number "$summary_json" "steps_failed")"
steps_total="$(extract_json_number "$summary_json" "steps_total")"

{
  echo "# Codex Context Pack"
  echo
  echo "Use this file as the default brief for follow-up implementation/fixes."
  echo
  echo "## Run Snapshot"
  echo "- feature_id: \`${feature_id:-unknown}\`"
  echo "- run_id: \`${run_id:-unknown}\`"
  echo "- status: \`${status:-unknown}\`"
  echo "- mode: \`${mode:-unknown}\`"
  echo "- steps_failed: \`${steps_failed:-0}\` / \`${steps_total:-0}\`"
  echo "- run_dir: \`$run_dir\`"
  echo

  echo "## Start Here (Low Token)"
  echo "- \`$compact_summary_md\`"
  echo "- \`$run_index_md\`"
  echo "- \`$summary_json\`"
  echo "- \`$artifact_dir/anomalies.json\`"
  echo "- \`$run_config_file\`"
  echo "- \`$model_summary_json\`"
  echo "- \`$model_summary_md\`"
  echo "- \`$model_summary_attempts_tsv\`"
  echo "- \`$model_summary_metrics_json\`"
  echo "- \`$freud_metrics_json\`"
  echo "- \`$step_index_file\`"
  echo "- \`$trail_index_file\`"
  echo "- \`$trail_file\`"
  echo "- \`$step_meta_dir/\`"
  echo

  echo "## Failed Steps"
  if [[ -f "$steps_file" ]]; then
    failed_count=0
    while IFS=$'\t' read -r step_name step_status step_duration step_log; do
      if [[ "$step_status" == "fail" ]]; then
        failed_count=$((failed_count + 1))
        echo "- \`$step_name\` (\`${step_duration}s\`) log: \`$step_log\`"
      fi
    done <"$steps_file"
    if [[ $failed_count -eq 0 ]]; then
      echo "- none"
    fi
  else
    echo "- steps.tsv missing"
  fi
  echo

  echo "## Trail Preview"
  if [[ -f "$trail_file" ]]; then
    echo '```jsonl'
    sed -n '1,12p' "$trail_file"
    echo '```'
  else
    echo "- trail.jsonl missing"
  fi
  echo

  echo "## Trail Index Preview"
  if [[ -f "$trail_index_file" ]]; then
    echo '```text'
    sed -n '1,20p' "$trail_index_file"
    echo '```'
  else
    echo "- trail-index.tsv missing"
  fi
  echo

  echo "## Working Tree Diff"
  diff_files="$(git -C "$repo_root" diff --name-only)"
  if [[ -n "$diff_files" ]]; then
    echo '```text'
    echo "$diff_files" | head -n 120
    echo '```'
  else
    echo "- clean working tree"
  fi
  echo

  echo "## Triage Snapshot"
  if [[ -f "$anomalies_md" ]]; then
    sed -n '1,120p' "$anomalies_md"
  else
    echo "- anomalies.md missing"
  fi
  echo

  echo "## Minimal Prompt Template"
  echo '```text'
  echo "Goal:"
  echo "Acceptance checks:"
  echo "Touch only these files:"
  echo "Use these artifacts first:"
  echo "  - $compact_summary_md"
  echo "  - $run_index_md"
  echo "  - $summary_json"
  echo "  - $artifact_dir/failures.json"
  echo "  - $artifact_dir/anomalies.json"
  echo "  - $run_config_file"
  echo "  - $model_summary_json"
  echo "  - $model_summary_md"
  echo "  - $model_summary_attempts_tsv"
  echo "  - $model_summary_metrics_json"
  echo "  - $freud_metrics_json"
  echo "  - $step_index_file"
  echo "  - $trail_index_file"
  echo "  - $trail_file"
  echo "  - $step_meta_dir/<step>.json"
  echo "Avoid reading full logs unless required."
  echo '```'
} >"$context_md"

echo "$context_md"
