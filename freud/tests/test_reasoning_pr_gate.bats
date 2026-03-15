#!/usr/bin/env bats

setup() {
  load helpers/setup.bash
  TEST_TMPDIR="$(mktemp -d)"
  CALL_LOG="$TEST_TMPDIR/calls.log"
  STUB="$TEST_TMPDIR/fake-psyke.sh"
  cat >"$STUB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FREUD_TEST_CALL_LOG"
if [[ "${FREUD_TEST_FAIL_ON_BEHAVIORAL:-false}" == "true" && "$*" == *"ledger_paraphrase_01"* ]]; then
  exit 1
fi
EOF
  chmod +x "$STUB"
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
}

@test "run-reasoning-pr-gate invokes logic-core and logic-behavioral packs" {
  run env \
    FREUD_REASONING_PR_GATE_PSYKE_CMD="$STUB" \
    FREUD_TEST_CALL_LOG="$CALL_LOG" \
    "$SCRIPTS_DIR/run-reasoning-pr-gate.sh"
  [[ "$status" -eq 0 ]]
  [[ "$(wc -l <"$CALL_LOG" | tr -d ' ')" -eq 2 ]]
  grep -q -- "--eval-stage freud-logic-core" "$CALL_LOG"
  grep -q -- "--eval-stage freud-logic-behavioral" "$CALL_LOG"
  grep -q -- "shape-lock,feedback-carry,multi-fix" "$CALL_LOG"
  grep -q -- "ledger_paraphrase_01" "$CALL_LOG"
  grep -q -- "state_machine_repair_03" "$CALL_LOG"
}

@test "run-reasoning-pr-gate fails when behavioral invocation fails" {
  run env \
    FREUD_REASONING_PR_GATE_PSYKE_CMD="$STUB" \
    FREUD_TEST_CALL_LOG="$CALL_LOG" \
    FREUD_TEST_FAIL_ON_BEHAVIORAL="true" \
    "$SCRIPTS_DIR/run-reasoning-pr-gate.sh"
  [[ "$status" -eq 1 ]]
}
