package orchestrator

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func createReplayAwareMockNeopsyke(dir, answer string) string {
	scriptPath := filepath.Join(dir, "mock-neopsyke-replay.sh")
	script := fmt.Sprintf(`#!/bin/bash
set -euo pipefail

if [[ "${NEOPSYKE_LLM_CACHE_MODE:-off}" == "record" ]]; then
  mkdir -p "$(dirname "$NEOPSYKE_LLM_CACHE_FILE")"
  printf '{"seq":0,"hash":"mock-hash","actor":"planner","call_site":"mock"}\n' > "$NEOPSYKE_LLM_CACHE_FILE"
fi

if [[ "${NEOPSYKE_SESSION_RECORDING_MODE:-off}" == "record" ]]; then
  mkdir -p "$NEOPSYKE_SESSION_RECORDING_DIR"
  printf '{"sequence_index":1}\n' > "$NEOPSYKE_SESSION_RECORDING_DIR/llm-cache.jsonl"
  printf '{"version":1}\n' > "$NEOPSYKE_SESSION_RECORDING_DIR/session-manifest.json"
fi

if [[ "${NEOPSYKE_SESSION_RECORDING_MODE:-off}" == "replay" ]]; then
  mkdir -p "$(dirname "$NEOPSYKE_EVENT_LOG_FILE")"
  printf '{"type":"llm_cache_hit","data":{"actor":"planner","call_site":"mock"}}\n' >> "$NEOPSYKE_EVENT_LOG_FILE"
  printf '{"type":"session_channel_replay_hit","data":{"channel":"llm","sequence_index":1}}\n' >> "$NEOPSYKE_EVENT_LOG_FILE"
fi

echo 'ego> %s'
`, answer)
	if err := os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		panic(err)
	}
	return scriptPath
}

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

func TestBBHSmokeEmitsStructuredProgress(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)
	mockScript := createMockNeopsyke(dir, "4")
	var updates []string

	result, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		Timeout:            10,
		NeopsykeCmd:        mockScript,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
		Progress: func(update ProgressUpdate) {
			var buf bytes.Buffer
			NewConsoleProgressReporter(&buf)(update)
			updates = append(updates, strings.TrimSpace(buf.String()))
		},
	})
	if err != nil {
		t.Fatalf("BBHSmoke failed: %v", err)
	}
	if result.ExitCode != 0 {
		t.Fatalf("expected exit 0, got %d", result.ExitCode)
	}
	if len(updates) == 0 {
		t.Fatal("expected structured progress updates")
	}
	if !strings.Contains(updates[0], "phase=suite") || !strings.Contains(updates[0], "status=start") {
		t.Fatalf("expected first update to announce suite start, got %q", updates[0])
	}
	if !strings.Contains(updates[len(updates)-1], "status=pass") || !strings.Contains(updates[len(updates)-1], "pass=2/2") {
		t.Fatalf("expected final update to announce pass summary, got %q", updates[len(updates)-1])
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

func TestBBHSmokeRecordCreatesReplayArtifacts(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)
	mockScript := createReplayAwareMockNeopsyke(dir, "4")

	result, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		Timeout:            10,
		NeopsykeCmd:        mockScript,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
		Record:             true,
	})
	if err != nil {
		t.Fatalf("BBHSmoke record failed: %v", err)
	}
	if result.ReplayStatsJSON == "" {
		t.Fatal("expected replay stats artifact path")
	}
	for _, caseID := range []string{"q1", "q2"} {
		sessionDir := filepath.Join(result.RunDir, "bbh-cases", "test-lane", caseID, "session")
		if !fileExists(filepath.Join(sessionDir, "session-manifest.json")) {
			t.Fatalf("expected session-manifest.json for case %s", caseID)
		}
		if !fileExists(filepath.Join(sessionDir, "llm-cache.jsonl")) {
			t.Fatalf("expected llm-cache.jsonl for case %s", caseID)
		}
	}

	statsData, err := os.ReadFile(result.ReplayStatsJSON)
	if err != nil {
		t.Fatalf("reading replay stats: %v", err)
	}
	var stats map[string]interface{}
	if err := json.Unmarshal(statsData, &stats); err != nil {
		t.Fatalf("decoding replay stats: %v", err)
	}
	if stats["mode"] != "record" {
		t.Fatalf("expected record mode, got %v", stats["mode"])
	}
	if stats["recorded_cases"].(float64) != 2 {
		t.Fatalf("expected 2 recorded cases, got %v", stats["recorded_cases"])
	}
}

func TestBBHSmokeSessionReplayAggregatesReplayStats(t *testing.T) {
	dir := t.TempDir()
	setupBBHFixtures(t, dir)
	mockScript := createReplayAwareMockNeopsyke(dir, "4")

	recorded, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		Timeout:            10,
		NeopsykeCmd:        mockScript,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
		Record:             true,
	})
	if err != nil {
		t.Fatalf("record run failed: %v", err)
	}

	replayed, err := BBHSmoke(BBHOpts{
		Lane:               "test-lane",
		PromptsFile:        filepath.Join(dir, "prompts.jsonl"),
		AnswersFile:        filepath.Join(dir, "answers.jsonl"),
		MinPassRatePercent: 100,
		Timeout:            10,
		NeopsykeCmd:        mockScript,
		RunRootAbs:         filepath.Join(dir, "runs"),
		RepoRoot:           dir,
		SessionReplayDir:   recorded.RunDir,
	})
	if err != nil {
		t.Fatalf("replay run failed: %v", err)
	}
	if replayed.ReplayStatsJSON == "" {
		t.Fatal("expected replay stats artifact path")
	}

	statsData, err := os.ReadFile(replayed.ReplayStatsJSON)
	if err != nil {
		t.Fatalf("reading replay stats: %v", err)
	}
	var stats map[string]interface{}
	if err := json.Unmarshal(statsData, &stats); err != nil {
		t.Fatalf("decoding replay stats: %v", err)
	}
	if stats["mode"] != "replay" {
		t.Fatalf("expected replay mode, got %v", stats["mode"])
	}
	if stats["replayed_cases"].(float64) != 2 {
		t.Fatalf("expected 2 replayed cases, got %v", stats["replayed_cases"])
	}
	sessionReplay, ok := stats["session_replay"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected session_replay stats object, got %T", stats["session_replay"])
	}
	if sessionReplay["total_replay_hits"].(float64) != 2 {
		t.Fatalf("expected 2 replay hits, got %v", sessionReplay["total_replay_hits"])
	}
	if sessionReplay["total_divergences"].(float64) != 0 {
		t.Fatalf("expected 0 replay divergences, got %v", sessionReplay["total_divergences"])
	}
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
