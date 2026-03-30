package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/config"
	"github.com/atomitl/neopsyke/freud/internal/orchestrator"
	"github.com/spf13/cobra"
)

var evalCmd = &cobra.Command{
	Use:   "eval",
	Short: "Run a single live evaluation",
	Long: `Run a single evaluation against NeoPsyke. Provider-backed execution requires
--live. Replay modes may still fall back to live provider calls on divergence,
so they also require --live unless you are using --dry-run for inspection only.`,
	RunE: runEval,
}

var (
	evalInput         string
	evalExpected      string
	evalTimeout       int
	evalCacheReplay   string
	evalLive          bool
	evalRecord        bool
	evalSessionReplay string
	evalLane          string
)

func init() {
	rootCmd.AddCommand(evalCmd)

	evalCmd.Flags().StringVar(&evalInput, "input", "", "input prompt file (required unless --session-replay is used)")
	evalCmd.Flags().StringVar(&evalExpected, "expected", "", "expected answer file for scoring")
	evalCmd.Flags().IntVar(&evalTimeout, "timeout", 0, "override live_eval.timeout (seconds)")
	evalCmd.Flags().BoolVar(&evalLive, "live", false, "allow provider-backed execution (may spend tokens)")
	evalCmd.Flags().StringVar(&evalCacheReplay, "cache-replay", "", "JSONL cache file to replay")
	evalCmd.Flags().BoolVar(&evalRecord, "record", false, "record replay artifacts for later cache/session replay")
	evalCmd.Flags().StringVar(&evalSessionReplay, "session-replay", "", "replay a recorded session directory")
	evalCmd.Flags().StringVar(&evalLane, "lane", "", "load profile for LLM routing: low-llm | high-llm")

	evalCmd.Flags().Bool("goals", false, "enable goals subsystem")
	evalCmd.Flags().Bool("no-goals", false, "disable goals subsystem")
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
		Live:             evalLive,
		DryRun:           dryRun,
		Record:           evalRecord,
		InputFile:        evalInput,
		SessionReplayDir: evalSessionReplay,
		CacheReplayFile:  evalCacheReplay,
	})
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	result, err := orchestrator.LiveEval(orchestrator.LiveEvalOpts{
		InputFile:        evalInput,
		ExpectedFile:     evalExpected,
		Timeout:          cfg.LiveEval.Timeout,
		CacheReplayFile:  evalCacheReplay,
		SessionReplayDir: evalSessionReplay,
		Record:           evalRecord,
		GoalsEnabled:     goals,
		PreserveMemory:   cfg.LiveEval.PreserveMemory,
		NeopsykeCmd:      cfg.LiveEval.NeopsykeCmd,
		RunRootAbs:       cfg.Project.RunRoot,
		GradleUserHome:   cfg.Project.GradleHome,
		LLMConfigFile:    cfg.LiveEval.LLMConfigFile,
		RetentionDays:    cfg.Project.RetentionDays,
		Verbose:          verbose,
		DryRun:           dryRun,
	})
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		os.Exit(result.ExitCode)
	}
	return nil
}
