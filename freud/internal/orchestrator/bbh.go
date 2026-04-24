package orchestrator

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
)

// BBHOpts configures a BBH smoke suite run.
type BBHOpts struct {
	Lane                 string
	PromptsFile          string
	AnswersFile          string
	MinPassRatePercent   int
	MaxTimeouts          int
	MaxRegressionPercent int
	BaselineFile         string
	Record               bool
	SessionReplayDir     string
	PreserveMemory       bool
	MemoryEnabled        bool
	LogbookEnabled       bool
	Timeout              int
	NeopsykeCmd          string
	RunRootAbs           string
	GradleUserHome       string
	LLMConfigFile        string
	RetentionDays        int
	RepoRoot             string
	RunDirOverride       string
	Verbose              int
	DryRun               bool
	Progress             ProgressReporter
}

// BBHResult holds the output of a BBH smoke suite run.
type BBHResult struct {
	RunDir          string
	SummaryJSON     string
	ReplayStatsJSON string
	ExitCode        int
}

type bbhPrompt struct {
	ID       string `json:"id"`
	Category string `json:"category"`
	Prompt   string `json:"prompt"`
}

type bbhAnswer struct {
	ID     string `json:"id"`
	Answer string `json:"answer"`
}

type bbhCaseResult struct {
	ID              string
	Category        string
	Status          string
	FailureClass    string
	Detail          string
	Expected        string
	Actual          string
	Timeout         bool
	SchemaDowngrade int
	RunDir          string
}

type bbhReplayStats struct {
	Mode                string                 `json:"mode"`
	SessionReplaySource string                 `json:"session_replay_source,omitempty"`
	RecordedCases       int                    `json:"recorded_cases"`
	ReplayedCases       int                    `json:"replayed_cases"`
	MissingReplayCases  []string               `json:"missing_replay_cases,omitempty"`
	LLMCache            map[string]interface{} `json:"llm_cache"`
	SessionReplay       map[string]interface{} `json:"session_replay"`
	Hints               []string               `json:"hints,omitempty"`
}

