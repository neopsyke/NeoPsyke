package orchestrator

import (
	"os"
	"path/filepath"
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
		InputFile:  inputFile,
		Timeout:    10,
		NeopsykeCmd: mockScript,
		RunRootAbs: filepath.Join(dir, "runs"),
		RepoRoot:   dir,
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
		InputFile:  inputFile,
		Timeout:    10,
		NeopsykeCmd: scriptPath,
		RunRootAbs: filepath.Join(dir, "runs"),
		RepoRoot:   dir,
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
		InputFile:  inputFile,
		Timeout:    10,
		NeopsykeCmd: scriptPath,
		RunRootAbs: filepath.Join(dir, "runs"),
		RepoRoot:   dir,
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
		InputFile:  inputFile,
		Timeout:    10,
		NeopsykeCmd: scriptPath,
		RunRootAbs: filepath.Join(dir, "runs"),
		RepoRoot:   dir,
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

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
