#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  freud/scripts/ai-triage-failure.sh [--run-dir <dir>] [--artifact-dir <dir>] [--if-configured] [--dry-run]

Description:
  AI-powered failure triage for Freud runs.
  Reads the first failed step log + anomalies, calls a cheap model for a
  focused root-cause + fix suggestion, and writes compact triage artifacts.
  Silently no-ops when no provider key is available and --if-configured is set.

Writes:
  <artifact_dir>/ai-triage.json
  <artifact_dir>/ai-triage.md
  <artifact_dir>/ai-triage-prompt.txt
  <artifact_dir>/ai-triage-attempts.tsv

Environment:
  FREUD_RUN_DIR / FREUD_ARTIFACT_DIR
  FREUD_TRIAGE_PROVIDER=auto|openai|groq|mistral|mock|none  (default: auto)
  FREUD_TRIAGE_MODEL=<model id>
  FREUD_TRIAGE_BASE_URL=<optional override>
  FREUD_TRIAGE_MAX_OUTPUT_TOKENS=500
  FREUD_TRIAGE_TIMEOUT_SEC=30
  FREUD_TRIAGE_LOG_TAIL_LINES=60
  FREUD_TRIAGE_ENABLE_FALLBACK=true
  FREUD_TRIAGE_FALLBACK_ORDER=openai,mistral,groq
  FREUD_TRIAGE_HEALTHCHECK_TIMEOUT_SEC=6
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
    --help|-h) usage; exit 0 ;;
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
    --if-configured) if_configured="true"; shift ;;
    --dry-run) dry_run="true"; shift ;;
    *) echo "Unknown argument: $1"; usage; exit 1 ;;
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
  echo "jq is required for ai-triage-failure."
  exit 1
fi

# ---- Config ----
provider="${FREUD_TRIAGE_PROVIDER:-auto}"
model="${FREUD_TRIAGE_MODEL:-}"
base_url="${FREUD_TRIAGE_BASE_URL:-}"
max_output_tokens="${FREUD_TRIAGE_MAX_OUTPUT_TOKENS:-500}"
timeout_sec="${FREUD_TRIAGE_TIMEOUT_SEC:-30}"
log_tail_lines="${FREUD_TRIAGE_LOG_TAIL_LINES:-60}"
enable_fallback="${FREUD_TRIAGE_ENABLE_FALLBACK:-true}"
fallback_order="${FREUD_TRIAGE_FALLBACK_ORDER:-openai,mistral,groq}"
healthcheck_timeout_sec="${FREUD_TRIAGE_HEALTHCHECK_TIMEOUT_SEC:-6}"

# ---- Paths ----
steps_tsv="$artifact_dir/steps.tsv"
anomalies_json="$artifact_dir/anomalies.json"
ai_triage_json="$artifact_dir/ai-triage.json"
ai_triage_md="$artifact_dir/ai-triage.md"
ai_triage_prompt="$artifact_dir/ai-triage-prompt.txt"
ai_triage_response_json="$artifact_dir/ai-triage-response.json"
ai_triage_attempts_tsv="$artifact_dir/ai-triage-attempts.tsv"

printf "attempt\tprovider\tmodel\tphase\tstatus\tdetail\n" >"$ai_triage_attempts_tsv"

# ---- Find first failed step ----
first_failed_step=""
first_failed_log=""
if [[ -f "$steps_tsv" ]]; then
  while IFS=$'\t' read -r step status _duration _log _rest; do
    [[ "$step" == "step" ]] && continue   # skip header
    if [[ "$status" == "fail" ]]; then
      first_failed_step="$step"
      first_failed_log="$_log"
      break
    fi
  done <"$steps_tsv"
fi

if [[ -z "$first_failed_step" ]]; then
  echo "No failed step found in $steps_tsv; skipping AI triage."
  exit 0
fi

echo "AI triage: failed step=$first_failed_step log=$first_failed_log"

# ---- Gather context ----
trim_to_chars() {
  local value="$1" max_chars="$2"
  if [[ "${#value}" -le "$max_chars" ]]; then printf '%s' "$value"
  else printf '%s' "${value:0:$max_chars}"; fi
}

