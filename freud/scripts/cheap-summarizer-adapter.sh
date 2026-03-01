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
usage_ledger_snapshot_json="$metrics_dir/summarizer-usage.json"
usage_ledger_json="$usage_ledger_snapshot_json"
summarizer_run_id="$(basename "$run_dir")"

expand_path() {
  local raw="$1"
  if [[ "$raw" == "~" ]]; then
    printf '%s' "$HOME"
    return
  fi
  if [[ "$raw" == ~/* ]]; then
    printf '%s/%s' "$HOME" "${raw#~/}"
    return
  fi
  if [[ "$raw" = /* ]]; then
    printf '%s' "$raw"
    return
  fi
  printf '%s/%s' "$repo_root" "$raw"
}

resolve_usage_ledger_db() {
  local configured="${PSYKE_METRICS_DB:-}"
  if [[ -z "$configured" ]]; then
    local defaults_file="${PSYKE_RUNTIME_DEFAULTS_FILE:-$repo_root/.psyke/runtime-defaults.yaml}"
    if [[ -f "$defaults_file" ]]; then
      configured="$(awk -F':' '/^[[:space:]]*metrics_db[[:space:]]*:/ {sub(/^[[:space:]]*/, "", $2); print $2; exit}' "$defaults_file" \
        | sed -E 's/^[[:space:]]+|[[:space:]]+$//g' \
        | sed -E "s/^[\"']|[\"']$//g")"
    fi
  fi
  if [[ -z "$configured" ]]; then
    configured=".psyke/metrics.db"
  fi
  expand_path "$configured"
}

usage_ledger_db="$(resolve_usage_ledger_db)"
usage_store_backend="json"
if command -v sqlite3 >/dev/null 2>&1; then
  usage_store_backend="sqlite"
fi

mkdir -p "$metrics_dir"
mkdir -p "$(dirname "$usage_ledger_db")"

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
model_summary_debug_log="$artifact_dir/model-summary-debug.log"

printf "attempt\tprovider\tmodel\tphase\tstatus\tdetail\n" >"$model_attempts_tsv"
: >"$model_summary_debug_log"

ledger_prompt_before=0
ledger_completion_before=0
ledger_runs_before=0
usage_cumulative_before=0
usage_cumulative_after=0
usage_limit_reached="false"
usage_warning=""
ledger_token_limit_reached_at=""
ledger_last_updated_at=""
ledger_last_run_dir=""
ledger_last_provider=""
ledger_last_model=""
ledger_last_api_key_source=""
usage_scope_id="bootstrap:none:none"
usage_scope_provider="bootstrap"
usage_scope_api_key_source=""
usage_scope_api_key_fingerprint="none"
usage_scope_safe="bootstrap-none-none"
usage_ledger_store_json="$metrics_dir/summarizer-usage-${usage_scope_safe}.json"
api_key_source=""
healthcheck_last_http_code=""
healthcheck_last_curl_exit=0
healthcheck_last_models_url=""
sqlite_field_sep=$'\x1f'

debug_log() {
  local msg="$1"
  local ts
  ts="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  printf '[%s] %s\n' "$ts" "$msg" >>"$model_summary_debug_log"
  printf '[%s] %s\n' "$ts" "$msg" >&2
}

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

sql_quote() {
  local value="${1:-}"
  value="${value//\'/''}"
  printf "'%s'" "$value"
}

hash_string() {
  local value="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$value" | sha256sum | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$value" | shasum -a 256 | awk '{print $1}'
    return
  fi
  if command -v openssl >/dev/null 2>&1; then
    printf '%s' "$value" | openssl dgst -sha256 | awk '{print $NF}'
    return
  fi
  printf '%s' "$value" | cksum | awk '{print $1}'
}

scope_safe_component() {
  local value="$1"
  if [[ -z "$value" ]]; then
    printf 'none'
    return
  fi
  printf '%s' "$value" | sed -E 's/[^a-zA-Z0-9._-]+/-/g; s/^-+//; s/-+$//'
}

set_usage_scope() {
  local scope_provider="$1"
  local scope_key_source="$2"
  local scope_key_value="${3:-}"
  local scope_key_fingerprint="none"
  if [[ -n "$scope_key_value" ]]; then
    scope_key_fingerprint="$(hash_string "$scope_key_value" | cut -c1-16)"
  fi

  usage_scope_provider="${scope_provider:-unknown}"
  usage_scope_api_key_source="${scope_key_source:-}"
  usage_scope_api_key_fingerprint="$scope_key_fingerprint"
  usage_scope_id="${usage_scope_provider}:${usage_scope_api_key_source:-none}:${usage_scope_api_key_fingerprint}"
  usage_scope_safe="$(scope_safe_component "${usage_scope_provider}")-$(scope_safe_component "${usage_scope_api_key_source:-none}")-$(scope_safe_component "$usage_scope_api_key_fingerprint")"
  usage_ledger_store_json="$metrics_dir/summarizer-usage-${usage_scope_safe}.json"
}

