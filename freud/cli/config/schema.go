package config

// FreudConfig is the top-level configuration structure for the Freud CLI.
// All fields map to YAML keys via mapstructure tags.
type FreudConfig struct {
	Project   ProjectConfig   `mapstructure:"project"`
	Pipeline  []PipelineStep  `mapstructure:"pipeline"`
	LiveEval  LiveEvalConfig  `mapstructure:"live_eval"`
	Session   SessionConfig   `mapstructure:"session"`
	BBH       BBHConfig       `mapstructure:"bbh"`
	Runtime   RuntimeConfig   `mapstructure:"runtime"`
	Telemetry TelemetryConfig `mapstructure:"telemetry"`
}

type ProjectConfig struct {
	Name          string `mapstructure:"name"`
	RunRoot       string `mapstructure:"run_root"`
	RetentionDays int    `mapstructure:"retention_days"`
	GradleHome    string `mapstructure:"gradle_home"`
}

type PipelineStep struct {
	Name     string `mapstructure:"name"`
	Cmd      string `mapstructure:"cmd"`
	LiveOnly bool   `mapstructure:"live_only"`
}

type LiveEvalConfig struct {
	Timeout        int    `mapstructure:"timeout"`
	PreserveMemory bool   `mapstructure:"preserve_memory"`
	GoalsEnabled   bool   `mapstructure:"goals_enabled"`
	LLMConfigFile  string `mapstructure:"llm_config_file"`
	NeopsykeCmd    string `mapstructure:"neopsyke_cmd"`
}

type SessionConfig struct {
	Record bool `mapstructure:"record"`
}

type BBHConfig struct {
	PromptsFile          string `mapstructure:"prompts_file"`
	AnswersFile          string `mapstructure:"answers_file"`
	MinPassRate          int    `mapstructure:"min_pass_rate"`
	MaxTimeouts          int    `mapstructure:"max_timeouts"`
	MaxRegressionPercent int    `mapstructure:"max_regression_percent"`
	PreserveMemory       bool   `mapstructure:"preserve_memory"`
	MemoryEnabled        bool   `mapstructure:"memory_enabled"`
	LogbookEnabled       bool   `mapstructure:"logbook_enabled"`
}

type RuntimeConfig struct {
	ContinueOnFail  bool `mapstructure:"continue_on_fail"`
	ScratchpadDebug bool `mapstructure:"scratchpad_debug"`
	IDEnabled       bool `mapstructure:"id_enabled"`
}

type TelemetryConfig struct {
	Enabled bool `mapstructure:"enabled"`
}

// DefaultConfig returns a FreudConfig with all built-in defaults.
func DefaultConfig() *FreudConfig {
	return &FreudConfig{
		Project: ProjectConfig{
			Name:          "neopsyke",
			RunRoot:       ".neopsyke/runs/freud",
			RetentionDays: 3,
			GradleHome:    ".freud/gradle-home",
		},
		Pipeline: []PipelineStep{
			{Name: "preflight_compile", Cmd: "./gradlew compileKotlin compileTestKotlin"},
			{Name: "targeted_tests", Cmd: "./gradlew :test --tests 'ai.neopsyke.agent.*'"},
			{Name: "full_tests", Cmd: "./gradlew test"},
			{Name: "scenario_pack", Cmd: "freud/scripts/run-scenarios.sh --file freud/scenarios/v1/neopsyke-agent-scenarios.json"},
			{Name: "reasoning_eval_logic", Cmd: "freud/scripts/run-reasoning-pr-gate.sh"},
			{Name: "reasoning_eval_model", Cmd: "", LiveOnly: true},
			{Name: "memory_live_smoke", Cmd: "", LiveOnly: true},
			{Name: "session_replay_test", Cmd: "", LiveOnly: true},
		},
		LiveEval: LiveEvalConfig{
			Timeout:        120,
			PreserveMemory: false,
			GoalsEnabled:   false,
			LLMConfigFile:  "",
			NeopsykeCmd:    "./run-neopsyke.sh",
		},
		Session: SessionConfig{
			Record: false,
		},
		BBH: BBHConfig{
			PromptsFile:          "freud/evals/bbh-smoke/prompts.jsonl",
			AnswersFile:          "freud/evals/bbh-smoke/answers.jsonl",
			MinPassRate:          100,
			MaxTimeouts:          0,
			MaxRegressionPercent: 0,
			PreserveMemory:       false,
			MemoryEnabled:        false,
			LogbookEnabled:       false,
		},
		Runtime: RuntimeConfig{
			ContinueOnFail:  false,
			ScratchpadDebug: true,
			IDEnabled:       false,
		},
		Telemetry: TelemetryConfig{
			Enabled: true,
		},
	}
}
