package orchestrator

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTrailEmitter(t *testing.T) {
	dir := t.TempDir()

	te, err := NewTrailEmitter(dir)
	if err != nil {
		t.Fatalf("NewTrailEmitter failed: %v", err)
	}

	te.Emit("step_start", "full_tests", "", "starting full tests", "./gradlew test", "", "")
	te.Emit("step_end", "full_tests", "pass", "completed", "", "logs/02-full-tests.log", "")

	if te.Seq() != 2 {
		t.Errorf("expected seq=2, got %d", te.Seq())
	}

	te.Close()

	// Check JSONL
	trailData, _ := os.ReadFile(filepath.Join(dir, "trail.jsonl"))
	trailLines := strings.Split(strings.TrimSpace(string(trailData)), "\n")
	if len(trailLines) != 2 {
		t.Errorf("expected 2 trail lines, got %d", len(trailLines))
	}
	if !strings.Contains(trailLines[0], `"event":"step_start"`) {
		t.Errorf("first line should contain step_start: %s", trailLines[0])
	}
	if !strings.Contains(trailLines[1], `"status":"pass"`) {
		t.Errorf("second line should contain status pass: %s", trailLines[1])
	}

	// Check TSV index
	indexData, _ := os.ReadFile(filepath.Join(dir, "trail-index.tsv"))
	indexLines := strings.Split(strings.TrimSpace(string(indexData)), "\n")
	// Header + 2 data lines
	if len(indexLines) != 3 {
		t.Errorf("expected 3 index lines (header + 2), got %d", len(indexLines))
	}
	if !strings.HasPrefix(indexLines[0], "seq\t") {
		t.Errorf("first line should be header: %s", indexLines[0])
	}
}

func TestTrailEmitterConcurrent(t *testing.T) {
	dir := t.TempDir()
	te, _ := NewTrailEmitter(dir)
	defer te.Close()

	done := make(chan bool, 10)
	for i := 0; i < 10; i++ {
		go func(n int) {
			te.Emit("test_event", "step", "ok", "", "", "", "")
			done <- true
		}(i)
	}
	for i := 0; i < 10; i++ {
		<-done
	}

	if te.Seq() != 10 {
		t.Errorf("expected seq=10 after concurrent writes, got %d", te.Seq())
	}
}
