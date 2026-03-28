package config

import (
	"fmt"
	"os"
	"path/filepath"
)

// Validate checks the merged config for consistency and returns all errors found.
func Validate(cfg *FreudConfig, command string, opts *ValidationOpts) []error {
	var errs []error

	// Type/range checks
	if cfg.Project.RetentionDays < 0 {
		errs = append(errs, fmt.Errorf("project.retention_days must be >= 0, got %d", cfg.Project.RetentionDays))
	}
	if cfg.LiveEval.Timeout < 1 {
		errs = append(errs, fmt.Errorf("live_eval.timeout must be >= 1, got %d", cfg.LiveEval.Timeout))
	}
	if cfg.BBH.MinPassRate < 0 || cfg.BBH.MinPassRate > 100 {
		errs = append(errs, fmt.Errorf("bbh.min_pass_rate must be 0-100, got %d", cfg.BBH.MinPassRate))
	}
	if cfg.BBH.MaxRegressionPercent < 0 || cfg.BBH.MaxRegressionPercent > 100 {
		errs = append(errs, fmt.Errorf("bbh.max_regression_percent must be 0-100, got %d", cfg.BBH.MaxRegressionPercent))
	}
	if cfg.MemoryLive.MaxAttempts < 1 {
		errs = append(errs, fmt.Errorf("memory_live.max_attempts must be >= 1, got %d", cfg.MemoryLive.MaxAttempts))
	}

	// Pipeline step names must be unique
	seen := make(map[string]bool)
	stepNames := make([]string, 0, len(cfg.Pipeline))
	for _, step := range cfg.Pipeline {
		if seen[step.Name] {
			errs = append(errs, fmt.Errorf("duplicate pipeline step name: %q", step.Name))
		}
		seen[step.Name] = true
		stepNames = append(stepNames, step.Name)
	}

	if opts == nil {
		return errs
	}

	// --from-step must be valid
	if opts.FromStep != "" && !seen[opts.FromStep] {
		errs = append(errs, fmt.Errorf("--from-step %q not in pipeline steps: %v", opts.FromStep, stepNames))
	}

	// --only must be valid
	if opts.OnlyStep != "" && !seen[opts.OnlyStep] {
		errs = append(errs, fmt.Errorf("--only %q not in pipeline steps: %v", opts.OnlyStep, stepNames))
	}

	// --skip steps must be valid
	for _, s := range opts.SkipSteps {
		if !seen[s] {
			errs = append(errs, fmt.Errorf("--skip %q not in pipeline steps: %v", s, stepNames))
		}
	}

	// Command-specific validation
	root, _ := RepoRoot()

	switch command {
	case "run":
		selected := selectedPipelineSteps(cfg.Pipeline, opts)
		filtered := make([]PipelineStep, 0, len(selected))
		for _, step := range selected {
			if !RuntimeStepEnabled(cfg, step.Name) {
				continue
			}
			filtered = append(filtered, step)
		}
		selected = filtered
		if len(selected) == 0 {
			errs = append(errs, fmt.Errorf("no pipeline steps selected after applying --from-step/--only/--skip filters"))
			break
		}

		if opts.OnlyStep == "memory_live_smoke" && !cfg.MemoryLive.Enabled {
			errs = append(errs, fmt.Errorf("memory_live_smoke is disabled; set memory_live.enabled=true to run it"))
		}
		if opts.OnlyStep == "test_replay_eval" && !cfg.Session.ReplayTestEnabled {
			errs = append(errs, fmt.Errorf("test_replay_eval is disabled; set session.replay_test_enabled=true to run it"))
		}

		if opts.OnlyStep != "" {
			for _, step := range cfg.Pipeline {
				if step.Name == opts.OnlyStep && step.LiveOnly && !opts.Live {
					errs = append(errs, fmt.Errorf("--only %q requires --live", opts.OnlyStep))
					break
				}
			}
		}

		if opts.Live {
			hasLiveStep := false
			for _, step := range selected {
				if step.LiveOnly {
					hasLiveStep = true
					if step.Cmd == "" && !isBuiltinStepName(step.Name) {
						errs = append(errs, fmt.Errorf("live step %q has no implementation configured", step.Name))
					}
				}
			}
			if !hasLiveStep {
				errs = append(errs, fmt.Errorf("--live requires at least one live step configured; use --lane low-llm or --lane high-llm"))
			}
			if cfg.LiveEval.LLMConfigFile == "" {
				errs = append(errs, fmt.Errorf("--live requires live_eval.llm_config_file; use --lane low-llm or --lane high-llm"))
			}
		}

	case "eval":
		if opts.InputFile == "" && opts.SessionReplayDir == "" {
			errs = append(errs, fmt.Errorf("either --input or --session-replay is required"))
		}
		if opts.InputFile != "" {
			if _, err := os.Stat(opts.InputFile); os.IsNotExist(err) {
				errs = append(errs, fmt.Errorf("--input file does not exist: %s", opts.InputFile))
			}
		}
		if opts.SessionReplayDir != "" {
			if _, err := os.Stat(opts.SessionReplayDir); os.IsNotExist(err) {
				errs = append(errs, fmt.Errorf("--session-replay directory does not exist: %s", opts.SessionReplayDir))
			}
		}
		if opts.CacheReplayFile != "" {
			if _, err := os.Stat(opts.CacheReplayFile); os.IsNotExist(err) {
				errs = append(errs, fmt.Errorf("--cache-replay file does not exist: %s", opts.CacheReplayFile))
			}
		}

	case "bbh":
		for _, attr := range []struct {
			name string
			path string
		}{
			{"bbh.prompts_file", cfg.BBH.PromptsFile},
			{"bbh.answers_file", cfg.BBH.AnswersFile},
		} {
			p := attr.path
			if !filepath.IsAbs(p) && root != "" {
				p = filepath.Join(root, p)
			}
			if _, err := os.Stat(p); os.IsNotExist(err) {
				errs = append(errs, fmt.Errorf("%s does not exist: %s", attr.name, p))
			}
		}
	}

	// llm_config_file if set must exist
	if cfg.LiveEval.LLMConfigFile != "" {
		p := cfg.LiveEval.LLMConfigFile
		if !filepath.IsAbs(p) && root != "" {
			p = filepath.Join(root, p)
		}
		if _, err := os.Stat(p); os.IsNotExist(err) {
			errs = append(errs, fmt.Errorf("live_eval.llm_config_file does not exist: %s", p))
		}
	}

	return errs
}

