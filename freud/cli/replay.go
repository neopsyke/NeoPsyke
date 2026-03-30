package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/config"
	"github.com/atomitl/neopsyke/freud/internal/orchestrator"
	"github.com/spf13/cobra"
)

var testFreudReplayCmd = &cobra.Command{
	Use:   "test-freud-replay",
	Short: "E2E test: record a Freud live eval and replay it",
	Long: `Records a Freud live eval session, replays it from cache, and asserts that all
LLM calls were served from cache (real_calls=0) and all session channels
replayed without divergence. This is an end-to-end Freud replay test, not a standalone
replay command. For standalone replay, use: freud eval --live --session-replay <run-dir>`,
	RunE: runTestFreudReplay,
}

var (
	testFreudReplayInput   string
	testFreudReplayTimeout int
)

func init() {
	rootCmd.AddCommand(testFreudReplayCmd)

	testFreudReplayCmd.Flags().StringVar(&testFreudReplayInput, "input", "", "input prompt file (default: 'What is 2 + 2?')")
	testFreudReplayCmd.Flags().IntVar(&testFreudReplayTimeout, "timeout", 0, "override timeout (seconds)")
}

func runTestFreudReplay(cmd *cobra.Command, args []string) error {
	cfg, err := config.LoadConfig(cfgFile, "", overrides)
	if err != nil {
		return err
	}

	if testFreudReplayTimeout > 0 {
		cfg.LiveEval.Timeout = testFreudReplayTimeout
	}

	errs := config.Validate(cfg, "test-freud-replay", nil)
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	result, err := orchestrator.SessionReplayTest(orchestrator.SessionReplayTestOpts{
		InputFile: testFreudReplayInput,
		Timeout:   cfg.LiveEval.Timeout,
		Cfg:       cfg,
		Verbose:   verbose,
		DryRun:    dryRun,
	})
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		os.Exit(result.ExitCode)
	}
	return nil
}
