#!/usr/bin/env bats

setup() {
  TEST_TMPDIR="$(mktemp -d)"
  LAUNCHER_ROOT="$TEST_TMPDIR/launcher-root"
  mkdir -p "$LAUNCHER_ROOT/build/install/psyke/bin"
  mkdir -p "$LAUNCHER_ROOT/mcp-memory-pgvector/build/libs"
  cp "$BATS_TEST_DIRNAME/../../run-psyke.sh" "$LAUNCHER_ROOT/run-psyke.sh"
  chmod +x "$LAUNCHER_ROOT/run-psyke.sh"

  cat >"$LAUNCHER_ROOT/build/install/psyke/bin/psyke" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'EGO_LOOP_DELAY_MS=%s\n' "${EGO_LOOP_DELAY_MS:-}" >"${PSYKE_STUB_ENV_OUT:?}"
printf 'ARGS=%s\n' "$*" >>"${PSYKE_STUB_ENV_OUT:?}"
EOF
  chmod +x "$LAUNCHER_ROOT/build/install/psyke/bin/psyke"

  : >"$LAUNCHER_ROOT/mcp-memory-pgvector/build/libs/mcp-memory-pgvector-0.1.0-all.jar"
  ENV_OUT="$TEST_TMPDIR/launcher-env.txt"
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
}

@test "run-psyke uses launcher default loop delay when EGO_LOOP_DELAY_MS is unset" {
  run env -u EGO_LOOP_DELAY_MS \
    PSYKE_STUB_ENV_OUT="$ENV_OUT" \
    "$LAUNCHER_ROOT/run-psyke.sh" --freud-live

  [[ "$status" -eq 0 ]]
  grep -q '^EGO_LOOP_DELAY_MS=1000$' "$ENV_OUT"
  grep -q 'ARGS=--freud-live' "$ENV_OUT"
}
