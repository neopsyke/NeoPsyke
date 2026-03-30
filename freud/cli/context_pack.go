package main

import (
	"fmt"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
	"github.com/spf13/cobra"
)

var contextPackCmd = &cobra.Command{
	Use:   "context-pack [run_dir]",
	Short: "Package run for LLM analysis",
	Long:  `Build a compact context pack for triaging a Freud run. Defaults to the latest run.`,
	Args:  cobra.MaximumNArgs(1),
	RunE:  runContextPack,
}

func init() {
	rootCmd.AddCommand(contextPackCmd)
}

func runContextPack(cmd *cobra.Command, args []string) error {
	runDir, err := resolveRunDir(args)
	if err != nil {
		return err
	}

	result, err := analysis.ContextPack(runDir)
	if err != nil {
		return err
	}
	fmt.Println(result)
	return nil
}
