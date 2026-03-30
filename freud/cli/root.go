package main

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
	"github.com/spf13/viper"
)

var (
	cfgFile   string
	overrides []string
	verbose   int
	dryRun    bool
)

var rootCmd = &cobra.Command{
	Use:   "freud",
	Short: "Unified Freud test & eval CLI",
	Long: `Freud is the unified CLI for running tests, evaluations, and analysis
for the NeoPsyke agent. It replaces the individual shell scripts with a single
entry point backed by a YAML config file.

Configuration precedence: CLI flag > env var > profile > YAML config > defaults`,
	SilenceUsage:  true,
	SilenceErrors: true,
}

func Execute() error {
	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return err
	}
	return nil
}

func init() {
	cobra.OnInitialize(initConfig)

	rootCmd.PersistentFlags().StringVarP(&cfgFile, "config", "c", "", "path to YAML config file")
	rootCmd.PersistentFlags().StringArrayVarP(&overrides, "override", "o", nil, "override config key (dot-notation, e.g. live_eval.timeout=60)")
	rootCmd.PersistentFlags().CountVarP(&verbose, "verbose", "v", "increase output detail")
	rootCmd.PersistentFlags().BoolVar(&dryRun, "dry-run", false, "show what would run, don't execute")
}

func initConfig() {
	if cfgFile != "" {
		viper.SetConfigFile(cfgFile)
	} else if p := os.Getenv("FREUD_CONFIG"); p != "" {
		viper.SetConfigFile(p)
	}
	// Config loading is done per-command via config.LoadConfig()
}
