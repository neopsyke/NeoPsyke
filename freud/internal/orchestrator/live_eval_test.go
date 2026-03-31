package orchestrator

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// createMockNeopsyke creates a shell script that echoes "ego> <answer>" to stdout.
func createMockNeopsyke(dir, answer string) string {
	scriptPath := filepath.Join(dir, "mock-neopsyke.sh")
	// Ignore all flags, just echo the answer
	script := "#!/bin/bash\necho 'ego> " + answer + "'\n"
	os.WriteFile(scriptPath, []byte(script), 0o755)
	return scriptPath
}

// createMockNeopsykeWithExit creates a mock that exits with the given code.
func createMockNeopsykeWithExit(dir string, exitCode int, stderr string) string {
	scriptPath := filepath.Join(dir, "mock-neopsyke.sh")
	script := "#!/bin/bash\n"
	if stderr != "" {
		script += "echo '" + stderr + "' >&2\n"
	}
	script += "exit " + string(rune('0'+exitCode)) + "\n"
	os.WriteFile(scriptPath, []byte(script), 0o755)
	return scriptPath
}

func TestLiveEvalDryRun(t *testing.T) {
	dir := t.TempDir()
	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("What is 2+2?\n"), 0o644)

	result, err := LiveEval(LiveEvalOpts{
		InputFile:  inputFile,
		Timeout:    10,
		RunRootAbs: filepath.Join(dir, "runs"),
		RepoRoot:   dir,
		DryRun:     true,
	})
	if err != nil {
		t.Fatalf("LiveEval dry-run failed: %v", err)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0, got %d", result.ExitCode)
	}
	if result.RunDir == "" {
		t.Error("expected run dir to be set")
	}
}

func TestLiveEvalPassWithMock(t *testing.T) {
	dir := t.TempDir()
	mockScript := createMockNeopsyke(dir, "4")

	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("What is 2+2?\n"), 0o644)

	expectedFile := filepath.Join(dir, "expected.txt")
	os.WriteFile(expectedFile, []byte("4\n"), 0o644)

	result, err := LiveEval(LiveEvalOpts{
		InputFile:    inputFile,
		ExpectedFile: expectedFile,
		Timeout:      10,
		NeopsykeCmd:  mockScript,
		RunRootAbs:   filepath.Join(dir, "runs"),
		RepoRoot:     dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}
	if result.Verdict.Verdict != "pass" {
		t.Errorf("expected pass, got %s (detail: %s)", result.Verdict.Verdict, result.Verdict.Detail)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0, got %d", result.ExitCode)
	}

	// Check artifacts exist
	if !fileExists(filepath.Join(result.RunDir, "artifacts", "answer.txt")) {
		t.Error("answer.txt should exist")
	}
	if !fileExists(filepath.Join(result.RunDir, "artifacts", "verdict.json")) {
		t.Error("verdict.json should exist")
	}
	if !fileExists(filepath.Join(result.RunDir, "artifacts", "input.txt")) {
		t.Error("input.txt should be copied to artifacts")
	}
}

func TestLiveEvalFailMismatch(t *testing.T) {
	dir := t.TempDir()
	mockScript := createMockNeopsyke(dir, "5")

	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("What is 2+2?\n"), 0o644)

	expectedFile := filepath.Join(dir, "expected.txt")
	os.WriteFile(expectedFile, []byte("4\n"), 0o644)

	result, err := LiveEval(LiveEvalOpts{
		InputFile:    inputFile,
		ExpectedFile: expectedFile,
		Timeout:      10,
		NeopsykeCmd:  mockScript,
		RunRootAbs:   filepath.Join(dir, "runs"),
		RepoRoot:     dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}
	if result.Verdict.Verdict != "fail" {
		t.Errorf("expected fail, got %s", result.Verdict.Verdict)
	}
	if result.Verdict.FailureClass != "live_eval_scoring_failure" {
		t.Errorf("expected live_eval_scoring_failure, got %s", result.Verdict.FailureClass)
	}
}

func TestLiveEvalNoExpected(t *testing.T) {
	dir := t.TempDir()
	mockScript := createMockNeopsyke(dir, "hello world")

	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("Say hello\n"), 0o644)

	result, err := LiveEval(LiveEvalOpts{
		InputFile:   inputFile,
		Timeout:     10,
		NeopsykeCmd: mockScript,
		RunRootAbs:  filepath.Join(dir, "runs"),
		RepoRoot:    dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}
	if result.Verdict.Verdict != "pass" {
		t.Errorf("expected pass (no expected file), got %s", result.Verdict.Verdict)
	}
}

func TestLiveEvalProcessFailure(t *testing.T) {
	dir := t.TempDir()
	scriptPath := filepath.Join(dir, "mock-neopsyke.sh")
	os.WriteFile(scriptPath, []byte("#!/bin/bash\necho 'something went wrong' >&2\nexit 1\n"), 0o755)

	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("test\n"), 0o644)

	result, err := LiveEval(LiveEvalOpts{
		InputFile:   inputFile,
		Timeout:     10,
		NeopsykeCmd: scriptPath,
		RunRootAbs:  filepath.Join(dir, "runs"),
		RepoRoot:    dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}
	if result.Verdict.Verdict != "error" {
		t.Errorf("expected error, got %s", result.Verdict.Verdict)
	}
}

