#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  freud/scripts/task-verifier-telemetry.sh [events_jsonl]

Defaults:
  events_jsonl=.psyke/logs/latest-events.jsonl

Description:
  Aggregates TaskVerifier telemetry from instrumentation JSONL events and prints
  tuning-oriented counters for intent/volatility and reason codes.
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
input_path="${1:-$repo_root/.psyke/logs/latest-events.jsonl}"

if [[ ! -f "$input_path" ]]; then
  echo "Event log not found: $input_path"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required."
  exit 1
fi

reviews_json="$(jq -s '[.[] | select(.type == "task_verifier_review")]' "$input_path")"
total="$(jq 'length' <<<"$reviews_json")"

if [[ "$total" -eq 0 ]]; then
  echo "No task_verifier_review events found in: $input_path"
  exit 0
fi

allow_count="$(jq '[.[] | select(.data.allow == true)] | length' <<<"$reviews_json")"
deny_count="$(jq '[.[] | select(.data.allow == false)] | length' <<<"$reviews_json")"
requires_count="$(jq '[.[] | select(.data.requires_external_evidence == true)] | length' <<<"$reviews_json")"
graceful_count="$(jq '[.[] | select(.data.allow == true and .data.reason_code == "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL")] | length' <<<"$reviews_json")"
unknown_intent_count="$(jq '[.[] | select((.data.intent_category // "") == "unknown")] | length' <<<"$reviews_json")"
volatile_count="$(jq '[.[] | select((.data.intent_category // "") == "volatile_fact")] | length' <<<"$reviews_json")"
volatile_deny_count="$(jq '[.[] | select((.data.intent_category // "") == "volatile_fact" and .data.allow == false)] | length' <<<"$reviews_json")"

pct() {
  local num="$1"
  local den="$2"
  if [[ "$den" -eq 0 ]]; then
    printf '0.00'
    return
  fi
  awk -v n="$num" -v d="$den" 'BEGIN { printf "%.2f", (n * 100.0) / d }'
}

echo "Task Verifier Telemetry"
echo "source: $input_path"
echo ""
echo "Totals"
echo "- reviews: $total"
echo "- allow: $allow_count ($(pct "$allow_count" "$total")%)"
echo "- deny: $deny_count ($(pct "$deny_count" "$total")%)"
echo "- requires_external_evidence: $requires_count ($(pct "$requires_count" "$total")%)"
echo "- graceful_allows: $graceful_count ($(pct "$graceful_count" "$total")%)"
echo "- unknown_intent: $unknown_intent_count ($(pct "$unknown_intent_count" "$total")%)"
echo "- volatile_intent: $volatile_count ($(pct "$volatile_count" "$total")%)"
echo "- volatile_denies: $volatile_deny_count"
echo ""

echo "Breakdown by reason_code"
jq -r '
  group_by(.data.reason_code // "none")
  | map({key: (.[0].data.reason_code // "none"), value: length})
  | sort_by(-.value, .key)
  | .[]
  | "- \(.key): \(.value)"
' <<<"$reviews_json"
echo ""

echo "Breakdown by intent_category"
jq -r '
  group_by(.data.intent_category // "none")
  | map({key: (.[0].data.intent_category // "none"), value: length})
  | sort_by(-.value, .key)
  | .[]
  | "- \(.key): \(.value)"
' <<<"$reviews_json"
echo ""

echo "Breakdown by volatility_level"
jq -r '
  group_by(.data.volatility_level // "none")
  | map({key: (.[0].data.volatility_level // "none"), value: length})
  | sort_by(-.value, .key)
  | .[]
  | "- \(.key): \(.value)"
' <<<"$reviews_json"
echo ""

echo "Tuning Hints"
if [[ "$unknown_intent_count" -gt 0 ]]; then
  echo "- unknown intent observed: review prompts and add deterministic intent rules before lowering volatility thresholds."
fi
if [[ "$graceful_count" -gt 0 ]]; then
  echo "- graceful allows occurred: inspect action capability health to reduce under-verified volatile answers."
fi
if [[ "$volatile_count" -gt 0 ]]; then
  volatile_deny_rate="$(pct "$volatile_deny_count" "$volatile_count")"
  echo "- volatile deny rate: ${volatile_deny_rate}% (target depends on tool availability and product posture)."
fi
if [[ "$deny_count" -eq 0 ]]; then
  echo "- no denies recorded: verify volatile scenarios are still covered by tests/evals."
fi
