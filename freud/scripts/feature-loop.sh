#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/scripts/feature-loop.sh <feature_id> [--live] [--dry-run] [--continue-on-fail]
                                [--config <path>] [--from-step <step>]

Step names for --from-step:
  preflight_compile  targeted_tests  full_tests  scenario_pack
  reasoning_eval_logic  reasoning_eval_model  memory_live_smoke

Description:
  Runs a deterministic-first feature workflow and writes compact artifacts under:
  .psyke/runs/freud/<timestamp>-<feature_id>/
  Use --from-step to resume from a specific step, skipping earlier ones.
EOF
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

feature_id_raw="$1"
shift

feature_id="$(echo "$feature_id_raw" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
if [[ -z "$feature_id" ]]; then
  echo "feature_id is empty after normalization."
  exit 1
fi

mode="stub"
dry_run="false"
continue_on_fail=""
config_path=""
from_step=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --live)
      mode="live"
      shift
      ;;
    --dry-run)
      dry_run="true"
      shift
      ;;
    --continue-on-fail)
      continue_on_fail="true"
      shift
      ;;
    --config)
      config_path="${2:-}"
      if [[ -z "$config_path" ]]; then
        echo "--config requires a value."
        exit 1
      fi
      shift 2
      ;;
    --from-step)
      from_step="${2:-}"
      if [[ -z "$from_step" ]]; then
        echo "--from-step requires a step name."
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

if [[ -z "$config_path" ]]; then
  if [[ -n "${FREUD_CONFIG:-}" ]]; then
    config_path="$FREUD_CONFIG"
  else
    config_path="$repo_root/freud/config/default.env"
  fi
fi

if [[ -f "$config_path" ]]; then
  # shellcheck disable=SC1090
  source "$config_path"
fi

# Keep full workspace debug dumps enabled in Freud workflow runs.
export EGO_TASK_WORKSPACE_DEBUG_CAPTURE_ENABLED="true"

# Ensure summarizer adapter receives config values from sourced env files.
for summarizer_env_var in \
  FREUD_SUMMARIZER_PROVIDER \
  FREUD_SUMMARIZER_MODEL \
  FREUD_SUMMARIZER_BASE_URL \
  FREUD_SUMMARIZER_MAX_OUTPUT_TOKENS \
  FREUD_SUMMARIZER_TEMPERATURE \
  FREUD_SUMMARIZER_TIMEOUT_SEC \
  FREUD_SUMMARIZER_HEALTHCHECK_TIMEOUT_SEC \
  FREUD_SUMMARIZER_MAX_SECTION_CHARS \
  FREUD_SUMMARIZER_ENABLE_FALLBACK \
  FREUD_SUMMARIZER_FALLBACK_ORDER \
  FREUD_SUMMARIZER_TOKEN_LIMIT \
  FREUD_RUNTIME_ROOT \
  PSYKE_METRICS_DB \
  PSYKE_RUNTIME_DEFAULTS_FILE; do
  if [[ "${!summarizer_env_var-__unset__}" != "__unset__" ]]; then
    export "$summarizer_env_var"
  fi
done

project_name="${FREUD_PROJECT_NAME:-unknown-project}"
preflight_compile_cmd="${FREUD_PREFLIGHT_COMPILE_CMD:-}"
targeted_cmd="${FREUD_TARGETED_TEST_CMD:-}"
full_cmd="${FREUD_FULL_TEST_CMD:-}"
scenario_pack_cmd="${FREUD_SCENARIO_PACK_CMD:-}"
reasoning_logic_cmd="${FREUD_REASONING_EVAL_LOGIC_CMD:-}"
reasoning_model_cmd="${FREUD_REASONING_EVAL_MODEL_CMD:-}"
memory_smoke_cmd="${FREUD_MEMORY_SMOKE_CMD:-}"
ai_triage_cmd="${FREUD_AI_TRIAGE_CMD:-freud/scripts/ai-triage-failure.sh --if-configured}"
summarizer_cmd="${FREUD_SUMMARIZER_CMD:-}"
run_root_cfg="${FREUD_RUN_ROOT:-.psyke/runs/freud}"
gradle_user_home_cfg="${FREUD_GRADLE_USER_HOME:-}"