sqlite_fallback_to_json() {
  local phase="$1"
  local detail="${2:-}"
  detail="$(printf '%s' "$detail" | tr '\n' ' ' | tr '\t' ' ' | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"
  if [[ "$usage_store_backend" == "sqlite" ]]; then
    usage_store_backend="json"
    if [[ -n "$detail" ]]; then
      debug_log "usage_ledger_backend_fallback phase=$phase detail=$detail"
    else
      debug_log "usage_ledger_backend_fallback phase=$phase"
    fi
  fi
}

sqlite_exec_stmt() {
  local phase="$1"
  local sql="$2"
  local output rc
  set +e
  output="$(sqlite3 "$usage_ledger_db" "$sql" 2>&1)"
  rc=$?
  set -e
  if (( rc != 0 )); then
    sqlite_fallback_to_json "$phase" "sqlite3_exit=$rc sql_error=$output"
    return 1
  fi
  return 0
}

sqlite_exec_script() {
  local phase="$1"
  local sql_script="$2"
  local output rc
  set +e
  output="$(printf '%s\n' "$sql_script" | sqlite3 "$usage_ledger_db" 2>&1)"
  rc=$?
  set -e
  if (( rc != 0 )); then
    sqlite_fallback_to_json "$phase" "sqlite3_exit=$rc sql_error=$output"
    return 1
  fi
  return 0
}

sqlite_query_stmt() {
  local phase="$1"
  local sql="$2"
  local output rc
  set +e
  output="$(sqlite3 -noheader -separator "$sqlite_field_sep" "$usage_ledger_db" "$sql" 2>&1)"
  rc=$?
  set -e
  if (( rc != 0 )); then
    sqlite_fallback_to_json "$phase" "sqlite3_exit=$rc sql_error=$output"
    return 1
  fi
  printf '%s' "$output"
  return 0
}

write_usage_ledger_snapshot_json() {
  local tmp
  tmp="$(mktemp)"
  jq -n \
    --arg backend "$usage_store_backend" \
    --arg scope_id "$usage_scope_id" \
    --arg scope_provider "$usage_scope_provider" \
    --arg scope_key_source "${usage_scope_api_key_source:-}" \
    --arg scope_key_fingerprint "$usage_scope_api_key_fingerprint" \
    --arg updated_at "$ledger_last_updated_at" \
    --arg run_dir "$ledger_last_run_dir" \
    --arg provider "$ledger_last_provider" \
    --arg model "$ledger_last_model" \
    --arg api_key_source "$ledger_last_api_key_source" \
    --arg reached_at "$ledger_token_limit_reached_at" \
    --arg ledger_path "$usage_ledger_store_json" \
    --arg snapshot_path "$usage_ledger_snapshot_json" \
    --arg db_path "$usage_ledger_db" \
    --argjson token_limit "$token_limit" \
    --argjson total_prompt_tokens "$ledger_prompt_before" \
    --argjson total_completion_tokens "$ledger_completion_before" \
    --argjson total_tokens "$usage_cumulative_before" \
    --argjson run_count "$ledger_runs_before" \
    --argjson limit_reached "$([[ "$usage_limit_reached" == "true" ]] && echo true || echo false)" \
    '{
      workflow: "freud",
      ledger: "summarizer_usage",
      backend: $backend,
      scope: {
        id: $scope_id,
        provider: $scope_provider,
        api_key_source: $scope_key_source,
        api_key_fingerprint: $scope_key_fingerprint
      },
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
      last_api_key_source: $api_key_source,
      ledger_path: $ledger_path,
      snapshot_path: $snapshot_path,
      sqlite_db_path: $db_path
    }' >"$tmp"
  mv "$tmp" "$usage_ledger_store_json"
  cp "$usage_ledger_store_json" "$usage_ledger_snapshot_json" 2>/dev/null || cat "$usage_ledger_store_json" >"$usage_ledger_snapshot_json"
}

init_json_usage_ledger() {
  if [[ -f "$usage_ledger_store_json" ]]; then
    return 0
  fi
  jq -n \
    --arg updated_at "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
    --arg scope_id "$usage_scope_id" \
    --arg scope_provider "$usage_scope_provider" \
    --arg scope_key_source "${usage_scope_api_key_source:-}" \
    --arg scope_key_fingerprint "$usage_scope_api_key_fingerprint" \
    --arg ledger_path "$usage_ledger_store_json" \
    --arg snapshot_path "$usage_ledger_snapshot_json" \
    --arg db_path "$usage_ledger_db" \
    --argjson token_limit "$token_limit" \
    '{
      workflow: "freud",
      ledger: "summarizer_usage",
      backend: "json",
      scope: {
        id: $scope_id,
        provider: $scope_provider,
        api_key_source: $scope_key_source,
        api_key_fingerprint: $scope_key_fingerprint
      },
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
      last_api_key_source: "",
      ledger_path: $ledger_path,
      snapshot_path: $snapshot_path,
      sqlite_db_path: $db_path
    }' >"$usage_ledger_store_json"
  cp "$usage_ledger_store_json" "$usage_ledger_snapshot_json" 2>/dev/null || cat "$usage_ledger_store_json" >"$usage_ledger_snapshot_json"
}

