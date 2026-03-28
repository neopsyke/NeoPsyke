//go:build cli_e2e

package main_test

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
)

func repoRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("failed to locate test file")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(file), "../.."))
}

func buildFreudBinary(t *testing.T, repoRoot string) string {
	t.Helper()
	binDir := t.TempDir()
	binPath := filepath.Join(binDir, "freud")
	cmd := exec.Command("go", "build", "-o", binPath, "./cli")
	cmd.Dir = filepath.Join(repoRoot, "freud")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("building freud binary: %v\n%s", err, out)
	}
	return binPath
}

func writeFile(t *testing.T, path, content string, mode os.FileMode) string {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("mkdir %s: %v", filepath.Dir(path), err)
	}
	if err := os.WriteFile(path, []byte(content), mode); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
	return path
}

func writeMockNeopsyke(t *testing.T, dir string) string {
	t.Helper()
	script := `#!/usr/bin/env bash
set -euo pipefail

contains_arg() {
  local want="$1"
  shift
  local arg
  for arg in "$@"; do
    if [[ "$arg" == "$want" ]]; then
      return 0
    fi
  done
  return 1
}

if contains_arg "--record-session" "$@"; then
  PORT="${FREUD_TEST_DASHBOARD_PORT:-8787}"
  HOST="${FREUD_TEST_DASHBOARD_HOST:-127.0.0.1}"
  SESSION_DIR="${FREUD_TEST_INTERACTIVE_SESSION_DIR:-$PWD/.neopsyke/runs/freud/mock-interactive-session/session}"
  mkdir -p "$SESSION_DIR"
  printf '{"type":"interactive_signal"}\n' > "$SESSION_DIR/signals.jsonl"
  printf '{"session":"ok"}\n' > "$SESSION_DIR/session-manifest.json"
  printf '{"sequence_index":1}\n' > "$SESSION_DIR/llm-cache.jsonl"
  echo "signals will be written to $SESSION_DIR"
  python3 - "$HOST" "$PORT" <<'PY' &
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

host = sys.argv[1]
port = int(sys.argv[2])
state = {"assistant": ""}

class Handler(BaseHTTPRequestHandler):
    def _write(self, obj, status=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/api/chat/sessions":
            self._write({"sessions": []})
            return
        if self.path == "/api/chat/sessions/sess-1":
            messages = []
            if state["assistant"]:
                messages.append({"role": "assistant", "content": state["assistant"]})
            self._write({"messages": messages})
            return
        self._write({"error": "not found"}, 404)

    def do_POST(self):
        if self.path == "/api/chat/sessions":
            self._write({"session_id": "sess-1"})
            return
        if self.path == "/api/chat/sessions/sess-1/messages":
            state["assistant"] = "4"
            self._write({"accepted": True, "enqueued": True})
            return
        self._write({"error": "not found"}, 404)

HTTPServer((host, port), Handler).serve_forever()
PY
  server_pid=$!
  while IFS= read -r line; do
    if [[ "$line" == "exit" ]]; then
      break
    fi
  done
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  exit 0
fi

if contains_arg "--eval-memory-live" "$@"; then
  mkdir -p "$PWD/.neopsyke/evals/memory-live/runs"
  printf '{"summary":{"totalModelCalls":1},"totalModelCalls":1,"totalTokens":11}\n' > "$PWD/.neopsyke/evals/memory-live/runs/memory-live-eval-mock.json"
  echo "memory live smoke ok"
  exit 0
fi

MODE="${NEOPSYKE_SESSION_RECORDING_MODE:-off}"
SESSION_DIR="${NEOPSYKE_SESSION_RECORDING_DIR:-}"
CACHE_FILE="${NEOPSYKE_LLM_CACHE_FILE:-}"
EVENT_LOG="${NEOPSYKE_EVENT_LOG_FILE:-}"

if [[ "$MODE" == "record" && -n "$SESSION_DIR" ]]; then
  mkdir -p "$SESSION_DIR"
  printf '{"type":"signal"}\n' > "$SESSION_DIR/signals.jsonl"
  printf '{"session":"ok"}\n' > "$SESSION_DIR/session-manifest.json"
fi

if [[ -n "$CACHE_FILE" ]]; then
  mkdir -p "$(dirname "$CACHE_FILE")"
  printf '{"sequence_index":1}\n' > "$CACHE_FILE"
fi

if [[ -n "$EVENT_LOG" ]]; then
  mkdir -p "$(dirname "$EVENT_LOG")"
  : > "$EVENT_LOG"
  if [[ "$MODE" == "replay" ]]; then
    printf '{"type":"llm_cache_hit","data":{"actor":"planner"}}\n' >> "$EVENT_LOG"
    printf '{"type":"session_channel_replay_hit","data":{"channel":"signals"}}\n' >> "$EVENT_LOG"
  else
    printf '{"type":"llm_cache_miss","data":{"actor":"planner"}}\n' >> "$EVENT_LOG"
  fi
fi

printf 'ego> 4\n'
`
	return writeFile(t, filepath.Join(dir, "mock-neopsyke.sh"), script, 0o755)
}

