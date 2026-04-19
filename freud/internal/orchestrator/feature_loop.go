package orchestrator

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
	"github.com/atomitl/neopsyke/freud/internal/config"
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
	AssignmentsEnabled *bool
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
	Name          string `json:"name"`
	Status        string `json:"status"`
	Command       string `json:"cmd"`
	DurationSec   int    `json:"duration_seconds"`
	ExitCode      int    `json:"exit_code"`
	LogPath       string `json:"log_path"`
	LogIndexPath  string `json:"log_index_path"`
	Lines         int    `json:"lines"`
	Warnings      int    `json:"warnings"`
	Errors        int    `json:"errors"`
	FirstWarning  string `json:"first_warning_ref"`
	FirstError    string `json:"first_error_ref"`
	FirstPressure string `json:"first_pressure_ref"`
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
	pipeline := effectivePipeline(featureID, cfg.Pipeline, opts)

	// Resolve paths
	runRootAbs := ResolveAbsPath(cfg.Project.RunRoot, repoRoot)
	gradleHome := ""
	if cfg.Project.GradleHome != "" {
		gradleHome = ResolveAbsPath(cfg.Project.GradleHome, repoRoot)
	}
	PrimeGradleHome(repoRoot, gradleHome, opts.Verbose)

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
	for _, step := range pipeline {
		commands[step.Name] = step.Cmd
	}
	runConfig := map[string]interface{}{
		"workflow":         "freud",
		"project":          cfg.Project.Name,
		"run_id":           filepath.Base(runDir),
		"feature_id":       featureID,
		"mode":             mode,
		"dry_run":          opts.DryRun,
		"continue_on_fail": opts.ContinueOnFail,
		"from_step":        opts.FromStep,
		"commands":         commands,
	}
	rcData, _ := json.MarshalIndent(runConfig, "", "  ")
	os.WriteFile(filepath.Join(artifactsDir, "run-config.json"), append(rcData, '\n'), 0o644)

	// Initialize TSV files
	stepsFile := filepath.Join(artifactsDir, "steps.tsv")
	stepIndexFile := filepath.Join(artifactsDir, "step-index.tsv")
	sf, _ := os.Create(stepsFile)
	sif, _ := os.Create(stepIndexFile)
	fmt.Fprintln(sif, "step\tstatus\tduration_sec\tlog\tlog_index\tlog_lines\twarnings\terrors\tfirst_warning\tfirst_error\tfirst_pressure")

	// Export assignments override
	if opts.AssignmentsEnabled != nil {
		os.Setenv("NEOPSYKE_ASSIGNMENTS_ENABLED", fmt.Sprintf("%v", *opts.AssignmentsEnabled))
	}
	// Export lane name for shell cmd steps that invoke `freud eval --lane`.
	if cfg.Lane != "" {
		os.Setenv("FREUD_LANE", cfg.Lane)
	}

	trail.Emit("run_start", "", "", fmt.Sprintf("feature=%s mode=%s", featureID, mode), "", "", "")

	// Run steps
	var stepResults []stepResult
	shouldStop := false
	fromStepReached := opts.FromStep == ""

	for stepIdx, step := range pipeline {
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
		if !config.RuntimeStepEnabled(cfg, step.Name) {
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
		sr.Command = cmdLabel
		sr.LogPath = logPath
		sr.LogIndexPath = filepath.Join(logIndexDir, step.Name+".tsv")

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
				sr.Lines = counts.Lines
				sr.Warnings = counts.Warnings
				sr.Errors = counts.Errors
				sr.FirstWarning = counts.FirstWarning
				sr.FirstError = counts.FirstError
				sr.FirstPressure = counts.FirstPressure
			}
		}

		// Write step-meta JSON
		metaData, _ := json.MarshalIndent(sr, "", "  ")
		os.WriteFile(filepath.Join(stepMetaDir, step.Name+".json"), append(metaData, '\n'), 0o644)

		// Write TSV rows
		fmt.Fprintf(sf, "%s\t%s\t%d\t%s\n", sr.Name, sr.Status, sr.DurationSec, sr.LogPath)
		fmt.Fprintf(sif, "%s\t%s\t%d\t%s\t%s\t%d\t%d\t%d\t%s\t%s\t%s\n",
			sr.Name, sr.Status, sr.DurationSec, sr.LogPath, sr.LogIndexPath,
			sr.Lines, sr.Warnings, sr.Errors,
			analysis.TSVEscape(sr.FirstWarning), analysis.TSVEscape(sr.FirstError), analysis.TSVEscape(sr.FirstPressure))

		stepRef := sr.FirstError
		if stepRef == "" {
			stepRef = sr.FirstWarning
		}
		if stepRef == "" {
			stepRef = filepath.Join(stepMetaDir, step.Name+".json")
		}
		trail.Emit("step_indexed", sr.Name, sr.Status, "", "", logPath, stepRef)
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
	stepsSkipped := 0
	firstFailedStep := ""
	for _, sr := range stepResults {
		if sr.Status == "fail" {
			overallStatus = "fail"
			stepsFailed++
			if firstFailedStep == "" {
				firstFailedStep = sr.Name
			}
		} else if sr.Status == "pass" {
			stepsPassed++
		} else if sr.Status == "skipped" || sr.Status == "dry_run" {
			stepsSkipped++
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
		"steps_skipped":     stepsSkipped,
		"first_failed_step": firstFailedStep,
		"failed_test_count": 0,
		"run_dir":           runDir,
	}
	summaryData, _ := json.MarshalIndent(summary, "", "  ")
	os.WriteFile(summaryPath, append(summaryData, '\n'), 0o644)

	// Write freud-metrics.json
	metricsPath := filepath.Join(artifactsDir, "freud-metrics.json")
	metrics := map[string]interface{}{
		"run_id":        filepath.Base(runDir),
		"feature_id":    featureID,
		"status":        overallStatus,
		"steps_total":   len(stepResults),
		"steps_passed":  stepsPassed,
		"steps_failed":  stepsFailed,
		"steps_skipped": stepsSkipped,
	}
	metricsData, _ := json.MarshalIndent(metrics, "", "  ")
	os.WriteFile(metricsPath, append(metricsData, '\n'), 0o644)

	failuresPath := filepath.Join(artifactsDir, "failures.json")
	failures := map[string]interface{}{
		"feature_id":   featureID,
		"run_id":       filepath.Base(runDir),
		"failed_steps": failedStepEntries(stepResults, stepMetaDir),
	}
	failuresData, _ := json.MarshalIndent(failures, "", "  ")
	os.WriteFile(failuresPath, append(failuresData, '\n'), 0o644)

	trail.Emit("run_end", "", overallStatus, fmt.Sprintf("steps_passed=%d steps_failed=%d", stepsPassed, stepsFailed), "", "", "")

	// Post-run analysis
	analysis.Summarize(runDir)
	analysis.ContextPack(runDir)

	runIndexJSONPath := filepath.Join(artifactsDir, "run-index.json")
	runIndexJSON := buildRunIndexJSON(featureID, filepath.Base(runDir), overallStatus, mode, runDir, firstFailedStep, summaryPath, failuresPath, metricsPath, artifactsDir, stepResults)
	runIndexData, _ := json.MarshalIndent(runIndexJSON, "", "  ")
	os.WriteFile(runIndexJSONPath, append(runIndexData, '\n'), 0o644)
	os.WriteFile(filepath.Join(artifactsDir, "run-index.md"), []byte(buildRunIndexMarkdown(featureID, filepath.Base(runDir), overallStatus, mode, runDir, firstFailedStep, artifactsDir)+"\n"), 0o644)

	// Append to run index
	AppendRunIndex(runRootAbs, RunIndexEntry{
		Timestamp: time.Now().UTC().Format("20060102T150405Z"),
		FeatureID: featureID,
		RunDir:    runDir,
		Status:    overallStatus,
	})
	WriteLatestPointers(runRootAbs, repoRoot, runDir)

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
	fmt.Printf("Run directory: %s\n", runDir)
	fmt.Printf("[freud] run_dir=%s\n", runDir)
	fmt.Printf("Summary: %s\n", summaryPath)
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
	"memory_live_smoke":    true,
	"test_freud_replay":    true,
}

