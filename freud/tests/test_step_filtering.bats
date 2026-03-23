#!/usr/bin/env bats
# Tests for step_is_active logic and feature_id normalization.

setup() {
  load helpers/setup.bash
  load helpers/source_functions.bash
  source_step_filtering
}

# --- step_is_active with no --from-step ---

@test "step_is_active: all steps active when from_step is empty" {
  from_step=""
  step_is_active "preflight_compile"
  step_is_active "full_tests"
  step_is_active "memory_live_smoke"
}

# --- step_is_active with --from-step ---

@test "step_is_active: from_step=full_tests skips preflight_compile" {
  from_step="full_tests"
  run step_is_active "preflight_compile"
  [[ "$status" -eq 1 ]]
}

@test "step_is_active: from_step=full_tests skips targeted_tests" {
  from_step="full_tests"
  run step_is_active "targeted_tests"
  [[ "$status" -eq 1 ]]
}

@test "step_is_active: from_step=full_tests activates full_tests itself" {
  from_step="full_tests"
  step_is_active "full_tests"
}

@test "step_is_active: from_step=full_tests activates scenario_pack (after)" {
  from_step="full_tests"
  step_is_active "scenario_pack"
}

@test "step_is_active: from_step=full_tests activates memory_live_smoke (last)" {
  from_step="full_tests"
  step_is_active "memory_live_smoke"
}

@test "step_is_active: from_step=preflight_compile activates everything" {
  from_step="preflight_compile"
  step_is_active "preflight_compile"
  step_is_active "targeted_tests"
  step_is_active "full_tests"
  step_is_active "scenario_pack"
  step_is_active "memory_live_smoke"
}

@test "step_is_active: from_step=memory_live_smoke skips all except last" {
  from_step="memory_live_smoke"
  run step_is_active "preflight_compile"
  [[ "$status" -eq 1 ]]
  run step_is_active "scenario_pack"
  [[ "$status" -eq 1 ]]
  step_is_active "memory_live_smoke"
}

@test "step_is_active: unknown step name treated as active" {
  from_step="full_tests"
  step_is_active "unknown_custom_step"
}

# --- feature_id normalization ---

@test "feature_id: lowercase conversion" {
  result="$(echo "My-Feature" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
  [[ "$result" == "my-feature" ]]
}

@test "feature_id: special chars become hyphens" {
  result="$(echo "feat!@#name" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
  [[ "$result" == "feat-name" ]]
}

@test "feature_id: leading/trailing hyphens stripped" {
  result="$(echo "--leading--trailing--" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
  [[ "$result" == "leading--trailing" ]]
}

@test "feature_id: dots and hyphens preserved" {
  result="$(echo "v1.2-beta" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
  [[ "$result" == "v1.2-beta" ]]
}

@test "feature_id: spaces become hyphens" {
  result="$(echo "my feature name" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
  [[ "$result" == "my-feature-name" ]]
}

@test "feature_id: all uppercase" {
  result="$(echo "UPPER" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
  [[ "$result" == "upper" ]]
}
