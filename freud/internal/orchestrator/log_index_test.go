package orchestrator

import (
	"os"
	"path/filepath"
	"testing"
)

func TestIndexStepLogEmpty(t *testing.T) {
	dir := t.TempDir()
	logFile := filepath.Join(dir, "step.log")
	os.WriteFile(logFile, []byte("all good\nno issues here\n"), 0o644)

	indexDir := filepath.Join(dir, "log-index")
	counts, err := IndexStepLog("test_step", logFile, indexDir)
	if err != nil {
		t.Fatalf("IndexStepLog failed: %v", err)
	}

	if counts.Lines != 2 {
		t.Errorf("expected 2 lines, got %d", counts.Lines)
	}
	if counts.Warnings != 0 {
		t.Errorf("expected 0 warnings, got %d", counts.Warnings)
	}
	if counts.Errors != 0 {
		t.Errorf("expected 0 errors, got %d", counts.Errors)
	}

	// Index file should exist
	if _, err := os.Stat(filepath.Join(indexDir, "test_step.tsv")); err != nil {
		t.Errorf("index TSV should exist: %v", err)
	}
}

func TestIndexStepLogMissingFile(t *testing.T) {
	dir := t.TempDir()
	counts, err := IndexStepLog("missing", "/nonexistent/step.log", dir)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if counts.Lines != 0 {
		t.Errorf("expected 0 lines for missing file, got %d", counts.Lines)
	}
}

func TestExtractLineRef(t *testing.T) {
	if got := extractLineRef("file.log:42:some content"); got != "file.log:42" {
		t.Errorf("expected 'file.log:42', got %q", got)
	}
	if got := extractLineRef("plain line"); got != "plain line" {
		t.Errorf("expected 'plain line', got %q", got)
	}
}
