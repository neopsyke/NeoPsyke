package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/viper"
)

// LoadConfig loads, merges, and validates the Freud configuration.
// Precedence: CLI overrides > env vars > profile overlay > YAML file > built-in defaults.
func LoadConfig(configPath string, lane string, overrides []string) (*FreudConfig, error) {
	v := viper.New()
	v.SetConfigType("yaml")

	// 1. Register built-in defaults
	setDefaults(v)

	// 2. Load base YAML config
	if configPath != "" {
		v.SetConfigFile(configPath)
	} else if p := os.Getenv("FREUD_CONFIG"); p != "" {
		v.SetConfigFile(p)
	} else {
		root, err := RepoRoot()
		if err == nil {
			v.SetConfigFile(filepath.Join(root, "freud", "config", "freud.yaml"))
		}
	}

	if err := v.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			// If a config file was explicitly set and can't be read, that's an error.
			// If it's auto-discovered and missing, we just use defaults.
			if configPath != "" || os.Getenv("FREUD_CONFIG") != "" {
				return nil, fmt.Errorf("reading config: %w", err)
			}
		}
	}

	// 3. Merge profile overlay if --lane given
	if lane != "" {
		if err := mergeProfile(v, lane); err != nil {
			return nil, err
		}
	}

	// 4. Bind env vars
	v.SetEnvPrefix("FREUD")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	for envVar, configPath := range ManualEnvBindings {
		_ = v.BindEnv(configPath, envVar)
	}

	// 5. Apply -o key=value overrides (highest precedence)
	for _, kv := range overrides {
		parts := strings.SplitN(kv, "=", 2)
		if len(parts) != 2 {
			return nil, fmt.Errorf("invalid override format %q, expected key=value", kv)
		}
		v.Set(parts[0], parts[1])
	}

	// 6. Unmarshal into struct
	cfg := DefaultConfig()
	if err := v.Unmarshal(cfg); err != nil {
		return nil, fmt.Errorf("unmarshalling config: %w", err)
	}

	// 7. Apply step cmd overrides from env vars (pipeline is a list, Viper can't auto-bind)
	applyStepEnvOverrides(cfg)

	return cfg, nil
}

func setDefaults(v *viper.Viper) {
	d := DefaultConfig()

	v.SetDefault("project.name", d.Project.Name)
	v.SetDefault("project.run_root", d.Project.RunRoot)
	v.SetDefault("project.retention_days", d.Project.RetentionDays)
	v.SetDefault("project.gradle_home", d.Project.GradleHome)

	v.SetDefault("live_eval.timeout", d.LiveEval.Timeout)
	v.SetDefault("live_eval.preserve_memory", d.LiveEval.PreserveMemory)
	v.SetDefault("live_eval.goals_enabled", d.LiveEval.GoalsEnabled)
	v.SetDefault("live_eval.llm_config_file", d.LiveEval.LLMConfigFile)
	v.SetDefault("live_eval.neopsyke_cmd", d.LiveEval.NeopsykeCmd)

	v.SetDefault("session.record", d.Session.Record)

	v.SetDefault("scenarios.manifest_file", d.Scenarios.ManifestFile)

	v.SetDefault("bbh.prompts_file", d.BBH.PromptsFile)
	v.SetDefault("bbh.answers_file", d.BBH.AnswersFile)
	v.SetDefault("bbh.min_pass_rate", d.BBH.MinPassRate)
	v.SetDefault("bbh.max_timeouts", d.BBH.MaxTimeouts)
	v.SetDefault("bbh.max_regression_percent", d.BBH.MaxRegressionPercent)
	v.SetDefault("bbh.preserve_memory", d.BBH.PreserveMemory)
	v.SetDefault("bbh.memory_enabled", d.BBH.MemoryEnabled)
	v.SetDefault("bbh.logbook_enabled", d.BBH.LogbookEnabled)

	v.SetDefault("runtime.continue_on_fail", d.Runtime.ContinueOnFail)
	v.SetDefault("runtime.scratchpad_debug", d.Runtime.ScratchpadDebug)
	v.SetDefault("runtime.id_enabled", d.Runtime.IDEnabled)

	v.SetDefault("telemetry.enabled", d.Telemetry.Enabled)
}

