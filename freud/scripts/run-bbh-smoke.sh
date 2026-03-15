#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

usage() {
  cat <<'EOF'
Usage: freud/scripts/run-bbh-smoke.sh [--lane <name>]

Runs the frozen BBH-style smoke manifest through freud/scripts/live-eval.sh and
scores exact normalized answer matches.
EOF
}

lane="manual"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --lane)
      lane="${2:-}"
      if [[ -z "$lane" ]]; then
        echo "--lane requires a value."
        exit 1
      fi
      shift 2
      ;;
    --lane=*)
      lane="${1#*=}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to run the BBH smoke lane."
  exit 1
fi

LIVE_EVAL_CMD="${FREUD_BBH_LIVE_EVAL_CMD:-$REPO_ROOT/freud/scripts/live-eval.sh}"
PROMPTS_FILE="${FREUD_BBH_PROMPTS_FILE:-$REPO_ROOT/freud/evals/bbh-smoke/prompts.jsonl}"
ANSWERS_FILE="${FREUD_BBH_ANSWERS_FILE:-$REPO_ROOT/freud/evals/bbh-smoke/answers.jsonl}"
MIN_PASS_RATE_PERCENT="${FREUD_BBH_MIN_PASS_RATE_PERCENT:-100}"
MAX_TIMEOUTS="${FREUD_BBH_MAX_TIMEOUTS:-0}"
BASELINE_FILE="${FREUD_BBH_BASELINE_FILE:-}"
MAX_REGRESSION_PERCENT="${FREUD_BBH_MAX_REGRESSION_PERCENT:-0}"
PRESERVE_MEMORY="${FREUD_BBH_PRESERVE_MEMORY:-${FREUD_LIVE_EVAL_PRESERVE_MEMORY:-false}}"

if [[ ! -f "$PROMPTS_FILE" ]]; then
  echo "Prompt manifest not found: $PROMPTS_FILE"
  exit 1
fi
if [[ ! -f "$ANSWERS_FILE" ]]; then
  echo "Answer manifest not found: $ANSWERS_FILE"
  exit 1
fi

if [[ -n "${FREUD_RUN_DIR:-}" ]]; then
  RUN_DIR="$FREUD_RUN_DIR"
  ARTIFACT_DIR="${FREUD_ARTIFACT_DIR:-$RUN_DIR/artifacts}"
else
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  RUN_DIR="$REPO_ROOT/.psyke/runs/freud/${timestamp}-bbh-smoke-${lane}"
  ARTIFACT_DIR="$RUN_DIR/artifacts"
fi

CASES_ROOT="$RUN_DIR/bbh-cases/$lane"
RESULTS_TSV="$ARTIFACT_DIR/bbh-smoke-${lane}-results.tsv"
SUMMARY_JSON="$ARTIFACT_DIR/bbh-smoke-${lane}-summary.json"
SUMMARY_MD="$ARTIFACT_DIR/bbh-smoke-${lane}-summary.md"
PROGRESS_JSON="$ARTIFACT_DIR/bbh-smoke-${lane}-progress.json"
PROGRESS_MD="$ARTIFACT_DIR/bbh-smoke-${lane}-progress.md"
ANSWERS_TSV="$ARTIFACT_DIR/bbh-smoke-${lane}-answers.tsv"

mkdir -p "$ARTIFACT_DIR" "$CASES_ROOT"

jq -r '[.id, .answer] | @tsv' "$ANSWERS_FILE" >"$ANSWERS_TSV"
TOTAL_CASES="$(wc -l <"$PROMPTS_FILE" | tr -d ' ')"

normalize_answer() {
  local raw="$1"
  local normalized
  normalized="$(printf '%s' "$raw" \
    | sed -E 's/^ego> //g' \
    | tr '[:upper:]' '[:lower:]' \
    | tr '\n' ' ' \
    | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"
  if printf '%s\n' "$normalized" | grep -Eq '^"[-[:alnum:]_]+([[:space:]][-[:alnum:]_]+)*"$'; then
    normalized="${normalized#\"}"
    normalized="${normalized%\"}"
  fi
  printf '%s' "$normalized"
}

search_logs_with_fallback() {
  local regex="$1"
  local path="$2"
  if command -v rg >/dev/null 2>&1; then
    rg -n "$regex" "$path" 2>/dev/null || true
  else
    grep -ERn -- "$regex" "$path" 2>/dev/null || true
  fi
}

