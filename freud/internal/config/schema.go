package config

// FreudConfig is the top-level configuration structure for the Freud CLI.
// All fields map to YAML keys via mapstructure tags.
type FreudConfig struct {
	Project    ProjectConfig    `mapstructure:"project"`
	Pipeline   []PipelineStep   `mapstructure:"pipeline"`
	LiveEval   LiveEvalConfig   `mapstructure:"live_eval"`
	Session    SessionConfig    `mapstructure:"session"`
	Scenarios  ScenariosConfig  `mapstructure:"scenarios"`
	BBH        BBHConfig        `mapstructure:"bbh"`
	MemoryLive MemoryLiveConfig `mapstructure:"memory_live"`
	Runtime    RuntimeConfig    `mapstructure:"runtime"`
	Telemetry  TelemetryConfig  `mapstructure:"telemetry"`

	// Lane is the profile lane name passed via --lane (e.g. "low-llm", "high-llm").
	// Set programmatically after config load, not mapped from YAML.
	Lane string `mapstructure:"-"`
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
	AssignmentsEnabled bool `mapstructure:"assignments_enabled"`
	LLMConfigFile  string `mapstructure:"llm_config_file"`
	NeopsykeCmd    string `mapstructure:"neopsyke_cmd"`
}

type SessionConfig struct {
	Record             bool `mapstructure:"record"`
	FreudReplayEnabled bool `mapstructure:"freud_replay_enabled"`
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

type ScenariosConfig struct {
	ManifestFile string `mapstructure:"manifest_file"`
}

type MemoryLiveConfig struct {
	Enabled     bool     `mapstructure:"enabled"`
	TaskIDs     []string `mapstructure:"task_ids"`
	Stage       string   `mapstructure:"stage"`
	MaxAttempts int      `mapstructure:"max_attempts"`
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
			{Name: "scenario_pack"},
			{Name: "reasoning_eval_logic"},
			{Name: "reasoning_eval_model", LiveOnly: true},
			{Name: "memory_live_smoke", LiveOnly: true},
			{Name: "test_freud_replay", LiveOnly: true},
		},
		Scenarios: ScenariosConfig{
			ManifestFile: "freud/scenarios/v1/neopsyke-agent-scenarios.json",
		},
		MemoryLive: MemoryLiveConfig{
			Enabled:     false,
			TaskIDs:     []string{"user-preference-color"},
			Stage:       "freud-memory-live-smoke",
			MaxAttempts: 1,
		},
		LiveEval: LiveEvalConfig{
			Timeout:        120,
			PreserveMemory: false,
			AssignmentsEnabled: false,
			LLMConfigFile:  "",
			NeopsykeCmd:    "./run-neopsyke.sh",
		},
		Session: SessionConfig{
			Record:             false,
			FreudReplayEnabled: false,
		},
		BBH: BBHConfig{
			PromptsFile:          "freud/evals/bbh-smoke/prompts.jsonl",
			AnswersFile:          "freud/evals/bbh-smoke/answers.jsonl",
			MinPassRate:          90,
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
