package cmd

import (
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/atomitl/neopsyke/freud/cli/config"
	"github.com/atomitl/neopsyke/freud/cli/dispatch"
	"github.com/spf13/cobra"
)

var replayCmd = &cobra.Command{
	Use:   "replay",
	Short: "Replay a recorded session",
	Long:  `Replay a previously recorded session to verify deterministic behavior.`,
	RunE:  runReplay,
}

var (
	replayInput   string
	replayTimeout int
)

func init() {
	rootCmd.AddCommand(replayCmd)

	replayCmd.Flags().StringVar(&replayInput, "input", "", "input prompt file")
	replayCmd.Flags().IntVar(&replayTimeout, "timeout", 0, "override timeout (seconds)")
}

func runReplay(cmd *cobra.Command, args []string) error {
	cfg, err := config.LoadConfig(cfgFile, "", overrides)
	if err != nil {
		return err
	}

	if replayTimeout > 0 {
		cfg.LiveEval.Timeout = replayTimeout
	}

	errs := config.Validate(cfg, "replay", nil)
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	scriptPath, err := dispatch.ScriptPath("test-session-replay.sh")
	if err != nil {
		return err
	}

	var scriptArgs []string
	if replayInput != "" {
		scriptArgs = append(scriptArgs, "--input", replayInput)
	}
	scriptArgs = append(scriptArgs, "--timeout", strconv.Itoa(cfg.LiveEval.Timeout))

	env := dispatch.BuildEnv(cfg)
	exitCode, err := dispatch.RunScript(scriptPath, scriptArgs, env, dryRun, verbose)
	if err != nil {
		return err
	}
	if exitCode != 0 {
		os.Exit(exitCode)
	}
	return nil
}
