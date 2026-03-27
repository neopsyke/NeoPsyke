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

var evalCmd = &cobra.Command{
	Use:   "eval",
	Short: "Run a single live evaluation",
	Long: `Run a single live evaluation against NeoPsyke. Requires --input with a
prompt file. Optionally compare against --expected for pass/fail scoring.`,
	RunE: runEval,
}

var (
	evalInput         string
	evalExpected      string
	evalTimeout       int
	evalCacheReplay   string
	evalRecordSession bool
	evalSessionReplay string
	evalLane          string
)

func init() {
	rootCmd.AddCommand(evalCmd)

	evalCmd.Flags().StringVar(&evalInput, "input", "", "input prompt file (required)")
	evalCmd.Flags().StringVar(&evalExpected, "expected", "", "expected answer file for scoring")
	evalCmd.Flags().IntVar(&evalTimeout, "timeout", 0, "override live_eval.timeout (seconds)")
	evalCmd.Flags().StringVar(&evalCacheReplay, "cache-replay", "", "JSONL cache file to replay")
	evalCmd.Flags().BoolVar(&evalRecordSession, "record-session", false, "record all signals for replay")
	evalCmd.Flags().StringVar(&evalSessionReplay, "session-replay", "", "replay a recorded session directory")
	evalCmd.Flags().StringVar(&evalLane, "lane", "", "load profile for LLM routing: low-llm | high-llm")

	evalCmd.Flags().Bool("goals", false, "enable goals subsystem")
	evalCmd.Flags().Bool("no-goals", false, "disable goals subsystem")

	_ = evalCmd.MarkFlagRequired("input")
}

func runEval(cmd *cobra.Command, args []string) error {
	cfg, err := config.LoadConfig(cfgFile, evalLane, overrides)
	if err != nil {
		return err
	}

	if evalTimeout > 0 {
		cfg.LiveEval.Timeout = evalTimeout
	}

	goals := resolveGoals(cmd)
	if goals != nil {
		cfg.LiveEval.GoalsEnabled = *goals
	}

	errs := config.Validate(cfg, "eval", &config.ValidationOpts{
		InputFile: evalInput,
	})
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	scriptPath, err := dispatch.ScriptPath("live-eval.sh")
	if err != nil {
		return err
	}

	scriptArgs := []string{"--input", evalInput}
	if evalExpected != "" {
		scriptArgs = append(scriptArgs, "--expected", evalExpected)
	}
	scriptArgs = append(scriptArgs, "--timeout", strconv.Itoa(cfg.LiveEval.Timeout))
	if evalCacheReplay != "" {
		scriptArgs = append(scriptArgs, "--cache-replay", evalCacheReplay)
	}
	if evalRecordSession {
		scriptArgs = append(scriptArgs, "--record-session")
	}
	if evalSessionReplay != "" {
		scriptArgs = append(scriptArgs, "--session-replay", evalSessionReplay)
	}
	if goals != nil {
		if *goals {
			scriptArgs = append(scriptArgs, "--goals")
		} else {
			scriptArgs = append(scriptArgs, "--no-goals")
		}
	}

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
