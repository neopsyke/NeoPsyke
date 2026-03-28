package orchestrator

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRunReasoningGateDryRun(t *testing.T) {
	dir := t.TempDir()

	// Create a mock neopsyke script
	mockScript := filepath.Join(dir, "run-neopsyke.sh")
	os.WriteFile(mockScript, []byte("#!/bin/bash\necho \"mock neopsyke: $@\"\n"), 0o755)

	err := RunReasoningGate(ReasoningGateOpts{
		NeopsykeCmd: mockScript,
		RepoRoot:    dir,
		DryRun:      true,
	})
	if err != nil {
		t.Fatalf("RunReasoningGate dry-run failed: %v", err)
	}
}

func TestRunReasoningGateWithMock(t *testing.T) {
	dir := t.TempDir()

	// Create a mock neopsyke script that always succeeds
	mockScript := filepath.Join(dir, "run-neopsyke.sh")
	os.WriteFile(mockScript, []byte("#!/bin/bash\nexit 0\n"), 0o755)

	err := RunReasoningGate(ReasoningGateOpts{
		NeopsykeCmd: mockScript,
		RepoRoot:    dir,
	})
	if err != nil {
		t.Fatalf("RunReasoningGate with mock failed: %v", err)
	}
}

func TestRunReasoningGateFailure(t *testing.T) {
	dir := t.TempDir()

	// Create a mock neopsyke script that fails
	mockScript := filepath.Join(dir, "run-neopsyke.sh")
	os.WriteFile(mockScript, []byte("#!/bin/bash\nexit 1\n"), 0o755)

	err := RunReasoningGate(ReasoningGateOpts{
		NeopsykeCmd: mockScript,
		RepoRoot:    dir,
	})
	if err == nil {
		t.Error("expected error when neopsyke fails")
	}
}