ensure_usage_ledger() {
  if [[ "$usage_store_backend" != "sqlite" ]]; then
    init_json_usage_ledger
    return 0
  fi

  local schema_sql
  schema_sql="$(cat <<'SQL'
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS freud_summarizer_budget_scopes (
  scope_id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  api_key_source TEXT NOT NULL,
  api_key_fingerprint TEXT NOT NULL,
  token_limit INTEGER NOT NULL,
  total_prompt_tokens INTEGER NOT NULL DEFAULT 0,
  total_completion_tokens INTEGER NOT NULL DEFAULT 0,
  total_tokens INTEGER NOT NULL DEFAULT 0,
  run_count INTEGER NOT NULL DEFAULT 0,
  token_limit_reached INTEGER NOT NULL DEFAULT 0,
  token_limit_reached_at TEXT NOT NULL DEFAULT '',
  last_updated_at TEXT NOT NULL DEFAULT '',
  last_run_dir TEXT NOT NULL DEFAULT '',
  last_provider TEXT NOT NULL DEFAULT '',
  last_model TEXT NOT NULL DEFAULT '',
  last_api_key_source TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_freud_summarizer_budget_scope_provider
  ON freud_summarizer_budget_scopes(provider, api_key_source, api_key_fingerprint);
CREATE TABLE IF NOT EXISTS freud_summarizer_runs (
  run_id TEXT PRIMARY KEY,
  run_dir TEXT NOT NULL,
  status TEXT NOT NULL,
  reason TEXT NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  api_key_source TEXT NOT NULL,
  prompt_tokens INTEGER NOT NULL DEFAULT 0,
  completion_tokens INTEGER NOT NULL DEFAULT 0,
  total_tokens INTEGER NOT NULL DEFAULT 0,
  token_limit INTEGER NOT NULL,
  cumulative_before INTEGER NOT NULL DEFAULT 0,
  cumulative_after INTEGER NOT NULL DEFAULT 0,
  recorded_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS freud_summarizer_attempts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT NOT NULL,
  attempt_seq INTEGER NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  phase TEXT NOT NULL,
  status TEXT NOT NULL,
  detail TEXT NOT NULL,
  ts TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_freud_summarizer_attempts_run ON freud_summarizer_attempts(run_id, attempt_seq);
SQL
)"
  if ! sqlite_exec_script "ensure_schema" "$schema_sql"; then
    init_json_usage_ledger
    return 0
  fi

  local existing
  existing="$(sqlite_query_stmt "load_budget_row_count" "SELECT COUNT(*) FROM freud_summarizer_budget_scopes WHERE scope_id = $(sql_quote "$usage_scope_id");")" || existing=""
  if [[ "$usage_store_backend" != "sqlite" ]]; then
    init_json_usage_ledger
    return 0
  fi
  existing="${existing:-0}"
  if [[ "$existing" == "0" ]]; then
    local seed_prompt=0 seed_completion=0 seed_total=0 seed_runs=0 seed_reached=0 seed_reached_at="" seed_last_updated="" seed_last_run="" seed_last_provider="" seed_last_model="" seed_last_key=""
    if [[ -f "$usage_ledger_store_json" ]]; then
      seed_prompt="$(jq -r '.total_prompt_tokens // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
      seed_completion="$(jq -r '.total_completion_tokens // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
      seed_total="$(jq -r '.total_tokens // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
      seed_runs="$(jq -r '.run_count // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
      if [[ "$(jq -r '.token_limit_reached // false' "$usage_ledger_store_json" 2>/dev/null || echo false)" == "true" ]]; then
        seed_reached=1
      fi
      seed_reached_at="$(jq -r '.token_limit_reached_at // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
      seed_last_updated="$(jq -r '.last_updated_at // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
      seed_last_run="$(jq -r '.last_run_dir // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
      seed_last_provider="$(jq -r '.last_provider // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
      seed_last_model="$(jq -r '.last_model // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
      seed_last_key="$(jq -r '.last_api_key_source // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
    fi
    if ! sqlite_exec_stmt "seed_budget" "INSERT INTO freud_summarizer_budget_scopes(scope_id, provider, api_key_source, api_key_fingerprint, token_limit, total_prompt_tokens, total_completion_tokens, total_tokens, run_count, token_limit_reached, token_limit_reached_at, last_updated_at, last_run_dir, last_provider, last_model, last_api_key_source) VALUES ($(sql_quote "$usage_scope_id"), $(sql_quote "$usage_scope_provider"), $(sql_quote "${usage_scope_api_key_source:-}"), $(sql_quote "$usage_scope_api_key_fingerprint"), $token_limit, ${seed_prompt:-0}, ${seed_completion:-0}, ${seed_total:-0}, ${seed_runs:-0}, ${seed_reached:-0}, $(sql_quote "$seed_reached_at"), $(sql_quote "$seed_last_updated"), $(sql_quote "$seed_last_run"), $(sql_quote "$seed_last_provider"), $(sql_quote "$seed_last_model"), $(sql_quote "$seed_last_key"));"; then
      init_json_usage_ledger
      return 0
    fi
  else
    if ! sqlite_exec_stmt "sync_token_limit" "UPDATE freud_summarizer_budget_scopes SET token_limit = $token_limit WHERE scope_id = $(sql_quote "$usage_scope_id");"; then
      init_json_usage_ledger
      return 0
    fi
  fi
}

load_usage_ledger() {
  ensure_usage_ledger
  if [[ "$usage_store_backend" == "sqlite" ]]; then
    local row
    row="$(sqlite_query_stmt "load_budget_row" "SELECT total_prompt_tokens, total_completion_tokens, run_count, total_tokens, token_limit_reached, COALESCE(token_limit_reached_at, ''), COALESCE(last_updated_at, ''), COALESCE(last_run_dir, ''), COALESCE(last_provider, ''), COALESCE(last_model, ''), COALESCE(last_api_key_source, '') FROM freud_summarizer_budget_scopes WHERE scope_id = $(sql_quote "$usage_scope_id");")" || row=""
    if [[ -z "$row" ]]; then
      usage_store_backend="json"
      init_json_usage_ledger
    else
      IFS="$sqlite_field_sep" read -r ledger_prompt_before ledger_completion_before ledger_runs_before usage_cumulative_before reached_int ledger_token_limit_reached_at ledger_last_updated_at ledger_last_run_dir ledger_last_provider ledger_last_model ledger_last_api_key_source <<<"$row"
      if [[ "${reached_int:-0}" == "1" ]]; then
        usage_limit_reached="true"
      else
        usage_limit_reached="false"
      fi
      usage_cumulative_after="$usage_cumulative_before"
      write_usage_ledger_snapshot_json
      return
    fi
  fi

  init_json_usage_ledger
  ledger_prompt_before="$(jq -r '.total_prompt_tokens // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
  ledger_completion_before="$(jq -r '.total_completion_tokens // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
  ledger_runs_before="$(jq -r '.run_count // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
  usage_cumulative_before="$(jq -r '.total_tokens // 0' "$usage_ledger_store_json" 2>/dev/null || echo "0")"
  if [[ "$(jq -r '.token_limit_reached // false' "$usage_ledger_store_json" 2>/dev/null || echo false)" == "true" ]]; then
    usage_limit_reached="true"
  else
    usage_limit_reached="false"
  fi
  ledger_token_limit_reached_at="$(jq -r '.token_limit_reached_at // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
  ledger_last_updated_at="$(jq -r '.last_updated_at // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
  ledger_last_run_dir="$(jq -r '.last_run_dir // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
  ledger_last_provider="$(jq -r '.last_provider // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
  ledger_last_model="$(jq -r '.last_model // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
  ledger_last_api_key_source="$(jq -r '.last_api_key_source // ""' "$usage_ledger_store_json" 2>/dev/null || echo "")"
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

  local reached_at updated_at
  reached_at="$ledger_token_limit_reached_at"
  if [[ "$usage_limit_reached" == "true" && -z "$reached_at" ]]; then
    reached_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  fi
  updated_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  if [[ "$usage_store_backend" == "sqlite" ]]; then
    local update_sql
    update_sql="$(cat <<SQL
BEGIN IMMEDIATE;
UPDATE freud_summarizer_budget_scopes
SET token_limit = $token_limit,
    provider = $(sql_quote "$usage_scope_provider"),
    api_key_source = $(sql_quote "${usage_scope_api_key_source:-}"),
    api_key_fingerprint = $(sql_quote "$usage_scope_api_key_fingerprint"),
    total_prompt_tokens = $prompt_after,
    total_completion_tokens = $completion_after,
    total_tokens = $total_after,
    run_count = $runs_after,
    token_limit_reached = $([[ "$usage_limit_reached" == "true" ]] && echo 1 || echo 0),
    token_limit_reached_at = $(sql_quote "$reached_at"),
    last_updated_at = $(sql_quote "$updated_at"),
    last_run_dir = $(sql_quote "$run_dir"),
    last_provider = $(sql_quote "$provider"),
    last_model = $(sql_quote "$model"),
    last_api_key_source = $(sql_quote "$api_key_source")
WHERE scope_id = $(sql_quote "$usage_scope_id");
COMMIT;
SQL
)"
    if sqlite_exec_script "update_budget" "$update_sql"; then
      ledger_prompt_before="$prompt_after"
      ledger_completion_before="$completion_after"
      ledger_runs_before="$runs_after"
      usage_cumulative_before="$total_after"
      ledger_token_limit_reached_at="$reached_at"
      ledger_last_updated_at="$updated_at"
      ledger_last_run_dir="$run_dir"
      ledger_last_provider="$provider"
      ledger_last_model="$model"
      ledger_last_api_key_source="$api_key_source"
      write_usage_ledger_snapshot_json
      return
    fi
    init_json_usage_ledger
  fi

  local tmp
  tmp="$(mktemp)"
  jq -n \
    --arg scope_id "$usage_scope_id" \
    --arg scope_provider "$usage_scope_provider" \
    --arg scope_key_source "${usage_scope_api_key_source:-}" \
    --arg scope_key_fingerprint "$usage_scope_api_key_fingerprint" \
    --arg updated_at "$updated_at" \
    --arg run_dir "$run_dir" \
    --arg provider "$provider" \
    --arg model "$model" \
    --arg api_key_source "$api_key_source" \
    --arg reached_at "$reached_at" \
    --arg ledger_path "$usage_ledger_store_json" \
    --arg snapshot_path "$usage_ledger_snapshot_json" \
    --arg db_path "$usage_ledger_db" \
    --argjson token_limit "$token_limit" \
    --argjson total_prompt_tokens "$prompt_after" \
    --argjson total_completion_tokens "$completion_after" \
    --argjson total_tokens "$total_after" \
    --argjson run_count "$runs_after" \
    --argjson limit_reached "$([[ "$usage_limit_reached" == "true" ]] && echo true || echo false)" \
    '{
      workflow: "freud",
      ledger: "summarizer_usage",
      backend: "json",
      scope: {
        id: $scope_id,
        provider: $scope_provider,
        api_key_source: $scope_key_source,
        api_key_fingerprint: $scope_key_fingerprint
      },
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
      last_api_key_source: $api_key_source,
      ledger_path: $ledger_path,
      snapshot_path: $snapshot_path,
      sqlite_db_path: $db_path
    }' >"$tmp"
  mv "$tmp" "$usage_ledger_store_json"
  cp "$usage_ledger_store_json" "$usage_ledger_snapshot_json" 2>/dev/null || cat "$usage_ledger_store_json" >"$usage_ledger_snapshot_json"
  ledger_prompt_before="$prompt_after"
  ledger_completion_before="$completion_after"
  ledger_runs_before="$runs_after"
  usage_cumulative_before="$total_after"
  ledger_token_limit_reached_at="$reached_at"
  ledger_last_updated_at="$updated_at"
  ledger_last_run_dir="$run_dir"
  ledger_last_provider="$provider"
  ledger_last_model="$model"
  ledger_last_api_key_source="$api_key_source"
}

