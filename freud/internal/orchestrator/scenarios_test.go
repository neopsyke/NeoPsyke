package orchestrator

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestRunScenariosDryRun(t *testing.T) {
	dir := t.TempDir()

	// Create manifest
	manifest := scenarioManifest{
		Scenarios: []scenarioEntry{
			{ID: "s1", Selector: "ai.neopsyke.eval.FakeTest.testOne", Description: "first scenario"},
			{ID: "s2", Selector: "ai.neopsyke.eval.FakeTest.testTwo", Description: "second scenario"},
		},
	}
	manifestPath := filepath.Join(dir, "scenarios.json")
	data, _ := json.MarshalIndent(manifest, "", "  ")
	os.WriteFile(manifestPath, data, 0o644)

	// Create a fake test source dir with matching class
	testSrcDir := filepath.Join(dir, "src", "test", "kotlin")
	os.MkdirAll(testSrcDir, 0o755)
	os.WriteFile(filepath.Join(testSrcDir, "FakeTest.kt"), []byte("class FakeTest {\n  fun testOne() {}\n  fun testTwo() {}\n}\n"), 0o644)

	result, err := RunScenarios(ScenariosOpts{
		ManifestFile: manifestPath,
		DryRun:       true,
		RepoRoot:     dir,
	})
	if err != nil {
		t.Fatalf("RunScenarios dry-run failed: %v", err)
	}

	if result.Total != 2 {
		t.Errorf("expected 2 total, got %d", result.Total)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0 in dry-run, got %d", result.ExitCode)
	}
}

func TestRunScenariosInvalidManifest(t *testing.T) {
	dir := t.TempDir()
	manifestPath := filepath.Join(dir, "bad.json")
	os.WriteFile(manifestPath, []byte("not json"), 0o644)

	_, err := RunScenarios(ScenariosOpts{
		ManifestFile: manifestPath,
		RepoRoot:     dir,
	})
	if err == nil {
		t.Error("expected error for invalid manifest")
	}
}

func TestRunScenariosMissingManifest(t *testing.T) {
	dir := t.TempDir()

	_, err := RunScenarios(ScenariosOpts{
		ManifestFile: filepath.Join(dir, "missing.json"),
		RepoRoot:     dir,
	})
	if err == nil {
		t.Error("expected error for missing manifest")
	}
}
