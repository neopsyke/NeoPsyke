#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/scripts/cheap-summarizer-adapter.sh [--run-dir <dir>] [--artifact-dir <dir>] [--if-configured] [--dry-run]

Description:
  Tier-2 optional model summarizer for Freud runs.
  Reads only compact artifacts:
    - summary.json
    - step-index.tsv
    - trail-index.tsv
    - anomalies.json
  Writes normalized artifacts:
    - model-summary.json
    - model-summary.md

Environment:
  FREUD_RUN_DIR / FREUD_ARTIFACT_DIR
  FREUD_SUMMARIZER_PROVIDER=auto|openai|groq|mistral|mock|none
  FREUD_SUMMARIZER_MODEL=<model id>
  FREUD_SUMMARIZER_BASE_URL=<optional override>
  FREUD_SUMMARIZER_MAX_OUTPUT_TOKENS=700
  FREUD_SUMMARIZER_TEMPERATURE=0.1
  FREUD_SUMMARIZER_TIMEOUT_SEC=45
  FREUD_SUMMARIZER_HEALTHCHECK_TIMEOUT_SEC=6
  FREUD_SUMMARIZER_MAX_SECTION_CHARS=5000
  FREUD_SUMMARIZER_ENABLE_FALLBACK=true
  FREUD_SUMMARIZER_FALLBACK_ORDER=openai,mistral,groq
  OPENAI_API_KEY / GROQ_API_KEY / MISTRAL_API_KEY
EOF
}

run_dir="${FREUD_RUN_DIR:-}"
artifact_dir="${FREUD_ARTIFACT_DIR:-}"
if_configured="false"
dry_run="false"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --run-dir)
      run_dir="${2:-}"
      [[ -z "$run_dir" ]] && { echo "--run-dir requires a value."; exit 1; }
      shift 2
      ;;
    --artifact-dir)
      artifact_dir="${2:-}"
      [[ -z "$artifact_dir" ]] && { echo "--artifact-dir requires a value."; exit 1; }
      shift 2
      ;;
    --if-configured)
      if_configured="true"
      shift
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

if [[ -z "$artifact_dir" && -n "$run_dir" ]]; then
  artifact_dir="$run_dir/artifacts"
fi
if [[ -z "$run_dir" && -n "$artifact_dir" ]]; then
  run_dir="$(cd "$artifact_dir/.." && pwd)"
fi

if [[ -z "$run_dir" || -z "$artifact_dir" ]]; then
  echo "Run/artifact directory not provided. Use --run-dir or FREUD_RUN_DIR."
  exit 1
fi
if [[ ! -d "$artifact_dir" ]]; then
  echo "Artifact directory does not exist: $artifact_dir"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required for cheap-summarizer-adapter."
  exit 1
fi

provider="${FREUD_SUMMARIZER_PROVIDER:-auto}"
model="${FREUD_SUMMARIZER_MODEL:-}"
base_url="${FREUD_SUMMARIZER_BASE_URL:-}"
max_output_tokens="${FREUD_SUMMARIZER_MAX_OUTPUT_TOKENS:-700}"
temperature="${FREUD_SUMMARIZER_TEMPERATURE:-0.1}"
timeout_sec="${FREUD_SUMMARIZER_TIMEOUT_SEC:-45}"
healthcheck_timeout_sec="${FREUD_SUMMARIZER_HEALTHCHECK_TIMEOUT_SEC:-6}"
max_section_chars="${FREUD_SUMMARIZER_MAX_SECTION_CHARS:-5000}"
fallback_order="${FREUD_SUMMARIZER_FALLBACK_ORDER:-openai,mistral,groq}"
token_limit="${FREUD_SUMMARIZER_TOKEN_LIMIT:-1000000}"
runtime_root="${FREUD_RUNTIME_ROOT:-$repo_root/.freud}"
metrics_dir="$runtime_root/metrics"
usage_ledger_json="$metrics_dir/summarizer-usage.json"

mkdir -p "$metrics_dir"

summary_json="$artifact_dir/summary.json"
step_index_tsv="$artifact_dir/step-index.tsv"
trail_index_tsv="$artifact_dir/trail-index.tsv"
anomalies_json="$artifact_dir/anomalies.json"

model_summary_json="$artifact_dir/model-summary.json"
model_summary_md="$artifact_dir/model-summary.md"
model_summary_metrics_json="$artifact_dir/model-summary-metrics.json"
model_summary_raw="$artifact_dir/model-summary-raw.txt"
model_summary_prompt="$artifact_dir/model-summary-prompt.txt"
model_response_json="$artifact_dir/model-summary-response.json"
model_parsed_json="$artifact_dir/model-summary-parsed.json"
model_attempts_tsv="$artifact_dir/model-summary-attempts.tsv"

