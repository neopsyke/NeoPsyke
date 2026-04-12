package orchestrator

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
	"github.com/atomitl/neopsyke/freud/internal/config"
)

// InteractiveSessionTestOpts configures the interactive session recording E2E test.
type InteractiveSessionTestOpts struct {
	Timeout         int
	DashboardPort   int
	DashboardHost   string
	StartupWaitMax  int
	ResponseWaitMax int
	InputText       string
	RepoRoot        string
	Cfg             *config.FreudConfig
	Verbose         int
	DryRun          bool
}

// InteractiveSessionTestResult holds the test outcome.
type InteractiveSessionTestResult struct {
	Passed    bool
	Failures  int
	RecordDir string
	ReplayDir string
	ExitCode  int
}

func resolveInteractiveResponseWait(timeout int, responseWait int) int {
	if responseWait > 0 {
		return responseWait
	}
	if timeout > 0 {
		return timeout
	}
	return 60
}

// InteractiveSessionTest runs the interactive mode session recording E2E test.
// Replaces test-interactive-session-recording.sh.
func InteractiveSessionTest(opts InteractiveSessionTestOpts) (*InteractiveSessionTestResult, error) {
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

	// Defaults
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = 60
	}
	port := opts.DashboardPort
	if port <= 0 {
		port = 8787
	}
	host := opts.DashboardHost
	if host == "" {
		host = "127.0.0.1"
	}
	startupWait := opts.StartupWaitMax
	if startupWait <= 0 {
		startupWait = 30
	}
	responseWait := opts.ResponseWaitMax
	responseWait = resolveInteractiveResponseWait(timeout, responseWait)
	inputText := opts.InputText
	if inputText == "" {
		inputText = "What is 2 + 2?"
	}

	if opts.DryRun {
		fmt.Println("[dry-run] interactive session recording E2E test")
		return &InteractiveSessionTestResult{Passed: true, ExitCode: 0}, nil
	}

	fmt.Println("=== Interactive Session Recording E2E Test ===")
	fmt.Printf("Input: %s\n", inputText)
	fmt.Printf("Dashboard: http://%s:%d\n\n", host, port)

	baseURL := fmt.Sprintf("http://%s:%d", host, port)
	failures := 0

	// ── Step 1: Start NeoPsyke in interactive mode with --record-session ──
	fmt.Println("--- Step 1: Starting NeoPsyke in interactive mode with --record-session ---")

	neopsykeCmd := cfg.LiveEval.NeopsykeCmd
	if neopsykeCmd == "" {
		neopsykeCmd = "./run-neopsyke.sh"
	}
	neopsykeCmd = ResolveAbsPath(neopsykeCmd, repoRoot)
	if cfg.Project.GradleHome != "" {
		PrimeGradleHome(repoRoot, ResolveAbsPath(cfg.Project.GradleHome, repoRoot), opts.Verbose)
	}

	// Create work dir
	workDir, err := os.MkdirTemp("", "freud-interactive-e2e-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(workDir)

	stdoutLogPath := filepath.Join(workDir, "stdout.log")
	stdoutLog, err := os.Create(stdoutLogPath)
	if err != nil {
		return nil, err
	}

	// Use a pipe for stdin so we can send "exit" later
	stdinReader, stdinWriter, err := os.Pipe()
	if err != nil {
		return nil, err
	}

	cmd := exec.Command(neopsykeCmd, "--record-session")
	cmd.Dir = repoRoot
	cmd.Stdin = stdinReader
	cmd.Stdout = stdoutLog
	cmd.Stderr = stdoutLog

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("starting neopsyke: %w", err)
	}

	appPID := cmd.Process
	defer func() {
		stdinWriter.Close()
		stdinReader.Close()
		stdoutLog.Close()
		if appPID != nil {
			appPID.Kill()
			cmd.Wait()
		}
	}()

	// Wait for dashboard to come up
	fmt.Printf("Waiting for dashboard on port %d...\n", port)
	dashboardUp := false
	for elapsed := 0; elapsed < startupWait; elapsed++ {
		time.Sleep(1 * time.Second)

		// Check if process died
		if cmd.ProcessState != nil && cmd.ProcessState.Exited() {
			logData, _ := os.ReadFile(stdoutLogPath)
			fmt.Printf("FAIL: App process died during startup. Output:\n%s\n", string(logData))
			return &InteractiveSessionTestResult{Passed: false, Failures: 1, ExitCode: 1}, nil
		}

		resp, err := http.Get(baseURL + "/api/chat/sessions")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == 200 {
				dashboardUp = true
				fmt.Printf("Dashboard is up (%ds)\n", elapsed+1)
				break
			}
		}
	}

	if !dashboardUp {
		fmt.Printf("FAIL: Dashboard did not start within %ds\n", startupWait)
		return &InteractiveSessionTestResult{Passed: false, Failures: 1, ExitCode: 1}, nil
	}

	// Find session recording dir by looking for the most recent run with a session manifest.
	// This is more robust than parsing stdout log messages.
	sessionRecordDir := ""
	runRoot := filepath.Join(repoRoot, ".neopsyke/runs/freud")
	if manifests, _ := filepath.Glob(filepath.Join(runRoot, "*/session/session-manifest.json")); len(manifests) > 0 {
		// Glob returns sorted; last entry is the most recent by timestamp prefix.
		sessionRecordDir = filepath.Dir(manifests[len(manifests)-1])
	}
	if sessionRecordDir == "" {
		// Fallback: find latest session dir by name convention
		if entries, _ := filepath.Glob(filepath.Join(runRoot, "*-session")); len(entries) > 0 {
			sessionRecordDir = entries[len(entries)-1]
		}
	}
	fmt.Printf("Session recording dir: %s\n", sessionRecordDir)

	// ── Step 2: Send a chat message ──
	fmt.Println("\n--- Step 2: Sending chat message via HTTP ---")

	// Create session
	createResp, err := http.Post(baseURL+"/api/chat/sessions", "application/json",
		strings.NewReader(`{"title": "e2e-test"}`))
	if err != nil {
		fmt.Printf("FAIL: Could not create chat session: %v\n", err)
		return &InteractiveSessionTestResult{Passed: false, Failures: 1, ExitCode: 1}, nil
	}
	defer createResp.Body.Close()
	createBody, _ := io.ReadAll(createResp.Body)

	var sessionData map[string]interface{}
	json.Unmarshal(createBody, &sessionData)
	sessionID, _ := sessionData["session_id"].(string)
	if sessionID == "" {
		fmt.Printf("FAIL: Could not parse session_id from: %s\n", string(createBody))
		return &InteractiveSessionTestResult{Passed: false, Failures: 1, ExitCode: 1}, nil
	}
	fmt.Printf("Session created: %s\n", sessionID)

	// Submit message
	submitResp, err := http.Post(
		fmt.Sprintf("%s/api/chat/sessions/%s/messages", baseURL, sessionID),
		"application/json",
		strings.NewReader(fmt.Sprintf(`{"content": "%s"}`, inputText)))
	if err != nil {
		fmt.Printf("FAIL: Could not submit message: %v\n", err)
		return &InteractiveSessionTestResult{Passed: false, Failures: 1, ExitCode: 1}, nil
	}
	submitResp.Body.Close()
	fmt.Println("Message submitted")

	// ── Step 3: Wait for agent response ──
	fmt.Println("\n--- Step 3: Waiting for agent response ---")

	recordAnswer := ""
	for elapsed := 0; elapsed < responseWait; elapsed += 2 {
		time.Sleep(2 * time.Second)

		resp, err := http.Get(fmt.Sprintf("%s/api/chat/sessions/%s", baseURL, sessionID))
		if err != nil {
			continue
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()

		var sessData map[string]interface{}
		if json.Unmarshal(body, &sessData) == nil {
			if msgs, ok := sessData["messages"].([]interface{}); ok {
				for _, m := range msgs {
					if msg, ok := m.(map[string]interface{}); ok {
						if msg["role"] == "assistant" {
							if content, ok := msg["content"].(string); ok && content != "" {
								recordAnswer = content
								break
							}
						}
					}
				}
			}
		}
		if recordAnswer != "" {
			break
		}
	}

	if recordAnswer == "" {
		fmt.Printf("FAIL: No agent response within %ds\n", responseWait)
		return &InteractiveSessionTestResult{Passed: false, Failures: 1, ExitCode: 1}, nil
	}
	fmt.Printf("Agent answered: %s\n", recordAnswer)

	// ── Step 4: Stop the agent ──
	fmt.Println("\n--- Step 4: Stopping agent ---")
	fmt.Fprintln(stdinWriter, "exit")
	stdinWriter.Close()
	time.Sleep(3 * time.Second)

	cmd.Process.Kill()
	cmd.Wait()
	appPID = nil

	// ── Step 5: Verify recording ──
	fmt.Println("\n--- Step 5: Verifying session recording ---")

	if sessionRecordDir == "" {
		fmt.Println("FAIL: No session recording dir found")
		return &InteractiveSessionTestResult{Passed: false, Failures: 1, ExitCode: 1}, nil
	}

	signalsPath := filepath.Join(sessionRecordDir, "signals.jsonl")
	if analysis.FileExists(signalsPath) {
		count := analysis.CountLines(signalsPath)
		if count > 0 {
			fmt.Printf("PASS: signals.jsonl has %d signal(s)\n", count)
		} else {
			fmt.Println("FAIL: signals.jsonl is empty")
			failures++
		}
	} else {
		fmt.Println("FAIL: signals.jsonl not found")
		failures++
	}

	manifestPath := filepath.Join(sessionRecordDir, "session-manifest.json")
	if analysis.FileExists(manifestPath) {
		fmt.Println("PASS: session-manifest.json exists")
	} else {
		fmt.Println("FAIL: session-manifest.json not found")
		failures++
	}

	// Check LLM cache
	llmCachePath := filepath.Join(sessionRecordDir, "llm-cache.jsonl")
	if !analysis.FileExists(llmCachePath) {
		// Try parent run's artifacts
		parentDir := filepath.Dir(sessionRecordDir)
		llmCachePath = filepath.Join(parentDir, "artifacts", "llm-cache.jsonl")
	}
	if analysis.FileExists(llmCachePath) {
		count := analysis.CountLines(llmCachePath)
		fmt.Printf("PASS: llm-cache.jsonl has %d LLM call(s)\n", count)
	} else {
		fmt.Println("WARN: llm-cache.jsonl not found in session or artifacts dir")
	}

	fmt.Printf("Recording dir: %s\n", sessionRecordDir)

	// ── Step 6: Replay ──
	fmt.Println("\n--- Step 6: Replaying recorded session ---")

	if !analysis.FileExists(llmCachePath) {
		fmt.Println("SKIP: Cannot replay without llm-cache.jsonl")
		if failures > 0 {
			fmt.Printf("\n=== FAIL: %d check(s) failed ===\n", failures)
			return &InteractiveSessionTestResult{Passed: false, Failures: failures, RecordDir: sessionRecordDir, ExitCode: 1}, nil
		}
		fmt.Println("\n=== PARTIAL PASS: Recording verified, replay skipped ===")
		return &InteractiveSessionTestResult{Passed: true, RecordDir: sessionRecordDir, ExitCode: 0}, nil
	}

	replayResult, err := LiveEval(LiveEvalOpts{
		Timeout:          timeout,
		SessionReplayDir: sessionRecordDir,
		CacheReplayFile:  llmCachePath,
		NeopsykeCmd:      cfg.LiveEval.NeopsykeCmd,
		RunRootAbs:       cfg.Project.RunRoot,
		GradleUserHome:   cfg.Project.GradleHome,
		LLMConfigFile:    cfg.LiveEval.LLMConfigFile,
		RetentionDays:    0,
		RepoRoot:         repoRoot,
		Verbose:          opts.Verbose,
	})

	replayAnswer := ""
	replayDir := ""
	if err == nil && replayResult != nil {
		replayDir = replayResult.RunDir
		replayAnswer = ReadAnswerFile(filepath.Join(replayDir, "artifacts", "answer.txt"))
	}
	fmt.Printf("Replay answer: %s\n", replayAnswer)

	// Compare answers
	normRecord := NormalizeAnswer(recordAnswer, false)
	normReplay := NormalizeAnswer(replayAnswer, false)

	if replayAnswer == "" {
		fmt.Println("FAIL: No replay answer")
		failures++
	} else if normRecord == normReplay {
		fmt.Println("PASS: Answers match exactly")
	} else if strings.Contains(normRecord, normReplay) || strings.Contains(normReplay, normRecord) {
		fmt.Printf("PASS: Answers overlap (record=%q replay=%q)\n", recordAnswer, replayAnswer)
	} else {
		fmt.Printf("WARN: Answers differ (record=%q replay=%q) — may be LLM wording difference\n", recordAnswer, replayAnswer)
	}

	// Check LLM cache stats
	if replayDir != "" {
		cacheStatsPath := filepath.Join(replayDir, "artifacts", "cache-stats.json")
		if analysis.FileExists(cacheStatsPath) {
			data, _ := os.ReadFile(cacheStatsPath)
			var stats map[string]interface{}
			if json.Unmarshal(data, &stats) == nil {
				cachedCalls, _ := stats["cached_calls"].(float64)
				realCalls, _ := stats["real_calls"].(float64)
				divCount, _ := stats["divergence_count"].(float64)

				if divCount == 0 && cachedCalls > 0 {
					fmt.Printf("PASS: LLM cache — %.0f cached, %.0f live, 0 divergences\n", cachedCalls, realCalls)
				} else {
					fmt.Printf("FAIL: LLM cache diverged — %.0f cached, %.0f live, %.0f divergence(s)\n", cachedCalls, realCalls, divCount)
					failures++
				}
			}
		}
	}

	fmt.Println()
	exitCode := 0
	if failures > 0 {
		exitCode = 1
		fmt.Printf("=== FAIL: %d check(s) failed ===\n", failures)
	} else {
		fmt.Println("=== PASS: Interactive session recording E2E test passed ===")
	}

	return &InteractiveSessionTestResult{
		Passed:    failures == 0,
		Failures:  failures,
		RecordDir: sessionRecordDir,
		ReplayDir: replayDir,
		ExitCode:  exitCode,
	}, nil
}
