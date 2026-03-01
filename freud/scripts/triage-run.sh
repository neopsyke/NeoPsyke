#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/scripts/triage-run.sh <run_dir> [--top N]
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
shift

top_n=20
while [[ $# -gt 0 ]]; do
  case "$1" in
    --top)
      top_n="${2:-}"
      if [[ -z "$top_n" || ! "$top_n" =~ ^[0-9]+$ ]]; then
        echo "--top requires an integer."
        exit 1
      fi
      shift 2
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ ! -d "$run_dir" ]]; then
  echo "Run directory does not exist: $run_dir"
  exit 1
fi

logs_dir="$run_dir/logs"
artifact_dir="$run_dir/artifacts"
mkdir -p "$artifact_dir"

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

patterns_file="$artifact_dir/pattern-counts.tsv"
top_signals_file="$artifact_dir/top-signals.tsv"
pressure_file="$artifact_dir/pressure-signals.tsv"
first_failure_file="$artifact_dir/first-failing-trace.txt"

: >"$patterns_file"
: >"$top_signals_file"
: >"$pressure_file"
: >"$first_failure_file"

record_pattern() {
  local pattern_id="$1"
  local regex="$2"
  local count sample matches

  if [[ -d "$logs_dir" ]]; then
    set +e
    matches="$(rg -i -n -H -e "$regex" "$logs_dir" 2>/dev/null)"
    set -e
    count="$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d ' ')"
    sample="$(printf '%s\n' "$matches" | sed '/^$/d' | head -n 1)"
  else
    count="0"
    sample=""
  fi

  printf "%s\t%s\t%s\n" "$pattern_id" "$count" "$sample" >>"$patterns_file"
}

record_pattern "planner_output_repaired" "planner_output_repaired|output_repaired"
record_pattern "parse_failures" "non-parseable|failed to parse|parse fallback|parse error|parse failure"
record_pattern "forced_terminal" "forced terminal|forced_terminal_answer"
record_pattern "queue_saturation" "queue full|queue_saturation|step limit reached"
record_pattern "policy_denials" "action denied|denied by superego|policy denied"
record_pattern "provider_failures" "timeout|unavailable|provider check failed"

if [[ -d "$logs_dir" ]]; then
  set +e
  top_signals="$(rg -i -n -H -e "error|exception|failed|warning" "$logs_dir" 2>/dev/null)"
  pressure_signals="$(rg -n -H -e "decision_pressure=[0-9]+\\.[0-9]+" "$logs_dir" 2>/dev/null)"
  first_fail_line="$(rg -i -n -H -m 1 -e "error|exception|failed|traceback|assert" "$logs_dir" 2>/dev/null | head -n 1)"
  set -e
  printf '%s\n' "$top_signals" | sed '/^$/d' | head -n "$top_n" >"$top_signals_file"
  printf '%s\n' "$pressure_signals" | sed '/^$/d' >"$pressure_file"

  if [[ -n "${first_fail_line:-}" ]]; then
    file_ref="${first_fail_line%%:*}"
    line_rest="${first_fail_line#*:}"
    line_num="${line_rest%%:*}"
    if [[ -f "$file_ref" && "$line_num" =~ ^[0-9]+$ ]]; then
      start_line=$((line_num > 2 ? line_num - 2 : 1))
      end_line=$((line_num + 2))
      {
        echo "$first_fail_line"
        echo "---"
        sed -n "${start_line},${end_line}p" "$file_ref" | nl -ba -v "$start_line"
      } >"$first_failure_file"
    else
      printf '%s\n' "$first_fail_line" >"$first_failure_file"
    fi
  fi
fi

max_pressure_ref=""
max_pressure_val=""
first_pressure_075=""
first_pressure_090=""
if [[ -s "$pressure_file" ]]; then
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    value="$(echo "$line" | sed -nE 's/.*decision_pressure=([0-9]+\.[0-9]+).*/\1/p' | head -n 1)"
    [[ -z "$value" ]] && continue

    if [[ -z "$max_pressure_val" ]] || awk "BEGIN { exit !($value > $max_pressure_val) }"; then
      max_pressure_val="$value"
      max_pressure_ref="$line"
    fi
    if [[ -z "$first_pressure_075" ]] && awk "BEGIN { exit !($value >= 0.75) }"; then
      first_pressure_075="$line"
    fi
    if [[ -z "$first_pressure_090" ]] && awk "BEGIN { exit !($value >= 0.90) }"; then
      first_pressure_090="$line"
    fi
  done <"$pressure_file"
fi

anomalies_json="$artifact_dir/anomalies.json"
{
  echo "{"
  echo "  \"generated_at\": \"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\","
  echo "  \"run_dir\": \"$(json_escape "$run_dir")\","
  echo "  \"pattern_counts\": ["
  first="true"
  while IFS=$'\t' read -r pattern_id count sample; do
    if [[ "$first" == "true" ]]; then
      first="false"
    else
      echo ","
    fi
    printf '    {"id":"%s","count":%s,"sample":"%s"}' \
      "$(json_escape "$pattern_id")" \
      "$count" \
      "$(json_escape "$sample")"
  done <"$patterns_file"
  echo
  echo "  ],"
  echo "  \"pressure\": {"
  echo "    \"max\": {\"value\": \"$(json_escape "${max_pressure_val:-}")\", \"ref\": \"$(json_escape "${max_pressure_ref:-}")\"},"
  echo "    \"first_ge_075\": \"$(json_escape "${first_pressure_075:-}")\","
  echo "    \"first_ge_090\": \"$(json_escape "${first_pressure_090:-}")\""
  echo "  },"
  echo "  \"first_failing_trace\": \"$(json_escape "$(cat "$first_failure_file")")\","
  echo "  \"top_signals\": ["
  first="true"
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if [[ "$first" == "true" ]]; then
      first="false"
    else
      echo ","
    fi
    printf '    "%s"' "$(json_escape "$line")"
  done <"$top_signals_file"
  echo
  echo "  ]"
  echo "}"
} >"$anomalies_json"

anomalies_md="$artifact_dir/anomalies.md"
{
  echo "# Anomaly Triage"
  echo
  echo "- Run dir: \`$run_dir\`"
  echo "- Generated at: \`$(date -u +"%Y-%m-%dT%H:%M:%SZ")\`"
  echo
  echo "## Pattern Counts"
  while IFS=$'\t' read -r pattern_id count sample; do
    echo "- \`$pattern_id\`: $count"
    if [[ -n "$sample" ]]; then
      echo "  sample: \`$sample\`"
    fi
  done <"$patterns_file"
  echo
  echo "## Pressure Spikes"
  echo "- max decision pressure: \`${max_pressure_val:-none}\`"
  if [[ -n "$max_pressure_ref" ]]; then
    echo "  ref: \`$max_pressure_ref\`"
  fi
  if [[ -n "$first_pressure_075" ]]; then
    echo "- first >= 0.75: \`$first_pressure_075\`"
  else
    echo "- first >= 0.75: none"
  fi
  if [[ -n "$first_pressure_090" ]]; then
    echo "- first >= 0.90: \`$first_pressure_090\`"
  else
    echo "- first >= 0.90: none"
  fi
  echo
  echo "## First Failing Trace Snippet"
  if [[ -s "$first_failure_file" ]]; then
    echo '```text'
    cat "$first_failure_file"
    echo '```'
  else
    echo "- none"
  fi
  echo
  echo "## Top Signals"
  if [[ -s "$top_signals_file" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      echo "- \`$line\`"
    done <"$top_signals_file"
  else
    echo "- none"
  fi
} >"$anomalies_md"

echo "$anomalies_json"
