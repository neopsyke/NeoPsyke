#!/usr/bin/env bats
# Integration tests for context-pack.sh.

setup() {
  load helpers/setup.bash
  setup_run_dir
  populate_run_fixtures "fail"
}

teardown() {
  teardown_run_dir
}

@test "context-pack: generates context-pack.md" {
  run "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_RUN_DIR/artifacts/context-pack.md" ]]
}

@test "context-pack: contains run snapshot with feature_id" {
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "feature_id: \`test-feature\`" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: contains run_id" {
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "run_id: \`20260314T120000Z\`" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: contains status" {
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "status: \`fail\`" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: lists failed steps" {
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "full_tests" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: no failures shows 'none'" {
  # Rewrite steps.tsv to all pass
  {
    printf "step\tstatus\tduration_sec\tlog\n"
    printf "preflight_compile\tpass\t5\t-\n"
    printf "targeted_tests\tpass\t10\t-\n"
  } >"$TEST_RUN_DIR/artifacts/steps.tsv"
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "none" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: contains Start Here section with artifact paths" {
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "Start Here" "$TEST_RUN_DIR/artifacts/context-pack.md"
  grep -q "summary.json" "$TEST_RUN_DIR/artifacts/context-pack.md"
  grep -q "freud-metrics.json" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: contains Trail Preview" {
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "Trail Preview" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: contains How To Use section" {
  "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR" >/dev/null
  grep -q "How To Use" "$TEST_RUN_DIR/artifacts/context-pack.md"
}

@test "context-pack: missing run dir exits 1" {
  run "$SCRIPTS_DIR/context-pack.sh" "/nonexistent/path"
  [[ "$status" -eq 1 ]]
}

@test "context-pack: outputs path to context-pack.md" {
  run "$SCRIPTS_DIR/context-pack.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"context-pack.md"* ]]
}
