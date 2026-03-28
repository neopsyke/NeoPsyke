package orchestrator

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
	"github.com/atomitl/neopsyke/freud/internal/analysis/telemetry"
)

// LiveEvalOpts configures a single live evaluation run.
type LiveEvalOpts struct {
	InputFile        string
	ExpectedFile     string
	Timeout          int
	CacheReplayFile  string
	SessionReplayDir string
	RecordSession    bool
	GoalsEnabled     *bool
	PreserveMemory   bool
	RunDirOverride   string // if set, use this as the run dir (for BBH cases)
	NeopsykeCmd      string
	RunRootAbs       string
	GradleUserHome   string
	LLMConfigFile    string
	RetentionDays    int
	RepoRoot         string
	Verbose          int
	DryRun           bool
	ConsoleWriter    io.Writer
}

// LiveEvalResult holds the output of a live eval run.
type LiveEvalResult struct {
	RunDir   string
	Verdict  Verdict
	ExitCode int
}

// LiveEval runs a single live evaluation. Replaces live-eval.sh.
func LiveEval(opts LiveEvalOpts) (*LiveEvalResult, error) {
	consoleWriter := opts.ConsoleWriter
	if consoleWriter == nil {
		consoleWriter = os.Stdout
	}

	repoRoot := opts.RepoRoot
	if repoRoot == "" {
		var err error
		repoRoot, err = analysis.RepoRoot()
		if err != nil {
			return nil, fmt.Errorf("finding repo root: %w", err)
		}
	}

	neopsykeCmd := opts.NeopsykeCmd
	if neopsykeCmd == "" {
		neopsykeCmd = filepath.Join(repoRoot, "run-neopsyke.sh")
	} else if !filepath.IsAbs(neopsykeCmd) {
		neopsykeCmd = filepath.Join(repoRoot, neopsykeCmd)
	}

	runRootAbs := opts.RunRootAbs
	if runRootAbs == "" {
		runRootAbs = filepath.Join(repoRoot, ".neopsyke", "runs", "freud")
	} else if !filepath.IsAbs(runRootAbs) {
		runRootAbs = filepath.Join(repoRoot, runRootAbs)
	}
	gradleHome := ""
	if opts.GradleUserHome != "" {
		gradleHome = ResolveAbsPath(opts.GradleUserHome, repoRoot)
		PrimeGradleHome(repoRoot, gradleHome, opts.Verbose)
	}

	// Create or use run directory
	var runDir string
	if opts.RunDirOverride != "" {
		runDir = opts.RunDirOverride
		for _, sub := range []string{"logs", "artifacts", "state"} {
			os.MkdirAll(filepath.Join(runDir, sub), 0o755)
		}
	} else {
		var err error
		runDir, err = CreateRunDir(runRootAbs, "live-eval", []string{"logs", "artifacts", "state"})
		if err != nil {
			return nil, fmt.Errorf("creating run dir: %w", err)
		}
	}

	logsDir := filepath.Join(runDir, "logs")
	artifactsDir := filepath.Join(runDir, "artifacts")
	stateDir := filepath.Join(runDir, "state")

	if opts.DryRun {
		fmt.Fprintf(consoleWriter, "[dry-run] live-eval in %s\n", runDir)
		return &LiveEvalResult{RunDir: runDir, ExitCode: 0}, nil
	}

	// Resolve session replay
	sessionReplayDir := opts.SessionReplayDir
	cacheReplayFile := opts.CacheReplayFile
	recordSession := opts.RecordSession

	if sessionReplayDir != "" {
		// Check for session subdir
		sessionSub := filepath.Join(sessionReplayDir, "session")
		if analysis.DirExists(sessionSub) {
			sessionReplayDir = sessionSub
		}
		// Auto-detect cache replay from session dir
		if cacheReplayFile == "" {
			cacheInSession := filepath.Join(sessionReplayDir, "llm-cache.jsonl")
			if analysis.FileExists(cacheInSession) {
				cacheReplayFile = cacheInSession
			}
		}
	}

	// Determine cache mode and file
	cacheMode := "record"
	cacheFile := filepath.Join(artifactsDir, "llm-cache.jsonl")
	if cacheReplayFile != "" {
		cacheMode = "replay"
		cacheFile = cacheReplayFile
	}

	// Session recording setup
	sessionDir := filepath.Join(runDir, "session")
	sessionMode := "off"
	if recordSession {
		sessionMode = "record"
		os.MkdirAll(sessionDir, 0o755)
	} else if sessionReplayDir != "" {
		sessionMode = "replay"
		sessionDir = sessionReplayDir
	}

	// Copy input to artifacts
	if opts.InputFile != "" {
		inputData, err := os.ReadFile(opts.InputFile)
		if err == nil {
			os.WriteFile(filepath.Join(artifactsDir, "input.txt"), inputData, 0o644)
		}
	}

	// Build isolation env vars
	runShortID := filepath.Base(runDir)
	pgNamespace := "freud-eval-" + runShortID

	env := os.Environ()
	envSet := func(key, value string) {
		prefix := key + "="
		for i, e := range env {
			if strings.HasPrefix(e, prefix) {
				env[i] = prefix + value
				return
			}
		}
		env = append(env, prefix+value)
	}

	envSet("NEOPSYKE_LLM_CACHE_MODE", cacheMode)
	envSet("NEOPSYKE_LLM_CACHE_FILE", cacheFile)
	envSet("NEOPSYKE_SESSION_RECORDING_MODE", sessionMode)
	envSet("NEOPSYKE_SESSION_RECORDING_DIR", sessionDir)
	envSet("NEOPSYKE_LOG_FILE", filepath.Join(logsDir, "neopsyke.log"))
	envSet("NEOPSYKE_EVENT_LOG_FILE", filepath.Join(logsDir, "events.jsonl"))
	envSet("EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED", "true")
	envSet("MEMORY_DEFAULT_NAMESPACE", pgNamespace)
	envSet("NEOPSYKE_LOGBOOK_DB_PATH", filepath.Join(stateDir, "logbook.db"))
	envSet("NEOPSYKE_METRICS_DB", filepath.Join(stateDir, "metrics.db"))
	envSet("NEOPSYKE_ACTION_CONTROL_DB_PATH", filepath.Join(stateDir, "action-control.db"))
	envSet("NEOPSYKE_GOALS_WORKSPACE_ROOT", filepath.Join(artifactsDir, "goals"))
	envSet("FREUD_RUN_DIR", runDir)
	envSet("FREUD_ARTIFACT_DIR", artifactsDir)
	envSet("NEOPSYKE_ID_ENABLED", "false")

	if opts.LLMConfigFile != "" {
		envSet("NEOPSYKE_LLM_CONFIG_FILE", ResolveAbsPath(opts.LLMConfigFile, repoRoot))
	}
	if opts.GoalsEnabled != nil {
		envSet("NEOPSYKE_GOALS_ENABLED", strconv.FormatBool(*opts.GoalsEnabled))
	}
	if opts.GradleUserHome != "" {
		envSet("GRADLE_USER_HOME", gradleHome)
	}

	// Write pgvector namespace for cleanup
	os.WriteFile(filepath.Join(stateDir, "pgvector-namespace.txt"), []byte(pgNamespace+"\n"), 0o644)

	// Build command
	cmdArgs := []string{"--freud-live", "--freud-live-timeout", strconv.Itoa(opts.Timeout), "--no-id"}
	if opts.GoalsEnabled != nil && *opts.GoalsEnabled {
		cmdArgs = append(cmdArgs, "--goals")
	}

	// Set up timeout context (grace period beyond neopsyke's own timeout)
	hardTimeout := time.Duration(opts.Timeout+30) * time.Second
	ctx, cancel := context.WithTimeout(context.Background(), hardTimeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, neopsykeCmd, cmdArgs...)
	cmd.Env = env
	cmd.Dir = repoRoot

	// Stdin
	if opts.InputFile != "" && sessionReplayDir == "" {
		inputFile, err := os.Open(opts.InputFile)
		if err != nil {
			return nil, fmt.Errorf("opening input file: %w", err)
		}
		defer inputFile.Close()
		cmd.Stdin = inputFile
	}

	// Stdout: tee to both os.Stdout and log file
	stdoutLogPath := filepath.Join(logsDir, "stdout.log")
	stdoutFile, err := os.Create(stdoutLogPath)
	if err != nil {
		return nil, fmt.Errorf("creating stdout log: %w", err)
	}
	defer stdoutFile.Close()
	cmd.Stdout = io.MultiWriter(consoleWriter, stdoutFile)

	// Stderr: file only
	stderrLogPath := filepath.Join(logsDir, "stderr.log")
	stderrFile, err := os.Create(stderrLogPath)
	if err != nil {
		return nil, fmt.Errorf("creating stderr log: %w", err)
	}
	defer stderrFile.Close()
	cmd.Stderr = stderrFile

	if opts.Verbose > 0 {
		fmt.Fprintf(consoleWriter, "[freud] exec: %s %s\n", neopsykeCmd, strings.Join(cmdArgs, " "))
		fmt.Fprintf(consoleWriter, "[freud] run_dir: %s\n", runDir)
	}
	fmt.Fprintf(consoleWriter, "Run directory: %s\n", runDir)

	// Execute
	startTime := time.Now()
	exitCode := 0

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("starting neopsyke: %w", err)
	}

	if err := cmd.Wait(); err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			// Hard timeout — try graceful kill first
			if cmd.Process != nil {
				cmd.Process.Signal(syscall.SIGTERM)
				time.Sleep(2 * time.Second)
			}
			exitCode = 2
		} else if exitErr, ok := err.(*exec.ExitError); ok {
			exitCode = exitErr.ExitCode()
		} else {
			exitCode = 1
		}
	}

	duration := int(time.Since(startTime).Seconds())

	// Extract answer
	answer := ExtractAnswerLine(stdoutLogPath)
	answerPath := filepath.Join(artifactsDir, "answer.txt")
	os.WriteFile(answerPath, []byte(answer+"\n"), 0o644)

	// Score verdict
	appLogPath := filepath.Join(logsDir, "neopsyke.log")
	verdict := ScoreVerdict(exitCode, opts.ExpectedFile, answerPath, stderrLogPath, appLogPath)
	verdict.DurationSec = duration
	verdict.CacheMode = cacheMode
	verdict.CacheFile = cacheFile
	verdict.TimeoutSec = opts.Timeout
	verdict.PreserveMemory = opts.PreserveMemory
	verdict.RunDir = runDir
	verdict.ArtifactsDir = artifactsDir
	verdict.LogsDir = logsDir
	verdict.AnswerFile = answerPath
	verdict.InputFile = opts.InputFile

	WriteVerdictJSON(verdict, artifactsDir)

	// Post-run telemetry (all native Go, non-blocking)
	if analysis.DirExists(logsDir) {
		analysis.Triage(runDir, 20)
	}

	eventsPath := filepath.Join(logsDir, "events.jsonl")
	if analysis.FileExists(eventsPath) {
		cacheStatsPath := filepath.Join(artifactsDir, "cache-stats.json")
		telemetry.LLMCacheTelemetryToFile(eventsPath, cacheStatsPath)
	}

	if sessionMode == "replay" && analysis.FileExists(eventsPath) {
		replayStatsPath := filepath.Join(artifactsDir, "session-replay-stats.json")
		telemetry.SessionReplayTelemetryToFile(eventsPath, replayStatsPath)
	}

	if analysis.FileExists(filepath.Join(artifactsDir, "verdict.json")) {
		analysis.Summarize(runDir)
	}

	// Append to run index (unless this is a BBH case subdir)
	if opts.RunDirOverride == "" {
		featureID := "live-eval"
		if opts.SessionReplayDir != "" {
			featureID = "live-eval-replay"
		}
		AppendRunIndex(runRootAbs, RunIndexEntry{
			Timestamp: time.Now().UTC().Format("20060102T150405Z"),
			FeatureID: featureID,
			RunDir:    runDir,
			Status:    verdict.Verdict,
		})
		WriteLatestPointers(runRootAbs, repoRoot, runDir)
	}

	// Print cache/session stats (informational, no assertions)
	cacheStatsPath := filepath.Join(artifactsDir, "cache-stats.json")
	if analysis.FileExists(cacheStatsPath) {
		if data, err := os.ReadFile(cacheStatsPath); err == nil {
			var cs map[string]interface{}
			if json.Unmarshal(data, &cs) == nil {
				totalCalls, _ := cs["total_calls"].(float64)
				cachedCalls, _ := cs["cached_calls"].(float64)
				realCalls, _ := cs["real_calls"].(float64)
				hitRate, _ := cs["hit_rate_percent"].(float64)
				divCount, _ := cs["divergence_count"].(float64)
				fmt.Fprintf(consoleWriter, "\n[freud] LLM cache: total=%.0f cached=%.0f real=%.0f hit_rate=%.1f%% divergences=%.0f\n",
					totalCalls, cachedCalls, realCalls, hitRate, divCount)
			}
		}
	}

	sessionStatsPath := filepath.Join(artifactsDir, "session-replay-stats.json")
	if analysis.FileExists(sessionStatsPath) {
		if data, err := os.ReadFile(sessionStatsPath); err == nil {
			var ss map[string]interface{}
			if json.Unmarshal(data, &ss) == nil {
				totalHits, _ := ss["total_replay_hits"].(float64)
				totalDiv, _ := ss["total_divergences"].(float64)
				fmt.Fprintf(consoleWriter, "[freud] session replay: hits=%.0f divergences=%.0f\n", totalHits, totalDiv)
				if channels, ok := ss["channels"].(map[string]interface{}); ok {
					for ch, v := range channels {
						if chStats, ok := v.(map[string]interface{}); ok {
							hits, _ := chStats["hits"].(float64)
							divs, _ := chStats["divergences"].(float64)
							fmt.Fprintf(consoleWriter, "[freud]   channel %-20s hits=%.0f divergences=%.0f\n", ch, hits, divs)
						}
					}
				}
			}
		}
	}

	// Print summary
	fmt.Fprintf(consoleWriter, "\n[freud] verdict=%s exit=%d duration=%ds run_dir=%s\n",
		verdict.Verdict, verdict.ExitCode, duration, runDir)
	fmt.Fprintf(consoleWriter, "Verdict: %s\n", verdict.Verdict)

	// Cleanup old runs
	if opts.RunDirOverride == "" {
		cleaned, _ := CleanupAllOldRuns(runRootAbs, opts.RetentionDays)
		if cleaned > 0 && opts.Verbose > 0 {
			fmt.Fprintf(consoleWriter, "[freud] cleaned %d old live-eval runs\n", cleaned)
		}
	}

	result := &LiveEvalResult{
		RunDir:   runDir,
		Verdict:  verdict,
		ExitCode: verdict.ExitCode,
	}

	return result, nil
}