failed_log_tail=""
if [[ -f "$first_failed_log" ]]; then
  failed_log_tail="$(tail -n "$log_tail_lines" "$first_failed_log" 2>/dev/null || true)"
fi
failed_log_tail="$(trim_to_chars "$failed_log_tail" 4000)"

anomalies_compact=""
if [[ -f "$anomalies_json" ]]; then
  anomalies_compact="$(jq -c . "$anomalies_json" 2>/dev/null || true)"
  anomalies_compact="$(trim_to_chars "$anomalies_compact" 1500)"
fi

git_diff_stat=""
set +e
git_diff_stat="$(git -C "$repo_root" diff --stat HEAD 2>/dev/null | tail -n 30 || true)"
set -e

# ---- Build prompt ----
cat >"$ai_triage_prompt" <<PROMPT_EOF
You are a CI triage assistant for a Kotlin/Gradle project.
A Freud workflow step just failed. Identify the root cause and suggest the minimal fix.
Return JSON only (no markdown fences) matching this exact schema:
{
  "failed_step": "step name",
  "root_cause": "concise 1-2 sentence description",
  "fix_suggestion": "concrete action to resolve the failure",
  "confidence": 0.0,
  "relevant_lines": ["file:line or brief excerpt"]
}

Failed step: $first_failed_step
Log path: $first_failed_log

Last ${log_tail_lines} lines of the failed step log:
${failed_log_tail}

Anomalies (compact JSON):
${anomalies_compact}

Recent git diff --stat:
${git_diff_stat:-none}
PROMPT_EOF

# ---- Provider helpers (mirrored from cheap-summarizer-adapter.sh) ----
attempt_seq=0
append_attempt() {
  local p="$1" m="$2" phase="$3" status="$4" detail="${5:-}"
  attempt_seq=$((attempt_seq + 1))
  detail="$(printf '%s' "$detail" | tr '\n' ' ' | tr '\t' ' ' | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"
  printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$attempt_seq" "$p" "$m" "$phase" "$status" "$detail" >>"$ai_triage_attempts_tsv"
}

provider_has_key() {
  case "$1" in
    openai) [[ -n "${OPENAI_API_KEY:-}" ]] ;;
    mistral) [[ -n "${MISTRAL_API_KEY:-}" ]] ;;
    groq) [[ -n "${GROQ_API_KEY:-}" ]] ;;
    mock) return 0 ;;
    *) return 1 ;;
  esac
}

default_model_for_provider() {
  case "$1" in
    openai) printf 'gpt-5-nano' ;;
    mistral) printf 'mistral-small-latest' ;;
    groq) printf 'openai/gpt-oss-20b' ;;
    mock) printf 'mock-v1' ;;
    *) printf '' ;;
  esac
}

default_base_url_for_provider() {
  case "$1" in
    openai) printf 'https://api.openai.com/v1/chat/completions' ;;
    mistral) printf 'https://api.mistral.ai/v1/chat/completions' ;;
    groq) printf 'https://api.groq.com/openai/v1/chat/completions' ;;
    *) printf '' ;;
  esac
}

api_key_for_provider() {
  case "$1" in
    openai) printf '%s' "${OPENAI_API_KEY:-}" ;;
    mistral) printf '%s' "${MISTRAL_API_KEY:-}" ;;
    groq) printf '%s' "${GROQ_API_KEY:-}" ;;
    *) printf '' ;;
  esac
}

healthcheck_provider() {
  local p="$1" url="$2" key="$3"
  [[ "$p" == "mock" ]] && return 0
  [[ -z "$key" ]] && return 1
  local models_url http_code curl_exit=0
  if [[ "$url" == */chat/completions ]]; then
    models_url="${url%/chat/completions}/models"
  else
    models_url="${url%/}/models"
  fi
  http_code="$(
    curl -sS --connect-timeout 4 --max-time "$healthcheck_timeout_sec" \
      -o /tmp/freud-ai-triage-hc.json -w "%{http_code}" \
      -H "Authorization: Bearer $key" "$models_url"
  )" || curl_exit=$?
  [[ $curl_exit -ne 0 ]] && return 1
  [[ "$http_code" =~ ^2 ]]
}

