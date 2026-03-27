package cmd

import (
	"fmt"
	"os"
	"strings"

	"github.com/atomitl/neopsyke/freud/cli/config"
	"github.com/atomitl/neopsyke/freud/cli/dispatch"
	"github.com/spf13/cobra"
)

var bbhCmd = &cobra.Command{
	Use:   "bbh",
	Short: "Run BBH reasoning smoke suite",
	Long: `Run the frozen BBH-style reasoning smoke test suite. Requires --lane to
select the LLM routing profile (low-llm for cheap models, high-llm for production).`,
	RunE: runBBH,
}

var (
	bbhLane     string
	bbhTimeout  int
	bbhBaseline string
)

func init() {
	rootCmd.AddCommand(bbhCmd)

	bbhCmd.Flags().StringVar(&bbhLane, "lane", "", "LLM profile: low-llm | high-llm (required)")
	bbhCmd.Flags().IntVar(&bbhTimeout, "timeout", 0, "override live_eval.timeout (seconds)")
	bbhCmd.Flags().StringVar(&bbhBaseline, "baseline", "", "regression baseline file")

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

	errs := config.Validate(cfg, "bbh", nil)
	if len(errs) > 0 {
		msgs := make([]string, len(errs))
		for i, e := range errs {
			msgs[i] = e.Error()
		}
		return fmt.Errorf("config validation failed:\n  %s", strings.Join(msgs, "\n  "))
	}

	scriptPath, err := dispatch.ScriptPath("run-bbh-smoke.sh")
	if err != nil {
		return err
	}

	// Map lane name to internal script name
	internalLane, ok := config.LaneMap[bbhLane]
	if !ok {
		return fmt.Errorf("unknown lane %q, valid lanes: %s", bbhLane, validLanes())
	}

	scriptArgs := []string{"--lane", internalLane}
	if bbhBaseline != "" {
		scriptArgs = append(scriptArgs, "--baseline", bbhBaseline)
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

func validLanes() string {
	lanes := make([]string, 0, len(config.LaneMap))
	for k := range config.LaneMap {
		lanes = append(lanes, k)
	}
	return strings.Join(lanes, ", ")
}
