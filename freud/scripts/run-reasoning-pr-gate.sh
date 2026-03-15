#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PSYKE_CMD="${FREUD_REASONING_PR_GATE_PSYKE_CMD:-$REPO_ROOT/run-psyke.sh}"

join_by_comma() {
  local first="true"
  local value
  for value in "$@"; do
    if [[ "$first" == "true" ]]; then
      printf '%s' "$value"
      first="false"
    else
      printf ',%s' "$value"
    fi
  done
}

behavioral_ids=()
for family in ledger assignment state_machine; do
  for idx in 01 02 03 04 05; do
    behavioral_ids+=("${family}_paraphrase_${idx}")
  done
  for idx in 01 02 03 04; do
    behavioral_ids+=("${family}_noise_${idx}")
  done
  for idx in 01 02 03; do
    behavioral_ids+=("${family}_reorder_${idx}")
  done
  for idx in 01 02 03; do
    behavioral_ids+=("${family}_repair_${idx}")
  done
done

core_ids="shape-lock,feedback-carry,multi-fix"
behavioral_id_csv="$(join_by_comma "${behavioral_ids[@]}")"

echo "[freud] reasoning gate: logic-core"
"$PSYKE_CMD" \
  --eval-reasoning-only \
  --eval-reasoning-mode logic \
  --no-id \
  --eval-stage freud-logic-core \
  --eval-reasoning-tasks "$core_ids"

echo "[freud] reasoning gate: logic-behavioral"
"$PSYKE_CMD" \
  --eval-reasoning-only \
  --eval-reasoning-mode logic \
  --no-id \
  --eval-stage freud-logic-behavioral \
  --eval-reasoning-tasks "$behavioral_id_csv"
