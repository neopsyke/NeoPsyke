package orchestrator

import (
	"os"
	"path/filepath"
	"testing"
)

func TestClassifyFailureTimeout(t *testing.T) {
	dir := t.TempDir()
	stderr := filepath.Join(dir, "stderr.log")
	appLog := filepath.Join(dir, "app.log")
	os.WriteFile(stderr, []byte(""), 0o644)
	os.WriteFile(appLog, []byte(""), 0o644)

	if got := ClassifyFailure(2, stderr, appLog); got != "timeout" {
		t.Errorf("expected 'timeout', got %q", got)
	}
}

func TestClassifyFailureBootstrap(t *testing.T) {
	dir := t.TempDir()
	stderr := filepath.Join(dir, "stderr.log")
	os.WriteFile(stderr, []byte("Error: Permission denied while starting Gradle"), 0o644)
	appLog := filepath.Join(dir, "app.log")
	os.WriteFile(appLog, []byte(""), 0o644)

	if got := ClassifyFailure(1, stderr, appLog); got != "local_runtime_bootstrap_failure" {
		t.Errorf("expected 'local_runtime_bootstrap_failure', got %q", got)
	}
}

func TestClassifyFailureProvider(t *testing.T) {
	dir := t.TempDir()
	stderr := filepath.Join(dir, "stderr.log")
	os.WriteFile(stderr, []byte(""), 0o644)
	appLog := filepath.Join(dir, "app.log")
	os.WriteFile(appLog, []byte("API returned 429 rate limit exceeded"), 0o644)

	if got := ClassifyFailure(1, stderr, appLog); got != "provider_model_failure" {
		t.Errorf("expected 'provider_model_failure', got %q", got)
	}
}

func TestClassifyFailureGeneric(t *testing.T) {
	dir := t.TempDir()
	stderr := filepath.Join(dir, "stderr.log")
	os.WriteFile(stderr, []byte("something went wrong"), 0o644)
	appLog := filepath.Join(dir, "app.log")
	os.WriteFile(appLog, []byte(""), 0o644)

	if got := ClassifyFailure(1, stderr, appLog); got != "live_eval_process_failure" {
		t.Errorf("expected 'live_eval_process_failure', got %q", got)
	}
}

func TestScoreVerdictPass(t *testing.T) {
	dir := t.TempDir()
	expected := filepath.Join(dir, "expected.txt")
	answer := filepath.Join(dir, "answer.txt")
	os.WriteFile(expected, []byte("4"), 0o644)
	os.WriteFile(answer, []byte("ego> 4"), 0o644)

	v := ScoreVerdict(0, expected, answer, "", "")
	if v.Verdict != "pass" {
		t.Errorf("expected pass, got %q", v.Verdict)
	}
}

func TestScoreVerdictFail(t *testing.T) {
	dir := t.TempDir()
	expected := filepath.Join(dir, "expected.txt")
	answer := filepath.Join(dir, "answer.txt")
	os.WriteFile(expected, []byte("4"), 0o644)
	os.WriteFile(answer, []byte("ego> 5"), 0o644)

	v := ScoreVerdict(0, expected, answer, "", "")
	if v.Verdict != "fail" {
		t.Errorf("expected fail, got %q", v.Verdict)
	}
	if v.FailureClass != "live_eval_scoring_failure" {
		t.Errorf("expected live_eval_scoring_failure, got %q", v.FailureClass)
	}
}

func TestScoreVerdictNoExpected(t *testing.T) {
	v := ScoreVerdict(0, "", "", "", "")
	if v.Verdict != "pass" {
		t.Errorf("expected pass with no expected file, got %q", v.Verdict)
	}
}

func TestScoreVerdictTimeout(t *testing.T) {
	v := ScoreVerdict(2, "", "", "", "")
	if v.Verdict != "timeout" {
		t.Errorf("expected timeout, got %q", v.Verdict)
	}
}

func TestScoreVerdictError(t *testing.T) {
	dir := t.TempDir()
	stderr := filepath.Join(dir, "stderr.log")
	os.WriteFile(stderr, []byte("generic error"), 0o644)

	v := ScoreVerdict(1, "", "", stderr, "")
	if v.Verdict != "error" {
		t.Errorf("expected error, got %q", v.Verdict)
	}
}

func TestWriteAndReadVerdictJSON(t *testing.T) {
	dir := t.TempDir()

	v := Verdict{
		Verdict:    "pass",
		Detail:     "test",
		ExitCode:   0,
		RunDir:     "/tmp/run",
		CacheMode:  "record",
		TimeoutSec: 120,
	}

	if err := WriteVerdictJSON(v, dir); err != nil {
		t.Fatalf("WriteVerdictJSON failed: %v", err)
	}

	v2, err := ReadVerdictJSON(dir)
	if err != nil {
		t.Fatalf("ReadVerdictJSON failed: %v", err)
	}

	if v2.Verdict != "pass" || v2.TimeoutSec != 120 || v2.RunDir != "/tmp/run" {
		t.Errorf("read verdict doesn't match written: %+v", v2)
	}
}