// ValidationOpts carries command-specific flags for validation.
type ValidationOpts struct {
	Live             bool
	FromStep         string
	OnlyStep         string
	SkipSteps        []string
	InputFile        string
	SessionReplayDir string
	CacheReplayFile  string
}

// builtinStepNames lists pipeline steps dispatched to native Go functions.
var builtinStepNames = map[string]bool{
	"scenario_pack":        true,
	"reasoning_eval_logic": true,
	"reasoning_eval_model": true,
	"test_replay_eval":     true,
}

func isBuiltinStepName(name string) bool {
	return builtinStepNames[name]
}

func selectedPipelineSteps(pipeline []PipelineStep, opts *ValidationOpts) []PipelineStep {
	if opts == nil {
		return pipeline
	}

	skip := make(map[string]bool, len(opts.SkipSteps))
	for _, step := range opts.SkipSteps {
		skip[step] = true
	}

	var selected []PipelineStep
	fromReached := opts.FromStep == ""
	for _, step := range pipeline {
		if !fromReached {
			if step.Name == opts.FromStep {
				fromReached = true
			} else {
				continue
			}
		}
		if opts.OnlyStep != "" && step.Name != opts.OnlyStep {
			continue
		}
		if skip[step.Name] {
			continue
		}
		if step.LiveOnly && !opts.Live {
			continue
		}
		selected = append(selected, step)
	}

	return selected
}

func RuntimeStepEnabled(cfg *FreudConfig, stepName string) bool {
	switch stepName {
	case "memory_live_smoke":
		return cfg.MemoryLive.Enabled
	case "test_replay_eval":
		return cfg.Session.ReplayTestEnabled
	default:
		return true
	}
}
