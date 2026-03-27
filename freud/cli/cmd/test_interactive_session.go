package cmd

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/cli/config"
	"github.com/atomitl/neopsyke/freud/cli/orchestrator"
	"github.com/spf13/cobra"
)

var testInteractiveSessionCmd = &cobra.Command{
	Use:   "test-replay-interactive",
	Short: "E2E test: interactive mode session recording and replay",
	Long: `Starts NeoPsyke in interactive mode with --record-session, sends a message
via the dashboard HTTP API, waits for the agent response, stops the agent,
verifies the session recording, replays it, and asserts determinism.

This test requires a working LLM config and exercises the full dashboard
chat flow, not just piped stdin.`,
	RunE: runTestInteractiveSession,
}

var (
	tisTimeout int
)

func init() {
	rootCmd.AddCommand(testInteractiveSessionCmd)

	testInteractiveSessionCmd.Flags().IntVar(&tisTimeout, "timeout", 60, "timeout in seconds")
}

func runTestInteractiveSession(cmd *cobra.Command, args []string) error {
	cfg, err := config.LoadConfig(cfgFile, "", overrides)
	if err != nil {
		return err
	}

	errs := config.Validate(cfg, "test-interactive-session", nil)
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	result, err := orchestrator.InteractiveSessionTest(orchestrator.InteractiveSessionTestOpts{
		Timeout: tisTimeout,
		Cfg:     cfg,
		Verbose: verbose,
		DryRun:  dryRun,
	})
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		os.Exit(result.ExitCode)
	}
	return nil
}