unique_provider_append() {
  local candidate="$1" current="$2"
  case "$candidate" in openai|mistral|groq|mock) ;; *) printf '%s' "$current"; return 0 ;; esac
  if [[ ",$current," == *",$candidate,"* ]]; then printf '%s' "$current"
  elif [[ -z "$current" ]]; then printf '%s' "$candidate"
  else printf '%s,%s' "$current" "$candidate"; fi
}

resolve_provider_sequence() {
  local requested="$1" out="" item
  [[ "$requested" == "none" || -z "$requested" ]] && { printf '%s' "$out"; return; }
  if [[ "$requested" == "auto" ]]; then
    IFS=',' read -r -a items <<<"$fallback_order"
    for item in "${items[@]}"; do
      item="$(printf '%s' "$item" | xargs)"
      out="$(unique_provider_append "$item" "$out")"
    done
  else
    out="$(unique_provider_append "$requested" "$out")"
    if [[ "$enable_fallback" == "true" ]]; then
      IFS=',' read -r -a items <<<"$fallback_order"
      for item in "${items[@]}"; do
        item="$(printf '%s' "$item" | xargs)"
        out="$(unique_provider_append "$item" "$out")"
      done
    fi
  fi
  printf '%s' "$out"
}

# ---- Write triage artifacts ----
write_result() {
  local status="$1" reason="$2" triage_text="$3" parsed_json="${4:-}" parsed_ok="${5:-false}"

  local root_cause="" fix_suggestion="" confidence="" relevant_lines_md=""
  if [[ "$parsed_ok" == "true" && -n "$parsed_json" ]]; then
    root_cause="$(printf '%s' "$parsed_json" | jq -r '.root_cause // ""' 2>/dev/null || true)"
    fix_suggestion="$(printf '%s' "$parsed_json" | jq -r '.fix_suggestion // ""' 2>/dev/null || true)"
    confidence="$(printf '%s' "$parsed_json" | jq -r '.confidence // ""' 2>/dev/null || true)"
    relevant_lines_md="$(printf '%s' "$parsed_json" | jq -r '.relevant_lines // [] | .[] | "- `" + . + "`"' 2>/dev/null || true)"
  fi

  # JSON artifact
  if [[ "$parsed_ok" == "true" && -n "$parsed_json" ]]; then
    jq -n \
      --arg workflow "freud" \
      --arg tier "ai_triage" \
      --arg status "$status" \
      --arg reason "$reason" \
      --arg provider "${provider:-unknown}" \
      --arg model "${model:-unknown}" \
      --arg generated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg run_dir "$run_dir" \
      --arg failed_step "$first_failed_step" \
      --arg failed_log "$first_failed_log" \
      --arg triage_text "$triage_text" \
      --argjson triage_json "$parsed_json" \
      '{
        workflow: $workflow, tier: $tier, status: $status, reason: $reason,
        provider: $provider, model: $model, generated_at: $generated_at,
        run_dir: $run_dir, failed_step: $failed_step, failed_log: $failed_log,
        triage_text: $triage_text, triage_json: $triage_json
      }' >"$ai_triage_json"
  else
    jq -n \
      --arg workflow "freud" \
      --arg tier "ai_triage" \
      --arg status "$status" \
      --arg reason "$reason" \
      --arg provider "${provider:-unknown}" \
      --arg model "${model:-unknown}" \
      --arg generated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg run_dir "$run_dir" \
      --arg failed_step "$first_failed_step" \
      --arg failed_log "$first_failed_log" \
      --arg triage_text "$triage_text" \
      '{
        workflow: $workflow, tier: $tier, status: $status, reason: $reason,
        provider: $provider, model: $model, generated_at: $generated_at,
        run_dir: $run_dir, failed_step: $failed_step, failed_log: $failed_log,
        triage_text: $triage_text, triage_json: null
      }' >"$ai_triage_json"
  fi

  # Markdown artifact
  {
    echo "# AI Failure Triage"
    echo
    echo "- **status**: \`$status\`"
    echo "- **provider/model**: \`${provider:-unknown}/${model:-unknown}\`"
    echo "- **failed step**: \`$first_failed_step\`"
    echo "- **generated at**: \`$(date -u +"%Y-%m-%dT%H:%M:%SZ")\`"
    echo "- **reason**: $reason"
    echo
    if [[ -n "$root_cause" ]]; then
      echo "## Root Cause"
      echo "$root_cause"
      echo
    fi
    if [[ -n "$fix_suggestion" ]]; then
      echo "## Fix Suggestion"
      echo "$fix_suggestion"
      echo
    fi
    if [[ -n "$confidence" ]]; then
      echo "## Confidence"
      echo "\`$confidence\`"
      echo
    fi
    if [[ -n "$relevant_lines_md" ]]; then
      echo "## Relevant Lines"
      echo "$relevant_lines_md"
      echo
    fi
    echo "## Raw Model Output"
    echo '```text'
    if [[ -n "$triage_text" ]]; then printf '%s\n' "$triage_text"; else echo "[empty]"; fi
    echo '```'
    echo
    echo "## Provider Attempts"
    if [[ -f "$ai_triage_attempts_tsv" ]]; then
      echo '```text'
      cat "$ai_triage_attempts_tsv"
      echo '```'
    fi
  } >"$ai_triage_md"

  echo "$ai_triage_json"
}

