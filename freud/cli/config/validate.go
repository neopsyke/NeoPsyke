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
		if opts.Live {
			hasLiveCmd := false
			for _, step := range cfg.Pipeline {
				if step.LiveOnly && step.Cmd != "" {
					hasLiveCmd = true
					break
				}
			}
			if !hasLiveCmd {
				errs = append(errs, fmt.Errorf("--live requires at least one live step with a non-empty cmd; use --lane low-llm or --lane high-llm"))
			}
		}

	case "eval":
		if opts.InputFile != "" {
			if _, err := os.Stat(opts.InputFile); os.IsNotExist(err) {
				errs = append(errs, fmt.Errorf("--input file does not exist: %s", opts.InputFile))
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
	Live      bool
	FromStep  string
	OnlyStep  string
	SkipSteps []string
	InputFile string
}
