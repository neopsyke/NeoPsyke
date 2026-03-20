#!/usr/bin/env bats

setup() {
  load helpers/setup.bash
  TEST_TMPDIR="$(mktemp -d)"
  ENV_LOG="$TEST_TMPDIR/child-env.log"
  STEP_STUB="$TEST_TMPDIR/step-stub.sh"
  CONFIG_FILE="$TEST_TMPDIR/feature-loop.env"

  cat >"$STEP_STUB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'NEOPSYKE_LLM_CONFIG_FILE=%s\n' "${NEOPSYKE_LLM_CONFIG_FILE:-}" >"${FREUD_TEST_ENV_LOG:?}"
EOF
  chmod +x "$STEP_STUB"

  cat >"$CONFIG_FILE" <<EOF
FREUD_PROJECT_NAME="neopsyke-test"
FREUD_RUN_ROOT=".freud/feature-loop-env-test-$$"
FREUD_REASONING_EVAL_MODEL_CMD="$STEP_STUB"
NEOPSYKE_LLM_CONFIG_FILE="$TEST_TMPDIR/llm-routing.yaml"
EOF
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
  [[ -d "$REPO_ROOT/.freud/feature-loop-env-test-$$" ]] && rm -rf "$REPO_ROOT/.freud/feature-loop-env-test-$$"
}

@test "feature-loop exports NEOPSYKE_LLM_CONFIG_FILE to live step commands" {
  run env \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/feature-loop.sh" llm-config-export --live --from-step reasoning_eval_model --config "$CONFIG_FILE"
  [[ "$status" -eq 0 ]]
  grep -q "NEOPSYKE_LLM_CONFIG_FILE=$TEST_TMPDIR/llm-routing.yaml" "$ENV_LOG"
}