# ---- Dry run ----
if [[ "$dry_run" == "true" ]]; then
  write_result "dry_run" "dry-run; no model call" "" "" "false"
  exit 0
fi

# ---- Resolve provider sequence ----
if [[ "$provider" == "none" || -z "$provider" ]]; then
  if [[ "$if_configured" == "true" ]]; then
    echo "Triage provider not configured; skipping."
    exit 0
  fi
  echo "Triage provider not configured."; exit 1
fi

provider_csv="$(resolve_provider_sequence "$provider")"
if [[ -z "$provider_csv" ]]; then
  if [[ "$if_configured" == "true" ]]; then
    write_result "skipped" "no provider candidates in fallback chain" "" "" "false"
    exit 0
  fi
  write_result "error" "no provider candidates in fallback chain" "" "" "false"
  exit 1
fi

IFS=',' read -r -a provider_candidates <<<"$provider_csv"
last_failure_reason=""
has_configured_provider="false"
requested_provider="$provider"
requested_model="$model"
requested_base_url="$base_url"
used_model_override="false"
used_base_override="false"

for cand_p in "${provider_candidates[@]}"; do
  cand_p="$(printf '%s' "$cand_p" | xargs)"
  [[ -z "$cand_p" ]] && continue

  # Mock provider: instant success without a network call
  if [[ "$cand_p" == "mock" ]]; then
    provider="mock"
    model="${requested_model:-$(default_model_for_provider mock)}"
    append_attempt "$provider" "$model" "selection" "pass" "mock provider selected"
    mock_json='{"failed_step":"'"$first_failed_step"'","root_cause":"Mock triage: no live model call made.","fix_suggestion":"Set FREUD_TRIAGE_PROVIDER=openai|groq|mistral for real analysis.","confidence":0.1,"relevant_lines":[]}'
    write_result "ok" "mock provider used" "$mock_json" "$mock_json" "true"
    exit 0
  fi

  if ! provider_has_key "$cand_p"; then
    append_attempt "$cand_p" "-" "selection" "skip" "missing API key"
    continue
  fi
  has_configured_provider="true"

  # Resolve model
  cand_model="$(default_model_for_provider "$cand_p")"
  if [[ -n "$requested_model" ]]; then
    if [[ "$requested_provider" == "$cand_p" ]] || \
       [[ "$requested_provider" == "auto" && "$used_model_override" == "false" ]]; then
      cand_model="$requested_model"
      used_model_override="true"
    fi
  fi

  # Resolve base URL
  cand_base="$(default_base_url_for_provider "$cand_p")"
  if [[ -n "$requested_base_url" ]]; then
    if [[ "$requested_provider" == "$cand_p" ]] || \
       [[ "$requested_provider" == "auto" && "$used_base_override" == "false" ]]; then
      cand_base="$requested_base_url"
      used_base_override="true"
    fi
  fi

  cand_key="$(api_key_for_provider "$cand_p")"
  if [[ -z "$cand_key" ]]; then
    append_attempt "$cand_p" "$cand_model" "selection" "skip" "resolved empty API key"
    continue
  fi

  if ! healthcheck_provider "$cand_p" "$cand_base" "$cand_key"; then
    append_attempt "$cand_p" "$cand_model" "healthcheck" "fail" "provider unavailable or timeout"
    last_failure_reason="healthcheck failed for provider=$cand_p"
    continue
  fi
  append_attempt "$cand_p" "$cand_model" "healthcheck" "pass" "provider reachable"

  payload="$(jq -n \
    --arg model "$cand_model" \
    --arg system "You are a CI triage assistant. Output strict JSON only, with no markdown fences." \
    --arg user "$(cat "$ai_triage_prompt")" \
    --argjson temperature 0.1 \
    --argjson max_tokens "$max_output_tokens" \
    '{
      model: $model,
      temperature: $temperature,
      max_tokens: $max_tokens,
      messages: [
        {role: "system", content: $system},
        {role: "user", content: $user}
      ]
    }')"

  curl_exit=0
  http_code="$(
    curl -sS --connect-timeout 10 --max-time "$timeout_sec" \
      -o "$ai_triage_response_json" -w "%{http_code}" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $cand_key" \
      -d "$payload" "$cand_base"
  )" || curl_exit=$?

  if [[ $curl_exit -ne 0 ]]; then
    append_attempt "$cand_p" "$cand_model" "completion" "fail" "curl request failed"
    last_failure_reason="curl request failed for provider=$cand_p"
    continue
  fi

  if [[ ! "$http_code" =~ ^2 ]]; then
    err_text="$(jq -r '.error.message // .error // "request failed"' "$ai_triage_response_json" 2>/dev/null || echo "request failed")"
    append_attempt "$cand_p" "$cand_model" "completion" "fail" "HTTP $http_code: $err_text"
    last_failure_reason="HTTP $http_code from provider=$cand_p"
    continue
  fi

  provider="$cand_p"
  model="$cand_model"

  model_text="$(jq -r '.choices[0].message.content // empty' "$ai_triage_response_json" 2>/dev/null || true)"
  if [[ -z "$model_text" ]]; then
    model_text="$(jq -r '.choices[0].message.content[0].text // empty' "$ai_triage_response_json" 2>/dev/null || true)"
  fi

  if [[ -z "$model_text" ]]; then
    append_attempt "$provider" "$model" "completion" "fail" "missing text content in response"
    last_failure_reason="empty model response from provider=$provider"
    continue
  fi

  # Parse JSON from model output
  clean_text="$(printf '%s\n' "$model_text" | sed -E 's/^```json[[:space:]]*$//; s/^```[[:space:]]*$//')"
  parsed_ok="false"
  parsed_json=""
  if printf '%s\n' "$clean_text" | jq -e . >/dev/null 2>&1; then
    parsed_json="$(printf '%s\n' "$clean_text" | jq -c .)"
    parsed_ok="true"
  else
    one_line="$(printf '%s' "$clean_text" | tr '\n' ' ')"
    extracted="$(printf '%s' "$one_line" | sed -n 's/^[^{]*\({.*}\)[^}]*$/\1/p')"
    if [[ -n "$extracted" ]] && printf '%s\n' "$extracted" | jq -e . >/dev/null 2>&1; then
      parsed_json="$(printf '%s\n' "$extracted" | jq -c .)"
      parsed_ok="true"
    fi
  fi

  append_attempt "$provider" "$model" "completion" "pass" "triage generated"
  if [[ "$parsed_ok" == "true" ]]; then
    write_result "ok" "triage generated" "$model_text" "$parsed_json" "true"
  else
    write_result "ok" "triage generated (non-JSON response)" "$model_text" "" "false"
  fi
  exit 0
done

# All candidates exhausted
if [[ "$has_configured_provider" != "true" && "$if_configured" == "true" ]]; then
  echo "No configured triage provider key available; skipping."
  exit 0
fi
[[ -z "$last_failure_reason" ]] && last_failure_reason="all provider attempts failed"
write_result "error" "$last_failure_reason" "" "" "false"
exit 1
