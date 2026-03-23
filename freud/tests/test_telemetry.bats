#!/usr/bin/env bats
# Tests for prompt-budget-telemetry.sh and task-verifier-telemetry.sh.

setup() {
  load helpers/setup.bash
}

# --- prompt-budget-telemetry ---

@test "prompt-budget: reports correct totals from fixture" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" "$FIXTURES_DIR/budget-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"allocations: 3"* ]]
  [[ "$output" == *"single_message_fallback: 1"* ]]
  [[ "$output" == *"floor_violation_events: 1"* ]]
  [[ "$output" == *"dropped_sections_total: 2"* ]]
}

@test "prompt-budget: breakdown by call_site" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" "$FIXTURES_DIR/budget-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"planner: 2"* ]]
  [[ "$output" == *"action_executor: 1"* ]]
}

@test "prompt-budget: tuning hints for fallback" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" "$FIXTURES_DIR/budget-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"single-message fallback occurred"* ]]
}

@test "prompt-budget: tuning hints for floor violations" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" "$FIXTURES_DIR/budget-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"floor violations occurred"* ]]
}

@test "prompt-budget: tuning hints for dropped sections" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" "$FIXTURES_DIR/budget-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"sections were dropped"* ]]
}

@test "prompt-budget: no matching events reports zero" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" "$FIXTURES_DIR/empty-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"No prompt_budget_allocation events"* ]]
}

@test "prompt-budget: missing file exits 1" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" "/nonexistent/file.jsonl"
  [[ "$status" -eq 1 ]]
  [[ "$output" == *"not found"* ]]
}

@test "prompt-budget: --help shows usage" {
  run "$SCRIPTS_DIR/prompt-budget-telemetry.sh" --help
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"usage"* || "$output" == *"Usage"* ]]
}

# --- task-verifier-telemetry ---

@test "task-verifier: reports correct totals from fixture" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"reviews: 3"* ]]
  [[ "$output" == *"allow: 2"* ]]
  [[ "$output" == *"deny: 1"* ]]
}

@test "task-verifier: reports requires_external_evidence" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"requires_external_evidence: 2"* ]]
}

@test "task-verifier: reports graceful_allows" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"graceful_allows: 1"* ]]
}

@test "task-verifier: reports unknown_intent" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"unknown_intent: 1"* ]]
}

@test "task-verifier: reports volatile_intent" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"volatile_intent: 1"* ]]
}

@test "task-verifier: breakdown by reason_code" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"TASK_VERIFIED"* ]]
  [[ "$output" == *"VOLATILE_UNVERIFIED"* ]]
  [[ "$output" == *"TASK_EVIDENCE_UNAVAILABLE_GRACEFUL"* ]]
}

@test "task-verifier: tuning hint for unknown intent" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"unknown intent observed"* ]]
}

@test "task-verifier: tuning hint for graceful allows" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"graceful allows occurred"* ]]
}

@test "task-verifier: volatile deny rate reported" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"volatile deny rate"* ]]
}

@test "task-verifier: no matching events reports zero" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/empty-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"No task_verifier_review events"* ]]
}

@test "task-verifier: missing file exits 1" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "/nonexistent/file.jsonl"
  [[ "$status" -eq 1 ]]
  [[ "$output" == *"not found"* ]]
}

@test "task-verifier: --help shows usage" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" --help
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"usage"* || "$output" == *"Usage"* ]]
}