persist_usage_run_snapshot() {
  local status="$1"
  local reason="$2"
  local prompt_tokens="${3:-0}"
  local completion_tokens="${4:-0}"
  local total_tokens="${5:-0}"
  if [[ "$usage_store_backend" != "sqlite" ]]; then
    return
  fi
  local now_iso
  now_iso="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  sqlite_exec_stmt "persist_run_snapshot" \
    "INSERT INTO freud_summarizer_runs(run_id, run_dir, status, reason, provider, model, api_key_source, prompt_tokens, completion_tokens, total_tokens, token_limit, cumulative_before, cumulative_after, recorded_at) VALUES ($(sql_quote "$summarizer_run_id"), $(sql_quote "$run_dir"), $(sql_quote "$status"), $(sql_quote "$reason"), $(sql_quote "$provider"), $(sql_quote "$model"), $(sql_quote "$api_key_source"), ${prompt_tokens:-0}, ${completion_tokens:-0}, ${total_tokens:-0}, $token_limit, ${usage_cumulative_before:-0}, ${usage_cumulative_after:-0}, $(sql_quote "$now_iso")) ON CONFLICT(run_id) DO UPDATE SET run_dir=excluded.run_dir, status=excluded.status, reason=excluded.reason, provider=excluded.provider, model=excluded.model, api_key_source=excluded.api_key_source, prompt_tokens=excluded.prompt_tokens, completion_tokens=excluded.completion_tokens, total_tokens=excluded.total_tokens, token_limit=excluded.token_limit, cumulative_before=excluded.cumulative_before, cumulative_after=excluded.cumulative_after, recorded_at=excluded.recorded_at;" >/dev/null || true
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
      --arg backend "$usage_store_backend" \
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
      --arg usage_scope_ledger "$usage_ledger_store_json" \
      --arg usage_db "$usage_ledger_db" \
      --arg debug_log "$model_summary_debug_log" \
      --arg api_key_source "$api_key_source" \
      --arg scope_id "$usage_scope_id" \
      --arg scope_provider "$usage_scope_provider" \
      --arg scope_key_source "${usage_scope_api_key_source:-}" \
      --arg scope_key_fingerprint "$usage_scope_api_key_fingerprint" \
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
        storage_backend: $backend,
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
          usage_ledger_json: $usage_ledger,
          usage_scope_ledger_json: $usage_scope_ledger,
          usage_ledger_db: $usage_db,
          debug_log: $debug_log
        },
        auth: {
          api_key_source: $api_key_source
        },
        usage_scope: {
          id: $scope_id,
          provider: $scope_provider,
          api_key_source: $scope_key_source,
          api_key_fingerprint: $scope_key_fingerprint
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
      --arg backend "$usage_store_backend" \
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
      --arg usage_scope_ledger "$usage_ledger_store_json" \
      --arg usage_db "$usage_ledger_db" \
      --arg debug_log "$model_summary_debug_log" \
      --arg api_key_source "$api_key_source" \
      --arg scope_id "$usage_scope_id" \
      --arg scope_provider "$usage_scope_provider" \
      --arg scope_key_source "${usage_scope_api_key_source:-}" \
      --arg scope_key_fingerprint "$usage_scope_api_key_fingerprint" \
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
        storage_backend: $backend,
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
          usage_ledger_json: $usage_ledger,
          usage_scope_ledger_json: $usage_scope_ledger,
          usage_ledger_db: $usage_db,
          debug_log: $debug_log
        },
        auth: {
          api_key_source: $api_key_source
        },
        usage_scope: {
          id: $scope_id,
          provider: $scope_provider,
          api_key_source: $scope_key_source,
          api_key_fingerprint: $scope_key_fingerprint
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
    echo "- \`$usage_ledger_store_json\`"
    echo "- \`$usage_ledger_db\`"
    echo "- \`$model_summary_debug_log\`"
    echo
    echo "## Auth Context"
    echo "- api_key_source: \`${api_key_source:-unknown}\` (redacted)"
    echo "- usage_scope_id: \`${usage_scope_id}\`"
    echo "- usage_scope_fingerprint: \`${usage_scope_api_key_fingerprint}\` (redacted)"
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
    --arg usage_scope_ledger "$usage_ledger_store_json" \
    --arg usage_db "$usage_ledger_db" \
    --arg backend "$usage_store_backend" \
    --arg attempts_tsv "$model_attempts_tsv" \
    --arg debug_log "$model_summary_debug_log" \
    --arg api_key_source "$api_key_source" \
    --arg scope_id "$usage_scope_id" \
    --arg scope_provider "$usage_scope_provider" \
    --arg scope_key_source "${usage_scope_api_key_source:-}" \
    --arg scope_key_fingerprint "$usage_scope_api_key_fingerprint" \
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
      storage_backend: $backend,
      status: $status,
      reason: $reason,
      provider: $provider,
      model: $model,
      generated_at: $generated_at,
      run_dir: $run_dir,
      usage_ledger_json: $usage_ledger,
      usage_scope_ledger_json: $usage_scope_ledger,
      usage_ledger_db: $usage_db,
      attempts_tsv: $attempts_tsv,
      debug_log: $debug_log,
      auth: {
        api_key_source: $api_key_source
      },
      usage_scope: {
        id: $scope_id,
        provider: $scope_provider,
        api_key_source: $scope_key_source,
        api_key_fingerprint: $scope_key_fingerprint
      },
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

  persist_usage_run_snapshot "$status" "$reason" "${prompt_tokens:-0}" "${completion_tokens:-0}" "$total_tokens"
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
  if [[ "$usage_store_backend" == "sqlite" ]]; then
    local now_iso
    now_iso="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    sqlite_exec_stmt "persist_attempt" \
      "INSERT INTO freud_summarizer_attempts(run_id, attempt_seq, provider, model, phase, status, detail, ts) VALUES ($(sql_quote "$summarizer_run_id"), $attempt_seq, $(sql_quote "$p"), $(sql_quote "$m"), $(sql_quote "$phase"), $(sql_quote "$status"), $(sql_quote "$detail"), $(sql_quote "$now_iso"));" >/dev/null || true
  fi
}