printf "attempt\tprovider\tmodel\tphase\tstatus\tdetail\n" >"$model_attempts_tsv"

ledger_prompt_before=0
ledger_completion_before=0
ledger_runs_before=0
usage_cumulative_before=0
usage_cumulative_after=0
usage_limit_reached="false"
usage_warning=""

trim_chars() {
  local value="$1"
  local max_chars="$2"
  if [[ "${#value}" -le "$max_chars" ]]; then
    printf '%s' "$value"
  else
    printf '%s' "${value:0:max_chars}"
  fi
}

read_compact_json() {
  local file="$1"
  local max_chars="$2"
  if [[ ! -f "$file" ]]; then
    printf '[missing]'
    return
  fi
  local compact
  compact="$(jq -c . "$file" 2>/dev/null || cat "$file")"
  trim_chars "$compact" "$max_chars"
}

read_compact_tsv() {
  local file="$1"
  local max_lines="$2"
  local max_chars="$3"
  if [[ ! -f "$file" ]]; then
    printf '[missing]'
    return
  fi
  local snippet
  snippet="$(sed -n "1,${max_lines}p" "$file")"
  trim_chars "$snippet" "$max_chars"
}

ensure_usage_ledger() {
  if [[ -f "$usage_ledger_json" ]]; then
    return 0
  fi
  jq -n \
    --arg updated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
    --arg ledger_path "$usage_ledger_json" \
    --argjson token_limit "$token_limit" \
    '{
      workflow: "freud",
      ledger: "summarizer_usage",
      token_limit: $token_limit,
      total_prompt_tokens: 0,
      total_completion_tokens: 0,
      total_tokens: 0,
      run_count: 0,
      token_limit_reached: false,
      token_limit_reached_at: "",
      last_updated_at: $updated_at,
      last_run_dir: "",
      last_provider: "",
      last_model: "",
      ledger_path: $ledger_path
    }' >"$usage_ledger_json"
}

load_usage_ledger() {
  ensure_usage_ledger
  ledger_prompt_before="$(jq -r '.total_prompt_tokens // 0' "$usage_ledger_json" 2>/dev/null || echo "0")"
  ledger_completion_before="$(jq -r '.total_completion_tokens // 0' "$usage_ledger_json" 2>/dev/null || echo "0")"
  ledger_runs_before="$(jq -r '.run_count // 0' "$usage_ledger_json" 2>/dev/null || echo "0")"
  usage_cumulative_before="$(jq -r '.total_tokens // 0' "$usage_ledger_json" 2>/dev/null || echo "0")"
  usage_cumulative_after="$usage_cumulative_before"
}

persist_usage_ledger() {
  local add_prompt="$1"
  local add_completion="$2"
  local add_run="$3"

  local prompt_after completion_after runs_after total_after
  prompt_after=$((ledger_prompt_before + add_prompt))
  completion_after=$((ledger_completion_before + add_completion))
  runs_after=$((ledger_runs_before + add_run))
  total_after=$((prompt_after + completion_after))
  usage_cumulative_after="$total_after"
  if (( total_after >= token_limit )); then
    usage_limit_reached="true"
  fi

  local reached_at
  reached_at="$(jq -r '.token_limit_reached_at // ""' "$usage_ledger_json" 2>/dev/null || true)"
  if [[ "$usage_limit_reached" == "true" && -z "$reached_at" ]]; then
    reached_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  fi

  local tmp
  tmp="$(mktemp)"
  jq -n \
    --arg updated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
    --arg run_dir "$run_dir" \
    --arg provider "$provider" \
    --arg model "$model" \
    --arg ledger_path "$usage_ledger_json" \
    --arg reached_at "$reached_at" \
    --argjson token_limit "$token_limit" \
    --argjson total_prompt_tokens "$prompt_after" \
    --argjson total_completion_tokens "$completion_after" \
    --argjson total_tokens "$total_after" \
    --argjson run_count "$runs_after" \
    --argjson limit_reached "$([[ "$usage_limit_reached" == "true" ]] && echo true || echo false)" \
    '{
      workflow: "freud",
      ledger: "summarizer_usage",
      token_limit: $token_limit,
      total_prompt_tokens: $total_prompt_tokens,
      total_completion_tokens: $total_completion_tokens,
      total_tokens: $total_tokens,
      run_count: $run_count,
      token_limit_reached: $limit_reached,
      token_limit_reached_at: $reached_at,
      last_updated_at: $updated_at,
      last_run_dir: $run_dir,
      last_provider: $provider,
      last_model: $model,
      ledger_path: $ledger_path
    }' >"$tmp"
  mv "$tmp" "$usage_ledger_json"
}

