#!/usr/bin/env bats
# Integration tests for triage-run.sh.

setup() {
  load helpers/setup.bash
  setup_run_dir
}

teardown() {
  teardown_run_dir
}

@test "triage: clean logs produce zero pattern counts" {
  cp "$FIXTURES_DIR/logs/clean.log" "$TEST_RUN_DIR/logs/00-clean.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_RUN_DIR/artifacts/anomalies.json" ]]

  # All known patterns should have count 0
  while IFS=$'\t' read -r pattern_id count sample; do
    [[ "$count" -eq 0 ]]
  done <"$TEST_RUN_DIR/artifacts/pattern-counts.tsv"
}

@test "triage: detects warning signals in top-signals.tsv" {
  cp "$FIXTURES_DIR/logs/warnings.log" "$TEST_RUN_DIR/logs/00-warnings.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -s "$TEST_RUN_DIR/artifacts/top-signals.tsv" ]]
  # top-signals should contain warning-related lines
  grep -qi "warning" "$TEST_RUN_DIR/artifacts/top-signals.tsv"
}

@test "triage: detects error patterns" {
  cp "$FIXTURES_DIR/logs/errors.log" "$TEST_RUN_DIR/logs/00-errors.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -s "$TEST_RUN_DIR/artifacts/top-signals.tsv" ]]
  grep -qi "error\|exception\|failed\|assert" "$TEST_RUN_DIR/artifacts/top-signals.tsv"
}

@test "triage: extracts pressure signals" {
  cp "$FIXTURES_DIR/logs/pressure.log" "$TEST_RUN_DIR/logs/00-pressure.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -s "$TEST_RUN_DIR/artifacts/pressure-signals.tsv" ]]
  # Should have pressure signal lines
  pressure_count="$(wc -l <"$TEST_RUN_DIR/artifacts/pressure-signals.tsv" | tr -d ' ')"
  [[ "$pressure_count" -ge 4 ]]

  # anomalies.json should report pressure max
  grep -q '"max"' "$TEST_RUN_DIR/artifacts/anomalies.json"
}

@test "triage: mixed log counts all pattern types" {
  cp "$FIXTURES_DIR/logs/mixed.log" "$TEST_RUN_DIR/logs/00-mixed.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]

  # Check that patterns with known matches have count > 0
  planner_count="$(awk -F '\t' '$1=="planner_output_repaired"{print $2}' "$TEST_RUN_DIR/artifacts/pattern-counts.tsv")"
  parse_count="$(awk -F '\t' '$1=="parse_failures"{print $2}' "$TEST_RUN_DIR/artifacts/pattern-counts.tsv")"
  forced_count="$(awk -F '\t' '$1=="forced_terminal"{print $2}' "$TEST_RUN_DIR/artifacts/pattern-counts.tsv")"
  queue_count="$(awk -F '\t' '$1=="queue_saturation"{print $2}' "$TEST_RUN_DIR/artifacts/pattern-counts.tsv")"
  policy_count="$(awk -F '\t' '$1=="policy_denials"{print $2}' "$TEST_RUN_DIR/artifacts/pattern-counts.tsv")"
  provider_count="$(awk -F '\t' '$1=="provider_failures"{print $2}' "$TEST_RUN_DIR/artifacts/pattern-counts.tsv")"

  [[ "$planner_count" -ge 1 ]]
  [[ "$parse_count" -ge 1 ]]
  [[ "$forced_count" -ge 1 ]]
  [[ "$queue_count" -ge 1 ]]
  [[ "$policy_count" -ge 1 ]]
  [[ "$provider_count" -ge 1 ]]
}

@test "triage: gradle task headers filtered from top signals" {
  cp "$FIXTURES_DIR/logs/gradle-headers.log" "$TEST_RUN_DIR/logs/00-gradle.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  # "> Task :" lines should not appear in top-signals
  if [[ -s "$TEST_RUN_DIR/artifacts/top-signals.tsv" ]]; then
    ! grep -q "> Task :" "$TEST_RUN_DIR/artifacts/top-signals.tsv"
  fi
}

@test "triage: anomalies.json is valid JSON" {
  cp "$FIXTURES_DIR/logs/mixed.log" "$TEST_RUN_DIR/logs/00-mixed.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  # Validate with jq
  jq . "$TEST_RUN_DIR/artifacts/anomalies.json" >/dev/null
}

