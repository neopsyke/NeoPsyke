package config

// StepEnvVars maps pipeline step names to their corresponding FREUD_*_CMD env var.
var StepEnvVars = map[string]string{
	"preflight_compile":    "FREUD_PREFLIGHT_COMPILE_CMD",
	"targeted_tests":       "FREUD_TARGETED_TEST_CMD",
	"full_tests":           "FREUD_FULL_TEST_CMD",
	"scenario_pack":        "FREUD_SCENARIO_PACK_CMD",
	"reasoning_eval_logic": "FREUD_REASONING_EVAL_LOGIC_CMD",
	"reasoning_eval_model": "FREUD_REASONING_EVAL_MODEL_CMD",
	"memory_live_smoke":    "FREUD_MEMORY_SMOKE_CMD",
	"session_replay_test":  "FREUD_SESSION_REPLAY_TEST_CMD",
}

// LaneMap maps user-facing lane names to the internal names used by shell scripts.
var LaneMap = map[string]string{
	"low-llm":  "weak-structure",
	"high-llm": "prod-acceptance",
}

// ManualEnvBindings maps env var names to Viper config paths for vars
// that don't follow the FREUD_ prefix convention.
var ManualEnvBindings = map[string]string{
	"NEOPSYKE_LLM_CONFIG_FILE":               "live_eval.llm_config_file",
	"NEOPSYKE_GOALS_ENABLED":                 "live_eval.goals_enabled",
	"NEOPSYKE_ID_ENABLED":                    "runtime.id_enabled",
	"EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED":   "runtime.scratchpad_debug",
	"FREUD_BBH_MIN_PASS_RATE_PERCENT":        "bbh.min_pass_rate",
	"FREUD_BBH_MAX_REGRESSION_PERCENT":       "bbh.max_regression_percent",
}