write_summary_artifacts() {
  local status="$1"
  local reason="$2"
  local summary_text="$3"
  local prompt_tokens="$4"
  local completion_tokens="$5"
  local has_model_json="$6"

  if [[ "$has_model_json" == "true" && -s "$model_parsed_json" ]]; then
    jq -n \
      --slurpfile parsed "$model_parsed_json" \
      --arg workflow "freud" \
      --arg tier "tier2_model_summary" \
      --arg status "$status" \
      --arg reason "$reason" \
      --arg provider "$provider" \
      --arg model "$model" \
      --arg generated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg run_dir "$run_dir" \
      --arg artifact_dir "$artifact_dir" \
      --arg summary_json "$summary_json" \
      --arg step_index "$step_index_tsv" \
      --arg trail_index "$trail_index_tsv" \
      --arg anomalies_json "$anomalies_json" \
      --arg attempts_tsv "$model_attempts_tsv" \
      --arg usage_ledger "$usage_ledger_json" \
      --arg summary_text "$summary_text" \
      --argjson prompt_tokens "${prompt_tokens:-0}" \
      --argjson completion_tokens "${completion_tokens:-0}" \
      --argjson total_tokens "$(( ${prompt_tokens:-0} + ${completion_tokens:-0} ))" \
      --argjson token_limit "$token_limit" \
      --argjson cumulative_before "${usage_cumulative_before:-0}" \
      --argjson cumulative_after "${usage_cumulative_after:-0}" \
      --argjson limit_reached "$([[ "$usage_limit_reached" == "true" ]] && echo true || echo false)" \
      --arg token_warning "$usage_warning" \
      '{
        workflow: $workflow,
        tier: $tier,
        status: $status,
        reason: $reason,
        provider: $provider,
        model: $model,
        generated_at: $generated_at,
        run_dir: $run_dir,
        artifact_dir: $artifact_dir,
        source_files: {
          summary_json: $summary_json,
          step_index_tsv: $step_index,
          trail_index_tsv: $trail_index,
          anomalies_json: $anomalies_json,
          attempts_tsv: $attempts_tsv,
          usage_ledger_json: $usage_ledger
        },
        usage: {
          prompt_tokens: $prompt_tokens,
          completion_tokens: $completion_tokens,
          total_tokens: $total_tokens
        },
        token_budget: {
          token_limit: $token_limit,
          cumulative_before: $cumulative_before,
          cumulative_after: $cumulative_after,
          limit_reached: $limit_reached,
          warning: $token_warning
        },
        model_output_text: $summary_text,
        model_output_json: ($parsed[0] // null)
      }' >"$model_summary_json"
  else
    jq -n \
      --arg workflow "freud" \
      --arg tier "tier2_model_summary" \
      --arg status "$status" \
      --arg reason "$reason" \
      --arg provider "$provider" \
      --arg model "$model" \
      --arg generated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg run_dir "$run_dir" \
      --arg artifact_dir "$artifact_dir" \
      --arg summary_json "$summary_json" \
      --arg step_index "$step_index_tsv" \
      --arg trail_index "$trail_index_tsv" \
      --arg anomalies_json "$anomalies_json" \
      --arg attempts_tsv "$model_attempts_tsv" \
      --arg usage_ledger "$usage_ledger_json" \
      --arg summary_text "$summary_text" \
      --argjson prompt_tokens "${prompt_tokens:-0}" \
      --argjson completion_tokens "${completion_tokens:-0}" \
      --argjson total_tokens "$(( ${prompt_tokens:-0} + ${completion_tokens:-0} ))" \
      --argjson token_limit "$token_limit" \
      --argjson cumulative_before "${usage_cumulative_before:-0}" \
      --argjson cumulative_after "${usage_cumulative_after:-0}" \
      --argjson limit_reached "$([[ "$usage_limit_reached" == "true" ]] && echo true || echo false)" \
      --arg token_warning "$usage_warning" \
      '{
        workflow: $workflow,
        tier: $tier,
        status: $status,
        reason: $reason,
        provider: $provider,
        model: $model,
        generated_at: $generated_at,
        run_dir: $run_dir,
        artifact_dir: $artifact_dir,
        source_files: {
          summary_json: $summary_json,
          step_index_tsv: $step_index,
          trail_index_tsv: $trail_index,
          anomalies_json: $anomalies_json,
          attempts_tsv: $attempts_tsv,
          usage_ledger_json: $usage_ledger
        },
        usage: {
          prompt_tokens: $prompt_tokens,
          completion_tokens: $completion_tokens,
          total_tokens: $total_tokens
        },
        token_budget: {
          token_limit: $token_limit,
          cumulative_before: $cumulative_before,
          cumulative_after: $cumulative_after,
          limit_reached: $limit_reached,
          warning: $token_warning
        },
        model_output_text: $summary_text,
        model_output_json: null
      }' >"$model_summary_json"
  fi

  local overview root_cause confidence risk_level
  overview="$(jq -r '.model_output_json.overview // ""' "$model_summary_json" 2>/dev/null || true)"
  root_cause="$(jq -r '.model_output_json.root_cause // ""' "$model_summary_json" 2>/dev/null || true)"
  confidence="$(jq -r '.model_output_json.confidence // ""' "$model_summary_json" 2>/dev/null || true)"
  risk_level="$(jq -r '.model_output_json.risk_level // ""' "$model_summary_json" 2>/dev/null || true)"

  {
    echo "# Tier-2 Model Summary"
    echo
    echo "- status: \`$status\`"
    echo "- reason: \`$reason\`"
    echo "- provider/model: \`${provider:-unknown}/${model:-unknown}\`"
    echo "- generated_at: \`$(date -u +"%Y-%m-%dT%H:%M:%SZ")\`"
    echo "- run_dir: \`$run_dir\`"
    echo
    echo "## Sources (Compact Only)"
    echo "- \`$summary_json\`"
    echo "- \`$step_index_tsv\`"
    echo "- \`$trail_index_tsv\`"
    echo "- \`$anomalies_json\`"
    echo "- \`$model_attempts_tsv\`"
    echo "- \`$usage_ledger_json\`"
    echo
    echo "## Token Usage"
    echo "- prompt_tokens: \`${prompt_tokens:-0}\`"
    echo "- completion_tokens: \`${completion_tokens:-0}\`"
    echo "- total_tokens: \`$(( ${prompt_tokens:-0} + ${completion_tokens:-0} ))\`"
    echo "- cumulative_before: \`${usage_cumulative_before:-0}\`"
    echo "- cumulative_after: \`${usage_cumulative_after:-0}\`"
    echo "- token_limit: \`${token_limit}\`"
    echo "- limit_reached: \`${usage_limit_reached}\`"
    if [[ -n "$usage_warning" ]]; then
      echo "- warning: \`$usage_warning\`"
    fi
    echo
    if [[ -n "$overview" ]]; then
      echo "## Overview"
      echo "$overview"
      echo
    fi
    if [[ -n "$root_cause" ]]; then
      echo "## Root Cause"
      echo "$root_cause"
      echo
    fi
    if [[ -n "$confidence" || -n "$risk_level" ]]; then
      echo "## Assessment"
      [[ -n "$confidence" ]] && echo "- confidence: \`$confidence\`"
      [[ -n "$risk_level" ]] && echo "- risk_level: \`$risk_level\`"
      echo
    fi
    echo "## Recommended Actions"
    set +e
    action_count="$(jq -r '.model_output_json.recommended_actions // [] | length' "$model_summary_json" 2>/dev/null)"
    set -e
    if [[ "${action_count:-0}" =~ ^[0-9]+$ ]] && [[ "${action_count:-0}" -gt 0 ]]; then
      jq -r '.model_output_json.recommended_actions[] | "- " + .' "$model_summary_json"
    else
      echo "- none"
    fi
    echo
    echo "## Output Text"
    echo '```text'
    if [[ -n "$summary_text" ]]; then
      printf '%s\n' "$summary_text"
    else
      echo "[empty]"
    fi
    echo '```'
    echo
    echo "## Provider Attempts"
    if [[ -f "$model_attempts_tsv" ]]; then
      echo '```text'
      sed -n '1,80p' "$model_attempts_tsv"
      echo '```'
    else
      echo "- none"
    fi
  } >"$model_summary_md"

  local attempts_total healthcheck_failures completion_failures fallback_used total_tokens
  attempts_total="$(awk 'NR>1 {c++} END{print c+0}' "$model_attempts_tsv")"
  healthcheck_failures="$(awk -F '\t' 'NR>1 && $4=="healthcheck" && $5=="fail"{c++} END{print c+0}' "$model_attempts_tsv")"
  completion_failures="$(awk -F '\t' 'NR>1 && $4=="completion" && $5=="fail"{c++} END{print c+0}' "$model_attempts_tsv")"
  fallback_used="false"
  if [[ "${attempts_total:-0}" -gt 1 ]]; then
    fallback_used="true"
  fi
  total_tokens=$(( ${prompt_tokens:-0} + ${completion_tokens:-0} ))

  jq -n \
    --arg status "$status" \
    --arg reason "$reason" \
    --arg provider "$provider" \
    --arg model "$model" \
    --arg generated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
    --arg run_dir "$run_dir" \
    --arg usage_ledger "$usage_ledger_json" \
    --arg attempts_tsv "$model_attempts_tsv" \
    --argjson prompt_tokens "${prompt_tokens:-0}" \
    --argjson completion_tokens "${completion_tokens:-0}" \
    --argjson total_tokens "$total_tokens" \
    --argjson cumulative_before "${usage_cumulative_before:-0}" \
    --argjson cumulative_after "${usage_cumulative_after:-0}" \
    --argjson token_limit "$token_limit" \
    --argjson limit_reached "$([[ "$usage_limit_reached" == "true" ]] && echo true || echo false)" \
    --arg token_warning "$usage_warning" \
    --argjson attempts_total "${attempts_total:-0}" \
    --argjson healthcheck_failures "${healthcheck_failures:-0}" \
    --argjson completion_failures "${completion_failures:-0}" \
    --argjson fallback_used "$([[ "$fallback_used" == "true" ]] && echo true || echo false)" \
    '{
      workflow: "freud",
      metric_set: "tier2_summarizer",
      status: $status,
      reason: $reason,
      provider: $provider,
      model: $model,
      generated_at: $generated_at,
      run_dir: $run_dir,
      usage_ledger_json: $usage_ledger,
      attempts_tsv: $attempts_tsv,
      tokens: {
        prompt_tokens: $prompt_tokens,
        completion_tokens: $completion_tokens,
        total_tokens: $total_tokens
      },
      token_budget: {
        token_limit: $token_limit,
        cumulative_before: $cumulative_before,
        cumulative_after: $cumulative_after,
        limit_reached: $limit_reached,
        warning: $token_warning
      },
      attempts: {
        total: $attempts_total,
        healthcheck_failures: $healthcheck_failures,
        completion_failures: $completion_failures,
        fallback_used: $fallback_used
      }
    }' >"$model_summary_metrics_json"
}

