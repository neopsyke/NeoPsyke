package orchestrator

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/atomitl/neopsyke/freud/cli/analysis"
	"github.com/atomitl/neopsyke/freud/cli/config"
)

// FeatureLoopOpts configures a full pipeline run.
type FeatureLoopOpts struct {
	FeatureID      string
	Live           bool
	DryRun         bool
	ContinueOnFail bool
	FromStep       string
	OnlyStep       string
	SkipSteps      []string
	GoalsEnabled   *bool
	Cfg            *config.FreudConfig
	RepoRoot       string
	Verbose        int
}

// FeatureLoopResult holds the output of a pipeline run.
type FeatureLoopResult struct {
	RunDir        string
	OverallStatus string
	SummaryJSON   string
	ExitCode      int
}

type stepResult struct {
	Name        string `json:"name"`
	Status      string `json:"status"`
	DurationSec int    `json:"duration_seconds"`
	ExitCode    int    `json:"exit_code"`
	LogPath     string `json:"log_path"`
	Warnings    int    `json:"warnings"`
	Errors      int    `json:"errors"`
	FirstError  string `json:"first_error_ref"`
}

var nonAlnum = regexp.MustCompile(`[^a-z0-9]+`)

// FeatureLoop runs the full pipeline. Replaces feature-loop.sh.
func FeatureLoop(opts FeatureLoopOpts) (*FeatureLoopResult, error) {
	repoRoot := opts.RepoRoot
	if repoRoot == "" {
		var err error
		repoRoot, err = findRepoRoot()
		if err != nil {
			return nil, err
		}
	}

	cfg := opts.Cfg
	if cfg == nil {
		cfg = config.DefaultConfig()
	}

	// Normalize feature ID
	featureID := strings.ToLower(opts.FeatureID)
	featureID = nonAlnum.ReplaceAllString(featureID, "-")
	featureID = strings.Trim(featureID, "-")

	// Resolve paths
	runRootAbs := ResolveAbsPath(cfg.Project.RunRoot, repoRoot)
	gradleHome := ""
	if cfg.Project.GradleHome != "" {
		gradleHome = ResolveAbsPath(cfg.Project.GradleHome, repoRoot)
	}

	// Determine mode
	mode := "stub"
	if opts.Live {
		mode = "live"
	}

	// Build skip set
	skipSet := make(map[string]bool)
	for _, s := range opts.SkipSteps {
		skipSet[s] = true
	}

	// Create run directory
	runDir, err := CreateRunDir(runRootAbs, featureID, []string{
		"logs", "artifacts", "artifacts/step-meta", "artifacts/log-index",
	})
	if err != nil {
		return nil, fmt.Errorf("creating run dir: %w", err)
	}

	logsDir := filepath.Join(runDir, "logs")
	artifactsDir := filepath.Join(runDir, "artifacts")
	logIndexDir := filepath.Join(artifactsDir, "log-index")
	stepMetaDir := filepath.Join(artifactsDir, "step-meta")

	// Initialize trail
	trail, err := NewTrailEmitter(artifactsDir)
	if err != nil {
		return nil, err
	}
	defer trail.Close()

	// Write run-config.json
	commands := map[string]string{}
	for _, step := range cfg.Pipeline {
		commands[step.Name] = step.Cmd
	}
	runConfig := map[string]interface{}{
		"workflow":         "freud",
		"project":         cfg.Project.Name,
		"run_id":          filepath.Base(runDir),
		"feature_id":      featureID,
		"mode":            mode,
		"dry_run":         opts.DryRun,
		"continue_on_fail": opts.ContinueOnFail,
		"from_step":       opts.FromStep,
		"commands":        commands,
	}
	rcData, _ := json.MarshalIndent(runConfig, "", "  ")
	os.WriteFile(filepath.Join(artifactsDir, "run-config.json"), append(rcData, '\n'), 0o644)

	// Initialize TSV files
	stepsFile := filepath.Join(artifactsDir, "steps.tsv")
	stepIndexFile := filepath.Join(artifactsDir, "step-index.tsv")
	sf, _ := os.Create(stepsFile)
	sif, _ := os.Create(stepIndexFile)
	fmt.Fprintln(sif, "step\tstatus\tduration\tlog\tlines\twarnings\terrors\tfirst_warning\tfirst_error")

	// Export goals override
	if opts.GoalsEnabled != nil {
		os.Setenv("NEOPSYKE_GOALS_ENABLED", fmt.Sprintf("%v", *opts.GoalsEnabled))
	}

	trail.Emit("run_start", "", "", fmt.Sprintf("feature=%s mode=%s", featureID, mode), "", "", "")

	// Run steps
	var stepResults []stepResult
	shouldStop := false
	fromStepReached := opts.FromStep == ""

	for stepIdx, step := range cfg.Pipeline {
		// from-step filtering
		if !fromStepReached {
			if step.Name == opts.FromStep {
				fromStepReached = true
			} else {
				continue
			}
		}

		// only-step filtering
		if opts.OnlyStep != "" && step.Name != opts.OnlyStep {
			continue
		}

		// skip filtering
		if skipSet[step.Name] {
			continue
		}

		// live-only filtering
		if step.LiveOnly && !opts.Live {
			continue
		}

		// stop on previous failure
		if shouldStop {
			break
		}

		// Determine if this is a built-in step or a shell command step
		isBuiltin := isBuiltinStep(step.Name)
		if step.Cmd == "" && !isBuiltin {
			continue
		}

		logNum := fmt.Sprintf("%02d", stepIdx)
		logPath := filepath.Join(logsDir, logNum+"-"+step.Name+".log")

		cmdLabel := step.Cmd
		if isBuiltin {
			cmdLabel = fmt.Sprintf("[builtin:%s]", step.Name)
		}
		trail.Emit("step_start", step.Name, "", cmdLabel, cmdLabel, "", "")

		startTime := time.Now()
		var sr stepResult
		sr.Name = step.Name
		sr.LogPath = logPath

		if opts.DryRun {
			sr.Status = "dry_run"
			sr.DurationSec = 0
			os.WriteFile(logPath, []byte(fmt.Sprintf("[dry-run] %s\n", cmdLabel)), 0o644)
			fmt.Printf("[dry-run] step=%s %s\n", step.Name, cmdLabel)
		} else {
			fmt.Printf("[freud] step=%s\n", step.Name)

			if isBuiltin {
				sr.ExitCode = runBuiltinStep(step.Name, cfg, repoRoot, gradleHome, logPath, opts)
			} else {
				if opts.Verbose > 0 {
					fmt.Printf("[freud] exec: %s\n", step.Cmd)
				}
				sr.ExitCode = runShellStep(step.Cmd, repoRoot, gradleHome, logPath)
			}

			sr.DurationSec = int(time.Since(startTime).Seconds())

			if sr.ExitCode != 0 {
				sr.Status = "fail"
			} else {
				sr.Status = "pass"
			}

			// Index step log
			counts, _ := IndexStepLog(step.Name, logPath, logIndexDir)
			if counts != nil {
				sr.Warnings = counts.Warnings
				sr.Errors = counts.Errors
				sr.FirstError = counts.FirstError
			}
		}

		// Write step-meta JSON
		metaData, _ := json.MarshalIndent(sr, "", "  ")
		os.WriteFile(filepath.Join(stepMetaDir, step.Name+".json"), append(metaData, '\n'), 0o644)

		// Write TSV rows
		fmt.Fprintf(sf, "%s\t%s\t%d\t%s\n", sr.Name, sr.Status, sr.DurationSec, sr.LogPath)
		fmt.Fprintf(sif, "%s\t%s\t%d\t%s\t%d\t%d\t%d\t%s\t%s\n",
			sr.Name, sr.Status, sr.DurationSec, sr.LogPath,
			0, sr.Warnings, sr.Errors,
			analysis.TSVEscape(sr.FirstError), analysis.TSVEscape(sr.FirstError))

		trail.Emit("step_indexed", sr.Name, sr.Status, "", "", logPath, sr.FirstError)
		trail.Emit("step_end", sr.Name, sr.Status,
			fmt.Sprintf("duration=%ds exit=%d", sr.DurationSec, sr.ExitCode), "", logPath, "")

		stepResults = append(stepResults, sr)

		if sr.Status == "fail" && !opts.ContinueOnFail {
			shouldStop = true
		}
	}
	sf.Close()
	sif.Close()

	// Compute overall status
	overallStatus := "pass"
	stepsFailed := 0
	stepsPassed := 0
	for _, sr := range stepResults {
		if sr.Status == "fail" {
			overallStatus = "fail"
			stepsFailed++
		} else if sr.Status == "pass" {
			stepsPassed++
		}
	}

	// Run triage
	analysis.Triage(runDir, 20)
	trail.Emit("triage_complete", "", "", "", "", "", "")

	// Write summary.json
	summaryPath := filepath.Join(artifactsDir, "summary.json")
	summary := map[string]interface{}{
		"feature_id":        featureID,
		"run_id":            filepath.Base(runDir),
		"status":            overallStatus,
		"mode":              mode,
		"steps_total":       len(stepResults),
		"steps_passed":      stepsPassed,
		"steps_failed":      stepsFailed,
		"failed_test_count": 0,
	}
	summaryData, _ := json.MarshalIndent(summary, "", "  ")
	os.WriteFile(summaryPath, append(summaryData, '\n'), 0o644)

	// Write freud-metrics.json
	metricsPath := filepath.Join(artifactsDir, "freud-metrics.json")
	metrics := map[string]interface{}{
		"run_id":       filepath.Base(runDir),
		"feature_id":   featureID,
		"status":       overallStatus,
		"steps_total":  len(stepResults),
		"steps_passed": stepsPassed,
		"steps_failed": stepsFailed,
	}
	metricsData, _ := json.MarshalIndent(metrics, "", "  ")
	os.WriteFile(metricsPath, append(metricsData, '\n'), 0o644)

	trail.Emit("run_end", "", overallStatus, fmt.Sprintf("steps_passed=%d steps_failed=%d", stepsPassed, stepsFailed), "", "", "")

	// Post-run analysis
	analysis.Summarize(runDir)
	analysis.ContextPack(runDir)

	// Append to run index
	AppendRunIndex(runRootAbs, RunIndexEntry{
		Timestamp: time.Now().UTC().Format("20060102T150405Z"),
		FeatureID: featureID,
		RunDir:    runDir,
		Status:    overallStatus,
	})

	// Cleanup old runs (all types, based on retention_days)
	cleaned, _ := CleanupAllOldRuns(runRootAbs, cfg.Project.RetentionDays)
	if cleaned > 0 && opts.Verbose > 0 {
		fmt.Printf("[freud] cleaned %d old run(s) (retention=%d days)\n", cleaned, cfg.Project.RetentionDays)
	}

	// Print final output
	exitCode := 0
	if overallStatus == "fail" {
		exitCode = 2
		fmt.Printf("\n[freud] FAILED: %s (%d/%d steps passed)\n", featureID, stepsPassed, len(stepResults))
		for _, sr := range stepResults {
			if sr.Status == "fail" {
				fmt.Printf("  - %s (exit=%d, log=%s)\n", sr.Name, sr.ExitCode, sr.LogPath)
			}
		}
	} else {
		fmt.Printf("\n[freud] PASSED: %s (%d/%d steps)\n", featureID, stepsPassed, len(stepResults))
	}
	fmt.Printf("[freud] run_dir=%s\n", runDir)
	fmt.Printf("[freud] summary=%s\n", summaryPath)

	return &FeatureLoopResult{
		RunDir:        runDir,
		OverallStatus: overallStatus,
		SummaryJSON:   summaryPath,
		ExitCode:      exitCode,
	}, nil
}

