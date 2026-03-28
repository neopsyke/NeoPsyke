package analysis

import (
	"os"
	"path/filepath"
	"testing"
)

func TestJSONEscape(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{`hello`, `hello`},
		{`say "hi"`, `say \"hi\"`},
		{"line\nnewline", `line\nnewline`},
		{"tab\there", `tab\there`},
		{`back\slash`, `back\\slash`},
		{"carriage\rreturn", "carriagereturn"},
	}
	for _, tc := range tests {
		result := JSONEscape(tc.input)
		if result != tc.expected {
			t.Errorf("JSONEscape(%q) = %q, want %q", tc.input, result, tc.expected)
		}
	}
}

func TestTSVEscape(t *testing.T) {
	result := TSVEscape("a\tb\nc\r")
	if result != "a b c " {
		t.Errorf("TSVEscape got %q", result)
	}
}

func TestPct(t *testing.T) {
	if Pct(1, 2) != "50.00" {
		t.Errorf("Pct(1,2) = %s", Pct(1, 2))
	}
	if Pct(0, 0) != "0.00" {
		t.Errorf("Pct(0,0) = %s", Pct(0, 0))
	}
	if Pct(3, 4) != "75.00" {
		t.Errorf("Pct(3,4) = %s", Pct(3, 4))
	}
}

func TestExtractJSONString(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "test.json")
	os.WriteFile(f, []byte(`{
  "status": "pass",
  "feature_id": "my-feature",
  "count": 42
}`), 0o644)

	if ExtractJSONString(f, "status") != "pass" {
		t.Error("expected status=pass")
	}
	if ExtractJSONString(f, "feature_id") != "my-feature" {
		t.Error("expected feature_id=my-feature")
	}
	if ExtractJSONString(f, "missing") != "" {
		t.Error("expected empty for missing key")
	}
	if ExtractJSONString("/nonexistent", "key") != "" {
		t.Error("expected empty for missing file")
	}
}

func TestExtractJSONNumber(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "test.json")
	os.WriteFile(f, []byte(`{
  "count": 42,
  "steps_failed": 0
}`), 0o644)

	if ExtractJSONNumber(f, "count") != "42" {
		t.Error("expected count=42")
	}
	if ExtractJSONNumber(f, "steps_failed") != "0" {
		t.Error("expected steps_failed=0")
	}
}

func TestCountLines(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "lines.txt")
	os.WriteFile(f, []byte("a\nb\n\nc\n"), 0o644)

	if CountLines(f) != 3 {
		t.Errorf("expected 3, got %d", CountLines(f))
	}
	if CountLines("/nonexistent") != 0 {
		t.Error("expected 0 for missing file")
	}
}

func TestHeadLines(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "lines.txt")
	os.WriteFile(f, []byte("a\nb\nc\nd\ne"), 0o644)

	lines := HeadLines(f, 3)
	if len(lines) != 3 {
		t.Errorf("expected 3 lines, got %d", len(lines))
	}
	if lines[0] != "a" || lines[2] != "c" {
		t.Errorf("unexpected lines: %v", lines)
	}
}

func TestNonEmptyLines(t *testing.T) {
	lines := NonEmptyLines("a\n\nb\n\nc")
	if len(lines) != 3 {
		t.Errorf("expected 3 non-empty lines, got %d", len(lines))
	}
}

func TestFileExists(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "exists.txt")
	os.WriteFile(f, []byte("x"), 0o644)

	if !FileExists(f) {
		t.Error("expected file to exist")
	}
	if FileExists(filepath.Join(dir, "nope.txt")) {
		t.Error("expected file to not exist")
	}
	if FileExists(dir) {
		t.Error("directories should not count as files")
	}
}

func TestDirExists(t *testing.T) {
	dir := t.TempDir()
	if !DirExists(dir) {
		t.Error("expected dir to exist")
	}
	if DirExists(filepath.Join(dir, "nope")) {
		t.Error("expected dir to not exist")
	}
}
