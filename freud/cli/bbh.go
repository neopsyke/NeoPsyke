package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/config"
	"github.com/atomitl/neopsyke/freud/internal/orchestrator"
	"github.com/spf13/cobra"
)

var bbhCmd = &cobra.Command{
	Use:   "bbh",
	Short: "Run BBH reasoning smoke suite",
	Long: `Run the frozen BBH-style reasoning smoke test suite. Requires --live
because it makes real provider-backed calls, and requires --lane to select
the LLM routing profile (low-llm for cheap models, high-llm for production).`,
	RunE: runBBH,
}

var (
	bbhLane          string
	bbhTimeout       int
	bbhBaseline      string
	bbhLive          bool
	bbhRecord        bool
	bbhSessionReplay string
)

func init() {
	rootCmd.AddCommand(bbhCmd)

	bbhCmd.Flags().StringVar(&bbhLane, "lane", "", "LLM profile: low-llm | high-llm (required)")
	bbhCmd.Flags().IntVar(&bbhTimeout, "timeout", 0, "override live_eval.timeout (seconds)")
	bbhCmd.Flags().StringVar(&bbhBaseline, "baseline", "", "regression baseline file")
	bbhCmd.Flags().BoolVar(&bbhLive, "live", false, "allow provider-backed execution (may spend tokens)")
	bbhCmd.Flags().BoolVar(&bbhRecord, "record", false, "record per-case replay artifacts for later BBH replay/debugging")
	bbhCmd.Flags().StringVar(&bbhSessionReplay, "session-replay", "", "replay a recorded BBH run directory")

	_ = bbhCmd.MarkFlagRequired("lane")
}

func runBBH(cmd *cobra.Command, args []string) error {
	cfg, err := config.LoadConfig(cfgFile, bbhLane, overrides)
	if err != nil {
		return err
	}

	if bbhTimeout > 0 {
		cfg.LiveEval.Timeout = bbhTimeout
	}

	errs := config.Validate(cfg, "bbh", &config.ValidationOpts{
		Live:             bbhLive,
		DryRun:           dryRun,
		Record:           bbhRecord,
		SessionReplayDir: bbhSessionReplay,
	})
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	// Validate lane name
	validLane := false
	for _, l := range config.LaneNames {
		if bbhLane == l {
			validLane = true
			break
		}
	}
	if !validLane {
		return fmt.Errorf("unknown lane %q, valid lanes: %s", bbhLane, validLanes())
	}

	result, err := orchestrator.BBHSmoke(orchestrator.BBHOpts{
		Lane:                 bbhLane,
		PromptsFile:          cfg.BBH.PromptsFile,
		AnswersFile:          cfg.BBH.AnswersFile,
		MinPassRatePercent:   cfg.BBH.MinPassRate,
		MaxTimeouts:          cfg.BBH.MaxTimeouts,
		MaxRegressionPercent: cfg.BBH.MaxRegressionPercent,
		BaselineFile:         bbhBaseline,
		Record:               bbhRecord,
		SessionReplayDir:     bbhSessionReplay,
		PreserveMemory:       cfg.BBH.PreserveMemory,
		MemoryEnabled:        cfg.BBH.MemoryEnabled,
		LogbookEnabled:       cfg.BBH.LogbookEnabled,
		Timeout:              cfg.LiveEval.Timeout,
		NeopsykeCmd:          cfg.LiveEval.NeopsykeCmd,
		RunRootAbs:           cfg.Project.RunRoot,
		GradleUserHome:       cfg.Project.GradleHome,
		LLMConfigFile:        cfg.LiveEval.LLMConfigFile,
		RetentionDays:        cfg.Project.RetentionDays,
		RepoRoot:             "",
		Verbose:              verbose,
		DryRun:               dryRun,
		Progress:             orchestrator.WithStepProgress("bbh", orchestrator.NewConsoleProgressReporter(os.Stdout)),
	})
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		os.Exit(result.ExitCode)
	}
	return nil
}

func validLanes() string {
	return strings.Join(config.LaneNames, ", ")
}