func TestLiveEvalTimeout(t *testing.T) {
	dir := t.TempDir()
	// Script that exits with code 2 (simulating neopsyke timeout)
	scriptPath := filepath.Join(dir, "mock-neopsyke.sh")
	os.WriteFile(scriptPath, []byte("#!/bin/bash\nexit 2\n"), 0o755)

	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("test\n"), 0o644)

	result, err := LiveEval(LiveEvalOpts{
		InputFile:   inputFile,
		Timeout:     10,
		NeopsykeCmd: scriptPath,
		RunRootAbs:  filepath.Join(dir, "runs"),
		RepoRoot:    dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}
	if result.Verdict.Verdict != "timeout" {
		t.Errorf("expected timeout, got %s", result.Verdict.Verdict)
	}
}

func TestLiveEvalIsolation(t *testing.T) {
	dir := t.TempDir()
	// Script that prints env vars we care about
	scriptPath := filepath.Join(dir, "mock-neopsyke.sh")
	os.WriteFile(scriptPath, []byte("#!/bin/bash\necho \"ego> env_ok\"\necho \"NAMESPACE=$MEMORY_DEFAULT_NAMESPACE\" >&2\necho \"LOGBOOK=$NEOPSYKE_LOGBOOK_DB_PATH\" >&2\n"), 0o755)

	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("test\n"), 0o644)

	result, err := LiveEval(LiveEvalOpts{
		InputFile:   inputFile,
		Timeout:     10,
		NeopsykeCmd: scriptPath,
		RunRootAbs:  filepath.Join(dir, "runs"),
		RepoRoot:    dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}

	// Check isolation files exist
	stateDir := filepath.Join(result.RunDir, "state")
	if !fileExists(filepath.Join(stateDir, "pgvector-namespace.txt")) {
		t.Error("pgvector-namespace.txt should exist")
	}

	// Check namespace is unique
	nsData, _ := os.ReadFile(filepath.Join(stateDir, "pgvector-namespace.txt"))
	ns := string(nsData)
	if ns == "" || ns == "\n" {
		t.Error("namespace should not be empty")
	}
}

func TestLiveEvalRunDirOverride(t *testing.T) {
	dir := t.TempDir()
	mockScript := createMockNeopsyke(dir, "ok")

	inputFile := filepath.Join(dir, "input.txt")
	os.WriteFile(inputFile, []byte("test\n"), 0o644)

	customRunDir := filepath.Join(dir, "custom-run")

	result, err := LiveEval(LiveEvalOpts{
		InputFile:      inputFile,
		Timeout:        10,
		NeopsykeCmd:    mockScript,
		RunDirOverride: customRunDir,
		RunRootAbs:     filepath.Join(dir, "runs"),
		RepoRoot:       dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}
	if result.RunDir != customRunDir {
		t.Errorf("expected run dir %s, got %s", customRunDir, result.RunDir)
	}
}

func TestLiveEvalSessionReplayFallsBackToArtifactsCache(t *testing.T) {
	dir := t.TempDir()
	recordDir := filepath.Join(dir, "recorded-run")
	sessionDir := filepath.Join(recordDir, "session")
	artifactsDir := filepath.Join(recordDir, "artifacts")
	if err := os.MkdirAll(sessionDir, 0o755); err != nil {
		t.Fatalf("creating session dir: %v", err)
	}
	if err := os.MkdirAll(artifactsDir, 0o755); err != nil {
		t.Fatalf("creating artifacts dir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(sessionDir, "signals.jsonl"), []byte("{\"type\":\"signal\"}\n"), 0o644); err != nil {
		t.Fatalf("writing session signal: %v", err)
	}
	if err := os.WriteFile(filepath.Join(artifactsDir, "llm-cache.jsonl"), []byte("{\"sequence_index\":1}\n"), 0o644); err != nil {
		t.Fatalf("writing artifacts cache: %v", err)
	}

	scriptPath := filepath.Join(dir, "mock-neopsyke.sh")
	script := "#!/bin/bash\n" +
		"echo 'ego> replay_ok'\n" +
		"echo \"CACHE=$NEOPSYKE_LLM_CACHE_FILE\" >&2\n" +
		"echo \"CACHE_MODE=$NEOPSYKE_LLM_CACHE_MODE\" >&2\n" +
		"echo \"SESSION_MODE=$NEOPSYKE_SESSION_RECORDING_MODE\" >&2\n" +
		"echo \"SESSION_DIR=$NEOPSYKE_SESSION_RECORDING_DIR\" >&2\n"
	if err := os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		t.Fatalf("writing mock script: %v", err)
	}

	result, err := LiveEval(LiveEvalOpts{
		SessionReplayDir: recordDir,
		Timeout:          10,
		NeopsykeCmd:      scriptPath,
		RunRootAbs:       filepath.Join(dir, "runs"),
		RepoRoot:         dir,
	})
	if err != nil {
		t.Fatalf("LiveEval failed: %v", err)
	}

	stderrData, err := os.ReadFile(filepath.Join(result.RunDir, "logs", "stderr.log"))
	if err != nil {
		t.Fatalf("reading stderr log: %v", err)
	}
	stderrText := string(stderrData)

	expectedCache := filepath.Join(recordDir, "artifacts", "llm-cache.jsonl")
	expectedSession := filepath.Join(recordDir, "session")
	if !strings.Contains(stderrText, "CACHE="+expectedCache) {
		t.Fatalf("expected fallback cache path %q in stderr log, got: %s", expectedCache, stderrText)
	}
	if !strings.Contains(stderrText, "CACHE_MODE=replay") {
		t.Fatalf("expected replay cache mode in stderr log, got: %s", stderrText)
	}
	if !strings.Contains(stderrText, "SESSION_MODE=replay") {
		t.Fatalf("expected replay session mode in stderr log, got: %s", stderrText)
	}
	if !strings.Contains(stderrText, "SESSION_DIR="+expectedSession) {
		t.Fatalf("expected replay session dir %q in stderr log, got: %s", expectedSession, stderrText)
	}
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