// BBHSmoke runs the BBH reasoning smoke suite. Replaces run-bbh-smoke.sh.
func BBHSmoke(opts BBHOpts) (*BBHResult, error) {
	repoRoot := opts.RepoRoot
	if repoRoot == "" {
		var err error
		repoRoot, err = findRepoRoot()
		if err != nil {
			return nil, err
		}
	}

	// Read prompts and answers
	promptsPath := ResolveAbsPath(opts.PromptsFile, repoRoot)
	answersPath := ResolveAbsPath(opts.AnswersFile, repoRoot)

	prompts, err := ReadJSONL[bbhPrompt](promptsPath)
	if err != nil {
		return nil, fmt.Errorf("reading prompts: %w", err)
	}

	answers, err := ReadJSONL[bbhAnswer](answersPath)
	if err != nil {
		return nil, fmt.Errorf("reading answers: %w", err)
	}

	answerMap := make(map[string]string, len(answers))
	for _, a := range answers {
		answerMap[a.ID] = a.Answer
	}

	// Create run directory
	runRootAbs := opts.RunRootAbs
	if runRootAbs == "" {
		runRootAbs = filepath.Join(repoRoot, ".neopsyke", "runs", "freud")
	} else if !filepath.IsAbs(runRootAbs) {
		runRootAbs = filepath.Join(repoRoot, runRootAbs)
	}

	var runDir string
	if opts.RunDirOverride != "" {
		runDir = opts.RunDirOverride
		os.MkdirAll(filepath.Join(runDir, "artifacts"), 0o755)
	} else {
		runDir, err = CreateRunDir(runRootAbs, "bbh-"+opts.Lane, []string{"artifacts"})
		if err != nil {
			return nil, fmt.Errorf("creating BBH run dir: %w", err)
		}
	}

	artifactsDir := filepath.Join(runDir, "artifacts")
	casesRoot := filepath.Join(runDir, "bbh-cases", opts.Lane)
	os.MkdirAll(casesRoot, 0o755)
	replayMode := "off"
	if opts.Record {
		replayMode = "record"
	}
	if opts.SessionReplayDir != "" {
		replayMode = "replay"
	}

	// Initialize results TSV
	resultsTSV := filepath.Join(artifactsDir, fmt.Sprintf("bbh-smoke-%s-results.tsv", opts.Lane))
	tsvFile, err := os.Create(resultsTSV)
	if err != nil {
		return nil, err
	}
	fmt.Fprintln(tsvFile, "id\tcategory\tstatus\tfailure_class\tdetail\texpected\tactual\ttimeout\tschema_downgrade\trun_dir")

	if opts.DryRun {
		tsvFile.Close()
		opts.Progress.Emit(ProgressUpdate{
			Phase:   "suite",
			Status:  "dry_run",
			Current: len(prompts),
			Total:   len(prompts),
			Message: fmt.Sprintf("lane=%s", opts.Lane),
		})
		fmt.Printf("[dry-run] BBH smoke %s: %d cases\n", opts.Lane, len(prompts))
		return &BBHResult{RunDir: runDir, ExitCode: 0}, nil
	}

	opts.Progress.Emit(ProgressUpdate{
		Phase:   "suite",
		Status:  "start",
		Total:   len(prompts),
		Message: fmt.Sprintf("lane=%s", opts.Lane),
	})

	// Run each case
	var results []bbhCaseResult
	passCount := 0
	timeoutCount := 0
	schemaDowngradeCount := 0
	bootstrapFailures := 0
	providerFailures := 0
	evalErrors := 0

	for i, p := range prompts {
		expected := answerMap[p.ID]
		caseDir := filepath.Join(casesRoot, p.ID)
		os.MkdirAll(filepath.Join(caseDir, "logs"), 0o755)
		os.MkdirAll(filepath.Join(caseDir, "artifacts"), 0o755)
		os.MkdirAll(filepath.Join(caseDir, "state"), 0o755)

		// Write input
		inputPath := filepath.Join(caseDir, "input.txt")
		os.WriteFile(inputPath, []byte(p.Prompt+"\n"), 0o644)

		// Write expected
		expectedPath := filepath.Join(caseDir, "expected.txt")
		os.WriteFile(expectedPath, []byte(expected+"\n"), 0o644)

		caseReplayDir := ""
		if opts.SessionReplayDir != "" {
			caseReplayDir = resolveBBHReplayCaseDir(opts.SessionReplayDir, opts.Lane, p.ID)
			if caseReplayDir == "" {
				return nil, fmt.Errorf("bbh replay source is missing case %q for lane %q", p.ID, opts.Lane)
			}
		}

		opts.Progress.Emit(ProgressUpdate{
			Phase:   "case_start",
			Current: i + 1,
			Total:   len(prompts),
			Message: p.ID,
		})
		if opts.Progress == nil {
			fmt.Printf("[freud] bbh %s case %d/%d: %s\n", opts.Lane, i+1, len(prompts), p.ID)
		}

		// Call LiveEval directly (in-process)
		assignmentsDisabled := false
		evalResult, evalErr := LiveEval(LiveEvalOpts{
			InputFile:        inputPath,
			ExpectedFile:     expectedPath,
			Timeout:          opts.Timeout,
			SessionReplayDir: caseReplayDir,
			Record:           opts.Record,
			AssignmentsEnabled: &assignmentsDisabled,
			PreserveMemory:   opts.PreserveMemory,
			RunDirOverride:   caseDir,
			NeopsykeCmd:      opts.NeopsykeCmd,
			RunRootAbs:       runRootAbs,
			GradleUserHome:   opts.GradleUserHome,
			LLMConfigFile:    opts.LLMConfigFile,
			RetentionDays:    0, // no cleanup per-case
			RepoRoot:         repoRoot,
			Verbose:          opts.Verbose,
			ConsoleWriter:    bbhCaseConsoleWriter(opts.Progress),
		})

		exitCode := 1
		failureClass := ""
		if evalErr != nil {
			failureClass = "live_eval_process_failure"
		} else {
			exitCode = evalResult.ExitCode
			failureClass = evalResult.Verdict.FailureClass
		}

		// Read actual answer
		actual := ReadAnswerFile(filepath.Join(caseDir, "artifacts", "answer.txt"))
		normalizedExpected := NormalizeAnswer(expected, true)
		normalizedActual := NormalizeAnswer(actual, true)

		// Schema downgrade detection
		sdCount := SchemaDowngradeCount(filepath.Join(caseDir, "logs"))

		// Classify case status
		var status string
		switch {
		case exitCode == 2:
			status = "timeout"
			timeoutCount++
		case sdCount > 0:
			status = "schema_downgrade"
			schemaDowngradeCount += sdCount
		case exitCode == 0 && normalizedExpected == normalizedActual:
			status = "pass"
			passCount++
		case failureClass == "local_runtime_bootstrap_failure" || (exitCode != 0 && evalErr != nil):
			status = "runtime_bootstrap_failure"
			bootstrapFailures++
		case failureClass == "provider_model_failure":
			status = "provider_model_failure"
			providerFailures++
		case exitCode == 0 && normalizedExpected != normalizedActual:
			status = "scoring_failure"
		default:
			status = "live_eval_error"
			evalErrors++
		}

		cr := bbhCaseResult{
			ID:              p.ID,
			Category:        p.Category,
			Status:          status,
			FailureClass:    failureClass,
			Detail:          BuildVerdictDetail(status, normalizedExpected, normalizedActual),
			Expected:        normalizedExpected,
			Actual:          normalizedActual,
			Timeout:         exitCode == 2,
			SchemaDowngrade: sdCount,
			RunDir:          caseDir,
		}
		results = append(results, cr)

		// Write TSV row
		fmt.Fprintf(tsvFile, "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%v\t%d\t%s\n",
			cr.ID, cr.Category, cr.Status, cr.FailureClass, cr.Detail,
			cr.Expected, cr.Actual, cr.Timeout, cr.SchemaDowngrade, cr.RunDir)

		// Write progress
		writeProgress(artifactsDir, opts.Lane, i+1, len(prompts), passCount, results)
		opts.Progress.Emit(ProgressUpdate{
			Phase:   "case_result",
			Current: i + 1,
			Total:   len(prompts),
			Status:  cr.Status,
			Message: fmt.Sprintf("%s pass=%d timeout=%d schema=%d", cr.ID, passCount, timeoutCount, schemaDowngradeCount),
		})
	}
	tsvFile.Close()

	// Compute pass rate
	total := len(prompts)
	exactMatchRate := 0.0
	if total > 0 {
		exactMatchRate = float64(passCount) * 100.0 / float64(total)
	}

	// Regression check
	regressionFail := false
	if opts.BaselineFile != "" {
		baselineRate := readBaselinePassRate(ResolveAbsPath(opts.BaselineFile, repoRoot))
		if baselineRate > 0 && (baselineRate-exactMatchRate) > float64(opts.MaxRegressionPercent) {
			regressionFail = true
		}
	}

	// Write summary JSON
	summary := map[string]interface{}{
		"lane":                            opts.Lane,
		"total_cases":                     total,
		"passed_cases":                    passCount,
		"failed_cases":                    total - passCount,
		"timeout_count":                   timeoutCount,
		"schema_downgrade_count":          schemaDowngradeCount,
		"runtime_bootstrap_failure_count": bootstrapFailures,
		"provider_model_failure_count":    providerFailures,
		"live_eval_error_count":           evalErrors,
		"exact_match_rate_percent":        round2f(exactMatchRate),
		"min_pass_rate_percent":           opts.MinPassRatePercent,
		"max_timeouts":                    opts.MaxTimeouts,
		"baseline_file":                   opts.BaselineFile,
		"max_regression_percent":          opts.MaxRegressionPercent,
		"regression_fail":                 regressionFail,
		"replay_mode":                     replayMode,
		"session_replay_source":           opts.SessionReplayDir,
		"results_tsv":                     resultsTSV,
		"cases_root":                      casesRoot,
	}

	summaryPath := filepath.Join(artifactsDir, fmt.Sprintf("bbh-smoke-%s-summary.json", opts.Lane))
	summaryData, _ := json.MarshalIndent(summary, "", "  ")
	os.WriteFile(summaryPath, append(summaryData, '\n'), 0o644)

	// Write summary MD
	writeSummaryMD(artifactsDir, opts.Lane, summary, results)

	replayStatsPath := ""
	if replayMode != "off" {
		replayStatsPath = writeReplayStats(artifactsDir, opts.Lane, replayMode, opts.SessionReplayDir, results)
		if replayStatsPath != "" {
			summary["replay_stats_json"] = replayStatsPath
			summaryData, _ = json.MarshalIndent(summary, "", "  ")
			os.WriteFile(summaryPath, append(summaryData, '\n'), 0o644)
		}
	}

	// Gate checks
	exitCode := 0
	var gateFailures []string

	if bootstrapFailures > 0 {
		gateFailures = append(gateFailures, fmt.Sprintf("runtime bootstrap failures: %d", bootstrapFailures))
	}
	if providerFailures > 0 {
		gateFailures = append(gateFailures, fmt.Sprintf("provider model failures: %d", providerFailures))
	}
	if evalErrors > 0 {
		gateFailures = append(gateFailures, fmt.Sprintf("live eval errors: %d", evalErrors))
	}
	if exactMatchRate < float64(opts.MinPassRatePercent) {
		gateFailures = append(gateFailures, fmt.Sprintf("pass rate %.1f%% < min %d%%", exactMatchRate, opts.MinPassRatePercent))
	}
	if timeoutCount > opts.MaxTimeouts {
		gateFailures = append(gateFailures, fmt.Sprintf("timeouts %d > max %d", timeoutCount, opts.MaxTimeouts))
	}
	if schemaDowngradeCount > 0 {
		gateFailures = append(gateFailures, fmt.Sprintf("schema downgrades: %d", schemaDowngradeCount))
	}
	if regressionFail {
		gateFailures = append(gateFailures, "regression detected vs baseline")
	}

	if len(gateFailures) > 0 {
		exitCode = 2
		fmt.Printf("\n[freud] BBH %s GATE FAILED:\n", opts.Lane)
		for _, f := range gateFailures {
			fmt.Printf("  - %s\n", f)
		}
	} else {
		fmt.Printf("\n[freud] BBH %s PASSED: %d/%d (%.1f%%)\n", opts.Lane, passCount, total, exactMatchRate)
	}

	if opts.RunDirOverride == "" {
		AppendRunIndex(runRootAbs, RunIndexEntry{
			Timestamp: time.Now().UTC().Format("20060102T150405Z"),
			FeatureID: "bbh-" + opts.Lane,
			RunDir:    runDir,
			Status:    map[bool]string{true: "fail", false: "pass"}[exitCode != 0],
		})
		WriteLatestPointers(runRootAbs, repoRoot, runDir)
	}

	// Cleanup old runs (all types)
	if opts.RetentionDays > 0 {
		cleaned, _ := CleanupAllOldRuns(runRootAbs, opts.RetentionDays)
		if cleaned > 0 && opts.Verbose > 0 {
			fmt.Printf("[freud] cleaned %d old run(s) (retention=%d days)\n", cleaned, opts.RetentionDays)
		}
	}

	finalStatus := "pass"
	if exitCode != 0 {
		finalStatus = "fail"
	}
	opts.Progress.Emit(ProgressUpdate{
		Phase:   "suite",
		Current: total,
		Total:   total,
		Status:  finalStatus,
		Message: fmt.Sprintf("lane=%s pass=%d/%d pass_rate=%.1f%% timeouts=%d schema=%d", opts.Lane, passCount, total, exactMatchRate, timeoutCount, schemaDowngradeCount),
	})

	return &BBHResult{
		RunDir:          runDir,
		SummaryJSON:     summaryPath,
		ReplayStatsJSON: replayStatsPath,
		ExitCode:        exitCode,
	}, nil
}

