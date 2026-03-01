#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/scripts/summarize-run.sh <run_dir>
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
artifact_dir="$run_dir/artifacts"
summary_json="$artifact_dir/summary.json"
anomalies_md="$artifact_dir/anomalies.md"
steps_file="$artifact_dir/step-index.tsv"
trail_file="$artifact_dir/trail.jsonl"
trail_index_file="$artifact_dir/trail-index.tsv"
run_config_file="$artifact_dir/run-config.json"
step_meta_dir="$artifact_dir/step-meta"
model_summary_json="$artifact_dir/model-summary.json"
model_summary_md="$artifact_dir/model-summary.md"
model_summary_attempts_tsv="$artifact_dir/model-summary-attempts.tsv"
model_summary_metrics_json="$artifact_dir/model-summary-metrics.json"
freud_metrics_json="$artifact_dir/freud-metrics.json"
summary_md="$artifact_dir/summary-compact.md"
summary_compact_json="$artifact_dir/summary-compact.json"

if [[ ! -d "$run_dir" ]]; then
  echo "Run directory does not exist: $run_dir"
  exit 1
fi

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/}"
  value="${value//$'\t'/\\t}"
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

feature_id="$(extract_json_string "$summary_json" "feature_id")"
run_id="$(extract_json_string "$summary_json" "run_id")"
status="$(extract_json_string "$summary_json" "status")"
mode="$(extract_json_string "$summary_json" "mode")"
steps_failed="$(extract_json_number "$summary_json" "steps_failed")"
failed_test_count="$(extract_json_number "$summary_json" "failed_test_count")"

first_failed_step=""
if [[ -f "$steps_file" ]]; then
  first_failed_step="$(awk -F '\t' 'NR>1 && $2=="fail"{print $1; exit}' "$steps_file")"
fi

trail_events=0
if [[ -f "$trail_file" ]]; then
  trail_events="$(wc -l <"$trail_file" | tr -d ' ')"
fi

top_warning_line=""
if [[ -f "$artifact_dir/top-signals.tsv" ]]; then
  set +e
  top_warning_line="$(rg -i -n -m 1 -e "warning" "$artifact_dir/top-signals.tsv" 2>/dev/null | head -n 1)"
  set -e
fi

{
  echo "# Freud Compact Summary"
  echo
  echo "- run_id: \`${run_id:-unknown}\`"
  echo "- feature_id: \`${feature_id:-unknown}\`"
  echo "- status: \`${status:-unknown}\`"
  echo "- mode: \`${mode:-unknown}\`"
  echo "- steps_failed: \`${steps_failed:-0}\`"
  echo "- failed_test_count: \`${failed_test_count:-0}\`"
  echo "- first_failed_step: \`${first_failed_step:-none}\`"
  echo "- trail_events: \`${trail_events}\`"
  echo
  echo "## Quick Files"
  echo "- \`$artifact_dir/summary.json\`"
  echo "- \`$artifact_dir/failures.json\`"
  echo "- \`$artifact_dir/anomalies.json\`"
  echo "- \`$run_config_file\`"
  echo "- \`$model_summary_json\`"
  echo "- \`$model_summary_md\`"
  echo "- \`$model_summary_attempts_tsv\`"
  echo "- \`$model_summary_metrics_json\`"
  echo "- \`$freud_metrics_json\`"
  echo "- \`$artifact_dir/step-index.tsv\`"
  echo "- \`$trail_index_file\`"
  echo "- \`$artifact_dir/trail.jsonl\`"
  echo "- \`$step_meta_dir/\`"
  echo
  if [[ -f "$steps_file" ]]; then
    echo "## Step Index Preview"
    echo '```text'
    sed -n '1,14p' "$steps_file"
    echo '```'
    echo
  fi
  if [[ -f "$trail_index_file" ]]; then
    echo "## Trail Index Preview"
    echo '```text'
    sed -n '1,14p' "$trail_index_file"
    echo '```'
    echo
  fi
  if [[ -f "$model_summary_md" ]]; then
    echo "## Tier-2 Model Summary Preview"
    sed -n '1,30p' "$model_summary_md"
    echo
  fi
  if [[ -f "$model_summary_attempts_tsv" ]]; then
    echo "## Tier-2 Attempts Preview"
    echo '```text'
    sed -n '1,20p' "$model_summary_attempts_tsv"
    echo '```'
    echo
  fi
  if [[ -f "$model_summary_metrics_json" ]]; then
    echo "## Tier-2 Metrics Preview"
    sed -n '1,40p' "$model_summary_metrics_json"
    echo
  fi
  if [[ -f "$freud_metrics_json" ]]; then
    echo "## Freud Metrics Preview"
    sed -n '1,40p' "$freud_metrics_json"
    echo
  fi
  if [[ -n "$top_warning_line" ]]; then
    echo "## First Warning Ref"
    echo "- \`$top_warning_line\`"
    echo
  fi
  if [[ -f "$anomalies_md" ]]; then
    echo "## Triage Preview"
    sed -n '1,40p' "$anomalies_md"
  fi
} >"$summary_md"

{
  echo "{"
  echo "  \"run_id\": \"$(json_escape "${run_id:-}")\","
  echo "  \"feature_id\": \"$(json_escape "${feature_id:-}")\","
  echo "  \"status\": \"$(json_escape "${status:-}")\","
  echo "  \"mode\": \"$(json_escape "${mode:-}")\","
  echo "  \"steps_failed\": ${steps_failed:-0},"
  echo "  \"failed_test_count\": ${failed_test_count:-0},"
  echo "  \"first_failed_step\": \"$(json_escape "${first_failed_step:-}")\","
  echo "  \"trail_events\": ${trail_events:-0}"
  echo "}"
} >"$summary_compact_json"

echo "$summary_md"
