package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/config"
	"github.com/atomitl/neopsyke/freud/internal/orchestrator"
	"github.com/spf13/cobra"
)

var runCmd = &cobra.Command{
	Use:   "run <feature-id>",
	Short: "Run the Freud feature loop (deterministic by default)",
	Long: `Run the Freud feature loop for a feature. By default it executes the
deterministic workflow. Use --live to include the live lane, which adds
provider-backed steps that require API calls.`,
	Args: cobra.ExactArgs(1),
	RunE: runRun,
}

var (
	runLive           bool
	runLane           string
	runFromStep       string
	runOnlyStep       string
	runSkipSteps      []string
	runContinueOnFail bool
	runTimeout        int
	runAssignments    *bool
)

func init() {
	rootCmd.AddCommand(runCmd)

	runCmd.Flags().BoolVar(&runLive, "live", false, "include live steps (costs money)")
	runCmd.Flags().StringVar(&runLane, "lane", "", "load profile: low-llm | high-llm")
	runCmd.Flags().StringVar(&runFromStep, "from-step", "", "resume from this step")
	runCmd.Flags().StringVar(&runOnlyStep, "only", "", "run only this step")
	runCmd.Flags().StringSliceVar(&runSkipSteps, "skip", nil, "skip these steps")
	runCmd.Flags().BoolVar(&runContinueOnFail, "continue-on-fail", false, "don't stop on first failure")
	runCmd.Flags().IntVar(&runTimeout, "timeout", 0, "override live_eval.timeout (seconds)")

	runCmd.Flags().Bool("assignments", false, "enable assignments subsystem")
	runCmd.Flags().Bool("no-assignments", false, "disable assignments subsystem")
}

func runRun(cmd *cobra.Command, args []string) error {
	featureID := args[0]

	cfg, err := config.LoadConfig(cfgFile, runLane, overrides)
	if err != nil {
		return err
	}

	if runTimeout > 0 {
		cfg.LiveEval.Timeout = runTimeout
	}
	if runContinueOnFail {
		cfg.Runtime.ContinueOnFail = true
	}

	assignments := resolveAssignments(cmd)
	if assignments != nil {
		cfg.LiveEval.AssignmentsEnabled = *assignments
	}

	errs := config.Validate(cfg, "run", &config.ValidationOpts{
		Live:      runLive,
		FromStep:  runFromStep,
		OnlyStep:  runOnlyStep,
		SkipSteps: runSkipSteps,
	})
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	result, err := orchestrator.FeatureLoop(orchestrator.FeatureLoopOpts{
		FeatureID:      featureID,
		Live:           runLive,
		DryRun:         dryRun,
		ContinueOnFail: cfg.Runtime.ContinueOnFail,
		FromStep:       runFromStep,
		OnlyStep:       runOnlyStep,
		SkipSteps:      runSkipSteps,
		AssignmentsEnabled: assignments,
		Cfg:            cfg,
		Verbose:        verbose,
	})
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		os.Exit(result.ExitCode)
	}
	return nil
}

// resolveAssignments returns nil if neither --assignments nor --no-assignments was set,
// or a pointer to the resolved value.
func resolveAssignments(cmd *cobra.Command) *bool {
	assignmentsSet := cmd.Flags().Changed("assignments")
	noAssignmentsSet := cmd.Flags().Changed("no-assignments")

	if noAssignmentsSet {
		v := false
		return &v
	}
	if assignmentsSet {
		v := true
		return &v
	}
	return nil
}