write_skipped() {
  local reason="$1"
  write_summary_artifacts "skipped" "$reason" "" "0" "0" "false"
  echo "$model_summary_json"
}

write_error() {
  local reason="$1"
  local text="$2"
  write_summary_artifacts "error" "$reason" "$text" "0" "0" "false"
  echo "$model_summary_json"
}

summary_compact="$(read_compact_json "$summary_json" "$max_section_chars")"
step_index_compact="$(read_compact_tsv "$step_index_tsv" "120" "$max_section_chars")"
trail_index_compact="$(read_compact_tsv "$trail_index_tsv" "120" "$max_section_chars")"
anomalies_compact="$(read_compact_json "$anomalies_json" "$max_section_chars")"

summary_status="$(jq -r '.status // "unknown"' "$summary_json" 2>/dev/null || echo "unknown")"
first_failed_step="$(jq -r '.first_failed_step // ""' "$summary_json" 2>/dev/null || echo "")"
failed_test_count="$(jq -r '.failed_test_count // 0' "$summary_json" 2>/dev/null || echo "0")"

cat >"$model_summary_prompt" <<EOF
You are summarizing a Freud run for engineering triage.
Use only the compact artifacts below.
Do not request additional logs.
Return JSON only (no markdown) with this schema:
{
  "overview": "short paragraph",
  "root_cause": "best-guess cause",
  "recommended_actions": ["action 1", "action 2"],
  "evidence_refs": ["artifact:line or field refs"],
  "confidence": 0.0,
  "risk_level": "low|medium|high"
}

