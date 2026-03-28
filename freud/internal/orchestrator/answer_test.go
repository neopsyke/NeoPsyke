package orchestrator

import (
	"os"
	"path/filepath"
	"testing"
)

func TestExtractAnswerLine(t *testing.T) {
	dir := t.TempDir()

	// Test with ego> lines
	f := filepath.Join(dir, "stdout.log")
	os.WriteFile(f, []byte("Starting agent...\nego> Hello\nProcessing...\nego> 4\n"), 0o644)
	if got := ExtractAnswerLine(f); got != "4" {
		t.Errorf("expected '4', got %q", got)
	}

	// Test with no ego> lines — fallback to full content
	f2 := filepath.Join(dir, "stdout2.log")
	os.WriteFile(f2, []byte("plain output\n"), 0o644)
	if got := ExtractAnswerLine(f2); got != "plain output" {
		t.Errorf("expected 'plain output', got %q", got)
	}

	// Test with missing file
	if got := ExtractAnswerLine("/nonexistent"); got != "" {
		t.Errorf("expected empty for missing file, got %q", got)
	}

	// Test with empty file
	f3 := filepath.Join(dir, "empty.log")
	os.WriteFile(f3, []byte(""), 0o644)
	if got := ExtractAnswerLine(f3); got != "" {
		t.Errorf("expected empty for empty file, got %q", got)
	}
}

func TestNormalizeAnswer(t *testing.T) {
	tests := []struct {
		input       string
		stripQuotes bool
		expected    string
	}{
		{"ego> Hello World", false, "hello world"},
		{"  HELLO  WORLD  ", false, "hello world"},
		{"line1\nline2", false, "line1 line2"},
		{`"answer"`, true, "answer"},
		{`"answer"`, false, `"answer"`},
		{"ego> The answer is 42.", false, "the answer is 42."},
		{"", false, ""},
		{"  ", false, ""},
	}

	for _, tc := range tests {
		got := NormalizeAnswer(tc.input, tc.stripQuotes)
		if got != tc.expected {
			t.Errorf("NormalizeAnswer(%q, %v) = %q, want %q", tc.input, tc.stripQuotes, got, tc.expected)
		}
	}
}
