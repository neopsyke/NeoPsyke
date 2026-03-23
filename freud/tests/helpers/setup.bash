#!/usr/bin/env bash
# Shared BATS test setup for Freud tests.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
FREUD_DIR="$REPO_ROOT/freud"
SCRIPTS_DIR="$FREUD_DIR/scripts"
FIXTURES_DIR="$FREUD_DIR/tests/fixtures"

# Create a temporary run directory that mimics the Freud run layout.
# Usage: setup_run_dir
# Sets: TEST_RUN_DIR
setup_run_dir() {
  TEST_RUN_DIR="$(mktemp -d)"
  mkdir -p "$TEST_RUN_DIR/logs" "$TEST_RUN_DIR/artifacts/step-meta" "$TEST_RUN_DIR/artifacts/log-index"
}

# Tear down the temporary run directory.
teardown_run_dir() {
  [[ -n "${TEST_RUN_DIR:-}" && -d "$TEST_RUN_DIR" ]] && rm -rf "$TEST_RUN_DIR"
}

# Populate a run directory with standard fixture artifacts.
# Usage: populate_run_fixtures [status]
# Requires: TEST_RUN_DIR set
populate_run_fixtures() {
  local status="${1:-pass}"
  local art="$TEST_RUN_DIR/artifacts"

  cat >"$art/summary.json" <<EOF
{
  "workflow": "freud",
  "project": "neopsyke",
  "feature_id": "test-feature",
  "run_id": "20260314T120000Z",
  "mode": "stub",
  "started_at": "2026-03-14T12:00:00Z",
  "finished_at": "2026-03-14T12:01:00Z",
  "status": "$status",
  "steps_total": 3,
  "steps_passed": 2,
  "steps_failed": 1,
  "steps_skipped": 0,
  "first_failed_step": "full_tests",
  "failed_test_count": 1,
  "triage": {"pattern_hits_total": 0, "top_signals_count": 0, "pressure_samples": 0},
  "eval_totals": {"model_calls": 0, "total_tokens": 0},
  "run_dir": "$TEST_RUN_DIR"
}
EOF

  printf "step\tstatus\tduration_sec\tlog\n" >"$art/steps.tsv"
  printf "preflight_compile\tpass\t5\t$TEST_RUN_DIR/logs/00-preflight.log\n" >>"$art/steps.tsv"
  printf "targeted_tests\tpass\t10\t$TEST_RUN_DIR/logs/01-targeted.log\n" >>"$art/steps.tsv"
  printf "full_tests\tfail\t20\t$TEST_RUN_DIR/logs/02-full.log\n" >>"$art/steps.tsv"

  printf "step\tstatus\tduration_sec\tlog\tlog_index\tlog_lines\twarnings\terrors\tfirst_warning\tfirst_error\tfirst_pressure\n" >"$art/step-index.tsv"
  printf "preflight_compile\tpass\t5\t-\t-\t10\t0\t0\t\t\t\n" >>"$art/step-index.tsv"
  printf "targeted_tests\tpass\t10\t-\t-\t50\t1\t0\t\t\t\n" >>"$art/step-index.tsv"
  printf "full_tests\tfail\t20\t-\t-\t100\t2\t3\t\t\t\n" >>"$art/step-index.tsv"

  printf '{"seq":1,"ts":"2026-03-14T12:00:00Z","event":"run_start","step":"","status":"running","message":"started","cmd":"","log":"","ref":""}\n' >"$art/trail.jsonl"
  printf '{"seq":2,"ts":"2026-03-14T12:00:05Z","event":"step_end","step":"preflight_compile","status":"pass","message":"done","cmd":"","log":"","ref":""}\n' >>"$art/trail.jsonl"

  cat >"$art/run-config.json" <<'EOF'
{
  "workflow": "freud",
  "project": "neopsyke",
  "run_id": "20260314T120000Z",
  "feature_id": "test-feature",
  "mode": "stub"
}
EOF

  cat >"$art/freud-metrics.json" <<'EOF'
{
  "workflow": "freud",
  "run_id": "20260314T120000Z",
  "feature_id": "test-feature",
  "status": "fail",
  "counters": {
    "steps_total": 3,
    "steps_passed": 2,
    "steps_failed": 1,
    "steps_skipped": 0,
    "failed_test_count": 1,
    "eval_model_calls": 0,
    "eval_total_tokens": 0
  },
  "triage": {
    "pattern_hits_total": 0,
    "top_signals_count": 0,
    "pressure_samples": 0
  }
}
EOF

  # Create minimal log files
  echo "BUILD SUCCESSFUL" >"$TEST_RUN_DIR/logs/00-preflight.log"
  echo "BUILD SUCCESSFUL" >"$TEST_RUN_DIR/logs/01-targeted.log"
  echo "Test failed: ai.neopsyke.agent.SomeTest > testMethod FAILED" >"$TEST_RUN_DIR/logs/02-full.log"
}
