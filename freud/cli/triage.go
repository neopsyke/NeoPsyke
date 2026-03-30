package main

import (
	"fmt"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
	"github.com/atomitl/neopsyke/freud/internal/orchestrator"
	"github.com/spf13/cobra"
)

var triageCmd = &cobra.Command{
	Use:   "triage [run_dir]",
	Short: "Analyze a run for anomalies",
	Long:  `Run heuristic triage on a Freud run directory. Defaults to the latest run.`,
	Args:  cobra.MaximumNArgs(1),
	RunE:  runTriage,
}

var triageTopN int

func init() {
	rootCmd.AddCommand(triageCmd)
	triageCmd.Flags().IntVar(&triageTopN, "top", 20, "max top signals to keep")
}

func runTriage(cmd *cobra.Command, args []string) error {
	runDir, err := resolveRunDir(args)
	if err != nil {
		return err
	}

	result, err := analysis.Triage(runDir, triageTopN)
	if err != nil {
		return err
	}
	fmt.Println(result)
	return nil
}

// resolveRunDir returns the run dir from args or falls back to latest.
func resolveRunDir(args []string) (string, error) {
	if len(args) > 0 {
		return args[0], nil
	}
	dir, err := orchestrator.LatestRunDir()
	if err != nil {
		return "", fmt.Errorf("no run_dir specified and %w", err)
	}
	return dir, nil
}
