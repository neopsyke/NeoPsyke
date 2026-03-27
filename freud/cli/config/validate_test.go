package config

import (
	"testing"
)

func TestValidateBasicDefaults(t *testing.T) {
	cfg := DefaultConfig()
	errs := Validate(cfg, "run", nil)
	if len(errs) > 0 {
		t.Errorf("default config should validate, got: %v", errs)
	}
}

func TestValidateRetentionDays(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Project.RetentionDays = -1
	errs := Validate(cfg, "run", nil)
	if len(errs) != 1 {
		t.Errorf("expected 1 error, got %d: %v", len(errs), errs)
	}
}

func TestValidateTimeout(t *testing.T) {
	cfg := DefaultConfig()
	cfg.LiveEval.Timeout = 0
	errs := Validate(cfg, "run", nil)
	if len(errs) != 1 {
		t.Errorf("expected 1 error, got %d: %v", len(errs), errs)
	}
}

func TestValidateMinPassRate(t *testing.T) {
	cfg := DefaultConfig()
	cfg.BBH.MinPassRate = 101
	errs := Validate(cfg, "run", nil)
	if len(errs) != 1 {
		t.Errorf("expected 1 error, got %d: %v", len(errs), errs)
	}
}

func TestValidateDuplicateStepNames(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Pipeline = append(cfg.Pipeline, PipelineStep{Name: "full_tests", Cmd: "echo dup"})
	errs := Validate(cfg, "run", nil)
	if len(errs) != 1 {
		t.Errorf("expected 1 error for duplicate step, got %d: %v", len(errs), errs)
	}
}

func TestValidateInvalidFromStep(t *testing.T) {
	cfg := DefaultConfig()
	errs := Validate(cfg, "run", &ValidationOpts{FromStep: "nonexistent"})
	found := false
	for _, e := range errs {
		if e.Error() != "" {
			found = true
		}
	}
	if !found {
		t.Error("expected error for invalid --from-step")
	}
}

func TestValidateValidFromStep(t *testing.T) {
	cfg := DefaultConfig()
	errs := Validate(cfg, "run", &ValidationOpts{FromStep: "full_tests"})
	if len(errs) > 0 {
		t.Errorf("expected no errors, got: %v", errs)
	}
}

func TestValidateLiveWithoutLiveSteps(t *testing.T) {
	cfg := DefaultConfig()
	// All live steps have empty cmds by default
	errs := Validate(cfg, "run", &ValidationOpts{Live: true})
	found := false
	for _, e := range errs {
		if e != nil {
			found = true
		}
	}
	if !found {
		t.Error("expected error for --live without configured live steps")
	}
}

func TestValidateLiveWithLiveSteps(t *testing.T) {
	cfg := DefaultConfig()
	// Set a live step cmd
	for i, step := range cfg.Pipeline {
		if step.Name == "reasoning_eval_model" {
			cfg.Pipeline[i].Cmd = "some-command"
		}
	}
	errs := Validate(cfg, "run", &ValidationOpts{Live: true})
	if len(errs) > 0 {
		t.Errorf("expected no errors with live step configured, got: %v", errs)
	}
}

func TestValidateEvalMissingInput(t *testing.T) {
	cfg := DefaultConfig()
	errs := Validate(cfg, "eval", &ValidationOpts{InputFile: "/nonexistent/file.txt"})
	found := false
	for _, e := range errs {
		if e != nil {
			found = true
		}
	}
	if !found {
		t.Error("expected error for missing input file")
	}
}

func TestValidateSkipInvalidStep(t *testing.T) {
	cfg := DefaultConfig()
	errs := Validate(cfg, "run", &ValidationOpts{SkipSteps: []string{"nonexistent"}})
	if len(errs) != 1 {
		t.Errorf("expected 1 error for invalid skip step, got %d: %v", len(errs), errs)
	}
}
