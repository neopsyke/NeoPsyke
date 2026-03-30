package orchestrator

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCreateRunDir(t *testing.T) {
	root := t.TempDir()

	dir, err := CreateRunDir(root, "live-eval", []string{"logs", "artifacts", "state"})
	if err != nil {
		t.Fatalf("CreateRunDir failed: %v", err)
	}

	base := filepath.Base(dir)
	if !strings.Contains(base, "live-eval") {
		t.Errorf("dir name %q doesn't contain 'live-eval'", base)
	}

	for _, sub := range []string{"logs", "artifacts", "state"} {
		info, err := os.Stat(filepath.Join(dir, sub))
		if err != nil {
			t.Errorf("subdir %s doesn't exist: %v", sub, err)
		} else if !info.IsDir() {
			t.Errorf("%s is not a directory", sub)
		}
	}
}

func TestCreateRunDirCreatesRoot(t *testing.T) {
	root := filepath.Join(t.TempDir(), "nested", "deep")

	dir, err := CreateRunDir(root, "test", nil)
	if err != nil {
		t.Fatalf("CreateRunDir failed: %v", err)
	}
	if !strings.HasPrefix(dir, root) {
		t.Errorf("dir %q not under root %q", dir, root)
	}
}

func TestAppendRunIndex(t *testing.T) {
	root := t.TempDir()

	// Append two entries
	AppendRunIndex(root, RunIndexEntry{
		Timestamp: "20260327T120000Z",
		FeatureID: "my-feature",
		RunDir:    "/path/to/run1",
		Status:    "pass",
	})
	AppendRunIndex(root, RunIndexEntry{
		Timestamp: "20260327T120100Z",
		FeatureID: "other-feature",
		RunDir:    "/path/to/run2",
		Status:    "fail",
	})

	// Read and verify
	data, err := os.ReadFile(filepath.Join(root, "run-index.tsv"))
	if err != nil {
		t.Fatalf("reading run index: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2 {
		t.Fatalf("expected 2 lines, got %d: %v", len(lines), lines)
	}

	if !strings.Contains(lines[0], "my-feature") || !strings.Contains(lines[0], "pass") {
		t.Errorf("first line should contain my-feature/pass: %s", lines[0])
	}
	if !strings.Contains(lines[1], "other-feature") || !strings.Contains(lines[1], "fail") {
		t.Errorf("second line should contain other-feature/fail: %s", lines[1])
	}
}

func TestAppendRunIndexConcurrent(t *testing.T) {
	root := t.TempDir()

	done := make(chan bool, 10)
	for i := 0; i < 10; i++ {
		go func(n int) {
			AppendRunIndex(root, RunIndexEntry{
				Timestamp: "20260327T120000Z",
				FeatureID: "concurrent",
				RunDir:    "/path/to/run",
				Status:    "pass",
			})
			done <- true
		}(i)
	}
	for i := 0; i < 10; i++ {
		<-done
	}

	data, _ := os.ReadFile(filepath.Join(root, "run-index.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 10 {
		t.Errorf("expected 10 lines from concurrent writes, got %d", len(lines))
	}
}

func TestResolveAbsPath(t *testing.T) {
	if p := ResolveAbsPath("/abs/path", "/repo"); p != "/abs/path" {
		t.Errorf("expected /abs/path, got %s", p)
	}
	if p := ResolveAbsPath("relative/path", "/repo"); p != "/repo/relative/path" {
		t.Errorf("expected /repo/relative/path, got %s", p)
	}
}

func TestCleanupOldRuns(t *testing.T) {
	root := t.TempDir()

	// Create an "old" run dir
	oldDir := filepath.Join(root, "20200101T000000Z-live-eval-abc123")
	os.MkdirAll(filepath.Join(oldDir, "state"), 0o755)
	oldTime := time.Now().Add(-10 * 24 * time.Hour)
	os.Chtimes(oldDir, oldTime, oldTime)

	// Create a "new" run dir
	newDir := filepath.Join(root, "20990101T000000Z-live-eval-xyz789")
	os.MkdirAll(newDir, 0o755)

	cleaned, err := CleanupOldRuns(root, 3, "live-eval")
	if err != nil {
		t.Fatalf("CleanupOldRuns failed: %v", err)
	}

	if cleaned != 1 {
		t.Errorf("expected 1 cleaned, got %d", cleaned)
	}
	if _, err := os.Stat(oldDir); !os.IsNotExist(err) {
		t.Error("old dir should have been deleted")
	}
	if _, err := os.Stat(newDir); err != nil {
		t.Error("new dir should still exist")
	}
}

func TestCleanupOldRunsSkipsNonMatchingPrefix(t *testing.T) {
	root := t.TempDir()

	otherDir := filepath.Join(root, "20200101T000000Z-feature-abc")
	os.MkdirAll(otherDir, 0o755)
	oldTime := time.Now().Add(-10 * 24 * time.Hour)
	os.Chtimes(otherDir, oldTime, oldTime)

	cleaned, _ := CleanupOldRuns(root, 3, "live-eval")
	if cleaned != 0 {
		t.Errorf("expected 0 cleaned (wrong prefix), got %d", cleaned)
	}
}

func TestCleanupAllOldRuns(t *testing.T) {
	root := t.TempDir()

	// Create old dirs of different types
	for _, name := range []string{"20200101T000000Z-signoff-gate-abc", "20200101T000000Z-live-eval-xyz", "20200101T000000Z-bbh-low-llm-def"} {
		dir := filepath.Join(root, name)
		os.MkdirAll(dir, 0o755)
		oldTime := time.Now().Add(-10 * 24 * time.Hour)
		os.Chtimes(dir, oldTime, oldTime)
	}

	// Create a new dir
	newDir := filepath.Join(root, "20990101T000000Z-signoff-gate-new")
	os.MkdirAll(newDir, 0o755)

	cleaned, _ := CleanupAllOldRuns(root, 3)
	if cleaned != 3 {
		t.Errorf("expected 3 cleaned (all old types), got %d", cleaned)
	}
	if _, err := os.Stat(newDir); err != nil {
		t.Error("new dir should still exist")
	}
}