Run snapshot:
- status: $summary_status
- first_failed_step: ${first_failed_step:-none}
- failed_test_count: $failed_test_count

Artifact: summary.json
$summary_compact

Artifact: step-index.tsv (first lines)
$step_index_compact

Artifact: trail-index.tsv (first lines)
$trail_index_compact

Artifact: anomalies.json
$anomalies_compact
EOF

enable_fallback="${FREUD_SUMMARIZER_ENABLE_FALLBACK:-true}"

attempt_seq=0
append_attempt() {
  local p="$1"
  local m="$2"
  local phase="$3"
  local status="$4"
  local detail="${5:-}"
  attempt_seq=$((attempt_seq + 1))
  detail="$(printf '%s' "$detail" | tr '\n' ' ' | tr '\t' ' ' | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"
  printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$attempt_seq" "$p" "$m" "$phase" "$status" "$detail" >>"$model_attempts_tsv"
}

provider_has_key() {
  local p="$1"
  case "$p" in
    openai) [[ -n "${OPENAI_API_KEY:-}" ]] ;;
    mistral) [[ -n "${MISTRAL_API_KEY:-}" ]] ;;
    groq) [[ -n "${GROQ_API_KEY:-}" ]] ;;
    mock) return 0 ;;
    *) return 1 ;;
  esac
}

