package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultConfig(t *testing.T) {
	cfg := DefaultConfig()

	if cfg.Project.Name != "neopsyke" {
		t.Errorf("expected project.name=neopsyke, got %s", cfg.Project.Name)
	}
	if cfg.Project.RetentionDays != 3 {
		t.Errorf("expected retention_days=3, got %d", cfg.Project.RetentionDays)
	}
	if cfg.LiveEval.Timeout != 120 {
		t.Errorf("expected live_eval.timeout=120, got %d", cfg.LiveEval.Timeout)
	}
	if cfg.BBH.MinPassRate != 90 {
		t.Errorf("expected bbh.min_pass_rate=90, got %d", cfg.BBH.MinPassRate)
	}
	if len(cfg.Pipeline) != 8 {
		t.Errorf("expected 8 pipeline steps, got %d", len(cfg.Pipeline))
	}
	if !cfg.Runtime.ScratchpadDebug {
		t.Error("expected runtime.scratchpad_debug=true")
	}
	if !cfg.Telemetry.Enabled {
		t.Error("expected telemetry.enabled=true")
	}
}

func TestLoadConfigFromYAML(t *testing.T) {
	// Write a minimal YAML config to a temp file
	dir := t.TempDir()
	yamlPath := filepath.Join(dir, "freud.yaml")
	content := `
project:
  name: test-project
  retention_days: 7
live_eval:
  timeout: 60
bbh:
  min_pass_rate: 80
`
	os.WriteFile(yamlPath, []byte(content), 0o644)

	cfg, err := LoadConfig(yamlPath, "", nil)
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	if cfg.Project.Name != "test-project" {
		t.Errorf("expected project.name=test-project, got %s", cfg.Project.Name)
	}
	if cfg.Project.RetentionDays != 7 {
		t.Errorf("expected retention_days=7, got %d", cfg.Project.RetentionDays)
	}
	if cfg.LiveEval.Timeout != 60 {
		t.Errorf("expected live_eval.timeout=60, got %d", cfg.LiveEval.Timeout)
	}
	if cfg.BBH.MinPassRate != 80 {
		t.Errorf("expected bbh.min_pass_rate=80, got %d", cfg.BBH.MinPassRate)
	}
	// Defaults should still apply for unset values
	if cfg.Project.RunRoot != ".neopsyke/runs/freud" {
		t.Errorf("expected default run_root, got %s", cfg.Project.RunRoot)
	}
}

func TestLoadConfigEnvOverride(t *testing.T) {
	dir := t.TempDir()
	yamlPath := filepath.Join(dir, "freud.yaml")
	content := `
live_eval:
  timeout: 120
`
	os.WriteFile(yamlPath, []byte(content), 0o644)

	// Set env var that should override YAML
	t.Setenv("FREUD_LIVE_EVAL_TIMEOUT", "60")

	cfg, err := LoadConfig(yamlPath, "", nil)
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	if cfg.LiveEval.Timeout != 60 {
		t.Errorf("expected env override timeout=60, got %d", cfg.LiveEval.Timeout)
	}
}

func TestLoadConfigOverrideFlag(t *testing.T) {
	dir := t.TempDir()
	yamlPath := filepath.Join(dir, "freud.yaml")
	content := `
bbh:
  min_pass_rate: 100
`
	os.WriteFile(yamlPath, []byte(content), 0o644)

	cfg, err := LoadConfig(yamlPath, "", []string{"bbh.min_pass_rate=90"})
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	if cfg.BBH.MinPassRate != 90 {
		t.Errorf("expected override min_pass_rate=90, got %d", cfg.BBH.MinPassRate)
	}
}

func TestLoadConfigProfileMerge(t *testing.T) {
	dir := t.TempDir()

	// Base YAML
	yamlPath := filepath.Join(dir, "freud.yaml")
	content := `
project:
  name: test-project
pipeline:
  - name: reasoning_eval_model
    cmd: ""
    live_only: true
live_eval:
  timeout: 120
  llm_config_file: ""
`
	os.WriteFile(yamlPath, []byte(content), 0o644)

	cfg, err := LoadConfig(yamlPath, "", nil)
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	// Without profile, llm_config_file should be empty
	if cfg.LiveEval.LLMConfigFile != "" {
		t.Errorf("expected empty llm_config_file without profile, got %s", cfg.LiveEval.LLMConfigFile)
	}
}