func writeLiveConfig(t *testing.T, dir, neopsykeCmd string, extra string) string {
	t.Helper()
	llmConfig := writeFile(t, filepath.Join(dir, "llm.yaml"), "model: mock\n", 0o644)
	config := fmt.Sprintf(`project:
  name: neopsyke
  run_root: %q
  retention_days: 3
  gradle_home: %q
live_eval:
  timeout: 15
  llm_config_file: %q
  neopsyke_cmd: %q
session:
  record: false
  replay_test_enabled: false
memory_live:
  enabled: false
  task_ids:
    - user-preference-color
  stage: freud-memory-live-smoke
  max_attempts: 1
%s
`, filepath.Join(dir, "runs"), filepath.Join(dir, "gradle-home"), llmConfig, neopsykeCmd, extra)
	return writeFile(t, filepath.Join(dir, "freud-test.yaml"), config, 0o644)
}

func runFreud(t *testing.T, repoRoot, binPath string, env []string, args ...string) (string, int) {
	t.Helper()
	cmd := exec.Command(binPath, args...)
	cmd.Dir = repoRoot
	cmd.Env = append(os.Environ(), env...)
	out, err := cmd.CombinedOutput()
	if err == nil {
		return string(out), 0
	}
	if exitErr, ok := err.(*exec.ExitError); ok {
		return string(out), exitErr.ExitCode()
	}
	t.Fatalf("running freud: %v\n%s", err, out)
	return "", 1
}

func parseRunDir(t *testing.T, output string) string {
	t.Helper()
	for _, pattern := range []string{
		`Run directory: ([^\n]+)`,
		`\[freud\] run_dir=([^\n]+)`,
	} {
		re := regexp.MustCompile(pattern)
		if match := re.FindStringSubmatch(output); len(match) == 2 {
			return strings.TrimSpace(match[1])
		}
	}
	t.Fatalf("run dir not found in output:\n%s", output)
	return ""
}

func TestEvalSessionReplayWithoutInputEndToEnd(t *testing.T) {
	root := repoRoot(t)
	bin := buildFreudBinary(t, root)
	tmp := t.TempDir()
	mock := writeMockNeopsyke(t, tmp)
	cfg := writeLiveConfig(t, tmp, mock, "")

	runDir := filepath.Join(tmp, "recorded-run")
	writeFile(t, filepath.Join(runDir, "session", "signals.jsonl"), `{"type":"signal"}`+"\n", 0o644)
	writeFile(t, filepath.Join(runDir, "session", "session-manifest.json"), `{"session":"ok"}`+"\n", 0o644)
	writeFile(t, filepath.Join(runDir, "session", "llm-cache.jsonl"), `{"sequence_index":1}`+"\n", 0o644)

	output, exitCode := runFreud(t, root, bin, []string{"FREUD_WRITE_LOCAL_POINTERS=false"}, "--config", cfg, "eval", "--session-replay", runDir)
	if exitCode != 0 {
		t.Fatalf("eval replay failed (%d):\n%s", exitCode, output)
	}
	if !strings.Contains(output, "Verdict: pass") {
		t.Fatalf("expected pass verdict, got:\n%s", output)
	}
}

func TestReplayEvalCommandEndToEnd(t *testing.T) {
	root := repoRoot(t)
	bin := buildFreudBinary(t, root)
	tmp := t.TempDir()
	mock := writeMockNeopsyke(t, tmp)
	cfg := writeLiveConfig(t, tmp, mock, "")

	output, exitCode := runFreud(t, root, bin, []string{"FREUD_WRITE_LOCAL_POINTERS=false"}, "--config", cfg, "test-replay-eval")
	if exitCode != 0 {
		t.Fatalf("test-replay-eval failed (%d):\n%s", exitCode, output)
	}
	if !strings.Contains(output, "PASSED") {
		t.Fatalf("expected pass output, got:\n%s", output)
	}
}

