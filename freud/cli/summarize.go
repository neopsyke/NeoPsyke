package main

import (
	"fmt"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
	"github.com/spf13/cobra"
)

var summarizeCmd = &cobra.Command{
	Use:   "summarize [run_dir]",
	Short: "Generate run summary",
	Long:  `Generate a compact summary for a Freud run directory. Defaults to the latest run.`,
	Args:  cobra.MaximumNArgs(1),
	RunE:  runSummarize,
}

func init() {
	rootCmd.AddCommand(summarizeCmd)
}

func runSummarize(cmd *cobra.Command, args []string) error {
	runDir, err := resolveRunDir(args)
	if err != nil {
		return err
	}

	result, err := analysis.Summarize(runDir)
	if err != nil {
		return err
	}
	fmt.Println(result)
	return nil
}