func bbhCaseConsoleWriter(progress ProgressReporter) io.Writer {
	if progress != nil {
		return io.Discard
	}
	return nil
}

func writeProgress(artifactsDir, lane string, done, total, passed int, results []bbhCaseResult) {
	progress := map[string]interface{}{
		"done":   done,
		"total":  total,
		"passed": passed,
	}
	data, _ := json.MarshalIndent(progress, "", "  ")
	os.WriteFile(filepath.Join(artifactsDir, fmt.Sprintf("bbh-smoke-%s-progress.json", lane)),
		append(data, '\n'), 0o644)

	// MD progress
	var md []string
	md = append(md, fmt.Sprintf("# BBH Smoke %s Progress", lane))
	md = append(md, fmt.Sprintf("\n%d/%d done, %d passed\n", done, total, passed))
	for _, r := range results {
		md = append(md, fmt.Sprintf("- %s: %s", r.ID, r.Status))
	}
	os.WriteFile(filepath.Join(artifactsDir, fmt.Sprintf("bbh-smoke-%s-progress.md", lane)),
		[]byte(strings.Join(md, "\n")+"\n"), 0o644)
}

func writeSummaryMD(artifactsDir, lane string, summary map[string]interface{}, results []bbhCaseResult) {
	var md []string
	md = append(md, fmt.Sprintf("# BBH Smoke %s Summary", lane))
	md = append(md, "")
	md = append(md, fmt.Sprintf("- Total: %v", summary["total_cases"]))
	md = append(md, fmt.Sprintf("- Passed: %v", summary["passed_cases"]))
	md = append(md, fmt.Sprintf("- Failed: %v", summary["failed_cases"]))
	md = append(md, fmt.Sprintf("- Timeouts: %v", summary["timeout_count"]))
	md = append(md, fmt.Sprintf("- Schema downgrades: %v", summary["schema_downgrade_count"]))
	md = append(md, fmt.Sprintf("- Pass rate: %v%%", summary["exact_match_rate_percent"]))
	md = append(md, "")
	md = append(md, "## Results")
	for _, r := range results {
		md = append(md, fmt.Sprintf("- %s (%s): %s", r.ID, r.Category, r.Status))
		if r.Status != "pass" && r.Detail != "" {
			md = append(md, fmt.Sprintf("  %s", r.Detail))
		}
	}
	os.WriteFile(filepath.Join(artifactsDir, fmt.Sprintf("bbh-smoke-%s-summary.md", lane)),
		[]byte(strings.Join(md, "\n")+"\n"), 0o644)
}

