#!/usr/bin/env bats
# Tests for run-scenarios.sh manifest parsing and dry-run mode.

setup() {
  load helpers/setup.bash
}

@test "scenarios: JSON manifest dry-run lists all scenarios" {
  run "$SCRIPTS_DIR/run-scenarios.sh" --file "$FIXTURES_DIR/scenarios.json" --dry-run
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"scenario_id=test_scenario_a"* ]]
  [[ "$output" == *"scenario_id=test_scenario_b"* ]]
  [[ "$output" == *"status=dry_run"* ]]
  [[ "$output" == *"scenarios_total=2"* ]]
  [[ "$output" == *"scenarios_passed=2"* ]]
  [[ "$output" == *"scenarios_failed=0"* ]]
}

@test "scenarios: TSV manifest dry-run lists scenarios, skips comments" {
  run "$SCRIPTS_DIR/run-scenarios.sh" --file "$FIXTURES_DIR/scenarios.tsv" --dry-run
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"scenario_id=test_scenario_a"* ]]
  [[ "$output" == *"scenario_id=test_scenario_b"* ]]
  [[ "$output" == *"scenarios_total=2"* ]]
}

@test "scenarios: missing manifest file exits 1" {
  run "$SCRIPTS_DIR/run-scenarios.sh" --file "/nonexistent/manifest.json" --dry-run
  [[ "$status" -eq 1 ]]
  [[ "$output" == *"does not exist"* ]]
}

@test "scenarios: empty JSON scenarios array reports zero" {
  tmpfile="$(mktemp).json"
  echo '{"version":"v1","scenarios":[]}' >"$tmpfile"
  run "$SCRIPTS_DIR/run-scenarios.sh" --file "$tmpfile" --dry-run
  rm -f "$tmpfile"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"scenarios_total=0"* ]]
}

@test "scenarios: dry-run does not invoke gradle" {
  run "$SCRIPTS_DIR/run-scenarios.sh" --file "$FIXTURES_DIR/scenarios.json" --dry-run
  [[ "$status" -eq 0 ]]
  # No gradle output or errors
  [[ "$output" != *"BUILD"* ]]
}

@test "scenarios: JSON extracts description field" {
  run "$SCRIPTS_DIR/run-scenarios.sh" --file "$FIXTURES_DIR/scenarios.json" --dry-run
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"description=First test scenario"* ]]
  [[ "$output" == *"description=Second test scenario"* ]]
}

@test "scenarios: stale selector fails manifest validation before execution" {
  tmpfile="$(mktemp)"
  cat >"$tmpfile" <<'EOF'
{"version":"v1","scenarios":[{"id":"broken","selector":"ai.neopsyke.eval.AgentScenarioPackTest.scenario_missing_selector","description":"Broken"}]}
EOF
  run "$SCRIPTS_DIR/run-scenarios.sh" --file "$tmpfile" --dry-run
  rm -f "$tmpfile"
  [[ "$status" -eq 1 ]]
  [[ "$output" == *"stale selector"* ]]
  [[ "$output" == *"scenario manifest validation failed"* ]]
}

@test "scenarios: --help shows usage" {
  run "$SCRIPTS_DIR/run-scenarios.sh" --help
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"Usage"* ]]
}