api_key_env_name_for_provider() {
  local p="$1"
  case "$p" in
    openai) printf 'OPENAI_API_KEY' ;;
    mistral) printf 'MISTRAL_API_KEY' ;;
    groq) printf 'GROQ_API_KEY' ;;
    *) printf '' ;;
  esac
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

  healthcheck_last_http_code=""
  healthcheck_last_curl_exit=0
  healthcheck_last_models_url=""

  [[ "$p" == "mock" ]] && return 0
  [[ -z "$key" ]] && return 1

  local models_url http_code curl_exit
  if [[ "$url" == */chat/completions ]]; then
    models_url="${url%/chat/completions}/models"
  else
    models_url="${url%/}/models"
  fi
  healthcheck_last_models_url="$models_url"

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
  healthcheck_last_http_code="$http_code"
  healthcheck_last_curl_exit="$curl_exit"

  if [[ $curl_exit -ne 0 ]]; then
    return 1
  fi
  [[ "$http_code" =~ ^2 ]]
}

debug_log "summarizer_start run_dir=$run_dir artifact_dir=$artifact_dir"
debug_log "config provider=$provider model_override=${model:-none} base_url_override=${base_url:-none} fallback_order=$fallback_order token_limit=$token_limit if_configured=$if_configured dry_run=$dry_run"
debug_log "api_key_presence OPENAI_API_KEY=$([[ -n "${OPENAI_API_KEY:-}" ]] && echo true || echo false) MISTRAL_API_KEY=$([[ -n "${MISTRAL_API_KEY:-}" ]] && echo true || echo false) GROQ_API_KEY=$([[ -n "${GROQ_API_KEY:-}" ]] && echo true || echo false)"
debug_log "usage_ledger backend=$usage_store_backend snapshot_json=$usage_ledger_snapshot_json db_path=$usage_ledger_db"