// mergeProfile loads a lane profile YAML and merges it into the Viper instance.
// Pipeline steps are merged by name (not replaced wholesale).
func mergeProfile(v *viper.Viper, lane string) error {
	root, err := RepoRoot()
	if err != nil {
		return fmt.Errorf("finding repo root for profile: %w", err)
	}

	profilePath := filepath.Join(root, "freud", "config", "profiles", lane+".yaml")
	if _, err := os.Stat(profilePath); os.IsNotExist(err) {
		return fmt.Errorf("unknown lane %q: no profile at %s", lane, profilePath)
	}

	profile := viper.New()
	profile.SetConfigType("yaml")
	profile.SetConfigFile(profilePath)
	if err := profile.ReadInConfig(); err != nil {
		return fmt.Errorf("reading profile %s: %w", profilePath, err)
	}

	// Extract profile pipeline steps before merging (Viper merges lists by replacing)
	var profileSteps []map[string]interface{}
	if profile.IsSet("pipeline") {
		raw := profile.Get("pipeline")
		if steps, ok := raw.([]interface{}); ok {
			for _, s := range steps {
				if m, ok := s.(map[string]interface{}); ok {
					profileSteps = append(profileSteps, m)
				}
			}
		}
	}

	// Save base pipeline before merge (MergeConfigMap replaces lists wholesale)
	var basePipeline []interface{}
	if raw := v.Get("pipeline"); raw != nil {
		if sl, ok := raw.([]interface{}); ok {
			basePipeline = make([]interface{}, len(sl))
			copy(basePipeline, sl)
		}
	}

	// Merge scalar config keys (this replaces the pipeline list)
	if err := v.MergeConfigMap(profile.AllSettings()); err != nil {
		return fmt.Errorf("merging profile: %w", err)
	}

	// Restore base pipeline and apply profile step overrides by name
	if len(basePipeline) > 0 {
		v.Set("pipeline", basePipeline)
		if len(profileSteps) > 0 {
			mergeStepsByName(v, profileSteps)
		}
	}

	return nil
}

// mergeStepsByName merges profile pipeline steps into the base config by matching on name.
func mergeStepsByName(v *viper.Viper, profileSteps []map[string]interface{}) {
	raw := v.Get("pipeline")
	baseSlice, ok := raw.([]interface{})
	if !ok {
		return
	}

	// Index base steps by name
	type indexedStep struct {
		idx  int
		data map[string]interface{}
	}
	baseIndex := make(map[string]indexedStep)
	for i, s := range baseSlice {
		if m, ok := s.(map[string]interface{}); ok {
			if name, ok := m["name"].(string); ok {
				baseIndex[name] = indexedStep{idx: i, data: m}
			}
		}
	}

	// Merge profile steps into base
	for _, ps := range profileSteps {
		name, ok := ps["name"].(string)
		if !ok {
			continue
		}
		if base, exists := baseIndex[name]; exists {
			// Merge fields from profile step into base step
			for k, val := range ps {
				base.data[k] = val
			}
			baseSlice[base.idx] = base.data
		}
		// Profile steps with unknown names are ignored (could be future steps)
	}

	v.Set("pipeline", baseSlice)
}

// applyStepEnvOverrides checks for FREUD_*_CMD env vars and overrides pipeline step cmds.
func applyStepEnvOverrides(cfg *FreudConfig) {
	for i, step := range cfg.Pipeline {
		if envVar, ok := StepEnvVars[step.Name]; ok {
			if val := os.Getenv(envVar); val != "" {
				cfg.Pipeline[i].Cmd = val
			}
		}
	}
}

// RepoRoot finds the repository root by looking for the .git directory.
func RepoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, ".git")); err == nil {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("not inside a git repository")
		}
		dir = parent
	}
}
