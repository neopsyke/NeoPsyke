package config

// StepEnvVars maps shell-command pipeline step names to their FREUD_*_CMD env var.
// Built-in steps (scenario_pack, reasoning_eval_logic, etc.) are not here —
// they are dispatched to native Go functions by the orchestrator.
var StepEnvVars = map[string]string{
	"preflight_compile": "FREUD_PREFLIGHT_COMPILE_CMD",
	"targeted_tests":    "FREUD_TARGETED_TEST_CMD",
	"full_tests":        "FREUD_FULL_TEST_CMD",
}

// LaneNames are the valid user-facing lane names.
var LaneNames = []string{"low-llm", "high-llm"}

// ManualEnvBindings maps env var names to Viper config paths for vars
// that don't follow the FREUD_ prefix convention.
var ManualEnvBindings = map[string]string{
	"NEOPSYKE_LLM_CONFIG_FILE":             "live_eval.llm_config_file",
	"NEOPSYKE_ASSIGNMENTS_ENABLED":               "live_eval.assignments_enabled",
	"NEOPSYKE_ID_ENABLED":                  "runtime.id_enabled",
	"EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED": "runtime.scratchpad_debug",
	"FREUD_BBH_MIN_PASS_RATE_PERCENT":      "bbh.min_pass_rate",
	"FREUD_BBH_MAX_REGRESSION_PERCENT":     "bbh.max_regression_percent",
}
