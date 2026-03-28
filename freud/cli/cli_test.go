package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"
)

func resetFlagSet(fs *pflag.FlagSet) {
	fs.VisitAll(func(f *pflag.Flag) {
		f.Changed = false
	})
}

func resetCommandState(cmd *cobra.Command) {
	resetFlagSet(cmd.PersistentFlags())
	resetFlagSet(cmd.Flags())
	for _, sub := range cmd.Commands() {
		resetCommandState(sub)
	}
}

func resetCLIState() {
	cfgFile = ""
	overrides = nil
	verbose = 0
	dryRun = false

	bbhLane = ""
	bbhTimeout = 0
	bbhBaseline = ""

	evalInput = ""
	evalExpected = ""
	evalTimeout = 0
	evalCacheReplay = ""
	evalRecordSession = false
	evalSessionReplay = ""
	evalLane = ""

	runLive = false
	runLane = ""
	runFromStep = ""
	runOnlyStep = ""
	runSkipSteps = nil
	runContinueOnFail = false
	runTimeout = 0
	runGoals = nil

	testReplayInput = ""
	testReplayTimeout = 0

	tisTimeout = 60
	tisDashboardPort = 8787
	tisDashboardHost = "127.0.0.1"

	triageTopN = 20

	resetCommandState(rootCmd)
	rootCmd.SetArgs(nil)
}

func executeCLIForTest(t *testing.T, args ...string) error {
	t.Helper()
	resetCLIState()
	rootCmd.SetArgs(args)
	return Execute()
}

func writeCLIConfig(t *testing.T, content string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "freud-test.yaml")
	writeFile := func(path string, body string) {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatalf("mkdir %s: %v", filepath.Dir(path), err)
		}
		if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}
	writeFile(path, content)
	return path
}

func TestRootCommandRegistersExpectedSubcommands(t *testing.T) {
	resetCLIState()
	want := map[string]bool{
		"bbh":                     true,
		"context-pack":            true,
		"eval":                    true,
		"run":                     true,
		"summarize":               true,
		"test-replay-eval":        true,
		"test-replay-interactive": true,
		"triage":                  true,
	}
	for _, cmd := range rootCmd.Commands() {
		delete(want, cmd.Name())
	}
	if len(want) != 0 {
		t.Fatalf("missing root subcommands: %v", want)
	}
}

func TestEvalSessionReplayDryRunWithoutInputSucceeds(t *testing.T) {
	t.Setenv("FREUD_WRITE_LOCAL_POINTERS", "false")

	runDir := filepath.Join(t.TempDir(), "recorded-run")
	if err := os.MkdirAll(filepath.Join(runDir, "session"), 0o755); err != nil {
		t.Fatalf("mkdir session dir: %v", err)
	}

	cfgPath := writeCLIConfig(t, fmt.Sprintf("project:\n  run_root: %q\n", filepath.Join(t.TempDir(), "runs")))

	err := executeCLIForTest(t, "--config", cfgPath, "--dry-run", "eval", "--session-replay", runDir)
	if err != nil {
		t.Fatalf("eval replay dry-run failed: %v", err)
	}
}

func TestRunLiveFailsFastWithoutLaneConfig(t *testing.T) {
	t.Setenv("FREUD_WRITE_LOCAL_POINTERS", "false")

	cfgPath := writeCLIConfig(t, fmt.Sprintf("project:\n  run_root: %q\npipeline:\n  - name: reasoning_eval_model\n    live_only: true\n", filepath.Join(t.TempDir(), "runs")))

	err := executeCLIForTest(t, "--config", cfgPath, "run", "live-misconfig", "--live")
	if err == nil {
		t.Fatal("expected live validation failure")
	}
	if !strings.Contains(err.Error(), "live_eval.llm_config_file") {
		t.Fatalf("expected llm_config_file validation error, got: %v", err)
	}
}