if [[ "$dry_run" == "true" ]]; then
  debug_log "dry_run=true; no provider call"
  write_summary_artifacts "dry_run" "adapter dry-run; no model call" "" "0" "0" "false"
  echo "$model_summary_json"
  exit 0
fi

requested_provider="$provider"
requested_model="$model"
requested_base_url="$base_url"

if [[ "$requested_provider" == "none" || -z "$requested_provider" ]]; then
  debug_log "provider_not_configured requested_provider=$requested_provider"
  if [[ "$if_configured" == "true" ]]; then
    write_skipped "summarizer provider not configured"
    exit 0
  fi
  write_error "summarizer provider not configured" ""
  exit 1
fi

provider_sequence_csv="$(resolve_provider_sequence "$requested_provider")"
debug_log "provider_sequence requested=$requested_provider resolved=${provider_sequence_csv:-none} fallback_enabled=$enable_fallback"
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
budget_skip_count=0
non_budget_failure_count=0
last_budget_skip_reason=""

for candidate_provider in "${provider_candidates[@]}"; do
  candidate_provider="$(printf '%s' "$candidate_provider" | xargs)"
  [[ -z "$candidate_provider" ]] && continue
  debug_log "candidate_start provider=$candidate_provider"

  if [[ "$candidate_provider" == "mock" ]]; then
    set_usage_scope "$candidate_provider" "" ""
    load_usage_ledger
    debug_log "candidate_budget scope=$usage_scope_id cumulative_before=$usage_cumulative_before token_limit=$token_limit runs_before=$ledger_runs_before"
    provider="mock"
    model="${requested_model:-$(default_model_for_provider "$candidate_provider")}"
    api_key_source=""
    debug_log "candidate_mock_selected provider=$provider model=$model"
    append_attempt "$provider" "$model" "selection" "pass" "mock provider selected"
    persist_usage_ledger 0 0 1
    debug_log "usage_ledger_persisted provider=$provider model=$model api_key_source=${api_key_source:-none} cumulative_after=$usage_cumulative_after"
    cat >"$model_parsed_json" <<EOF
{"overview":"Mock Tier-2 summary generated without network call.","root_cause":"Mock mode enabled.","recommended_actions":["Set FREUD_SUMMARIZER_PROVIDER to openai, groq, or mistral for live model summaries."],"evidence_refs":["$summary_json"],"confidence":0.2,"risk_level":"low"}
EOF
    write_summary_artifacts "ok" "mock provider used" "$(cat "$model_parsed_json")" "0" "0" "true"
    echo "$model_summary_json"
    exit 0
  fi

  if ! provider_has_key "$candidate_provider"; then
    debug_log "candidate_skip_missing_key provider=$candidate_provider"
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
    debug_log "candidate_skip_empty_key provider=$candidate_provider"
    append_attempt "$candidate_provider" "$candidate_model" "selection" "skip" "resolved empty API key"
    continue
  fi
  candidate_key_source="$(api_key_env_name_for_provider "$candidate_provider")"
  set_usage_scope "$candidate_provider" "$candidate_key_source" "$candidate_api_key"
  load_usage_ledger
  api_key_source="$candidate_key_source"
  debug_log "candidate_budget scope=$usage_scope_id cumulative_before=$usage_cumulative_before token_limit=$token_limit runs_before=$ledger_runs_before"
  if (( usage_cumulative_before >= token_limit )); then
    usage_limit_reached="true"
    usage_cumulative_after="$usage_cumulative_before"
    usage_warning="summarizer token limit reached for scope=${usage_scope_id} (${usage_cumulative_before}/${token_limit}); skipping provider"
    debug_log "candidate_budget_skip provider=$candidate_provider scope=$usage_scope_id reason=$usage_warning"
    append_attempt "$candidate_provider" "$candidate_model" "budget" "skip" "$usage_warning"
    last_budget_skip_reason="$usage_warning"
    budget_skip_count=$((budget_skip_count + 1))
    continue
  fi
  debug_log "candidate_ready provider=$candidate_provider model=$candidate_model base_url=$candidate_base_url key_source=$(api_key_env_name_for_provider "$candidate_provider")"

  if ! healthcheck_provider "$candidate_provider" "$candidate_base_url" "$candidate_api_key"; then
    debug_log "healthcheck_fail provider=$candidate_provider url=$healthcheck_last_models_url curl_exit=$healthcheck_last_curl_exit http_code=${healthcheck_last_http_code:-none}"
    append_attempt "$candidate_provider" "$candidate_model" "healthcheck" "fail" "provider unavailable or timeout"
    last_failure_reason="healthcheck failed for provider=$candidate_provider"
    non_budget_failure_count=$((non_budget_failure_count + 1))
    continue
  fi
  debug_log "healthcheck_pass provider=$candidate_provider url=$healthcheck_last_models_url http_code=${healthcheck_last_http_code:-unknown}"
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
  debug_log "completion_http provider=$candidate_provider model=$candidate_model url=$candidate_base_url response_file=$candidate_response_json curl_exit=$curl_exit http_code=${http_code:-none}"

  if [[ $curl_exit -ne 0 ]]; then
    append_attempt "$candidate_provider" "$candidate_model" "completion" "fail" "curl request failed"
    last_failure_reason="curl request failed for provider=$candidate_provider"
    non_budget_failure_count=$((non_budget_failure_count + 1))
    continue
  fi

  if [[ ! "$http_code" =~ ^2 ]]; then
    err_text="$(jq -r '.error.message // .error // "request failed"' "$candidate_response_json" 2>/dev/null || echo "request failed")"
    append_attempt "$candidate_provider" "$candidate_model" "completion" "fail" "HTTP $http_code: $err_text"
    last_failure_reason="HTTP $http_code from provider=$candidate_provider"
    non_budget_failure_count=$((non_budget_failure_count + 1))
    continue
  fi

  provider="$candidate_provider"
  model="$candidate_model"
  base_url="$candidate_base_url"
  api_key_source="$(api_key_env_name_for_provider "$candidate_provider")"
  prompt_tokens="$(jq -r '.usage.prompt_tokens // 0' "$candidate_response_json" 2>/dev/null || echo "0")"
  completion_tokens="$(jq -r '.usage.completion_tokens // 0' "$candidate_response_json" 2>/dev/null || echo "0")"
  persist_usage_ledger "$prompt_tokens" "$completion_tokens" 1
  debug_log "usage_ledger_persisted provider=$provider model=$model api_key_source=${api_key_source:-none} prompt_tokens=$prompt_tokens completion_tokens=$completion_tokens cumulative_after=$usage_cumulative_after"
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
    non_budget_failure_count=$((non_budget_failure_count + 1))
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
  debug_log "completion_pass provider=$provider model=$model parsed_ok=$parsed_ok"
  if [[ "$parsed_ok" == "true" ]]; then
    write_summary_artifacts "ok" "model summary generated" "$model_text" "$prompt_tokens" "$completion_tokens" "true"
  else
    write_summary_artifacts "ok" "model summary generated (non-JSON response)" "$model_text" "$prompt_tokens" "$completion_tokens" "false"
  fi
  echo "$model_summary_json"
  exit 0
done

if [[ "$has_configured_provider" != "true" && "$if_configured" == "true" ]]; then
  debug_log "no_configured_provider_in_fallback_chain"
  write_skipped "no configured provider key available in fallback chain"
  exit 0
fi

if (( budget_skip_count > 0 )) && (( non_budget_failure_count == 0 )); then
  usage_warning="${last_budget_skip_reason:-summarizer token limit reached for all configured providers; Tier-2 skipped}"
  debug_log "all_candidates_budget_skipped count=$budget_skip_count reason=$usage_warning"
  if [[ "$if_configured" == "true" ]]; then
    write_skipped "$usage_warning"
    exit 0
  fi
fi

if [[ -z "$last_failure_reason" ]]; then
  last_failure_reason="all provider attempts failed"
fi
debug_log "summarizer_error reason=$last_failure_reason"
write_error "$last_failure_reason" "see $model_attempts_tsv"
exit 1
