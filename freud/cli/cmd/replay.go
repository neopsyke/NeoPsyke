package cmd

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/cli/config"
	"github.com/atomitl/neopsyke/freud/cli/orchestrator"
	"github.com/spf13/cobra"
)

var testReplayEvalCmd = &cobra.Command{
	Use:   "test-replay-eval",
	Short: "E2E test: record a live eval, replay it, verify determinism",
	Long: `Records a live eval session, replays it from cache, and asserts that all
LLM calls were served from cache (real_calls=0) and all session channels
replayed without divergence. This is a determinism gate, not a standalone
replay command. For standalone replay, use: freud eval --session-replay <run-dir>`,
	RunE: runTestReplayEval,
}

var (
	testReplayInput   string
	testReplayTimeout int
)

func init() {
	rootCmd.AddCommand(testReplayEvalCmd)

	testReplayEvalCmd.Flags().StringVar(&testReplayInput, "input", "", "input prompt file (default: 'What is 2 + 2?')")
	testReplayEvalCmd.Flags().IntVar(&testReplayTimeout, "timeout", 0, "override timeout (seconds)")
}

func runTestReplayEval(cmd *cobra.Command, args []string) error {
	cfg, err := config.LoadConfig(cfgFile, "", overrides)
	if err != nil {
		return err
	}

	if testReplayTimeout > 0 {
		cfg.LiveEval.Timeout = testReplayTimeout
	}

	errs := config.Validate(cfg, "test-replay-eval", nil)
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	result, err := orchestrator.SessionReplayTest(orchestrator.SessionReplayTestOpts{
		InputFile: testReplayInput,
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