default_model_for_provider() {
  local p="$1"
  case "$p" in
    openai) printf 'gpt-5-nano' ;;
    mistral) printf 'mistral-small-latest' ;;
    groq) printf 'openai/gpt-oss-20b' ;;
    mock) printf 'mock-v1' ;;
    *) printf '' ;;
  esac
}

default_base_url_for_provider() {
  local p="$1"
  case "$p" in
    openai) printf 'https://api.openai.com/v1/chat/completions' ;;
    mistral) printf 'https://api.mistral.ai/v1/chat/completions' ;;
    groq) printf 'https://api.groq.com/openai/v1/chat/completions' ;;
    *) printf '' ;;
  esac
}

api_key_for_provider() {
  local p="$1"
  case "$p" in
    openai) printf '%s' "${OPENAI_API_KEY:-}" ;;
    mistral) printf '%s' "${MISTRAL_API_KEY:-}" ;;
    groq) printf '%s' "${GROQ_API_KEY:-}" ;;
    *) printf '' ;;
  esac
}

unique_provider_append() {
  local candidate="$1"
  local current="$2"
  case "$candidate" in
    openai|mistral|groq|mock) ;;
    *) return 0 ;;
  esac
  if [[ ",$current," == *",$candidate,"* ]]; then
    printf '%s' "$current"
  elif [[ -z "$current" ]]; then
    printf '%s' "$candidate"
  else
    printf '%s,%s' "$current" "$candidate"
  fi
}

resolve_provider_sequence() {
  local requested="$1"
  local out=""
  local item

  if [[ "$requested" == "none" || -z "$requested" ]]; then
    printf '%s' "$out"
    return
  fi

  if [[ "$requested" == "auto" ]]; then
    IFS=',' read -r -a auto_items <<<"$fallback_order"
    for item in "${auto_items[@]}"; do
      item="$(printf '%s' "$item" | xargs)"
      out="$(unique_provider_append "$item" "$out")"
    done
  else
    out="$(unique_provider_append "$requested" "$out")"
    if [[ "$enable_fallback" == "true" ]]; then
      IFS=',' read -r -a fb_items <<<"$fallback_order"
      for item in "${fb_items[@]}"; do
        item="$(printf '%s' "$item" | xargs)"
        out="$(unique_provider_append "$item" "$out")"
      done
    fi
  fi

  printf '%s' "$out"
}

healthcheck_provider() {
  local p="$1"
  local url="$2"
  local key="$3"

  [[ "$p" == "mock" ]] && return 0
  [[ -z "$key" ]] && return 1

  local models_url http_code curl_exit
  if [[ "$url" == */chat/completions ]]; then
    models_url="${url%/chat/completions}/models"
  else
    models_url="${url%/}/models"
  fi

  curl_exit=0
  http_code="$(
    curl -sS \
      --connect-timeout 4 \
      --max-time "$healthcheck_timeout_sec" \
      -o /tmp/freud-healthcheck.json \
      -w "%{http_code}" \
      -H "Authorization: Bearer $key" \
      "$models_url"
  )" || curl_exit=$?

  if [[ $curl_exit -ne 0 ]]; then
    return 1
  fi
  [[ "$http_code" =~ ^2 ]]
}

load_usage_ledger

if [[ "$dry_run" == "true" ]]; then
  write_summary_artifacts "dry_run" "adapter dry-run; no model call" "" "0" "0" "false"
  echo "$model_summary_json"
  exit 0
fi

requested_provider="$provider"
requested_model="$model"
requested_base_url="$base_url"