func TestReplayInteractiveCommandEndToEnd(t *testing.T) {
	root := repoRoot(t)
	bin := buildFreudBinary(t, root)
	tmp := t.TempDir()
	mock := writeMockNeopsyke(t, tmp)
	cfg := writeLiveConfig(t, tmp, mock, "")
	port := 18787

	env := []string{
		"FREUD_WRITE_LOCAL_POINTERS=false",
		fmt.Sprintf("FREUD_TEST_DASHBOARD_PORT=%d", port),
		"FREUD_TEST_DASHBOARD_HOST=127.0.0.1",
		fmt.Sprintf("FREUD_TEST_INTERACTIVE_SESSION_DIR=%s", filepath.Join(tmp, "interactive-session")),
	}
	output, exitCode := runFreud(t, root, bin, env, "--config", cfg, "test-replay-interactive", "--dashboard-port", fmt.Sprintf("%d", port))
	if exitCode != 0 {
		t.Fatalf("test-replay-interactive failed (%d):\n%s", exitCode, output)
	}
	if !strings.Contains(output, "PASS") {
		t.Fatalf("expected passing output, got:\n%s", output)
	}
}

func TestRunMemoryLiveSmokeOnlyEndToEnd(t *testing.T) {
	root := repoRoot(t)
	bin := buildFreudBinary(t, root)
	tmp := t.TempDir()
	mock := writeMockNeopsyke(t, tmp)
	cfg := writeFile(t, filepath.Join(tmp, "freud-memory.yaml"), fmt.Sprintf(`project:
  name: neopsyke
  run_root: %q
  retention_days: 3
  gradle_home: %q
pipeline:
  - name: memory_live_smoke
    live_only: true
live_eval:
  timeout: 15
  llm_config_file: %q
  neopsyke_cmd: %q
session:
  record: false
  replay_test_enabled: false
memory_live:
  enabled: true
  task_ids:
    - user-preference-color
  stage: freud-memory-live-smoke
  max_attempts: 1
`, filepath.Join(tmp, "runs"), filepath.Join(tmp, "gradle-home"), writeFile(t, filepath.Join(tmp, "llm.yaml"), "model: mock\n", 0o644), mock), 0o644)

	output, exitCode := runFreud(t, root, bin, []string{"FREUD_WRITE_LOCAL_POINTERS=false"}, "--config", cfg, "run", "memory-smoke", "--live")
	if exitCode != 0 {
		t.Fatalf("memory_live_smoke run failed (%d):\n%s", exitCode, output)
	}
	if !strings.Contains(output, "memory_live_smoke") {
		t.Fatalf("expected memory_live_smoke output, got:\n%s", output)
	}
}

func TestRunDryRunWritesParityArtifactsAndPointers(t *testing.T) {
	root := repoRoot(t)
	bin := buildFreudBinary(t, root)
	tmp := t.TempDir()
	cfg := writeFile(t, filepath.Join(tmp, "freud-test.yaml"), fmt.Sprintf(`project:
  name: neopsyke
  run_root: %q
  retention_days: 3
  gradle_home: %q
pipeline:
  - name: smoke_step
    cmd: "printf 'hello\n'"
`, filepath.Join(tmp, "runs"), filepath.Join(tmp, "gradle-home")), 0o644)

	output, exitCode := runFreud(t, root, bin, []string{"FREUD_WRITE_LOCAL_POINTERS=false"}, "--config", cfg, "run", "artifact-smoke", "--dry-run")
	if exitCode != 0 {
		t.Fatalf("dry-run failed (%d):\n%s", exitCode, output)
	}

	runDir := parseRunDir(t, output)
	for _, path := range []string{
		filepath.Join(runDir, "artifacts", "failures.json"),
		filepath.Join(runDir, "artifacts", "run-index.json"),
		filepath.Join(runDir, "artifacts", "run-index.md"),
		filepath.Join(runDir, "artifacts", "summary.json"),
		filepath.Join(tmp, "runs", "latest-run.txt"),
	} {
		if _, err := os.Stat(path); err != nil {
			t.Fatalf("expected artifact/pointer %s: %v", path, err)
		}
	}
}

func TestRunLiveFailsFastWithoutLaneConfig(t *testing.T) {
	root := repoRoot(t)
	bin := buildFreudBinary(t, root)
	tmp := t.TempDir()
	cfg := writeFile(t, filepath.Join(tmp, "freud-test.yaml"), fmt.Sprintf(`project:
  name: neopsyke
  run_root: %q
pipeline:
  - name: reasoning_eval_model
    live_only: true
`, filepath.Join(tmp, "runs")), 0o644)

	output, exitCode := runFreud(t, root, bin, []string{"FREUD_WRITE_LOCAL_POINTERS=false"}, "--config", cfg, "run", "live-misconfig", "--live")
	if exitCode == 0 {
		t.Fatalf("expected live validation failure, got success:\n%s", output)
	}
	if !strings.Contains(output, "live_eval.llm_config_file") {
		t.Fatalf("expected llm_config_file validation error, got:\n%s", output)
	}
}
