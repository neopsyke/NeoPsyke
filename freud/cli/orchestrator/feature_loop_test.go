package orchestrator

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/atomitl/neopsyke/freud/cli/config"
)

func testCfgWithSteps(steps []config.PipelineStep) *config.FreudConfig {
	cfg := config.DefaultConfig()
	cfg.Pipeline = steps
	return cfg
}

func TestFeatureLoopDryRun(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo a"},
		{Name: "step_b", Cmd: "echo b"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-dry",
		DryRun:    true,
		Cfg:       cfg,
		RepoRoot:  dir, // just needs to exist
	})
	if err != nil {
		t.Fatalf("FeatureLoop dry-run failed: %v", err)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0, got %d", result.ExitCode)
	}
	if result.OverallStatus != "pass" {
		t.Errorf("expected pass, got %s", result.OverallStatus)
	}

	// Run dir should exist with artifacts
	if _, err := os.Stat(filepath.Join(result.RunDir, "artifacts", "summary.json")); err != nil {
		t.Errorf("summary.json should exist: %v", err)
	}
	if _, err := os.Stat(filepath.Join(result.RunDir, "artifacts", "trail.jsonl")); err != nil {
		t.Errorf("trail.jsonl should exist: %v", err)
	}
	if _, err := os.Stat(filepath.Join(result.RunDir, "artifacts", "run-config.json")); err != nil {
		t.Errorf("run-config.json should exist: %v", err)
	}
}

func TestFeatureLoopPassingSteps(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo step_a_output"},
		{Name: "step_b", Cmd: "echo step_b_output"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-pass",
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}
	if result.OverallStatus != "pass" {
		t.Errorf("expected pass, got %s", result.OverallStatus)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0, got %d", result.ExitCode)
	}

	// Check summary.json
	data, _ := os.ReadFile(result.SummaryJSON)
	var summary map[string]interface{}
	json.Unmarshal(data, &summary)
	if summary["status"] != "pass" {
		t.Errorf("summary status should be pass, got %v", summary["status"])
	}
	if summary["steps_passed"].(float64) != 2 {
		t.Errorf("expected 2 steps passed, got %v", summary["steps_passed"])
	}

	// Check step logs exist
	entries, _ := os.ReadDir(filepath.Join(result.RunDir, "logs"))
	logCount := 0
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".log") {
			logCount++
		}
	}
	if logCount != 2 {
		t.Errorf("expected 2 step logs, got %d", logCount)
	}
}

func TestFeatureLoopFailingStep(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo ok"},
		{Name: "step_b", Cmd: "exit 1"},
		{Name: "step_c", Cmd: "echo should_not_run"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-fail",
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}
	if result.OverallStatus != "fail" {
		t.Errorf("expected fail, got %s", result.OverallStatus)
	}
	if result.ExitCode != 2 {
		t.Errorf("expected exit 2, got %d", result.ExitCode)
	}

	// step_c should not have run (fail-fast)
	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "steps.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2 {
		t.Errorf("expected 2 step entries (a=pass, b=fail), got %d: %v", len(lines), lines)
	}
}

func TestFeatureLoopContinueOnFail(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo ok"},
		{Name: "step_b", Cmd: "exit 1"},
		{Name: "step_c", Cmd: "echo also_ok"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID:      "test-continue",
		ContinueOnFail: true,
		Cfg:            cfg,
		RepoRoot:       dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}
	if result.OverallStatus != "fail" {
		t.Errorf("expected fail (step_b failed), got %s", result.OverallStatus)
	}

	// All 3 steps should have run despite step_b failing
	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "steps.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 3 {
		t.Errorf("expected 3 step entries with continue-on-fail, got %d", len(lines))
	}
}

func TestFeatureLoopFromStep(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo a"},
		{Name: "step_b", Cmd: "echo b"},
		{Name: "step_c", Cmd: "echo c"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-from",
		FromStep:  "step_b",
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}

	// Only step_b and step_c should run (step_a skipped)
	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "steps.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2 {
		t.Errorf("expected 2 steps (b, c), got %d: %v", len(lines), lines)
	}
	if !strings.HasPrefix(lines[0], "step_b") {
		t.Errorf("first step should be step_b, got %s", lines[0])
	}
}

func TestFeatureLoopOnlyStep(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo a"},
		{Name: "step_b", Cmd: "echo b"},
		{Name: "step_c", Cmd: "echo c"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-only",
		OnlyStep:  "step_b",
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}

	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "steps.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 1 {
		t.Errorf("expected 1 step (only step_b), got %d: %v", len(lines), lines)
	}
}

func TestFeatureLoopSkipSteps(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo a"},
		{Name: "step_b", Cmd: "echo b"},
		{Name: "step_c", Cmd: "echo c"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-skip",
		SkipSteps: []string{"step_b"},
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}

	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "steps.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2 {
		t.Errorf("expected 2 steps (a, c), got %d: %v", len(lines), lines)
	}
}

func TestFeatureLoopLiveOnlySkippedByDefault(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo a"},
		{Name: "step_live", Cmd: "echo live", LiveOnly: true},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-no-live",
		Live:      false,
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}

	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "steps.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 1 {
		t.Errorf("expected 1 step (live skipped), got %d: %v", len(lines), lines)
	}
}

func TestFeatureLoopLiveStepsIncluded(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo a"},
		{Name: "step_live", Cmd: "echo live", LiveOnly: true},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-with-live",
		Live:      true,
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}

	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "steps.tsv"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2 {
		t.Errorf("expected 2 steps (live included), got %d: %v", len(lines), lines)
	}
}

func TestFeatureLoopTrailEvents(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "step_a", Cmd: "echo a"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-trail",
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}

	data, _ := os.ReadFile(filepath.Join(result.RunDir, "artifacts", "trail.jsonl"))
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")

	// Expected events: run_start, step_start, step_indexed, step_end, triage_complete, run_end
	if len(lines) < 5 {
		t.Errorf("expected at least 5 trail events, got %d", len(lines))
	}

	// First event should be run_start
	if !strings.Contains(lines[0], `"event":"run_start"`) {
		t.Errorf("first event should be run_start: %s", lines[0])
	}

	// Last event should be run_end
	if !strings.Contains(lines[len(lines)-1], `"event":"run_end"`) {
		t.Errorf("last event should be run_end: %s", lines[len(lines)-1])
	}
}

func TestFeatureLoopStepMetaJSON(t *testing.T) {
	dir := t.TempDir()
	cfg := testCfgWithSteps([]config.PipelineStep{
		{Name: "compile", Cmd: "echo compiled"},
	})
	cfg.Project.RunRoot = dir

	result, err := FeatureLoop(FeatureLoopOpts{
		FeatureID: "test-meta",
		Cfg:       cfg,
		RepoRoot:  dir,
	})
	if err != nil {
		t.Fatalf("FeatureLoop failed: %v", err)
	}

	metaPath := filepath.Join(result.RunDir, "artifacts", "step-meta", "compile.json")
	data, err := os.ReadFile(metaPath)
	if err != nil {
		t.Fatalf("step-meta should exist: %v", err)
	}

	var meta map[string]interface{}
	json.Unmarshal(data, &meta)
	if meta["name"] != "compile" {
		t.Errorf("expected name=compile, got %v", meta["name"])
	}
	if meta["status"] != "pass" {
		t.Errorf("expected status=pass, got %v", meta["status"])
	}
}
