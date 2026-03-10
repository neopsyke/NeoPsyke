#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  freud/scripts/prompt-budget-telemetry.sh [events_jsonl]

Defaults:
  events_jsonl=.psyke/logs/latest-events.jsonl

Description:
  Aggregates prompt budget allocator telemetry from instrumentation JSONL events
  and prints fallback/degradation indicators for tuning.
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

allocations_json="$(jq -s '[.[] | select(.type == "prompt_budget_allocation")]' "$input_path")"
total="$(jq 'length' <<<"$allocations_json")"

if [[ "$total" -eq 0 ]]; then
  echo "No prompt_budget_allocation events found in: $input_path"
  exit 0
fi

fallback_count="$(jq '[.[] | select(.data.single_message_fallback == true)] | length' <<<"$allocations_json")"
floor_violation_events="$(jq '[.[] | select((.data.floor_violation_count // 0) > 0)] | length' <<<"$allocations_json")"
dropped_sections_total="$(jq '[.[] | (.data.dropped_section_count // 0)] | add // 0' <<<"$allocations_json")"
allocated_total_cost_avg="$(jq '[.[] | (.data.allocated_total_cost // 0)] | (add / length)' <<<"$allocations_json")"
reserved_floor_cost_avg="$(jq '[.[] | (.data.reserved_floor_cost // 0)] | (add / length)' <<<"$allocations_json")"

pct() {
  local num="$1"
  local den="$2"
  if [[ "$den" -eq 0 ]]; then
    printf '0.00'
    return
  fi
  awk -v n="$num" -v d="$den" 'BEGIN { printf "%.2f", (n * 100.0) / d }'
}

echo "Prompt Budget Telemetry"
echo "source: $input_path"
echo ""
echo "Totals"
echo "- allocations: $total"
echo "- single_message_fallback: $fallback_count ($(pct "$fallback_count" "$total")%)"
echo "- floor_violation_events: $floor_violation_events ($(pct "$floor_violation_events" "$total")%)"
echo "- dropped_sections_total: $dropped_sections_total"
echo "- avg_allocated_total_cost: $(printf '%.2f' "$allocated_total_cost_avg")"
echo "- avg_reserved_floor_cost: $(printf '%.2f' "$reserved_floor_cost_avg")"
echo ""

echo "Breakdown by call_site"
jq -r '
  group_by(.data.call_site // "unknown")
  | map({key: (.[0].data.call_site // "unknown"), value: length})
  | sort_by(-.value, .key)
  | .[]
  | "- \(.key): \(.value)"
' <<<"$allocations_json"
echo ""

echo "Breakdown by degradation_path"
jq -r '
  group_by(.data.degradation_path // "none")
  | map({key: (.[0].data.degradation_path // "none"), value: length})
  | sort_by(-.value, .key)
  | .[]
  | "- \(.key): \(.value)"
' <<<"$allocations_json"
echo ""

echo "Fallback rate by call_site"
jq -r '
  group_by(.data.call_site // "unknown")
  | map({
      key: (.[0].data.call_site // "unknown"),
      total: length,
      fallback: ([.[] | select(.data.single_message_fallback == true)] | length),
      floor_violations: ([.[] | select((.data.floor_violation_count // 0) > 0)] | length)
    })
  | sort_by(-.total, .key)
  | .[]
  | "- \(.key): total=\(.total), fallback=\(.fallback), floor_violations=\(.floor_violations)"
' <<<"$allocations_json"

echo ""
echo "Tuning Hints"
if [[ "$fallback_count" -gt 0 ]]; then
  echo "- single-message fallback occurred: reduce required floors or increase max prompt budget for affected call sites."
fi
if [[ "$floor_violation_events" -gt 0 ]]; then
  echo "- floor violations occurred: required floor reservation exceeds budget in some prompts; inspect degradation_path and band usage."
fi
if [[ "$dropped_sections_total" -gt 0 ]]; then
  echo "- sections were dropped: verify optional/context blocks are ordered and banded by true criticality."
fi
if [[ "$fallback_count" -eq 0 && "$floor_violation_events" -eq 0 ]]; then
  echo "- no severe prompt pressure observed in this sample."
fi