schema_downgrade_count_for_case() {
  local case_dir="$1"
  local matches
  set +e
  matches="$(search_logs_with_fallback \
    'Structured-output schema adapted.*schema=(ego_planner_decision|meta_reasoner_assessment).*(strict downgraded to false|JSON schema output disabled)' \
    "$case_dir/logs")"
  set -e
  printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d ' '
}

expected_answer_for_id() {
  local id="$1"
  awk -F '\t' -v key="$id" '$1 == key { print $2; exit }' "$ANSWERS_TSV"
}

write_summary() {
  local total="$1"
  local passed="$2"
  local failed="$3"
  local timeout_count="$4"
  local schema_downgrade_count="$5"
  local exact_match_rate="$6"
  local regression_fail="$7"

  cat >"$SUMMARY_JSON" <<EOF
{
  "lane": "$lane",
  "total_cases": $total,
  "passed_cases": $passed,
  "failed_cases": $failed,
  "timeout_count": $timeout_count,
  "schema_downgrade_count": $schema_downgrade_count,
  "exact_match_rate_percent": $exact_match_rate,
  "min_pass_rate_percent": $MIN_PASS_RATE_PERCENT,
  "max_timeouts": $MAX_TIMEOUTS,
  "baseline_file": "$BASELINE_FILE",
  "max_regression_percent": $MAX_REGRESSION_PERCENT,
  "regression_fail": $regression_fail,
  "results_tsv": "$RESULTS_TSV",
  "cases_root": "$CASES_ROOT"
}
EOF

  {
    echo "# BBH Smoke Summary"
    echo
    echo "- lane: \`$lane\`"
    echo "- total_cases: \`$total\`"
    echo "- passed_cases: \`$passed\`"
    echo "- failed_cases: \`$failed\`"
    echo "- timeout_count: \`$timeout_count\`"
    echo "- schema_downgrade_count: \`$schema_downgrade_count\`"
    echo "- exact_match_rate_percent: \`$exact_match_rate\`"
    echo
    echo "## Results"
    echo '```text'
    sed -n '1,80p' "$RESULTS_TSV"
    echo '```'
  } >"$SUMMARY_MD"
}

write_progress() {
  local phase="$1"
  local current_id="$2"
  local current_category="$3"
  local completed="$4"
  local passed_count="$5"
  local failed_count="$6"
  local timeout_total="$7"
  local schema_total="$8"

  cat >"$PROGRESS_JSON" <<EOF
{
  "lane": "$lane",
  "phase": "$phase",
  "total_cases": $TOTAL_CASES,
  "completed_cases": $completed,
  "remaining_cases": $((TOTAL_CASES - completed)),
  "passed_cases": $passed_count,
  "failed_cases": $failed_count,
  "timeout_count": $timeout_total,
  "schema_downgrade_count": $schema_total,
  "current_case_id": "$current_id",
  "current_category": "$current_category",
  "results_tsv": "$RESULTS_TSV",
  "summary_json": "$SUMMARY_JSON",
  "cases_root": "$CASES_ROOT"
}
EOF

  {
    echo "# BBH Smoke Progress"
    echo
    echo "- lane: \`$lane\`"
    echo "- phase: \`$phase\`"
    echo "- completed_cases: \`$completed / $TOTAL_CASES\`"
    echo "- remaining_cases: \`$((TOTAL_CASES - completed))\`"
    echo "- passed_cases: \`$passed_count\`"
    echo "- failed_cases: \`$failed_count\`"
    echo "- timeout_count: \`$timeout_total\`"
    echo "- schema_downgrade_count: \`$schema_total\`"
    echo "- current_case_id: \`${current_id:-none}\`"
    echo "- current_category: \`${current_category:-none}\`"
    echo
    echo "## Recent Results"
    echo '```text'
    sed -n '1,20p' "$RESULTS_TSV"
    echo '```'
  } >"$PROGRESS_MD"
}

printf "id\tcategory\tstatus\texpected\tactual\ttimeout\tschema_downgrade\trun_dir\n" >"$RESULTS_TSV"

total=0
passed=0
failed=0
timeout_count=0
schema_downgrade_count=0
write_progress "starting" "" "" 0 "$passed" "$failed" "$timeout_count" "$schema_downgrade_count"