func TestLoadConfigStepEnvOverride(t *testing.T) {
	dir := t.TempDir()
	yamlPath := filepath.Join(dir, "freud.yaml")
	content := `
pipeline:
  - name: full_tests
    cmd: "./gradlew test"
`
	os.WriteFile(yamlPath, []byte(content), 0o644)

	t.Setenv("FREUD_FULL_TEST_CMD", "./gradlew test --info")

	cfg, err := LoadConfig(yamlPath, "", nil)
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	for _, step := range cfg.Pipeline {
		if step.Name == "full_tests" {
			if step.Cmd != "./gradlew test --info" {
				t.Errorf("expected env override for full_tests cmd, got %s", step.Cmd)
			}
			return
		}
	}
	t.Error("full_tests step not found")
}

func TestLoadConfigInvalidOverrideFormat(t *testing.T) {
	dir := t.TempDir()
	yamlPath := filepath.Join(dir, "freud.yaml")
	os.WriteFile(yamlPath, []byte("project:\n  name: test\n"), 0o644)

	_, err := LoadConfig(yamlPath, "", []string{"no-equals-sign"})
	if err == nil {
		t.Error("expected error for invalid override format")
	}
}

func TestLoadConfigLaneStoredInConfig(t *testing.T) {
	dir := t.TempDir()

	yamlPath := filepath.Join(dir, "freud.yaml")
	os.WriteFile(yamlPath, []byte("project:\n  name: test\n"), 0o644)

	cfg, err := LoadConfig(yamlPath, "", nil)
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}
	if cfg.Lane != "" {
		t.Errorf("expected empty lane without --lane, got %q", cfg.Lane)
	}

	cfg2, err := LoadConfig(yamlPath, "low-llm", nil)
	if err != nil {
		// Profile file won't exist in temp dir, which is expected.
		// The lane should still be stored before profile merge fails.
		// Skip this sub-test if profile lookup fails.
		t.Skipf("profile merge failed (expected in test without repo root): %v", err)
	}
	if cfg2.Lane != "low-llm" {
		t.Errorf("expected lane=low-llm, got %q", cfg2.Lane)
	}
}

func TestLoadConfigProfileScalarOverride(t *testing.T) {
	dir := t.TempDir()

	// Base config with explicit empty llm_config_file
	yamlPath := filepath.Join(dir, "freud.yaml")
	baseContent := `
live_eval:
  timeout: 120
  llm_config_file: ""
`
	os.WriteFile(yamlPath, []byte(baseContent), 0o644)

	// Profile that should override the empty value
	profileDir := filepath.Join(dir, "freud", "config", "profiles")
	os.MkdirAll(profileDir, 0o755)
	profileContent := `
live_eval:
  llm_config_file: freud/config/llm-test.yaml
  timeout: 60
`
	os.WriteFile(filepath.Join(profileDir, "test-lane.yaml"), []byte(profileContent), 0o644)

	// Also need a .git directory so RepoRoot finds it
	os.MkdirAll(filepath.Join(dir, ".git"), 0o755)

	// Run from the temp dir so RepoRoot resolves correctly
	origDir, _ := os.Getwd()
	os.Chdir(dir)
	defer os.Chdir(origDir)

	cfg, err := LoadConfig(yamlPath, "test-lane", nil)
	if err != nil {
		t.Fatalf("LoadConfig with profile failed: %v", err)
	}

	if cfg.LiveEval.LLMConfigFile != "freud/config/llm-test.yaml" {
		t.Errorf("profile scalar override failed: expected llm_config_file=freud/config/llm-test.yaml, got %q", cfg.LiveEval.LLMConfigFile)
	}
	if cfg.LiveEval.Timeout != 60 {
		t.Errorf("profile scalar override failed: expected timeout=60, got %d", cfg.LiveEval.Timeout)
	}
	if cfg.Lane != "test-lane" {
		t.Errorf("expected lane=test-lane, got %q", cfg.Lane)
	}
}

func TestLaneNames(t *testing.T) {
	if len(LaneNames) != 2 {
		t.Errorf("expected 2 lane names, got %d", len(LaneNames))
	}
	found := map[string]bool{}
	for _, l := range LaneNames {
		found[l] = true
	}
	if !found["low-llm"] || !found["high-llm"] {
		t.Errorf("expected low-llm and high-llm, got %v", LaneNames)
	}
}
