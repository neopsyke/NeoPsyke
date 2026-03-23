#!/usr/bin/env bats

setup() {
  load helpers/setup.bash
  TEST_TMPDIR="$(mktemp -d)"
  INPUT_FILE="$TEST_TMPDIR/input.txt"
  EXPECTED_FILE="$TEST_TMPDIR/expected.txt"
  RUN_ROOT_REL=".freud/live-eval-test-$$"
  NEOPSYKE_STUB="$TEST_TMPDIR/fake-neopsyke.sh"
  ARGS_LOG="$TEST_TMPDIR/neopsyke-args.log"
  ENV_LOG="$TEST_TMPDIR/neopsyke-env.log"
  printf 'hello\n' >"$INPUT_FILE"
  printf 'synthetic-answer\n' >"$EXPECTED_FILE"
  cat >"$NEOPSYKE_STUB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"${FREUD_TEST_ARGS_LOG:-/dev/null}"
printf 'GRADLE_USER_HOME=%s\n' "${GRADLE_USER_HOME:-}" >"${FREUD_TEST_ENV_LOG:-/dev/null}"
printf 'NEOPSYKE_LLM_CONFIG_FILE=%s\n' "${NEOPSYKE_LLM_CONFIG_FILE:-}" >>"${FREUD_TEST_ENV_LOG:-/dev/null}"
cat >/dev/null
mkdir -p "$(dirname "$NEOPSYKE_LOG_FILE")" "$(dirname "$NEOPSYKE_EVENT_LOG_FILE")"
printf '%s\n' "stub log" >"$NEOPSYKE_LOG_FILE"
printf '%s\n' '{"type":"noop","data":{}}' >"$NEOPSYKE_EVENT_LOG_FILE"
if [[ "${NEOPSYKE_LLM_CACHE_MODE:-off}" == "record" ]]; then
  printf '%s\n' '{"cached":true}' >"$NEOPSYKE_LLM_CACHE_FILE"
fi
if [[ "${FREUD_TEST_NOISY_STDOUT:-false}" == "true" ]]; then
  printf '%s\n' 'NeoPsyke logs for this run: /tmp/fake.log'
  printf '%s\n' 'Latest run log pointer: /tmp/latest.log'
fi
printf 'ego> synthetic-answer\n'
EOF
  chmod +x "$NEOPSYKE_STUB"
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
  [[ -d "$REPO_ROOT/$RUN_ROOT_REL" ]] && rm -rf "$REPO_ROOT/$RUN_ROOT_REL"
}

@test "live-eval creates unique run directories across repeated invocations" {
  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  dir_count="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
  [[ "$dir_count" -ge 2 ]]
}

@test "live-eval record then replay updates verdict cache mode" {
  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  latest_dir="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  cache_file="$latest_dir/artifacts/llm-cache.jsonl"
  [[ -f "$cache_file" ]]
  grep -q '"cache_mode": "record"' "$latest_dir/artifacts/verdict.json"

  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --cache-replay "$cache_file"
  [[ "$status" -eq 0 ]]
  replay_verdict="$(rg -l '"cache_mode": "replay"' "$REPO_ROOT/$RUN_ROOT_REL" -g verdict.json | head -n 1)"
  [[ -n "$replay_verdict" ]]
}

@test "live-eval writes stable verdict artifact fields" {
  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --expected "$EXPECTED_FILE"
  [[ "$status" -eq 0 ]]
  latest_dir="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  verdict_file="$latest_dir/artifacts/verdict.json"
  [[ -f "$verdict_file" ]]
  grep -q '"run_dir"' "$verdict_file"
  grep -q '"answer_file"' "$verdict_file"
  grep -q '"artifacts_dir"' "$verdict_file"
}

@test "live-eval normalizes expected answers before comparison" {
  printf 'SYNTHETIC-ANSWER\n' >"$EXPECTED_FILE"
  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --expected "$EXPECTED_FILE"
  [[ "$status" -eq 0 ]]
}

@test "live-eval omits clear-memory-all when preserve-memory is enabled" {
  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --preserve-memory
  [[ "$status" -eq 0 ]]
  ! grep -q -- '--clear-memory-all' "$ARGS_LOG"
  grep -q -- '--freud-live' "$ARGS_LOG"
}

@test "live-eval sources Freud config and exports isolated GRADLE_USER_HOME" {
  CONFIG_FILE="$TEST_TMPDIR/freud-live.env"
  cat >"$CONFIG_FILE" <<EOF
FREUD_RUN_ROOT="$RUN_ROOT_REL"
FREUD_GRADLE_USER_HOME=".freud/test-gradle-home"
EOF

  run env \
    FREUD_CONFIG="$CONFIG_FILE" \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  grep -q "GRADLE_USER_HOME=$REPO_ROOT/.freud/test-gradle-home" "$ENV_LOG"
}

@test "live-eval exports NEOPSYKE_LLM_CONFIG_FILE from Freud config to child process" {
  CONFIG_FILE="$TEST_TMPDIR/freud-live.env"
  cat >"$CONFIG_FILE" <<EOF
NEOPSYKE_LLM_CONFIG_FILE="$TEST_TMPDIR/llm-routing.yaml"
EOF

  run env \
    FREUD_CONFIG="$CONFIG_FILE" \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  grep -q "NEOPSYKE_LLM_CONFIG_FILE=$TEST_TMPDIR/llm-routing.yaml" "$ENV_LOG"
}

@test "live-eval extracts the final ego answer from noisy stdout" {
  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$NEOPSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_TEST_ENV_LOG="$ENV_LOG" \
    FREUD_TEST_NOISY_STDOUT="true" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --expected "$EXPECTED_FILE"
  [[ "$status" -eq 0 ]]
  latest_dir="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  grep -qx 'ego> synthetic-answer' "$latest_dir/artifacts/answer.txt"
}

@test "live-eval classifies local bootstrap failures in verdict artifacts" {
  FAILING_STUB="$TEST_TMPDIR/failing-neopsyke.sh"
  cat >"$FAILING_STUB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cat >/dev/null
printf '%s\n' 'Gradle could not start your build.' >&2
exit 1
EOF
  chmod +x "$FAILING_STUB"

  run env \
    FREUD_LIVE_EVAL_NEOPSYKE_CMD="$FAILING_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 1 ]]
  latest_dir="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  grep -q '"failure_class": "local_runtime_bootstrap_failure"' "$latest_dir/artifacts/verdict.json"
}
