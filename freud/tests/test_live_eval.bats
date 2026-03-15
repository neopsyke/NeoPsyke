#!/usr/bin/env bats

setup() {
  load helpers/setup.bash
  TEST_TMPDIR="$(mktemp -d)"
  INPUT_FILE="$TEST_TMPDIR/input.txt"
  EXPECTED_FILE="$TEST_TMPDIR/expected.txt"
  RUN_ROOT_REL=".freud/live-eval-test-$$"
  PSYKE_STUB="$TEST_TMPDIR/fake-psyke.sh"
  ARGS_LOG="$TEST_TMPDIR/psyke-args.log"
  printf 'hello\n' >"$INPUT_FILE"
  printf 'synthetic-answer\n' >"$EXPECTED_FILE"
  cat >"$PSYKE_STUB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"${FREUD_TEST_ARGS_LOG:-/dev/null}"
cat >/dev/null
mkdir -p "$(dirname "$PSYKE_LOG_FILE")" "$(dirname "$PSYKE_EVENT_LOG_FILE")"
printf '%s\n' "stub log" >"$PSYKE_LOG_FILE"
printf '%s\n' '{"type":"noop","data":{}}' >"$PSYKE_EVENT_LOG_FILE"
if [[ "${PSYKE_LLM_CACHE_MODE:-off}" == "record" ]]; then
  printf '%s\n' '{"cached":true}' >"$PSYKE_LLM_CACHE_FILE"
fi
printf 'ego> synthetic-answer\n'
EOF
  chmod +x "$PSYKE_STUB"
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
  [[ -d "$REPO_ROOT/$RUN_ROOT_REL" ]] && rm -rf "$REPO_ROOT/$RUN_ROOT_REL"
}

@test "live-eval creates unique run directories across repeated invocations" {
  run env \
    FREUD_LIVE_EVAL_PSYKE_CMD="$PSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  run env \
    FREUD_LIVE_EVAL_PSYKE_CMD="$PSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  dir_count="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
  [[ "$dir_count" -ge 2 ]]
}

@test "live-eval record then replay updates verdict cache mode" {
  run env \
    FREUD_LIVE_EVAL_PSYKE_CMD="$PSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE"
  [[ "$status" -eq 0 ]]
  latest_dir="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  cache_file="$latest_dir/artifacts/llm-cache.jsonl"
  [[ -f "$cache_file" ]]
  grep -q '"cache_mode": "record"' "$latest_dir/artifacts/verdict.json"

  run env \
    FREUD_LIVE_EVAL_PSYKE_CMD="$PSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --cache-replay "$cache_file"
  [[ "$status" -eq 0 ]]
  replay_dir="$(find "$REPO_ROOT/$RUN_ROOT_REL" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  grep -q '"cache_mode": "replay"' "$replay_dir/artifacts/verdict.json"
}

@test "live-eval writes stable verdict artifact fields" {
  run env \
    FREUD_LIVE_EVAL_PSYKE_CMD="$PSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
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
    FREUD_LIVE_EVAL_PSYKE_CMD="$PSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --expected "$EXPECTED_FILE"
  [[ "$status" -eq 0 ]]
}

@test "live-eval omits clear-memory-all when preserve-memory is enabled" {
  run env \
    FREUD_LIVE_EVAL_PSYKE_CMD="$PSYKE_STUB" \
    FREUD_RUN_ROOT="$RUN_ROOT_REL" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    "$SCRIPTS_DIR/live-eval.sh" --input "$INPUT_FILE" --preserve-memory
  [[ "$status" -eq 0 ]]
  ! grep -q -- '--clear-memory-all' "$ARGS_LOG"
  grep -q -- '--freud-live' "$ARGS_LOG"
}