// Built-in step registry — steps dispatched to native Go functions by name.
var builtinSteps = map[string]bool{
	"scenario_pack":        true,
	"reasoning_eval_logic": true,
	"reasoning_eval_model": true,
	"test_replay_eval":     true,
}

func isBuiltinStep(name string) bool {
	return builtinSteps[name]
}

// runBuiltinStep dispatches a built-in step by name to its Go implementation.
// Output is captured to logPath. Returns exit code.
func runBuiltinStep(name string, cfg *config.FreudConfig, repoRoot, gradleHome, logPath string, opts FeatureLoopOpts) int {
	// Capture stdout/stderr to log file
	logFile, err := os.Create(logPath)
	if err != nil {
		return 1
	}
	defer logFile.Close()

	// Redirect stdout/stderr to the log file for the duration of this step
	origStdout := os.Stdout
	origStderr := os.Stderr
	os.Stdout = logFile
	os.Stderr = logFile
	defer func() {
		os.Stdout = origStdout
		os.Stderr = origStderr
	}()

	switch name {
	case "scenario_pack":
		result, err := RunScenarios(ScenariosOpts{
			ManifestFile:   cfg.Scenarios.ManifestFile,
			GradleUserHome: gradleHome,
			RepoRoot:       repoRoot,
			Verbose:        opts.Verbose,
		})
		if err != nil {
			fmt.Fprintf(logFile, "error: %v\n", err)
			return 1
		}
		return result.ExitCode

	case "reasoning_eval_logic":
		err := RunReasoningGate(ReasoningGateOpts{
			NeopsykeCmd: cfg.LiveEval.NeopsykeCmd,
			RepoRoot:    repoRoot,
			Verbose:     opts.Verbose,
		})
		if err != nil {
			fmt.Fprintf(logFile, "error: %v\n", err)
			return 1
		}
		return 0

	case "reasoning_eval_model":
		result, err := BBHSmoke(BBHOpts{
			Lane:                 resolveLane(cfg),
			PromptsFile:          cfg.BBH.PromptsFile,
			AnswersFile:          cfg.BBH.AnswersFile,
			MinPassRatePercent:   cfg.BBH.MinPassRate,
			MaxTimeouts:          cfg.BBH.MaxTimeouts,
			MaxRegressionPercent: cfg.BBH.MaxRegressionPercent,
			PreserveMemory:       cfg.BBH.PreserveMemory,
			MemoryEnabled:        cfg.BBH.MemoryEnabled,
			LogbookEnabled:       cfg.BBH.LogbookEnabled,
			Timeout:              cfg.LiveEval.Timeout,
			NeopsykeCmd:          cfg.LiveEval.NeopsykeCmd,
			RunRootAbs:           cfg.Project.RunRoot,
			GradleUserHome:       gradleHome,
			LLMConfigFile:        cfg.LiveEval.LLMConfigFile,
			RetentionDays:        cfg.Project.RetentionDays,
			RepoRoot:             repoRoot,
			Verbose:              opts.Verbose,
		})
		if err != nil {
			fmt.Fprintf(logFile, "error: %v\n", err)
			return 1
		}
		return result.ExitCode

	case "test_replay_eval":
		result, err := SessionReplayTest(SessionReplayTestOpts{
			Timeout:  cfg.LiveEval.Timeout,
			RepoRoot: repoRoot,
			Cfg:      cfg,
			Verbose:  opts.Verbose,
		})
		if err != nil {
			fmt.Fprintf(logFile, "error: %v\n", err)
			return 1
		}
		return result.ExitCode

	default:
		fmt.Fprintf(logFile, "unknown built-in step: %s\n", name)
		return 1
	}
}

// runShellStep executes a shell command, capturing output to logPath. Returns exit code.
func runShellStep(cmd string, repoRoot, gradleHome, logPath string) int {
	logFile, err := os.Create(logPath)
	if err != nil {
		return 1
	}
	defer logFile.Close()

	c := exec.Command("bash", "-c", cmd)
	c.Dir = repoRoot
	c.Stdout = logFile
	c.Stderr = logFile
	if gradleHome != "" {
		c.Env = append(os.Environ(), "GRADLE_USER_HOME="+gradleHome)
	}

	if err := c.Run(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return exitErr.ExitCode()
		}
		return 1
	}
	return 0
}

// resolveLane determines the BBH lane name from the LLM config file path.
func resolveLane(cfg *config.FreudConfig) string {
	llmConfig := cfg.LiveEval.LLMConfigFile
	if strings.Contains(llmConfig, "weak-structure") {
		return "low-llm"
	}
	if strings.Contains(llmConfig, "prod-acceptance") {
		return "high-llm"
	}
	return "default"
}