func isBuiltinStep(name string) bool {
	return builtinSteps[name]
}

func effectivePipeline(featureID string, pipeline []config.PipelineStep, opts FeatureLoopOpts) []config.PipelineStep {
	if phasePipeline, ok := cognitiveRuntimePhasePipeline(featureID, pipeline); ok {
		return phasePipeline
	}

	if featureID != "signoff-gate" || opts.OnlyStep == "targeted_tests" || opts.FromStep == "targeted_tests" {
		return pipeline
	}

	filtered := make([]config.PipelineStep, 0, len(pipeline))
	for _, step := range pipeline {
		if step.Name == "targeted_tests" {
			continue
		}
		filtered = append(filtered, step)
	}
	return filtered
}

func cognitiveRuntimePhasePipeline(featureID string, pipeline []config.PipelineStep) ([]config.PipelineStep, bool) {
	stepByName := make(map[string]config.PipelineStep, len(pipeline))
	for _, step := range pipeline {
		stepByName[step.Name] = step
	}

	buildPipeline := func(stepNames ...string) ([]config.PipelineStep, bool) {
		result := make([]config.PipelineStep, 0, len(stepNames))
		for _, name := range stepNames {
			step, ok := stepByName[name]
			if !ok {
				return nil, false
			}
			result = append(result, step)
		}
		return result, true
	}

	switch featureID {
	case "cognitive-runtime-p0-tests":
		result, ok := buildPipeline(
			"preflight_compile",
			"targeted_tests",
		)
		return result, ok
	case "cognitive-runtime-p1-foundation",
		"cognitive-runtime-p2-opportunities",
		"cognitive-runtime-p3-intentions",
		"cognitive-runtime-p4-feedback",
		"cognitive-runtime-p5-assignments-scratchpad",
		"cognitive-runtime-p6-policy-control",
		"cognitive-runtime-p7-convergence":
		result, ok := buildPipeline(
			"preflight_compile",
			"full_tests",
			"scenario_pack",
			"reasoning_eval_logic",
		)
		return result, ok
	default:
		return nil, false
	}
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
	progress := newBuiltinStepReporter(name, origStdout)
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
			Lane:                 orDefault(cfg.Lane, "default"),
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
			Progress:             progress,
		})
		if err != nil {
			fmt.Fprintf(logFile, "error: %v\n", err)
			return 1
		}
		return result.ExitCode

	case "memory_live_smoke":
		result, err := MemoryLiveSmoke(MemoryLiveSmokeOpts{
			TaskIDs:        cfg.MemoryLive.TaskIDs,
			Stage:          cfg.MemoryLive.Stage,
			MaxAttempts:    cfg.MemoryLive.MaxAttempts,
			NeopsykeCmd:    cfg.LiveEval.NeopsykeCmd,
			RunRootAbs:     cfg.Project.RunRoot,
			GradleUserHome: gradleHome,
			LLMConfigFile:  cfg.LiveEval.LLMConfigFile,
			RepoRoot:       repoRoot,
			Verbose:        opts.Verbose,
			Progress:       progress,
		})
		if err != nil {
			fmt.Fprintf(logFile, "error: %v\n", err)
			return 1
		}
		return result.ExitCode

	case "test_freud_replay":
		result, err := SessionReplayTest(SessionReplayTestOpts{
			Timeout:  cfg.LiveEval.Timeout,
			RepoRoot: repoRoot,
			Cfg:      cfg,
			Verbose:  opts.Verbose,
			Progress: progress,
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

func newBuiltinStepReporter(stepName string, w io.Writer) ProgressReporter {
	return WithStepProgress(stepName, NewConsoleProgressReporter(w))
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

func failedStepEntries(stepResults []stepResult, stepMetaDir string) []map[string]interface{} {
	var failed []map[string]interface{}
	for _, sr := range stepResults {
		if sr.Status != "fail" {
			continue
		}
		failed = append(failed, map[string]interface{}{
			"name":         sr.Name,
			"duration_sec": sr.DurationSec,
			"log":          sr.LogPath,
			"step_meta":    filepath.Join(stepMetaDir, sr.Name+".json"),
		})
	}
	return failed
}

func buildRunIndexJSON(featureID, runID, status, mode, runDir, firstFailedStep, summaryPath, failuresPath, metricsPath, artifactsDir string, stepResults []stepResult) map[string]interface{} {
	return map[string]interface{}{
		"workflow":          workflowDirName,
		"feature_id":        featureID,
		"run_id":            runID,
		"status":            status,
		"mode":              mode,
		"run_dir":           runDir,
		"first_failed_step": firstFailedStep,
		"steps_total":       len(stepResults),
		"files": map[string]interface{}{
			"summary":            summaryPath,
			"failures":           failuresPath,
			"anomalies":          filepath.Join(artifactsDir, "anomalies.json"),
			"run_config":         filepath.Join(artifactsDir, "run-config.json"),
			"freud_metrics_json": metricsPath,
			"trail":              filepath.Join(artifactsDir, "trail.jsonl"),
			"trail_index":        filepath.Join(artifactsDir, "trail-index.tsv"),
			"step_index":         filepath.Join(artifactsDir, "step-index.tsv"),
			"step_meta_dir":      filepath.Join(artifactsDir, "step-meta"),
			"context_pack":       filepath.Join(artifactsDir, "context-pack.md"),
		},
	}
}

func buildRunIndexMarkdown(featureID, runID, status, mode, runDir, firstFailedStep, artifactsDir string) string {
	lines := []string{
		"# Freud Run Index",
		"",
		fmt.Sprintf("- run_id: `%s`", runID),
		fmt.Sprintf("- feature_id: `%s`", featureID),
		fmt.Sprintf("- status: `%s`", status),
		fmt.Sprintf("- mode: `%s`", mode),
		fmt.Sprintf("- first_failed_step: `%s`", orDefault(firstFailedStep, "none")),
		fmt.Sprintf("- run_dir: `%s`", runDir),
		"",
		"## Core Artifacts",
		fmt.Sprintf("- summary: `%s`", filepath.Join(artifactsDir, "summary.json")),
		fmt.Sprintf("- failures: `%s`", filepath.Join(artifactsDir, "failures.json")),
		fmt.Sprintf("- anomalies: `%s`", filepath.Join(artifactsDir, "anomalies.json")),
		fmt.Sprintf("- run config: `%s`", filepath.Join(artifactsDir, "run-config.json")),
		fmt.Sprintf("- freud metrics: `%s`", filepath.Join(artifactsDir, "freud-metrics.json")),
		fmt.Sprintf("- trail: `%s`", filepath.Join(artifactsDir, "trail.jsonl")),
		fmt.Sprintf("- trail index: `%s`", filepath.Join(artifactsDir, "trail-index.tsv")),
		fmt.Sprintf("- step index: `%s`", filepath.Join(artifactsDir, "step-index.tsv")),
		fmt.Sprintf("- step meta dir: `%s`", filepath.Join(artifactsDir, "step-meta")),
		fmt.Sprintf("- context pack: `%s`", filepath.Join(artifactsDir, "context-pack.md")),
	}
	return strings.Join(lines, "\n")
}

func orDefault(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
