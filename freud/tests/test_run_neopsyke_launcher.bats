#!/usr/bin/env bats

setup() {
  TEST_TMPDIR="$(mktemp -d)"
  LAUNCHER_ROOT="$TEST_TMPDIR/launcher-root"
  mkdir -p "$LAUNCHER_ROOT/build/install/neopsyke/bin"
  mkdir -p "$LAUNCHER_ROOT/neopsyke-pgvector-memory/build/libs"
  cp "$BATS_TEST_DIRNAME/../../run-neopsyke.sh" "$LAUNCHER_ROOT/run-neopsyke.sh"
  chmod +x "$LAUNCHER_ROOT/run-neopsyke.sh"

  cat >"$LAUNCHER_ROOT/build/install/neopsyke/bin/neopsyke" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'EGO_LOOP_DELAY_MS=%s\n' "${EGO_LOOP_DELAY_MS:-}" >"${NEOPSYKE_STUB_ENV_OUT:?}"
printf 'ARGS=%s\n' "$*" >>"${NEOPSYKE_STUB_ENV_OUT:?}"
EOF
  chmod +x "$LAUNCHER_ROOT/build/install/neopsyke/bin/neopsyke"

  : >"$LAUNCHER_ROOT/neopsyke-pgvector-memory/build/libs/neopsyke-pgvector-memory-0.1.0-all.jar"
  ENV_OUT="$TEST_TMPDIR/launcher-env.txt"
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
}

@test "run-neopsyke uses launcher default loop delay when EGO_LOOP_DELAY_MS is unset" {
  run env -u EGO_LOOP_DELAY_MS \
    NEOPSYKE_STUB_ENV_OUT="$ENV_OUT" \
    "$LAUNCHER_ROOT/run-neopsyke.sh" --freud-live

  [[ "$status" -eq 0 ]]
  grep -q '^EGO_LOOP_DELAY_MS=1000$' "$ENV_OUT"
  grep -q 'ARGS=--freud-live' "$ENV_OUT"
}

@test "run-neopsyke forwards --clear-memory-lessons and no longer documents the old flag name" {
  run env \
    NEOPSYKE_STUB_ENV_OUT="$ENV_OUT" \
    "$LAUNCHER_ROOT/run-neopsyke.sh" --clear-memory-lessons --freud-live

  [[ "$status" -eq 0 ]]
  grep -q 'ARGS=--clear-memory-lessons --freud-live' "$ENV_OUT"

  run "$LAUNCHER_ROOT/run-neopsyke.sh" --help

  [[ "$status" -eq 0 ]]
  [[ "$output" == *"--clear-memory-lessons"* ]]
  [[ "$output" != *"--clear-memory-reflection"* ]]
  [[ "$output" == *"GOOGLE_API_KEY"* ]]
  [[ "$output" == *"OPENAI_API_KEY"* ]]
  [[ "$output" != *"LLM_PROVIDER"* ]]
  [[ "$output" != *"LLM_WEBSEARCH_PROVIDER"* ]]
  [[ "$output" != *"LLM_API_KEY"* ]]
  [[ "$output" != *"LLM_WEBSEARCH_API_KEY"* ]]
  [[ "$output" != *"LLM_WEBSEARCH_BASE_URL"* ]]
}