while IFS=$'\t' read -r id category prompt; do
  [[ -z "${id:-}" ]] && continue
  total=$((total + 1))
  write_progress "running" "$id" "$category" $((total - 1)) "$passed" "$failed" "$timeout_count" "$schema_downgrade_count"
  case_dir="$CASES_ROOT/$id"
  mkdir -p "$case_dir"
  input_file="$case_dir/input.txt"
  printf '%s\n' "$prompt" >"$input_file"

  set +e
  if [[ "$PRESERVE_MEMORY" == "true" ]]; then
    FREUD_LIVE_EVAL_RUN_DIR="$case_dir" "$LIVE_EVAL_CMD" --input "$input_file" --timeout "${FREUD_LIVE_EVAL_TIMEOUT:-120}" --preserve-memory >/dev/null
  else
    FREUD_LIVE_EVAL_RUN_DIR="$case_dir" "$LIVE_EVAL_CMD" --input "$input_file" --timeout "${FREUD_LIVE_EVAL_TIMEOUT:-120}" >/dev/null
  fi
  live_status=$?
  set -e

  verdict_file="$case_dir/artifacts/verdict.json"
  answer_file="$case_dir/artifacts/answer.txt"
  expected="$(expected_answer_for_id "$id")"
  if [[ -z "$expected" ]]; then
    echo "Missing expected answer for BBH case id: $id"
    exit 1
  fi
  actual_raw=""
  if [[ -f "$answer_file" ]]; then
    actual_raw="$(cat "$answer_file")"
  fi
  actual="$(normalize_answer "$actual_raw")"
  expected_normalized="$(normalize_answer "$expected")"

  schema_count_for_case="$(schema_downgrade_count_for_case "$case_dir")"
  schema_downgrade_count=$((schema_downgrade_count + schema_count_for_case))

  timeout_flag="false"
  schema_flag="false"
  case_status="fail"

  if [[ "$live_status" -eq 2 ]]; then
    timeout_count=$((timeout_count + 1))
    timeout_flag="true"
    case_status="timeout"
    failed=$((failed + 1))
  elif [[ "$schema_count_for_case" -gt 0 ]]; then
    schema_flag="true"
    case_status="schema_downgrade"
    failed=$((failed + 1))
  elif [[ "$live_status" -eq 0 && "$actual" == "$expected_normalized" ]]; then
    case_status="pass"
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$id" \
    "$category" \
    "$case_status" \
    "$(printf '%s' "$expected_normalized" | tr '\t' ' ')" \
    "$(printf '%s' "$actual" | tr '\t' ' ')" \
    "$timeout_flag" \
    "$schema_flag" \
    "$case_dir" >>"$RESULTS_TSV"
  write_progress "running" "$id" "$category" "$total" "$passed" "$failed" "$timeout_count" "$schema_downgrade_count"
done < <(jq -r '[.id, .category, .prompt] | @tsv' "$PROMPTS_FILE")

exact_match_rate="$(awk -v p="$passed" -v t="$total" 'BEGIN { if (t == 0) print "0.00"; else printf "%.2f", (p * 100.0) / t }')"
regression_fail="false"
if [[ -n "$BASELINE_FILE" && -f "$BASELINE_FILE" ]]; then
  baseline_rate="$(jq -r '.exact_match_rate_percent // empty' "$BASELINE_FILE" 2>/dev/null || true)"
  if [[ -n "$baseline_rate" ]]; then
    regression_fail="$(awk -v current="$exact_match_rate" -v baseline="$baseline_rate" -v max_drop="$MAX_REGRESSION_PERCENT" \
      'BEGIN { if ((baseline - current) > max_drop) print "true"; else print "false" }')"
  fi
fi

write_summary "$total" "$passed" "$failed" "$timeout_count" "$schema_downgrade_count" "$exact_match_rate" "$regression_fail"
write_progress "completed" "" "" "$total" "$passed" "$failed" "$timeout_count" "$schema_downgrade_count"

if awk -v current="$exact_match_rate" -v min="$MIN_PASS_RATE_PERCENT" 'BEGIN { exit !(current < min) }'; then
  echo "BBH smoke exact-match rate $exact_match_rate is below minimum $MIN_PASS_RATE_PERCENT."
  exit 2
fi

if [[ "$timeout_count" -gt "$MAX_TIMEOUTS" ]]; then
  echo "BBH smoke timeout count $timeout_count exceeds max $MAX_TIMEOUTS."
  exit 2
fi

if [[ "$schema_downgrade_count" -gt 0 ]]; then
  echo "BBH smoke detected $schema_downgrade_count strict-schema downgrade events."
  exit 2
fi

if [[ "$regression_fail" == "true" ]]; then
  echo "BBH smoke exact-match rate regressed more than allowed threshold."
  exit 2
fi

echo "bbh_smoke lane=$lane total=$total passed=$passed failed=$failed exact_match_rate_percent=$exact_match_rate"