func writeReplayStats(artifactsDir, lane, mode, sessionReplaySource string, results []bbhCaseResult) string {
	stats := bbhReplayStats{
		Mode:                mode,
		SessionReplaySource: sessionReplaySource,
		LLMCache: map[string]interface{}{
			"cases_with_stats": 0,
			"total_calls":      0,
			"cached_calls":     0,
			"real_calls":       0,
			"divergence_count": 0,
		},
		SessionReplay: map[string]interface{}{
			"cases_with_stats":   0,
			"total_replay_hits":  0,
			"total_divergences":  0,
			"diverged_channels":  []string{},
			"fully_replayed_all": 0,
		},
	}

	divergedChannels := map[string]struct{}{}
	recordedCases := 0
	replayedCases := 0
	var missingReplayCases []string

	for _, result := range results {
		caseDir := result.RunDir
		if analysisFileExists(filepath.Join(caseDir, "session", "session-manifest.json")) ||
			analysisFileExists(filepath.Join(caseDir, "session", "llm-cache.jsonl")) ||
			analysisFileExists(filepath.Join(caseDir, "artifacts", "llm-cache.jsonl")) {
			recordedCases++
		}

		cacheStatsPath := filepath.Join(caseDir, "artifacts", "cache-stats.json")
		if data := readJSONMap(cacheStatsPath); data != nil {
			stats.LLMCache["cases_with_stats"] = intFromAny(stats.LLMCache["cases_with_stats"]) + 1
			stats.LLMCache["total_calls"] = intFromAny(stats.LLMCache["total_calls"]) + intFromAny(data["total_calls"])
			stats.LLMCache["cached_calls"] = intFromAny(stats.LLMCache["cached_calls"]) + intFromAny(data["cached_calls"])
			stats.LLMCache["real_calls"] = intFromAny(stats.LLMCache["real_calls"]) + intFromAny(data["real_calls"])
			stats.LLMCache["divergence_count"] = intFromAny(stats.LLMCache["divergence_count"]) + intFromAny(data["divergence_count"])
		}

		sessionStatsPath := filepath.Join(caseDir, "artifacts", "session-replay-stats.json")
		if data := readJSONMap(sessionStatsPath); data != nil {
			replayedCases++
			stats.SessionReplay["cases_with_stats"] = intFromAny(stats.SessionReplay["cases_with_stats"]) + 1
			stats.SessionReplay["total_replay_hits"] = intFromAny(stats.SessionReplay["total_replay_hits"]) + intFromAny(data["total_replay_hits"])
			stats.SessionReplay["total_divergences"] = intFromAny(stats.SessionReplay["total_divergences"]) + intFromAny(data["total_divergences"])
			if intFromAny(data["total_divergences"]) == 0 {
				stats.SessionReplay["fully_replayed_all"] = intFromAny(stats.SessionReplay["fully_replayed_all"]) + 1
			}
			if channels, ok := data["diverged_channels"].([]interface{}); ok {
				for _, channel := range channels {
					if s, ok := channel.(string); ok && s != "" {
						divergedChannels[s] = struct{}{}
					}
				}
			}
		} else if mode == "replay" {
			missingReplayCases = append(missingReplayCases, filepath.Base(caseDir))
		}
	}

	stats.RecordedCases = recordedCases
	stats.ReplayedCases = replayedCases
	if len(missingReplayCases) > 0 {
		slices.Sort(missingReplayCases)
		stats.MissingReplayCases = missingReplayCases
	}
	if len(divergedChannels) > 0 {
		names := make([]string, 0, len(divergedChannels))
		for channel := range divergedChannels {
			names = append(names, channel)
		}
		slices.Sort(names)
		stats.SessionReplay["diverged_channels"] = names
	}
	switch mode {
	case "record":
		stats.Hints = append(stats.Hints, fmt.Sprintf("Recorded replay artifacts for %d BBH case(s).", recordedCases))
	case "replay":
		stats.Hints = append(stats.Hints, fmt.Sprintf("Replayed %d BBH case(s) from %s.", replayedCases, sessionReplaySource))
		if len(missingReplayCases) > 0 {
			stats.Hints = append(stats.Hints, fmt.Sprintf("Missing replay stats for case(s): %s.", strings.Join(missingReplayCases, ", ")))
		}
	}

	statsPath := filepath.Join(artifactsDir, fmt.Sprintf("bbh-smoke-%s-replay-stats.json", lane))
	statsData, _ := json.MarshalIndent(stats, "", "  ")
	os.WriteFile(statsPath, append(statsData, '\n'), 0o644)
	return statsPath
}

