package orchestrator

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
	"github.com/atomitl/neopsyke/freud/internal/config"
)

// SessionReplayTestOpts configures the session replay E2E test.
type SessionReplayTestOpts struct {
	InputFile string
	Timeout   int
	RepoRoot  string
	Cfg       *config.FreudConfig
	Verbose   int
	DryRun    bool
}

// SessionReplayTestResult holds the session replay E2E test outcome.
type SessionReplayTestResult struct {
	Passed    bool
	RecordDir string
	ReplayDir string
	ExitCode  int
}

// SessionReplayTest runs the E2E session record/replay test.
// Replaces test-session-replay.sh.
func SessionReplayTest(opts SessionReplayTestOpts) (*SessionReplayTestResult, error) {
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

	// Default input if not provided
	inputFile := opts.InputFile
	if inputFile == "" {
		tmpFile, err := os.CreateTemp("", "freud-replay-input-*.txt")
		if err != nil {
			return nil, err
		}
		tmpFile.WriteString("What is 2 + 2?\n")
		tmpFile.Close()
		inputFile = tmpFile.Name()
		defer os.Remove(inputFile)
	}

	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = cfg.LiveEval.Timeout
	}

	if opts.DryRun {
		fmt.Println("[dry-run] session replay E2E test")
		return &SessionReplayTestResult{Passed: true, ExitCode: 0}, nil
	}

	// Step 1: Record
	fmt.Println("[freud] session-replay: recording...")
	recordResult, err := LiveEval(LiveEvalOpts{
		InputFile:      inputFile,
		Timeout:        timeout,
		RecordSession:  true,
		NeopsykeCmd:    cfg.LiveEval.NeopsykeCmd,
		RunRootAbs:     cfg.Project.RunRoot,
		GradleUserHome: cfg.Project.GradleHome,
		LLMConfigFile:  cfg.LiveEval.LLMConfigFile,
		RetentionDays:  0, // no cleanup during test
		RepoRoot:       repoRoot,
		Verbose:        opts.Verbose,
	})
	if err != nil {
		return nil, fmt.Errorf("record step failed: %w", err)
	}

	recordDir := recordResult.RunDir
	sessionDir := filepath.Join(recordDir, "session")

	// Verify session files exist
	if !analysis.FileExists(filepath.Join(sessionDir, "signals.jsonl")) {
		return &SessionReplayTestResult{
			Passed:    false,
			RecordDir: recordDir,
			ExitCode:  1,
		}, fmt.Errorf("missing session file: signals.jsonl")
	}

	// LLM cache may be in session/ or artifacts/ depending on recording mode
	cacheFile := filepath.Join(sessionDir, "llm-cache.jsonl")
	if !analysis.FileExists(cacheFile) {
		cacheFile = filepath.Join(recordDir, "artifacts", "llm-cache.jsonl")
	}
	if !analysis.FileExists(cacheFile) {
		return &SessionReplayTestResult{
			Passed:    false,
			RecordDir: recordDir,
			ExitCode:  1,
		}, fmt.Errorf("missing LLM cache file in both session/ and artifacts/")
	}

	recordAnswer := ReadAnswerFile(filepath.Join(recordDir, "artifacts", "answer.txt"))
	fmt.Printf("[freud] session-replay: recorded answer=%q\n", recordAnswer)

	// Step 2: Replay
	fmt.Println("[freud] session-replay: replaying...")
	replayResult, err := LiveEval(LiveEvalOpts{
		InputFile:        inputFile,
		Timeout:          timeout,
		SessionReplayDir: recordDir,
		CacheReplayFile:  cacheFile,
		NeopsykeCmd:      cfg.LiveEval.NeopsykeCmd,
		RunRootAbs:       cfg.Project.RunRoot,
		GradleUserHome:   cfg.Project.GradleHome,
		LLMConfigFile:    cfg.LiveEval.LLMConfigFile,
		RetentionDays:    0,
		RepoRoot:         repoRoot,
		Verbose:          opts.Verbose,
	})
	if err != nil {
		return nil, fmt.Errorf("replay step failed: %w", err)
	}

	replayDir := replayResult.RunDir
	replayAnswer := ReadAnswerFile(filepath.Join(replayDir, "artifacts", "answer.txt"))
	fmt.Printf("[freud] session-replay: replayed answer=%q\n", replayAnswer)

	// Step 3: Compare and verify determinism
	normalizedRecord := NormalizeAnswer(recordAnswer, false)
	normalizedReplay := NormalizeAnswer(replayAnswer, false)

	passed := true
	var failures []string

	// Check answer match (exact or substring)
	if normalizedRecord != normalizedReplay {
		if !strings.Contains(normalizedReplay, normalizedRecord) && !strings.Contains(normalizedRecord, normalizedReplay) {
			passed = false
			failures = append(failures, fmt.Sprintf("answer mismatch: record=%q replay=%q", normalizedRecord, normalizedReplay))
		}
	}

	// Parse and verify LLM cache stats
	fmt.Println("\n[freud] session-replay: LLM Cache Stats")
	cacheStatsPath := filepath.Join(replayDir, "artifacts", "cache-stats.json")
	if analysis.FileExists(cacheStatsPath) {
		data, err := os.ReadFile(cacheStatsPath)
		if err == nil {
			var stats map[string]interface{}
			if json.Unmarshal(data, &stats) == nil {
				totalCalls, _ := stats["total_calls"].(float64)
				cachedCalls, _ := stats["cached_calls"].(float64)
				realCalls, _ := stats["real_calls"].(float64)
				hitRate, _ := stats["hit_rate_percent"].(float64)
				divCount, _ := stats["divergence_count"].(float64)

				fmt.Printf("  total_calls:      %.0f\n", totalCalls)
				fmt.Printf("  cached_calls:     %.0f\n", cachedCalls)
				fmt.Printf("  real_calls:       %.0f\n", realCalls)
				fmt.Printf("  hit_rate:         %.1f%%\n", hitRate)
				fmt.Printf("  divergences:      %.0f\n", divCount)

				if divCount > 0 {
					passed = false
					failures = append(failures, fmt.Sprintf("LLM cache divergences: %.0f", divCount))
				}
				if realCalls > 0 {
					passed = false
					failures = append(failures, fmt.Sprintf("LLM cache had %.0f real (uncached) calls — replay is not fully deterministic", realCalls))
				}
			}
		}
	} else {
		fmt.Println("  (no cache-stats.json found)")
	}

	// Parse and verify session replay stats
	fmt.Println("[freud] session-replay: Session Channel Stats")
	replayStatsPath := filepath.Join(replayDir, "artifacts", "session-replay-stats.json")
	if analysis.FileExists(replayStatsPath) {
		data, err := os.ReadFile(replayStatsPath)
		if err == nil {
			var stats map[string]interface{}
			if json.Unmarshal(data, &stats) == nil {
				totalHits, _ := stats["total_replay_hits"].(float64)
				totalDiv, _ := stats["total_divergences"].(float64)

				fmt.Printf("  total_replay_hits: %.0f\n", totalHits)
				fmt.Printf("  total_divergences: %.0f\n", totalDiv)

				// Print per-channel breakdown
				if channels, ok := stats["channels"].(map[string]interface{}); ok {
					for ch, v := range channels {
						if chStats, ok := v.(map[string]interface{}); ok {
							hits, _ := chStats["hits"].(float64)
							divs, _ := chStats["divergences"].(float64)
							fmt.Printf("  channel %-20s hits=%.0f divergences=%.0f\n", ch, hits, divs)
						}
					}
				}

				if totalDiv > 0 {
					passed = false
					failures = append(failures, fmt.Sprintf("session channel divergences: %.0f", totalDiv))
				}
			}
		}
	} else {
		fmt.Println("  (no session-replay-stats.json found)")
	}

	// Final verdict
	fmt.Println()
	exitCode := 0
	if passed {
		fmt.Println("[freud] session-replay: PASSED — fully deterministic replay")
	} else {
		exitCode = 1
		fmt.Println("[freud] session-replay: FAILED")
		for _, f := range failures {
			fmt.Printf("  - %s\n", f)
		}
	}

	return &SessionReplayTestResult{
		Passed:    passed,
		RecordDir: recordDir,
		ReplayDir: replayDir,
		ExitCode:  exitCode,
	}, nil
}
