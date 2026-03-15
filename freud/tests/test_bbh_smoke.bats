#!/usr/bin/env bats

setup() {
  load helpers/setup.bash
  TEST_TMPDIR="$(mktemp -d)"
  PROMPTS_FILE="$TEST_TMPDIR/prompts.jsonl"
  ANSWERS_FILE="$TEST_TMPDIR/answers.jsonl"
  LIVE_EVAL_STUB="$TEST_TMPDIR/fake-live-eval.sh"
  TEST_RUN_DIR="$TEST_TMPDIR/freud-run"
  TEST_ARTIFACT_DIR="$TEST_RUN_DIR/artifacts"
  mkdir -p "$TEST_ARTIFACT_DIR"

  cat >"$LIVE_EVAL_STUB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"${FREUD_TEST_ARGS_LOG:-/dev/null}"
INPUT_FILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT_FILE="$2"; shift 2 ;;
    --timeout) shift 2 ;;
    *) shift ;;
  esac
done
RUN_DIR="$FREUD_LIVE_EVAL_RUN_DIR"
mkdir -p "$RUN_DIR/artifacts" "$RUN_DIR/logs"
id="$(basename "$RUN_DIR")"
answer="$id"
status=0
verdict="pass"
detail="ok"
if [[ "${FREUD_TEST_FAIL_CASE:-}" == "$id" ]]; then
  answer="wrong-$id"
  verdict="fail"
  detail="wrong answer"
fi
if [[ "${FREUD_TEST_TIMEOUT_CASE:-}" == "$id" ]]; then
  status=2
  verdict="timeout"
  detail="timed out"
fi
printf 'ego> %s\n' "$answer" >"$RUN_DIR/artifacts/answer.txt"
if [[ "${FREUD_TEST_SCHEMA_CASE:-}" == "$id" ]]; then
  printf '%s\n' 'Structured-output schema adapted for provider=groq model=openai/gpt-oss-20b actor=ego call_site=input schema=ego_planner_decision. strict downgraded to false.' >"$RUN_DIR/logs/psyke.log"
else
  : >"$RUN_DIR/logs/psyke.log"
fi
cat >"$RUN_DIR/artifacts/verdict.json" <<JSON
{"verdict":"$verdict","detail":"$detail","exit_code":$status,"run_dir":"$RUN_DIR","answer_file":"$RUN_DIR/artifacts/answer.txt"}
JSON
exit "$status"
EOF
  chmod +x "$LIVE_EVAL_STUB"
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
}

@test "run-bbh-smoke aggregates passing cases" {
  cat >"$PROMPTS_FILE" <<'EOF'
{"id":"case_a","category":"boolean_expressions","prompt":"case a"}
{"id":"case_b","category":"logical_deduction","prompt":"case b"}
EOF
  cat >"$ANSWERS_FILE" <<'EOF'
{"id":"case_a","answer":"case_a"}
{"id":"case_b","answer":"case_b"}
EOF

  run env \
    FREUD_BBH_LIVE_EVAL_CMD="$LIVE_EVAL_STUB" \
    FREUD_BBH_PROMPTS_FILE="$PROMPTS_FILE" \
    FREUD_BBH_ANSWERS_FILE="$ANSWERS_FILE" \
    FREUD_RUN_DIR="$TEST_RUN_DIR" \
    FREUD_ARTIFACT_DIR="$TEST_ARTIFACT_DIR" \
    "$SCRIPTS_DIR/run-bbh-smoke.sh" --lane weak-structure
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_ARTIFACT_DIR/bbh-smoke-weak-structure-summary.json" ]]
  [[ -f "$TEST_ARTIFACT_DIR/bbh-smoke-weak-structure-progress.json" ]]
  grep -q '"total_cases": 2' "$TEST_ARTIFACT_DIR/bbh-smoke-weak-structure-summary.json"
  grep -q '"passed_cases": 2' "$TEST_ARTIFACT_DIR/bbh-smoke-weak-structure-summary.json"
  grep -q '"phase": "completed"' "$TEST_ARTIFACT_DIR/bbh-smoke-weak-structure-progress.json"
}

@test "run-bbh-smoke fails when one case answer mismatches" {
  cat >"$PROMPTS_FILE" <<'EOF'
{"id":"case_a","category":"boolean_expressions","prompt":"case a"}
{"id":"case_b","category":"logical_deduction","prompt":"case b"}
EOF
  cat >"$ANSWERS_FILE" <<'EOF'
{"id":"case_a","answer":"case_a"}
{"id":"case_b","answer":"case_b"}
EOF

  run env \
    FREUD_BBH_LIVE_EVAL_CMD="$LIVE_EVAL_STUB" \
    FREUD_BBH_PROMPTS_FILE="$PROMPTS_FILE" \
    FREUD_BBH_ANSWERS_FILE="$ANSWERS_FILE" \
    FREUD_RUN_DIR="$TEST_RUN_DIR" \
    FREUD_ARTIFACT_DIR="$TEST_ARTIFACT_DIR" \
    FREUD_TEST_FAIL_CASE="case_b" \
    "$SCRIPTS_DIR/run-bbh-smoke.sh" --lane weak-structure
  [[ "$status" -eq 2 ]]
  grep -q '"failed_cases": 1' "$TEST_ARTIFACT_DIR/bbh-smoke-weak-structure-summary.json"
}

