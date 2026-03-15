#!/usr/bin/env bats
# Integration tests for summarize-run.sh.

setup() {
  load helpers/setup.bash
  setup_run_dir
  populate_run_fixtures "fail"
}

teardown() {
  teardown_run_dir
}

@test "summarize: generates summary-compact.md" {
  run "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_RUN_DIR/artifacts/summary-compact.md" ]]
}

@test "summarize: generates summary-compact.json" {
  run "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_RUN_DIR/artifacts/summary-compact.json" ]]
}

@test "summarize: compact JSON is valid" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  jq . "$TEST_RUN_DIR/artifacts/summary-compact.json" >/dev/null
}

@test "summarize: extracts feature_id correctly" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  feature_id="$(jq -r '.feature_id' "$TEST_RUN_DIR/artifacts/summary-compact.json")"
  [[ "$feature_id" == "test-feature" ]]
}

@test "summarize: extracts status correctly" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  status_val="$(jq -r '.status' "$TEST_RUN_DIR/artifacts/summary-compact.json")"
  [[ "$status_val" == "fail" ]]
}

@test "summarize: extracts steps_failed correctly" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  steps_failed="$(jq -r '.steps_failed' "$TEST_RUN_DIR/artifacts/summary-compact.json")"
  [[ "$steps_failed" -eq 1 ]]
}

@test "summarize: identifies first_failed_step" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  first_failed="$(jq -r '.first_failed_step' "$TEST_RUN_DIR/artifacts/summary-compact.json")"
  [[ "$first_failed" == "full_tests" ]]
}

@test "summarize: markdown contains status line" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "status: \`fail\`" "$TEST_RUN_DIR/artifacts/summary-compact.md"
}

@test "summarize: counts trail events" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  trail_events="$(jq -r '.trail_events' "$TEST_RUN_DIR/artifacts/summary-compact.json")"
  [[ "$trail_events" -eq 2 ]]
}

@test "summarize: pass status run" {
  # Overwrite summary.json to be a pass
  tmp_summary="$TEST_RUN_DIR/artifacts/summary.json.tmp"
  sed 's/"status": "fail"/"status": "pass"/; s/"steps_failed": 1/"steps_failed": 0/' \
    "$TEST_RUN_DIR/artifacts/summary.json" >"$tmp_summary"
  mv "$tmp_summary" "$TEST_RUN_DIR/artifacts/summary.json"
  # Fix step-index to have no fails
  tmp_step_index="$TEST_RUN_DIR/artifacts/step-index.tsv.tmp"
  sed 's/full_tests\tfail/full_tests\tpass/' \
    "$TEST_RUN_DIR/artifacts/step-index.tsv" >"$tmp_step_index"
  mv "$tmp_step_index" "$TEST_RUN_DIR/artifacts/step-index.tsv"
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  status_val="$(jq -r '.status' "$TEST_RUN_DIR/artifacts/summary-compact.json")"
  [[ "$status_val" == "pass" ]]
}

@test "summarize: missing step-index.tsv handled" {
  rm -f "$TEST_RUN_DIR/artifacts/step-index.tsv"
  run "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_RUN_DIR/artifacts/summary-compact.md" ]]
}

@test "summarize: missing run dir exits 1" {
  run "$SCRIPTS_DIR/summarize-run.sh" "/nonexistent/path"
  [[ "$status" -eq 1 ]]
}

@test "summarize: freud metrics preview included when present" {
  "$SCRIPTS_DIR/summarize-run.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "Freud Metrics Preview" "$TEST_RUN_DIR/artifacts/summary-compact.md"
}
