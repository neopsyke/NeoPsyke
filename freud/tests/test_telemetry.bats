#!/usr/bin/env bats
# Tests for prompt-budget-telemetry.sh and grounding-gate telemetry script.

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

# --- grounding-gate telemetry ---

@test "grounding-gate: reports correct totals from fixture" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"reviews: 3"* ]]
  [[ "$output" == *"allow: 2"* ]]
  [[ "$output" == *"deny: 1"* ]]
}

@test "grounding-gate: reports grounding_required" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"grounding_required: 2"* ]]
}

@test "grounding-gate: reports evidence_unavailable" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"evidence_unavailable: 1"* ]]
}

@test "grounding-gate: breakdown by reason_code" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"GROUNDING_EVIDENCE_REQUIRED"* ]]
  [[ "$output" == *"GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL"* ]]
}

@test "grounding-gate: tuning hint for unavailable evidence" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/verifier-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"evidence unavailable observed"* ]]
}

@test "grounding-gate: no matching events reports zero" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "$FIXTURES_DIR/empty-events.jsonl"
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"No grounding_gate_review events"* ]]
}

@test "grounding-gate: missing file exits 1" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" "/nonexistent/file.jsonl"
  [[ "$status" -eq 1 ]]
  [[ "$output" == *"not found"* ]]
}

@test "grounding-gate: --help shows usage" {
  run "$SCRIPTS_DIR/task-verifier-telemetry.sh" --help
  [[ "$status" -eq 0 ]]
  [[ "$output" == *"usage"* || "$output" == *"Usage"* ]]
}
