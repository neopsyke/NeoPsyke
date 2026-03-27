package orchestrator

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBBHSmokeDryRun(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)

	result, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		Timeout:            10,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
		DryRun:             true,
	})
	if err != nil {
		t.Fatalf("BBHSmoke dry-run failed: %v", err)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0, got %d", result.ExitCode)
	}
}

func TestBBHSmokeAllPass(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)
	mockScript := createMockNeopsyke(dir, "4")

	result, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		MaxTimeouts:        0,
		Timeout:            10,
		NeopsykeCmd:        mockScript,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
	})
	if err != nil {
		t.Fatalf("BBHSmoke failed: %v", err)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0 (all pass), got %d", result.ExitCode)
	}

	// Check summary JSON
	data, err := os.ReadFile(result.SummaryJSON)
	if err != nil {
		t.Fatalf("reading summary: %v", err)
	}
	var summary map[string]interface{}
	json.Unmarshal(data, &summary)
	if summary["passed_cases"].(float64) != 2 {
		t.Errorf("expected 2 passed, got %v", summary["passed_cases"])
	}
	if summary["exact_match_rate_percent"].(float64) != 100 {
		t.Errorf("expected 100%% pass rate, got %v", summary["exact_match_rate_percent"])
	}

	// Check results TSV exists
	resultsTSV := filepath.Join(result.RunDir, "artifacts", "bbh-smoke-test-lane-results.tsv")
	if !fileExists(resultsTSV) {
		t.Error("results TSV should exist")
	}
}

func TestBBHSmokeMismatch(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)
	// Mock returns "5" but expected is "4"
	mockScript := createMockNeopsyke(dir, "5")

	result, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		Timeout:            10,
		NeopsykeCmd:        mockScript,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
	})
	if err != nil {
		t.Fatalf("BBHSmoke failed: %v", err)
	}
	// Should fail gate (0% pass rate < 100% min)
	if result.ExitCode != 2 {
		t.Errorf("expected exit 2 (gate failed), got %d", result.ExitCode)
	}
}

func TestBBHSmokeTimeout(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)

	// Mock that exits with code 2 (timeout)
	scriptPath := filepath.Join(dir, "mock-neopsyke.sh")
	os.WriteFile(scriptPath, []byte("#!/bin/bash\nexit 2\n"), 0o755)

	result, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 0,
		MaxTimeouts:        0,
		Timeout:            10,
		NeopsykeCmd:        scriptPath,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
	})
	if err != nil {
		t.Fatalf("BBHSmoke failed: %v", err)
	}
	// Should fail gate (timeouts > max_timeouts)
	if result.ExitCode != 2 {
		t.Errorf("expected exit 2, got %d", result.ExitCode)
	}
}

func TestBBHSmokeProgressArtifacts(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)
	mockScript := createMockNeopsyke(dir, "4")

	result, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		Timeout:            10,
		NeopsykeCmd:        mockScript,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
	})
	if err != nil {
		t.Fatalf("BBHSmoke failed: %v", err)
	}

	artDir := filepath.Join(result.RunDir, "artifacts")
	for _, name := range []string{
		"bbh-smoke-test-lane-results.tsv",
		"bbh-smoke-test-lane-summary.json",
		"bbh-smoke-test-lane-summary.md",
		"bbh-smoke-test-lane-progress.json",
		"bbh-smoke-test-lane-progress.md",
	} {
		if !fileExists(filepath.Join(artDir, name)) {
			t.Errorf("artifact %s should exist", name)
		}
	}
}

func TestBBHSmokeRegression(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)
	// All pass
	mockScript := createMockNeopsyke(dir, "4")

	// Create baseline with 100% pass rate
	baselinePath := filepath.Join(dir, "baseline.json")
	baseline := map[string]interface{}{"exact_match_rate_percent": 100.0}
	data, _ := json.Marshal(baseline)
	os.WriteFile(baselinePath, data, 0o644)

	// Mock returns wrong answer — 0% current vs 100% baseline
	mockWrong := filepath.Join(dir, "mock-wrong.sh")
	os.WriteFile(mockWrong, []byte("#!/bin/bash\necho 'ego> wrong'\n"), 0o755)

	result, err := BBHSmoke(BBHOpts{
		Lane:                 "test-lane",
		PromptsFile:          filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:          filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent:   0,
		MaxTimeouts:          99,
		MaxRegressionPercent: 0,
		BaselineFile:         baselinePath,
		Timeout:              10,
		NeopsykeCmd:          mockWrong,
		RunRootAbs:           filepath.Join(dir, "runs"),
		RepoRoot:             dir,
	})
	if err != nil {
		t.Fatalf("BBHSmoke failed: %v", err)
	}
	if result.ExitCode != 2 {
		t.Errorf("expected exit 2 (regression detected), got %d", result.ExitCode)
	}

	_ = mockScript // keep linter happy
}

// setupBBHFixtures creates a 2-case prompts.jsonl and answers.jsonl fixture.
func setupBBHFixtures(t *testing.T, dir string) {
	t.Helper()

	prompts := []string{
		`{"id":"q1","category":"math","prompt":"What is 2+2?"}`,
		`{"id":"q2","category":"math","prompt":"What is 3+1?"}`,
	}
	os.WriteFile(filepath.Join(dir, "prompts.jsonl"),
		[]byte(strings.Join(prompts, "\n")+"\n"), 0o644)

	answers := []string{
		`{"id":"q1","answer":"4"}`,
		`{"id":"q2","answer":"4"}`,
	}
	os.WriteFile(filepath.Join(dir, "answers.jsonl"),
		[]byte(strings.Join(answers, "\n")+"\n"), 0o644)
}