if [[ "$requested_provider" == "none" || -z "$requested_provider" ]]; then
  if [[ "$if_configured" == "true" ]]; then
    write_skipped "summarizer provider not configured"
    exit 0
  fi
  write_error "summarizer provider not configured" ""
  exit 1
fi

if (( usage_cumulative_before >= token_limit )); then
  usage_limit_reached="true"
  usage_cumulative_after="$usage_cumulative_before"
  usage_warning="summarizer token limit reached (${usage_cumulative_before}/${token_limit}); Tier-2 skipped"
  append_attempt "-" "-" "budget" "skip" "$usage_warning"
  echo "WARNING: $usage_warning" >&2
  write_summary_artifacts "skipped" "$usage_warning" "" "0" "0" "false"
  echo "$model_summary_json"
  exit 0
fi

provider_sequence_csv="$(resolve_provider_sequence "$requested_provider")"
if [[ -z "$provider_sequence_csv" ]]; then
  if [[ "$if_configured" == "true" ]]; then
    write_skipped "no provider candidates in fallback chain"
    exit 0
  fi
  write_error "no provider candidates in fallback chain" ""
  exit 1
fi

IFS=',' read -r -a provider_candidates <<<"$provider_sequence_csv"
last_failure_reason=""
has_configured_provider="false"
used_requested_model_override="false"
used_requested_base_override="false"