@test "triage: anomalies.md generated" {
  cp "$FIXTURES_DIR/logs/clean.log" "$TEST_RUN_DIR/logs/00-clean.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_RUN_DIR/artifacts/anomalies.md" ]]
  grep -q "# Anomaly Triage" "$TEST_RUN_DIR/artifacts/anomalies.md"
}

@test "triage: pass runs explain that signal counts are informational" {
  cat >"$TEST_RUN_DIR/artifacts/summary.json" <<'EOF'
{
  "status": "pass"
}
EOF
  cp "$FIXTURES_DIR/logs/scenario-pass.log" "$TEST_RUN_DIR/logs/00-scenario-pass.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  grep -q "## Signal Counts" "$TEST_RUN_DIR/artifacts/anomalies.md"
  grep -q "Interpretation for pass runs:" "$TEST_RUN_DIR/artifacts/anomalies.md"
  grep -q "informational signal hits" "$TEST_RUN_DIR/artifacts/anomalies.md"
}

@test "triage: fail runs explain that signal counts are heuristic" {
  cat >"$TEST_RUN_DIR/artifacts/summary.json" <<'EOF'
{
  "status": "fail"
}
EOF
  cp "$FIXTURES_DIR/logs/scenario-fail.log" "$TEST_RUN_DIR/logs/00-scenario-fail.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  grep -q "## Signal Counts" "$TEST_RUN_DIR/artifacts/anomalies.md"
  grep -q "Interpretation for fail runs:" "$TEST_RUN_DIR/artifacts/anomalies.md"
  grep -q "heuristic signal hits" "$TEST_RUN_DIR/artifacts/anomalies.md"
}

@test "triage: passing scenario output does not create fake failure signals" {
  cp "$FIXTURES_DIR/logs/scenario-pass.log" "$TEST_RUN_DIR/logs/00-scenario-pass.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]

  [[ ! -s "$TEST_RUN_DIR/artifacts/top-signals.tsv" ]]

  first_failing_trace="$(cat "$TEST_RUN_DIR/artifacts/first-failing-trace.txt")"
  [[ -z "$first_failing_trace" ]]
}

@test "triage: real failing scenario lines still surface as failure signals" {
  cp "$FIXTURES_DIR/logs/scenario-fail.log" "$TEST_RUN_DIR/logs/00-scenario-fail.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]

  grep -q "status=fail description=Forced terminal failed unexpectedly." "$TEST_RUN_DIR/artifacts/top-signals.tsv"
  grep -q "AssertionError" "$TEST_RUN_DIR/artifacts/top-signals.tsv"
  grep -q "status=fail description=Forced terminal failed unexpectedly." "$TEST_RUN_DIR/artifacts/first-failing-trace.txt"
}

@test "triage: empty logs dir handled gracefully" {
  # logs dir exists but is empty
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  [[ -f "$TEST_RUN_DIR/artifacts/anomalies.json" ]]
}

@test "triage: no logs dir handled gracefully" {
  rmdir "$TEST_RUN_DIR/logs"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  # All counts should be 0
  while IFS=$'\t' read -r pattern_id count sample; do
    [[ "$count" -eq 0 ]]
  done <"$TEST_RUN_DIR/artifacts/pattern-counts.tsv"
}

@test "triage: missing run dir exits 1" {
  run "$SCRIPTS_DIR/triage-run.sh" "/nonexistent/path"
  [[ "$status" -eq 1 ]]
}

@test "triage: --top flag limits top signals" {
  cp "$FIXTURES_DIR/logs/mixed.log" "$TEST_RUN_DIR/logs/00-mixed.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR" --top 2
  [[ "$status" -eq 0 ]]
  count="$(wc -l <"$TEST_RUN_DIR/artifacts/top-signals.tsv" | tr -d ' ')"
  [[ "$count" -le 2 ]]
}

@test "triage: pressure threshold detection (0.75 and 0.90)" {
  cp "$FIXTURES_DIR/logs/pressure.log" "$TEST_RUN_DIR/logs/00-pressure.log"
  run "$SCRIPTS_DIR/triage-run.sh" "$TEST_RUN_DIR"
  [[ "$status" -eq 0 ]]
  # anomalies.json should have first_ge_075 and first_ge_090 populated
  ge075="$(jq -r '.pressure.first_ge_075' "$TEST_RUN_DIR/artifacts/anomalies.json")"
  ge090="$(jq -r '.pressure.first_ge_090' "$TEST_RUN_DIR/artifacts/anomalies.json")"
  [[ "$ge075" != "" && "$ge075" != "null" ]]
  [[ "$ge090" != "" && "$ge090" != "null" ]]
}