if [[ "$run_root_cfg" = /* ]]; then
  run_root="$run_root_cfg"
else
  run_root="$repo_root/$run_root_cfg"
fi

gradle_user_home=""
if [[ -n "$gradle_user_home_cfg" ]]; then
  if [[ "$gradle_user_home_cfg" = /* ]]; then
    gradle_user_home="$gradle_user_home_cfg"
  else
    gradle_user_home="$repo_root/$gradle_user_home_cfg"
  fi
fi

if [[ -z "$continue_on_fail" ]]; then
  continue_on_fail="${FREUD_CONTINUE_ON_FAIL:-false}"
fi

# Ordered list of all step names for --from-step resolution.
all_steps_ordered=(
  preflight_compile
  targeted_tests
  full_tests
  scenario_pack
  reasoning_eval_logic
  reasoning_eval_model
  memory_live_smoke
)

# step_is_active <step_name>: returns 0 (active) or 1 (skip) based on --from-step.
step_is_active() {
  local step="$1"
  if [[ -z "$from_step" ]]; then
    return 0
  fi
  local seen_from="false"
  local s
  for s in "${all_steps_ordered[@]}"; do
    if [[ "$s" == "$from_step" ]]; then
      seen_from="true"
    fi
    if [[ "$s" == "$step" ]]; then
      if [[ "$seen_from" == "true" || "$s" == "$from_step" ]]; then
        return 0
      else
        return 1
      fi
    fi
  done
  # Unknown step name: treat as active (don't silently skip unknown steps).
  return 0
}

if [[ -n "$from_step" ]]; then
  valid_from="false"
  for s in "${all_steps_ordered[@]}"; do
    if [[ "$s" == "$from_step" ]]; then valid_from="true"; break; fi
  done
  if [[ "$valid_from" != "true" ]]; then
    echo "Unknown step name for --from-step: '$from_step'"
    echo "Valid step names: ${all_steps_ordered[*]}"
    exit 1
  fi
  echo "Resuming from step: $from_step (earlier steps will be skipped)"
fi

run_id="$(date -u +"%Y%m%dT%H%M%SZ")"
started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
run_dir="$run_root/${run_id}-${feature_id}"
log_dir="$run_dir/logs"
artifact_dir="$run_dir/artifacts"
log_index_dir="$artifact_dir/log-index"
step_meta_dir="$artifact_dir/step-meta"
steps_file="$artifact_dir/steps.tsv"
step_index_file="$artifact_dir/step-index.tsv"
failed_tests_file="$artifact_dir/failed-tests.tsv"
eval_metrics_file="$artifact_dir/eval-metrics.tsv"
trail_file="$artifact_dir/trail.jsonl"
trail_index_file="$artifact_dir/trail-index.tsv"
run_config_json="$artifact_dir/run-config.json"
model_summary_json="$artifact_dir/model-summary.json"
model_summary_md="$artifact_dir/model-summary.md"
model_summary_attempts_tsv="$artifact_dir/model-summary-attempts.tsv"
model_summary_metrics_json="$artifact_dir/model-summary-metrics.json"
freud_metrics_json="$artifact_dir/freud-metrics.json"
run_index_json="$artifact_dir/run-index.json"
run_index_md="$artifact_dir/run-index.md"

mkdir -p "$log_dir" "$artifact_dir" "$log_index_dir" "$step_meta_dir"
if [[ -n "$gradle_user_home" ]]; then
  mkdir -p "$gradle_user_home"
fi

write_local_freud_pointers() {
  local local_root="$repo_root/freud"
  mkdir -p "$local_root/logs"
  ln -sfn "$run_dir" "$local_root/latest"
  ln -sfn "$run_dir/logs" "$local_root/logs/latest"
  ln -sfn "$run_dir/artifacts" "$local_root/artifacts-latest"
  printf '%s\n' "$run_dir" >"$local_root/latest-run.txt"
}

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

: >"$steps_file"
: >"$step_index_file"
: >"$failed_tests_file"
: >"$eval_metrics_file"
: >"$trail_file"
: >"$trail_index_file"

printf "step\tstatus\tduration_sec\tlog\n" >"$steps_file"
printf "step\tstatus\tduration_sec\tlog\tlog_index\tlog_lines\twarnings\terrors\tfirst_warning\tfirst_error\tfirst_pressure\n" >"$step_index_file"
printf "seq\tts\tevent\tstep\tstatus\tlog\tref\tmessage\n" >"$trail_index_file"

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

trail_seq=0

emit_trail() {
  local event="$1"
  local step="${2:-}"
  local status="${3:-}"
  local message="${4:-}"
  local cmd="${5:-}"
  local log="${6:-}"
  local ref="${7:-}"
  local ts
  ts="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  trail_seq=$((trail_seq + 1))
  printf '{"seq":%s,"ts":"%s","event":"%s","step":"%s","status":"%s","message":"%s","cmd":"%s","log":"%s","ref":"%s"}\n' \
    "$trail_seq" \
    "$ts" \
    "$(json_escape "$event")" \
    "$(json_escape "$step")" \
    "$(json_escape "$status")" \
    "$(json_escape "$message")" \
    "$(json_escape "$cmd")" \
    "$(json_escape "$log")" \
    "$(json_escape "$ref")" >>"$trail_file"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$trail_seq" \
    "$ts" \
    "$(tsv_escape "$event")" \
    "$(tsv_escape "$step")" \
    "$(tsv_escape "$status")" \
    "$(tsv_escape "$log")" \
    "$(tsv_escape "$ref")" \
    "$(tsv_escape "$message")" >>"$trail_index_file"
}

write_run_config() {
  {
    echo "{"
    echo "  \"workflow\": \"freud\","
    echo "  \"project\": \"$(json_escape "$project_name")\","
    echo "  \"run_id\": \"$(json_escape "$run_id")\","
    echo "  \"feature_id\": \"$(json_escape "$feature_id")\","
    echo "  \"config_path\": \"$(json_escape "$config_path")\","
    echo "  \"mode\": \"$(json_escape "$mode")\","
    echo "  \"dry_run\": \"$(json_escape "$dry_run")\","
    echo "  \"continue_on_fail\": \"$(json_escape "$continue_on_fail")\","
    echo "  \"from_step\": \"$(json_escape "${from_step:-}")\","
    echo "  \"commands\": {"
    echo "    \"targeted_tests\": \"$(json_escape "$targeted_cmd")\","
    echo "    \"full_tests\": \"$(json_escape "$full_cmd")\","
    echo "    \"scenario_pack\": \"$(json_escape "$scenario_pack_cmd")\","
    echo "    \"reasoning_eval_logic\": \"$(json_escape "$reasoning_logic_cmd")\","
    echo "    \"reasoning_eval_model\": \"$(json_escape "$reasoning_model_cmd")\","
    echo "    \"memory_live_smoke\": \"$(json_escape "$memory_smoke_cmd")\","
    echo "    \"ai_triage\": \"$(json_escape "$ai_triage_cmd")\","
    echo "    \"summarizer\": \"$(json_escape "$summarizer_cmd")\""
    echo "  }"
    echo "}"
  } >"$run_config_json"
}

latest_reasoning_eval_file() {
  local latest=""
  if compgen -G "$repo_root/.psyke/evals/reasoning/runs/reasoning-eval-*.json" >/dev/null; then
    latest="$(ls -1t "$repo_root"/.psyke/evals/reasoning/runs/reasoning-eval-*.json 2>/dev/null | head -n 1)"
  fi
  printf '%s' "$latest"
}

latest_memory_eval_file() {
  local latest=""
  if compgen -G "$repo_root/.psyke/evals/memory-live/runs/memory-live-eval-*.json" >/dev/null; then
    latest="$(ls -1t "$repo_root"/.psyke/evals/memory-live/runs/memory-live-eval-*.json 2>/dev/null | head -n 1)"
  fi
  printf '%s' "$latest"
}

collect_failed_tests_for_step() {
  local step_name="$1"
  if ! compgen -G "$repo_root/build/test-results/test/TEST-*.xml" >/dev/null; then
    return 0
  fi

  while IFS= read -r testcase; do
    [[ -z "$testcase" ]] && continue
    printf "%s\t%s\n" "$step_name" "$testcase" >>"$failed_tests_file"
  done < <(
    for xml in "$repo_root"/build/test-results/test/TEST-*.xml; do
      awk '
        BEGIN { RS="</testcase>"; ORS="\n" }
        /<failure|<error/ {
          cls="unknown"
          name="unknown"
          if (match($0, /classname="[^"]+"/)) {
            cls = substr($0, RSTART + 11, RLENGTH - 12)
          }
          if (match($0, /name="[^"]+"/)) {
            name = substr($0, RSTART + 6, RLENGTH - 7)
          }
          gsub(/[[:space:]]+/, " ", name)
          print cls "." name
        }
      ' "$xml"
    done | sort -u
  )
}

collect_eval_metrics() {
  local step_name="$1"
  local eval_file="$2"
  [[ -z "$eval_file" ]] && return 0
  [[ ! -f "$eval_file" ]] && return 0

  local calls tokens
  calls="$(sed -nE 's/^[[:space:]]*"totalModelCalls"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p' "$eval_file" | head -n 1)"
  tokens="$(sed -nE 's/^[[:space:]]*"totalTokens"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p' "$eval_file" | awk '{s+=$1} END{print s+0}')"

  calls="${calls:-0}"
  tokens="${tokens:-0}"
  printf "%s\t%s\t%s\t%s\n" "$step_name" "$calls" "$tokens" "$eval_file" >>"$eval_metrics_file"
}

index_step_log() {
  local step_name="$1"
  local step_log="$2"
  local index_file="$log_index_dir/${step_name}.tsv"
  printf "category\tline\tpreview\n" >"$index_file"

  local cat regex matches
  while IFS='|' read -r cat regex; do
    set +e
    matches="$(rg -n -i -e "$regex" "$step_log" 2>/dev/null | rg -v '^[0-9]+:> Task :')"
    set -e
    if [[ -z "$matches" ]]; then
      continue
    fi
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      local line_num preview
      line_num="${line%%:*}"
      preview="${line#*:}"
      preview="$(echo "$preview" | sed -E 's/[[:space:]]+/ /g' | sed -E 's/^ //; s/ $//')"
      preview="${preview:0:180}"
      printf "%s\t%s\t%s\n" "$cat" "$line_num" "$(tsv_escape "$preview")" >>"$index_file"
    done < <(printf '%s\n' "$matches" | head -n 40)
  done <<'EOF'
warning|warning
error|error|exception|failed|assert
pressure|decision_pressure=[0-9]+\.[0-9]+
queue|queue_saturation|queue full|step limit reached
parse|parse fallback|failed to parse|non-parseable|parse error|parse failure
EOF
}

summarize_step_log_counts() {
  local step_log="$1"
  local out_prefix="$2"

  local log_lines warning_count error_count first_warning first_error first_pressure
  log_lines="$(wc -l <"$step_log" | tr -d ' ')"

  set +e
  # Filter out Gradle task-header lines (e.g. "> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED")
  # to avoid false-positive error/warning counts on every passing Gradle build.
  warning_count="$(rg -n -i -e "warning" "$step_log" 2>/dev/null | rg -v '^[0-9]+:> Task :' | wc -l | tr -d ' ')"
  error_count="$(rg -n -i -e "error|exception|failed|assert" "$step_log" 2>/dev/null | rg -v '^[0-9]+:> Task :' | wc -l | tr -d ' ')"
  first_warning="$(rg -n -i -e "warning" "$step_log" 2>/dev/null | rg -v '^[0-9]+:> Task :' | head -n 1)"
  first_error="$(rg -n -i -e "error|exception|failed|assert" "$step_log" 2>/dev/null | rg -v '^[0-9]+:> Task :' | head -n 1)"
  first_pressure="$(rg -n -m 1 -e "decision_pressure=[0-9]+\\.[0-9]+" "$step_log" 2>/dev/null | head -n 1)"
  set -e

  eval "${out_prefix}_log_lines='$log_lines'"
  eval "${out_prefix}_warning_count='${warning_count:-0}'"
  eval "${out_prefix}_error_count='${error_count:-0}'"
  eval "${out_prefix}_first_warning='${first_warning:-}'"
  eval "${out_prefix}_first_error='${first_error:-}'"
  eval "${out_prefix}_first_pressure='${first_pressure:-}'"
}

run_step() {
  local step_name="$1"
  local step_cmd="$2"
  local step_log="$3"

  local status="skipped"
  local started_at_iso finished_at_iso
  local started_epoch ended_epoch duration_sec exit_code
  local pre_reasoning post_reasoning pre_memory post_memory
  local log_lines warning_count error_count first_warning first_error first_pressure
  local first_warning_ref first_error_ref first_pressure_ref
  local step_meta_file step_ref

  emit_trail "step_start" "$step_name" "running" "step started" "$step_cmd" "$step_log" ""

  pre_reasoning="$(latest_reasoning_eval_file)"
  pre_memory="$(latest_memory_eval_file)"
  started_at_iso="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  started_epoch="$(date +%s)"
  exit_code=0

  if [[ -n "$step_cmd" ]]; then
    if [[ "$dry_run" == "true" ]]; then
      status="dry_run"
      {
        echo "[dry-run] command not executed"
        echo "cmd=$step_cmd"
      } >"$step_log"
    else
      set +e
      (
        cd "$repo_root"
        if [[ -n "$gradle_user_home" ]]; then
          export GRADLE_USER_HOME="$gradle_user_home"
        fi
        eval "$step_cmd"
      ) >"$step_log" 2>&1
      exit_code=$?
      set -e
      if [[ $exit_code -eq 0 ]]; then
        status="pass"
      else
        status="fail"
      fi
    fi
  else
    status="skipped"
    {
      echo "[skip] command is empty"
    } >"$step_log"
  fi

  finished_at_iso="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  ended_epoch="$(date +%s)"
  duration_sec="$((ended_epoch - started_epoch))"

  index_step_log "$step_name" "$step_log"
  summarize_step_log_counts "$step_log" "tmp"
  log_lines="$tmp_log_lines"
  warning_count="$tmp_warning_count"
  error_count="$tmp_error_count"
  first_warning="$tmp_first_warning"
  first_error="$tmp_first_error"
  first_pressure="$tmp_first_pressure"
  first_warning_ref=""
  first_error_ref=""
  first_pressure_ref=""
  if [[ -n "$first_warning" ]]; then
    first_warning_ref="$step_log:${first_warning%%:*}"
  fi
  if [[ -n "$first_error" ]]; then
    first_error_ref="$step_log:${first_error%%:*}"
  fi
  if [[ -n "$first_pressure" ]]; then
    first_pressure_ref="$step_log:${first_pressure%%:*}"
  fi

  printf "%s\t%s\t%s\t%s\n" "$step_name" "$status" "$duration_sec" "$step_log" >>"$steps_file"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$(tsv_escape "$step_name")" \
    "$(tsv_escape "$status")" \
    "$duration_sec" \
    "$(tsv_escape "$step_log")" \
    "$(tsv_escape "$log_index_dir/${step_name}.tsv")" \
    "$log_lines" \
    "$warning_count" \
    "$error_count" \
    "$(tsv_escape "$first_warning_ref")" \
    "$(tsv_escape "$first_error_ref")" \
    "$(tsv_escape "$first_pressure_ref")" >>"$step_index_file"

  step_meta_file="$step_meta_dir/${step_name}.json"
  {
    echo "{"
    echo "  \"step\": \"$(json_escape "$step_name")\","
    echo "  \"status\": \"$(json_escape "$status")\","
    echo "  \"cmd\": \"$(json_escape "$step_cmd")\","
    echo "  \"started_at\": \"$(json_escape "$started_at_iso")\","
    echo "  \"finished_at\": \"$(json_escape "$finished_at_iso")\","
    echo "  \"duration_sec\": $duration_sec,"
    echo "  \"exit_code\": $exit_code,"
    echo "  \"log\": \"$(json_escape "$step_log")\","
    echo "  \"log_index\": \"$(json_escape "$log_index_dir/${step_name}.tsv")\","
    echo "  \"counts\": {\"lines\": $log_lines, \"warnings\": $warning_count, \"errors\": $error_count},"
    echo "  \"first_refs\": {"
    echo "    \"warning\": \"$(json_escape "$first_warning_ref")\","
    echo "    \"error\": \"$(json_escape "$first_error_ref")\","
    echo "    \"pressure\": \"$(json_escape "$first_pressure_ref")\""
    echo "  }"
    echo "}"
  } >"$step_meta_file"

  if [[ "$dry_run" != "true" && "$step_name" =~ ^(targeted_tests|full_tests|scenario_pack)$ ]]; then
    collect_failed_tests_for_step "$step_name"
  fi

  if [[ "$dry_run" != "true" && "$status" == "pass" ]]; then
    if [[ "$step_name" =~ ^(reasoning_eval_logic|reasoning_eval_model)$ ]]; then
      post_reasoning="$(latest_reasoning_eval_file)"
      collect_eval_metrics "$step_name" "${post_reasoning:-$pre_reasoning}"
    fi
    if [[ "$step_name" == "memory_live_smoke" ]]; then
      post_memory="$(latest_memory_eval_file)"
      collect_eval_metrics "$step_name" "${post_memory:-$pre_memory}"
    fi
  fi

  emit_trail "step_indexed" "$step_name" "$status" "step metadata persisted" "" "$step_log" "$step_meta_file"
  step_ref="$first_error_ref"
  if [[ -z "$step_ref" ]]; then
    step_ref="$first_warning_ref"
  fi
  if [[ -z "$step_ref" ]]; then
    step_ref="$step_meta_file"
  fi
  emit_trail "step_end" "$step_name" "$status" "step finished duration_sec=$duration_sec warnings=$warning_count errors=$error_count" "$step_cmd" "$step_log" "$step_ref"
  echo "step=$step_name status=$status duration_sec=$duration_sec"
  if [[ "$status" == "fail" && "$continue_on_fail" != "true" ]]; then
    return 1
  fi
  return 0
}

build_run_index() {
  local run_status="$1"
  local first_failed="$2"
  local steps_total="$3"
  local failed_tests="$4"
  local eval_calls="$5"
  local eval_tokens="$6"

  local trail_events
  trail_events="$(wc -l <"$trail_file" | tr -d ' ')"

  {
    echo "{"
    echo "  \"workflow\": \"freud\","
    echo "  \"project\": \"$(json_escape "$project_name")\","
    echo "  \"feature_id\": \"$(json_escape "$feature_id")\","
    echo "  \"run_id\": \"$(json_escape "$run_id")\","
    echo "  \"status\": \"$(json_escape "$run_status")\","
    echo "  \"mode\": \"$(json_escape "$mode")\","
    echo "  \"run_dir\": \"$(json_escape "$run_dir")\","
    echo "  \"first_failed_step\": \"$(json_escape "$first_failed")\","
    echo "  \"steps_total\": $steps_total,"
    echo "  \"failed_test_count\": $failed_tests,"
    echo "  \"eval_totals\": {\"model_calls\": $eval_calls, \"total_tokens\": $eval_tokens},"
    echo "  \"trail_events\": $trail_events,"
    echo "  \"files\": {"
    echo "    \"summary\": \"$(json_escape "$artifact_dir/summary.json")\","
    echo "    \"failures\": \"$(json_escape "$artifact_dir/failures.json")\","
    echo "    \"anomalies\": \"$(json_escape "$artifact_dir/anomalies.json")\","
    echo "    \"run_config\": \"$(json_escape "$run_config_json")\","
    echo "    \"model_summary_json\": \"$(json_escape "$model_summary_json")\","
    echo "    \"model_summary_md\": \"$(json_escape "$model_summary_md")\","
    echo "    \"model_summary_attempts_tsv\": \"$(json_escape "$model_summary_attempts_tsv")\","
    echo "    \"model_summary_metrics_json\": \"$(json_escape "$model_summary_metrics_json")\","
    echo "    \"freud_metrics_json\": \"$(json_escape "$freud_metrics_json")\","
    echo "    \"trail\": \"$(json_escape "$trail_file")\","
    echo "    \"trail_index\": \"$(json_escape "$trail_index_file")\","
    echo "    \"step_index\": \"$(json_escape "$step_index_file")\","
    echo "    \"step_meta_dir\": \"$(json_escape "$step_meta_dir")\","
    echo "    \"codex_context\": \"$(json_escape "$artifact_dir/codex-context.md")\""
    echo "  }"
    echo "}"
  } >"$run_index_json"

  {
    echo "# Freud Run Index"
    echo
    echo "- run_id: \`$run_id\`"
    echo "- feature_id: \`$feature_id\`"
    echo "- status: \`$run_status\`"
    echo "- mode: \`$mode\`"
    echo "- first_failed_step: \`${first_failed:-none}\`"
    echo "- run_dir: \`$run_dir\`"
    echo
    echo "## Core Artifacts"
    echo "- summary: \`$artifact_dir/summary.json\`"
    echo "- failures: \`$artifact_dir/failures.json\`"
    echo "- anomalies: \`$artifact_dir/anomalies.json\`"
    echo "- run config: \`$run_config_json\`"
    echo "- model summary json: \`$model_summary_json\`"
    echo "- model summary md: \`$model_summary_md\`"
    echo "- model summary attempts: \`$model_summary_attempts_tsv\`"
    echo "- model summary metrics: \`$model_summary_metrics_json\`"
    echo "- freud metrics: \`$freud_metrics_json\`"
    echo "- trail: \`$trail_file\`"
    echo "- trail index: \`$trail_index_file\`"
    echo "- step index: \`$step_index_file\`"
    echo "- step meta dir: \`$step_meta_dir\`"
    echo "- codex context: \`$artifact_dir/codex-context.md\`"
    echo
    echo "## Step Table"
    echo '```text'
    sed -n '1,80p' "$step_index_file"
    echo '```'
  } >"$run_index_md"
}

write_run_config
emit_trail "run_start" "" "running" "feature loop started" "" "" "$run_config_json"

should_stop="false"

# Helper: resolve the effective command for a step, respecting --from-step.
# If the step is before --from-step, returns "" so run_step records it as skipped.
step_cmd_for() {
  local step_name="$1" cmd="$2"
  if step_is_active "$step_name"; then printf '%s' "$cmd"
  else printf ''; fi
}

run_step "preflight_compile" "$(step_cmd_for preflight_compile "$preflight_compile_cmd")" "$log_dir/00-preflight-compile.log" || should_stop="true"
if [[ "$should_stop" != "true" ]]; then
  run_step "targeted_tests" "$(step_cmd_for targeted_tests "$targeted_cmd")" "$log_dir/01-targeted-tests.log" || should_stop="true"
fi
if [[ "$should_stop" != "true" ]]; then
  run_step "full_tests" "$(step_cmd_for full_tests "$full_cmd")" "$log_dir/02-full-tests.log" || should_stop="true"
fi
if [[ "$should_stop" != "true" ]]; then
  run_step "scenario_pack" "$(step_cmd_for scenario_pack "$scenario_pack_cmd")" "$log_dir/03-scenario-pack.log" || should_stop="true"
fi
if [[ "$should_stop" != "true" ]]; then
  run_step "reasoning_eval_logic" "$(step_cmd_for reasoning_eval_logic "$reasoning_logic_cmd")" "$log_dir/04-reasoning-eval-logic.log" || should_stop="true"
fi
if [[ "$should_stop" != "true" && "$mode" == "live" ]]; then
  run_step "reasoning_eval_model" "$(step_cmd_for reasoning_eval_model "$reasoning_model_cmd")" "$log_dir/05-reasoning-eval-model.log" || should_stop="true"
fi
if [[ "$should_stop" != "true" && "$mode" == "live" ]]; then
  run_step "memory_live_smoke" "$(step_cmd_for memory_live_smoke "$memory_smoke_cmd")" "$log_dir/06-memory-live-smoke.log" || should_stop="true"
fi

"$repo_root/freud/scripts/triage-run.sh" "$run_dir" >/dev/null
emit_trail "triage_complete" "" "ok" "triage artifacts generated" "" "$artifact_dir/anomalies.json" ""

if [[ "$should_stop" == "true" && -n "$ai_triage_cmd" && "$dry_run" != "true" ]]; then
  emit_trail "ai_triage_start" "" "running" "AI failure triage started" "$ai_triage_cmd" "" ""
  set +e
  (
    cd "$repo_root"
    FREUD_RUN_DIR="$run_dir" FREUD_ARTIFACT_DIR="$artifact_dir" eval "$ai_triage_cmd"
  ) >"$log_dir/08-ai-triage.log" 2>&1
  ai_triage_exit=$?
  set -e
  if [[ $ai_triage_exit -eq 0 ]]; then
    emit_trail "ai_triage_end" "" "pass" "AI triage complete" "$ai_triage_cmd" "$log_dir/08-ai-triage.log" "$artifact_dir/ai-triage.json"
  else
    emit_trail "ai_triage_end" "" "fail" "AI triage failed (non-fatal)" "$ai_triage_cmd" "$log_dir/08-ai-triage.log" ""
  fi
fi

if [[ -n "$summarizer_cmd" && "$dry_run" != "true" ]]; then
  emit_trail "summarizer_start" "" "running" "external summarizer start" "$summarizer_cmd" "" ""
  set +e
  (
    cd "$repo_root"
    FREUD_RUN_DIR="$run_dir" FREUD_ARTIFACT_DIR="$artifact_dir" eval "$summarizer_cmd"
  ) >"$log_dir/07-summarizer.log" 2>&1
  summarizer_exit=$?
  set -e
  if [[ $summarizer_exit -eq 0 ]]; then
    emit_trail "summarizer_end" "" "pass" "external summarizer finished" "$summarizer_cmd" "$log_dir/07-summarizer.log" ""
    if [[ -f "$model_summary_json" ]]; then
      _ms_actual_status="$(sed -nE 's/^[[:space:]]*"status"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$model_summary_json" 2>/dev/null | head -n 1)"
      if [[ "${_ms_actual_status:-}" == "done" ]]; then
        emit_trail "summarizer_artifact" "" "ok" "tier-2 model summary written" "" "$model_summary_json" "$model_summary_md"
      else
        emit_trail "summarizer_skipped" "" "skipped" "tier-2 summarizer skipped (${_ms_actual_status:-no-status})" "" "$model_summary_json" ""
      fi
    fi
  else
    emit_trail "summarizer_end" "" "fail" "external summarizer failed" "$summarizer_cmd" "$log_dir/07-summarizer.log" ""
  fi
fi

finished_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

steps_total="$(awk 'NR>1 {c++} END{print c+0}' "$steps_file")"
steps_passed="$(awk -F '\t' 'NR>1 && $2=="pass"{c++} END{print c+0}' "$steps_file")"
steps_failed="$(awk -F '\t' 'NR>1 && $2=="fail"{c++} END{print c+0}' "$steps_file")"
steps_skipped="$(awk -F '\t' 'NR>1 && ($2=="skipped" || $2=="dry_run"){c++} END{print c+0}' "$steps_file")"
first_failed_step="$(awk -F '\t' 'NR>1 && $2=="fail"{print $1; exit}' "$steps_file")"
overall_status="pass"
if [[ "$steps_failed" != "0" ]]; then
  overall_status="fail"
fi

failed_test_count="$(awk -F '\t' 'NF>=2 {c++} END{print c+0}' "$failed_tests_file")"
eval_total_calls="$(awk -F '\t' 'NF>=3 {s+=$2} END{print s+0}' "$eval_metrics_file")"
eval_total_tokens="$(awk -F '\t' 'NF>=3 {s+=$3} END{print s+0}' "$eval_metrics_file")"

summarizer_prompt_tokens=0
summarizer_completion_tokens=0
summarizer_total_tokens=0
summarizer_cumulative_before=0
summarizer_cumulative_after=0
summarizer_token_limit=1000000
summarizer_limit_reached="false"
summarizer_warning=""
summarizer_attempts_total=0
summarizer_healthcheck_failures=0
summarizer_completion_failures=0
summarizer_fallback_used="false"
if [[ -f "$model_summary_metrics_json" ]]; then
  summarizer_prompt_tokens="$(jq -r '.tokens.prompt_tokens // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_completion_tokens="$(jq -r '.tokens.completion_tokens // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_total_tokens="$(jq -r '.tokens.total_tokens // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_cumulative_before="$(jq -r '.token_budget.cumulative_before // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_cumulative_after="$(jq -r '.token_budget.cumulative_after // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_token_limit="$(jq -r '.token_budget.token_limit // 1000000' "$model_summary_metrics_json" 2>/dev/null || echo "1000000")"
  summarizer_limit_reached="$(jq -r '.token_budget.limit_reached // false' "$model_summary_metrics_json" 2>/dev/null || echo "false")"
  summarizer_warning="$(jq -r '.token_budget.warning // ""' "$model_summary_metrics_json" 2>/dev/null || echo "")"
  summarizer_attempts_total="$(jq -r '.attempts.total // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_healthcheck_failures="$(jq -r '.attempts.healthcheck_failures // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_completion_failures="$(jq -r '.attempts.completion_failures // 0' "$model_summary_metrics_json" 2>/dev/null || echo "0")"
  summarizer_fallback_used="$(jq -r '.attempts.fallback_used // false' "$model_summary_metrics_json" 2>/dev/null || echo "false")"
fi
summarizer_prompt_tokens="${summarizer_prompt_tokens:-0}"
summarizer_completion_tokens="${summarizer_completion_tokens:-0}"
summarizer_total_tokens="${summarizer_total_tokens:-0}"
summarizer_cumulative_before="${summarizer_cumulative_before:-0}"
summarizer_cumulative_after="${summarizer_cumulative_after:-0}"
summarizer_token_limit="${summarizer_token_limit:-1000000}"
summarizer_limit_reached="${summarizer_limit_reached:-false}"
summarizer_attempts_total="${summarizer_attempts_total:-0}"
summarizer_healthcheck_failures="${summarizer_healthcheck_failures:-0}"
summarizer_completion_failures="${summarizer_completion_failures:-0}"
summarizer_fallback_used="${summarizer_fallback_used:-false}"
if [[ "$summarizer_limit_reached" == "true" && -n "$summarizer_warning" ]]; then
  emit_trail "summarizer_budget_limit" "" "warn" "$summarizer_warning" "" "$model_summary_metrics_json" "$model_summary_json"
fi

anomalies_json="$artifact_dir/anomalies.json"
top_signals_file="$artifact_dir/top-signals.tsv"
pressure_file="$artifact_dir/pressure-signals.tsv"
triage_pattern_hits_total=0
triage_top_signals_count=0
triage_pressure_samples=0
if [[ -f "$anomalies_json" ]]; then
  triage_pattern_hits_total="$(jq -r '[.pattern_counts[]?.count // 0] | add // 0' "$anomalies_json" 2>/dev/null || echo "0")"
  triage_top_signals_count="$(jq -r '.top_signals | length // 0' "$anomalies_json" 2>/dev/null || echo "0")"
fi
if [[ -f "$pressure_file" ]]; then
  triage_pressure_samples="$(awk 'NF>0 {c++} END{print c+0}' "$pressure_file")"
fi
triage_pattern_hits_total="${triage_pattern_hits_total:-0}"
triage_top_signals_count="${triage_top_signals_count:-0}"
triage_pressure_samples="${triage_pressure_samples:-0}"

{
  echo "{"
  echo "  \"workflow\": \"freud\","
  echo "  \"run_id\": \"$(json_escape "$run_id")\","
  echo "  \"feature_id\": \"$(json_escape "$feature_id")\","
  echo "  \"status\": \"$(json_escape "$overall_status")\","
  echo "  \"counters\": {"
  echo "    \"steps_total\": $steps_total,"
  echo "    \"steps_passed\": $steps_passed,"
  echo "    \"steps_failed\": $steps_failed,"
  echo "    \"steps_skipped\": $steps_skipped,"
  echo "    \"failed_test_count\": $failed_test_count,"
  echo "    \"eval_model_calls\": $eval_total_calls,"
  echo "    \"eval_total_tokens\": $eval_total_tokens"
  echo "  },"
  echo "  \"triage\": {"
  echo "    \"pattern_hits_total\": $triage_pattern_hits_total,"
  echo "    \"top_signals_count\": $triage_top_signals_count,"
  echo "    \"pressure_samples\": $triage_pressure_samples"
  echo "  },"
  echo "  \"summarizer\": {"
  echo "    \"prompt_tokens\": $summarizer_prompt_tokens,"
  echo "    \"completion_tokens\": $summarizer_completion_tokens,"
  echo "    \"total_tokens\": $summarizer_total_tokens,"
  echo "    \"cumulative_before\": $summarizer_cumulative_before,"
  echo "    \"cumulative_after\": $summarizer_cumulative_after,"
  echo "    \"token_limit\": $summarizer_token_limit,"
  echo "    \"limit_reached\": $summarizer_limit_reached,"
  echo "    \"attempts_total\": $summarizer_attempts_total,"
  echo "    \"healthcheck_failures\": $summarizer_healthcheck_failures,"
  echo "    \"completion_failures\": $summarizer_completion_failures,"
  echo "    \"fallback_used\": $summarizer_fallback_used,"
  echo "    \"warning\": \"$(json_escape "$summarizer_warning")\""
  echo "  }"
  echo "}"
} >"$freud_metrics_json"

summary_json="$artifact_dir/summary.json"
{
  echo "{"
  echo "  \"workflow\": \"freud\","
  echo "  \"project\": \"$(json_escape "$project_name")\","
  echo "  \"feature_id\": \"$(json_escape "$feature_id")\","
  echo "  \"run_id\": \"$(json_escape "$run_id")\","
  echo "  \"mode\": \"$(json_escape "$mode")\","
  echo "  \"started_at\": \"$(json_escape "$started_at")\","
  echo "  \"finished_at\": \"$(json_escape "$finished_at")\","
  echo "  \"status\": \"$(json_escape "$overall_status")\","
  echo "  \"steps_total\": $steps_total,"
  echo "  \"steps_passed\": $steps_passed,"
  echo "  \"steps_failed\": $steps_failed,"
  echo "  \"steps_skipped\": $steps_skipped,"
  echo "  \"first_failed_step\": \"$(json_escape "${first_failed_step:-}")\","
  echo "  \"failed_test_count\": $failed_test_count,"
  echo "  \"triage\": {\"pattern_hits_total\": $triage_pattern_hits_total, \"top_signals_count\": $triage_top_signals_count, \"pressure_samples\": $triage_pressure_samples},"
  echo "  \"eval_totals\": {\"model_calls\": $eval_total_calls, \"total_tokens\": $eval_total_tokens},"
  echo "  \"summarizer\": {\"prompt_tokens\": $summarizer_prompt_tokens, \"completion_tokens\": $summarizer_completion_tokens, \"total_tokens\": $summarizer_total_tokens, \"token_limit\": $summarizer_token_limit, \"cumulative_before\": $summarizer_cumulative_before, \"cumulative_after\": $summarizer_cumulative_after, \"limit_reached\": $summarizer_limit_reached, \"attempts_total\": $summarizer_attempts_total, \"healthcheck_failures\": $summarizer_healthcheck_failures, \"completion_failures\": $summarizer_completion_failures, \"fallback_used\": $summarizer_fallback_used, \"warning\": \"$(json_escape "$summarizer_warning")\"},"
  echo "  \"run_dir\": \"$(json_escape "$run_dir")\","
  echo "  \"trace_files\": {\"trail\":\"$(json_escape "$trail_file")\",\"trail_index\":\"$(json_escape "$trail_index_file")\",\"step_index\":\"$(json_escape "$step_index_file")\",\"step_meta_dir\":\"$(json_escape "$step_meta_dir")\",\"run_config\":\"$(json_escape "$run_config_json")\",\"model_summary_json\":\"$(json_escape "$model_summary_json")\",\"model_summary_md\":\"$(json_escape "$model_summary_md")\",\"model_summary_attempts_tsv\":\"$(json_escape "$model_summary_attempts_tsv")\",\"model_summary_metrics_json\":\"$(json_escape "$model_summary_metrics_json")\",\"freud_metrics_json\":\"$(json_escape "$freud_metrics_json")\"},"
  echo "  \"top_warnings\": ["
  warn_count=0
  if [[ -f "$top_signals_file" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      if [[ "$line" != *"warning"* && "$line" != *"WARNING"* ]]; then
        continue
      fi
      if [[ $warn_count -gt 0 ]]; then
        echo ","
      fi
      printf '    "%s"' "$(json_escape "$line")"
      warn_count=$((warn_count + 1))
      if [[ $warn_count -ge 5 ]]; then
        break
      fi
    done <"$top_signals_file"
  fi
  echo
  echo "  ],"
  echo "  \"failed_tests\": ["
  ft_count=0
  if [[ -f "$failed_tests_file" ]]; then
    while IFS=$'\t' read -r step_name test_name; do
      [[ -z "$test_name" ]] && continue
      if [[ $ft_count -gt 0 ]]; then
        echo ","
      fi
      printf '    {"step":"%s","test":"%s"}' "$(json_escape "$step_name")" "$(json_escape "$test_name")"
      ft_count=$((ft_count + 1))
    done < <(sort -u "$failed_tests_file")
  fi
  echo
  echo "  ],"
  echo "  \"steps\": ["
  first="true"
  while IFS=$'\t' read -r step_name step_status step_duration step_log; do
    if [[ "$step_name" == "step" ]]; then
      continue
    fi
    if [[ "$first" == "true" ]]; then
      first="false"
    else
      echo ","
    fi
    printf '    {"name":"%s","status":"%s","duration_sec":%s,"log":"%s"}' \
      "$(json_escape "$step_name")" \
      "$(json_escape "$step_status")" \
      "$step_duration" \
      "$(json_escape "$step_log")"
  done <"$steps_file"
  echo
  echo "  ]"
  echo "}"
} >"$summary_json"

failures_json="$artifact_dir/failures.json"
{
  echo "{"
  echo "  \"feature_id\": \"$(json_escape "$feature_id")\","
  echo "  \"run_id\": \"$(json_escape "$run_id")\","
  echo "  \"failed_steps\": ["
  first="true"
  while IFS=$'\t' read -r step_name step_status step_duration step_log; do
    if [[ "$step_name" == "step" || "$step_status" != "fail" ]]; then
      continue
    fi
    if [[ "$first" == "true" ]]; then
      first="false"
    else
      echo ","
    fi
    sample="$(tail -n 20 "$step_log" | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' | sed -E 's/^ //; s/ $//')"
    printf '    {"name":"%s","duration_sec":%s,"log":"%s","step_meta":"%s","tail20":"%s"}' \
      "$(json_escape "$step_name")" \
      "$step_duration" \
      "$(json_escape "$step_log")" \
      "$(json_escape "$step_meta_dir/${step_name}.json")" \
      "$(json_escape "$sample")"
  done <"$steps_file"
  echo
  echo "  ]"
  echo "}"
} >"$failures_json"

emit_trail "run_end" "" "$overall_status" "feature loop completed" "" "$summary_json" "${first_failed_step:-}"
"$repo_root/freud/scripts/summarize-run.sh" "$run_dir" >/dev/null
"$repo_root/freud/scripts/codex-context-pack.sh" "$run_dir" >/dev/null
build_run_index "$overall_status" "${first_failed_step:-}" "$steps_total" "$failed_test_count" "$eval_total_calls" "$eval_total_tokens"

ln -sfn "$run_dir" "$run_root/latest"
printf '%s\n' "$run_dir" >"$run_root/latest-run.txt"
write_local_freud_pointers

echo "run_dir=$run_dir"
echo "summary=$summary_json"
echo "failures=$failures_json"
echo "anomalies=$artifact_dir/anomalies.json"
echo "trail=$trail_file"
echo "trail_index=$trail_index_file"
echo "step_index=$step_index_file"
echo "run_config=$run_config_json"
echo "model_summary_json=$model_summary_json"
echo "model_summary_md=$model_summary_md"
echo "model_summary_attempts_tsv=$model_summary_attempts_tsv"
echo "model_summary_metrics_json=$model_summary_metrics_json"
echo "model_summary_debug_log=$artifact_dir/model-summary-debug.log"
echo "freud_metrics_json=$freud_metrics_json"
echo "run_index=$run_index_json"
echo "codex_context=$artifact_dir/codex-context.md"

if [[ "$overall_status" == "fail" ]]; then
  exit 2
fi