for candidate_provider in "${provider_candidates[@]}"; do
  candidate_provider="$(printf '%s' "$candidate_provider" | xargs)"
  [[ -z "$candidate_provider" ]] && continue

  if [[ "$candidate_provider" == "mock" ]]; then
    provider="mock"
    model="${requested_model:-$(default_model_for_provider "$candidate_provider")}"
    append_attempt "$provider" "$model" "selection" "pass" "mock provider selected"
    persist_usage_ledger 0 0 1
    cat >"$model_parsed_json" <<EOF
{"overview":"Mock Tier-2 summary generated without network call.","root_cause":"Mock mode enabled.","recommended_actions":["Set FREUD_SUMMARIZER_PROVIDER to openai, groq, or mistral for live model summaries."],"evidence_refs":["$summary_json"],"confidence":0.2,"risk_level":"low"}
EOF
    write_summary_artifacts "ok" "mock provider used" "$(cat "$model_parsed_json")" "0" "0" "true"
    echo "$model_summary_json"
    exit 0
  fi

  if ! provider_has_key "$candidate_provider"; then
    append_attempt "$candidate_provider" "-" "selection" "skip" "missing API key"
    continue
  fi
  has_configured_provider="true"

  candidate_model="$(default_model_for_provider "$candidate_provider")"
  if [[ -n "$requested_model" ]]; then
    if [[ "$requested_provider" == "$candidate_provider" ]]; then
      candidate_model="$requested_model"
      used_requested_model_override="true"
    elif [[ "$requested_provider" == "auto" && "$used_requested_model_override" == "false" ]]; then
      candidate_model="$requested_model"
      used_requested_model_override="true"
    fi
  fi

  candidate_base_url="$(default_base_url_for_provider "$candidate_provider")"
  if [[ -n "$requested_base_url" ]]; then
    if [[ "$requested_provider" == "$candidate_provider" ]]; then
      candidate_base_url="$requested_base_url"
      used_requested_base_override="true"
    elif [[ "$requested_provider" == "auto" && "$used_requested_base_override" == "false" ]]; then
      candidate_base_url="$requested_base_url"
      used_requested_base_override="true"
    fi
  fi

  candidate_api_key="$(api_key_for_provider "$candidate_provider")"
  if [[ -z "$candidate_api_key" ]]; then
    append_attempt "$candidate_provider" "$candidate_model" "selection" "skip" "resolved empty API key"
    continue
  fi

  if ! healthcheck_provider "$candidate_provider" "$candidate_base_url" "$candidate_api_key"; then
    append_attempt "$candidate_provider" "$candidate_model" "healthcheck" "fail" "provider unavailable or timeout"
    last_failure_reason="healthcheck failed for provider=$candidate_provider"
    continue
  fi
  append_attempt "$candidate_provider" "$candidate_model" "healthcheck" "pass" "provider reachable"

  payload="$(
    jq -n \
      --arg model "$candidate_model" \
      --arg system "You are a concise triage summarizer. Output strict JSON only." \
      --arg user "$(cat "$model_summary_prompt")" \
      --argjson temperature "$temperature" \
      --argjson max_tokens "$max_output_tokens" \
      '{
        model: $model,
        temperature: $temperature,
        max_tokens: $max_tokens,
        messages: [
          {role: "system", content: $system},
          {role: "user", content: $user}
        ]
      }'
  )"

  candidate_response_json="$artifact_dir/model-summary-response-${candidate_provider}.json"
  curl_exit=0
  http_code="$(
    curl -sS \
      --connect-timeout 10 \
      --max-time "$timeout_sec" \
      -o "$candidate_response_json" \
      -w "%{http_code}" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $candidate_api_key" \
      -d "$payload" \
      "$candidate_base_url"
  )" || curl_exit=$?

  if [[ $curl_exit -ne 0 ]]; then
    append_attempt "$candidate_provider" "$candidate_model" "completion" "fail" "curl request failed"
    last_failure_reason="curl request failed for provider=$candidate_provider"
    continue
  fi

  if [[ ! "$http_code" =~ ^2 ]]; then
    err_text="$(jq -r '.error.message // .error // "request failed"' "$candidate_response_json" 2>/dev/null || echo "request failed")"
    append_attempt "$candidate_provider" "$candidate_model" "completion" "fail" "HTTP $http_code: $err_text"
    last_failure_reason="HTTP $http_code from provider=$candidate_provider"
    continue
  fi

  provider="$candidate_provider"
  model="$candidate_model"
  base_url="$candidate_base_url"
  prompt_tokens="$(jq -r '.usage.prompt_tokens // 0' "$candidate_response_json" 2>/dev/null || echo "0")"
  completion_tokens="$(jq -r '.usage.completion_tokens // 0' "$candidate_response_json" 2>/dev/null || echo "0")"
  persist_usage_ledger "$prompt_tokens" "$completion_tokens" 1
  if [[ "$usage_limit_reached" == "true" ]]; then
    usage_warning="summarizer token limit reached (${usage_cumulative_after}/${token_limit})"
    append_attempt "$candidate_provider" "$candidate_model" "budget" "warn" "$usage_warning"
    echo "WARNING: $usage_warning" >&2
  fi

  model_text="$(
    jq -r '.choices[0].message.content // empty' "$candidate_response_json" 2>/dev/null \
      || true
  )"
  if [[ -z "$model_text" ]]; then
    model_text="$(
      jq -r '.choices[0].message.content[0].text // empty' "$candidate_response_json" 2>/dev/null \
        || true
    )"
  fi
  if [[ -z "$model_text" ]]; then
    append_attempt "$candidate_provider" "$candidate_model" "completion" "fail" "missing text content"
    last_failure_reason="model response missing text for provider=$candidate_provider"
    continue
  fi

  cp "$candidate_response_json" "$model_response_json"
  printf '%s\n' "$model_text" >"$model_summary_raw"

  clean_text="$model_text"
  clean_text="$(printf '%s\n' "$clean_text" | sed -E 's/^```json[[:space:]]*$//; s/^```[[:space:]]*$//' )"

  parsed_ok="false"
  if printf '%s\n' "$clean_text" | jq -e . >/dev/null 2>&1; then
    printf '%s\n' "$clean_text" | jq . >"$model_parsed_json"
    parsed_ok="true"
  else
    one_line="$(printf '%s' "$clean_text" | tr '\n' ' ')"
    extracted="$(printf '%s' "$one_line" | sed -n 's/^[^{]*\({.*}\)[^}]*$/\1/p')"
    if [[ -n "$extracted" ]] && printf '%s\n' "$extracted" | jq -e . >/dev/null 2>&1; then
      printf '%s\n' "$extracted" | jq . >"$model_parsed_json"
      parsed_ok="true"
    fi
  fi

  append_attempt "$provider" "$model" "completion" "pass" "summary generated"
  if [[ "$parsed_ok" == "true" ]]; then
    write_summary_artifacts "ok" "model summary generated" "$model_text" "$prompt_tokens" "$completion_tokens" "true"
  else
    write_summary_artifacts "ok" "model summary generated (non-JSON response)" "$model_text" "$prompt_tokens" "$completion_tokens" "false"
  fi
  echo "$model_summary_json"
  exit 0
done

if [[ "$has_configured_provider" != "true" && "$if_configured" == "true" ]]; then
  write_skipped "no configured provider key available in fallback chain"
  exit 0
fi

if [[ -z "$last_failure_reason" ]]; then
  last_failure_reason="all provider attempts failed"
fi
write_error "$last_failure_reason" "see $model_attempts_tsv"
exit 1