@test "run-bbh-smoke writes progress artifacts during execution" {
  cat >"$PROMPTS_FILE" <<'EOF'
{"id":"case_a","category":"boolean_expressions","prompt":"case a"}
{"id":"case_b","category":"logical_deduction","prompt":"case b"}
EOF
  cat >"$ANSWERS_FILE" <<'EOF'
{"id":"case_a","answer":"case_a"}
{"id":"case_b","answer":"case_b"}
EOF

  run env \
    FREUD_BBH_LIVE_EVAL_CMD="$LIVE_EVAL_STUB" \
    FREUD_BBH_PROMPTS_FILE="$PROMPTS_FILE" \
    FREUD_BBH_ANSWERS_FILE="$ANSWERS_FILE" \
    FREUD_RUN_DIR="$TEST_RUN_DIR" \
    FREUD_ARTIFACT_DIR="$TEST_ARTIFACT_DIR" \
    "$SCRIPTS_DIR/run-bbh-smoke.sh" --lane prod-acceptance
  [[ "$status" -eq 0 ]]
  grep -q '"completed_cases": 2' "$TEST_ARTIFACT_DIR/bbh-smoke-prod-acceptance-progress.json"
  grep -q '"remaining_cases": 0' "$TEST_ARTIFACT_DIR/bbh-smoke-prod-acceptance-progress.json"
}

@test "run-bbh-smoke normalizes exact-match answers" {
  cat >"$PROMPTS_FILE" <<'EOF'
{"id":"case_a","category":"boolean_expressions","prompt":"case a"}
EOF
  cat >"$ANSWERS_FILE" <<'EOF'
{"id":"case_a","answer":"CASE_A"}
EOF
  cat >"$LIVE_EVAL_STUB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
RUN_DIR="$FREUD_LIVE_EVAL_RUN_DIR"
mkdir -p "$RUN_DIR/artifacts" "$RUN_DIR/logs"
printf 'ego>   case_a   \n' >"$RUN_DIR/artifacts/answer.txt"
cat >"$RUN_DIR/artifacts/verdict.json" <<JSON
{"verdict":"pass","detail":"ok","exit_code":0,"run_dir":"$RUN_DIR","answer_file":"$RUN_DIR/artifacts/answer.txt"}
JSON
: >"$RUN_DIR/logs/psyke.log"
EOF
  chmod +x "$LIVE_EVAL_STUB"

  run env \
    FREUD_BBH_LIVE_EVAL_CMD="$LIVE_EVAL_STUB" \
    FREUD_BBH_PROMPTS_FILE="$PROMPTS_FILE" \
    FREUD_BBH_ANSWERS_FILE="$ANSWERS_FILE" \
    FREUD_RUN_DIR="$TEST_RUN_DIR" \
    FREUD_ARTIFACT_DIR="$TEST_ARTIFACT_DIR" \
    "$SCRIPTS_DIR/run-bbh-smoke.sh" --lane prod-acceptance
  [[ "$status" -eq 0 ]]
}

@test "run-bbh-smoke fails hard on strict-schema downgrade" {
  cat >"$PROMPTS_FILE" <<'EOF'
{"id":"case_a","category":"boolean_expressions","prompt":"case a"}
EOF
  cat >"$ANSWERS_FILE" <<'EOF'
{"id":"case_a","answer":"case_a"}
EOF

  run env \
    FREUD_BBH_LIVE_EVAL_CMD="$LIVE_EVAL_STUB" \
    FREUD_BBH_PROMPTS_FILE="$PROMPTS_FILE" \
    FREUD_BBH_ANSWERS_FILE="$ANSWERS_FILE" \
    FREUD_RUN_DIR="$TEST_RUN_DIR" \
    FREUD_ARTIFACT_DIR="$TEST_ARTIFACT_DIR" \
    FREUD_TEST_SCHEMA_CASE="case_a" \
    "$SCRIPTS_DIR/run-bbh-smoke.sh" --lane weak-structure
  [[ "$status" -eq 2 ]]
  grep -q '"schema_downgrade_count": 1' "$TEST_ARTIFACT_DIR/bbh-smoke-weak-structure-summary.json"
}

@test "run-bbh-smoke passes preserve-memory through to live-eval" {
  ARGS_LOG="$TEST_TMPDIR/live-eval-args.log"
  cat >"$PROMPTS_FILE" <<'EOF'
{"id":"case_a","category":"boolean_expressions","prompt":"case a"}
EOF
  cat >"$ANSWERS_FILE" <<'EOF'
{"id":"case_a","answer":"case_a"}
EOF

  run env \
    FREUD_BBH_LIVE_EVAL_CMD="$LIVE_EVAL_STUB" \
    FREUD_BBH_PROMPTS_FILE="$PROMPTS_FILE" \
    FREUD_BBH_ANSWERS_FILE="$ANSWERS_FILE" \
    FREUD_RUN_DIR="$TEST_RUN_DIR" \
    FREUD_ARTIFACT_DIR="$TEST_ARTIFACT_DIR" \
    FREUD_TEST_ARGS_LOG="$ARGS_LOG" \
    FREUD_BBH_PRESERVE_MEMORY="true" \
    "$SCRIPTS_DIR/run-bbh-smoke.sh" --lane weak-structure
  [[ "$status" -eq 0 ]]
  grep -q -- '--preserve-memory' "$ARGS_LOG"
}
