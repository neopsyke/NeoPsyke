package cmd

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/cli/config"
	"github.com/atomitl/neopsyke/freud/cli/dispatch"
	"github.com/spf13/cobra"
)

var runCmd = &cobra.Command{
	Use:   "run <feature-id>",
	Short: "Run the pipeline (deterministic by default)",
	Long: `Run the Freud pipeline for a feature. By default only deterministic steps
(compile, test, scenarios, reasoning logic) are executed. Use --live to include
live evaluation steps that require API calls.`,
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
	runGoals          *bool
)

func init() {
	rootCmd.AddCommand(runCmd)

	runCmd.Flags().BoolVar(&runLive, "live", false, "include live steps (costs money)")
	runCmd.Flags().StringVar(&runLane, "lane", "", "load profile: low-llm | high-llm")
	runCmd.Flags().StringVar(&runFromStep, "from-step", "", "resume from this pipeline step")
	runCmd.Flags().StringVar(&runOnlyStep, "only", "", "run only this pipeline step")
	runCmd.Flags().StringSliceVar(&runSkipSteps, "skip", nil, "skip these pipeline steps")
	runCmd.Flags().BoolVar(&runContinueOnFail, "continue-on-fail", false, "don't stop on first failure")
	runCmd.Flags().IntVar(&runTimeout, "timeout", 0, "override live_eval.timeout (seconds)")

	// --goals / --no-goals using a custom bool pointer
	runCmd.Flags().Bool("goals", false, "enable goals subsystem")
	runCmd.Flags().Bool("no-goals", false, "disable goals subsystem")
}

func runRun(cmd *cobra.Command, args []string) error {
	featureID := args[0]

	// Load config with lane profile
	cfg, err := config.LoadConfig(cfgFile, runLane, overrides)
	if err != nil {
		return err
	}

	// Apply CLI flag overrides
	if runTimeout > 0 {
		cfg.LiveEval.Timeout = runTimeout
	}
	if runContinueOnFail {
		cfg.Runtime.ContinueOnFail = true
	}

	// Handle --goals / --no-goals
	goals := resolveGoals(cmd)
	if goals != nil {
		cfg.LiveEval.GoalsEnabled = *goals
	}

	// Validate
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

	// Build script args
	scriptPath, err := dispatch.ScriptPath("feature-loop.sh")
	if err != nil {
		return err
	}

	scriptArgs := []string{featureID}
	if runLive {
		scriptArgs = append(scriptArgs, "--live")
	}
	if dryRun {
		scriptArgs = append(scriptArgs, "--dry-run")
	}
	if cfg.Runtime.ContinueOnFail {
		scriptArgs = append(scriptArgs, "--continue-on-fail")
	}
	if runFromStep != "" {
		scriptArgs = append(scriptArgs, "--from-step", runFromStep)
	}
	if goals != nil {
		if *goals {
			scriptArgs = append(scriptArgs, "--goals")
		} else {
			scriptArgs = append(scriptArgs, "--no-goals")
		}
	}

	// Build env and dispatch
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

// resolveGoals returns nil if neither --goals nor --no-goals was set,
// or a pointer to the resolved value.
func resolveGoals(cmd *cobra.Command) *bool {
	goalsSet := cmd.Flags().Changed("goals")
	noGoalsSet := cmd.Flags().Changed("no-goals")

	if noGoalsSet {
		v := false
		return &v
	}
	if goalsSet {
		v := true
		return &v
	}
	return nil
}
