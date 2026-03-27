package dispatch

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/atomitl/neopsyke/freud/cli/config"
)

// BuildEnv creates a full environment variable slice from the merged config.
// It inherits the current environment and overlays all FREUD_* vars.
func BuildEnv(cfg *config.FreudConfig) []string {
	env := os.Environ()

	env = setEnv(env, "FREUD_PROJECT_NAME", cfg.Project.Name)
	env = setEnv(env, "FREUD_RUN_ROOT", cfg.Project.RunRoot)
	env = setEnv(env, "FREUD_RUN_RETENTION_DAYS", strconv.Itoa(cfg.Project.RetentionDays))
	if cfg.Project.GradleHome != "" {
		env = setEnv(env, "FREUD_GRADLE_USER_HOME", cfg.Project.GradleHome)
	}

	// Pipeline step commands
	for _, step := range cfg.Pipeline {
		if envVar, ok := config.StepEnvVars[step.Name]; ok {
			env = setEnv(env, envVar, step.Cmd)
		}
	}

	// Live eval
	env = setEnv(env, "FREUD_LIVE_EVAL_TIMEOUT", strconv.Itoa(cfg.LiveEval.Timeout))
	env = setEnv(env, "FREUD_LIVE_EVAL_PRESERVE_MEMORY", boolStr(cfg.LiveEval.PreserveMemory))
	env = setEnv(env, "FREUD_LIVE_EVAL_NEOPSYKE_CMD", cfg.LiveEval.NeopsykeCmd)
	if cfg.LiveEval.LLMConfigFile != "" {
		env = setEnv(env, "NEOPSYKE_LLM_CONFIG_FILE", cfg.LiveEval.LLMConfigFile)
	}
	env = setEnv(env, "NEOPSYKE_GOALS_ENABLED", boolStr(cfg.LiveEval.GoalsEnabled))

	// BBH
	env = setEnv(env, "FREUD_BBH_PROMPTS_FILE", cfg.BBH.PromptsFile)
	env = setEnv(env, "FREUD_BBH_ANSWERS_FILE", cfg.BBH.AnswersFile)
	env = setEnv(env, "FREUD_BBH_MIN_PASS_RATE_PERCENT", strconv.Itoa(cfg.BBH.MinPassRate))
	env = setEnv(env, "FREUD_BBH_MAX_TIMEOUTS", strconv.Itoa(cfg.BBH.MaxTimeouts))
	env = setEnv(env, "FREUD_BBH_MAX_REGRESSION_PERCENT", strconv.Itoa(cfg.BBH.MaxRegressionPercent))
	env = setEnv(env, "FREUD_BBH_PRESERVE_MEMORY", boolStr(cfg.BBH.PreserveMemory))
	env = setEnv(env, "FREUD_BBH_MEMORY_ENABLED", boolStr(cfg.BBH.MemoryEnabled))
	env = setEnv(env, "FREUD_BBH_LOGBOOK_ENABLED", boolStr(cfg.BBH.LogbookEnabled))

	// Runtime
	env = setEnv(env, "FREUD_CONTINUE_ON_FAIL", boolStr(cfg.Runtime.ContinueOnFail))
	env = setEnv(env, "EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED", boolStr(cfg.Runtime.ScratchpadDebug))
	env = setEnv(env, "NEOPSYKE_ID_ENABLED", boolStr(cfg.Runtime.IDEnabled))

	return env
}

// RunScript executes a shell script with the given args and env.
// Returns the process exit code.
func RunScript(script string, args []string, env []string, dryRun bool, verbose int) (int, error) {
	root, err := config.RepoRoot()
	if err != nil {
		return 1, fmt.Errorf("finding repo root: %w", err)
	}

	scriptPath := script
	if !filepath.IsAbs(scriptPath) {
		scriptPath = filepath.Join(root, scriptPath)
	}

	if dryRun {
		fmt.Printf("[dry-run] %s %s\n", scriptPath, strings.Join(args, " "))
		return 0, nil
	}

	if verbose > 0 {
		fmt.Printf("[freud] exec: %s %s\n", scriptPath, strings.Join(args, " "))
	}

	cmd := exec.Command(scriptPath, args...)
	cmd.Env = env
	cmd.Dir = root
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Stdin = os.Stdin

	if err := cmd.Run(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return exitErr.ExitCode(), nil
		}
		return 1, fmt.Errorf("executing %s: %w", script, err)
	}
	return 0, nil
}

// ScriptPath returns the absolute path to a script in freud/scripts/.
func ScriptPath(name string) (string, error) {
	root, err := config.RepoRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, "freud", "scripts", name), nil
}

// LatestRunDir reads the latest-run.txt pointer and returns the run directory path.
func LatestRunDir() (string, error) {
	root, err := config.RepoRoot()
	if err != nil {
		return "", err
	}
	pointer := filepath.Join(root, "freud", "latest-run.txt")
	data, err := os.ReadFile(pointer)
	if err != nil {
		// Try the symlink as fallback
		link := filepath.Join(root, ".neopsyke", "runs", "freud", "latest")
		target, err2 := os.Readlink(link)
		if err2 != nil {
			return "", fmt.Errorf("no latest run found: %v", err)
		}
		if !filepath.IsAbs(target) {
			target = filepath.Join(filepath.Dir(link), target)
		}
		return target, nil
	}
	dir := strings.TrimSpace(string(data))
	if !filepath.IsAbs(dir) {
		dir = filepath.Join(root, dir)
	}
	return dir, nil
}

func setEnv(env []string, key, value string) []string {
	prefix := key + "="
	for i, e := range env {
		if strings.HasPrefix(e, prefix) {
			env[i] = prefix + value
			return env
		}
	}
	return append(env, prefix+value)
}

func boolStr(b bool) string {
	if b {
		return "true"
	}
	return "false"
}