func resolveBBHReplayCaseDir(baseDir, lane, caseID string) string {
	for _, candidate := range []string{
		filepath.Join(baseDir, "bbh-cases", lane, caseID),
		filepath.Join(baseDir, caseID),
	} {
		if analysisFileExists(candidate) || analysisDirExists(candidate) {
			return candidate
		}
	}
	return ""
}

func readJSONMap(path string) map[string]interface{} {
	if !analysisFileExists(path) {
		return nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var decoded map[string]interface{}
	if err := json.Unmarshal(data, &decoded); err != nil {
		return nil
	}
	return decoded
}

func intFromAny(value interface{}) int {
	switch v := value.(type) {
	case int:
		return v
	case int64:
		return int(v)
	case float64:
		return int(v)
	default:
		return 0
	}
}

func analysisFileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func analysisDirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func readBaselinePassRate(path string) float64 {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	var baseline map[string]interface{}
	if err := json.Unmarshal(data, &baseline); err != nil {
		return 0
	}
	if rate, ok := baseline["exact_match_rate_percent"].(float64); ok {
		return rate
	}
	return 0
}

func round2f(f float64) float64 {
	return float64(int(f*100+0.5)) / 100
}

func findRepoRoot() (string, error) {
	root, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(root, ".git")); err == nil {
			return root, nil
		}
		parent := filepath.Dir(root)
		if parent == root {
			return "", fmt.Errorf("not inside a git repository")
		}
		root = parent
	}
}
