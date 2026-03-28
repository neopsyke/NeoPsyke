package orchestrator

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// ReasoningGateOpts configures the reasoning PR gate.
type ReasoningGateOpts struct {
	NeopsykeCmd string
	RepoRoot    string
	Verbose     int
	DryRun      bool
}

// RunReasoningGate runs both core and behavioral reasoning eval gates.
// Replaces run-reasoning-pr-gate.sh.
func RunReasoningGate(opts ReasoningGateOpts) error {
	repoRoot := opts.RepoRoot
	if repoRoot == "" {
		var err error
		repoRoot, err = findRepoRoot()
		if err != nil {
			return err
		}
	}

	neopsykeCmd := opts.NeopsykeCmd
	if neopsykeCmd == "" {
		neopsykeCmd = filepath.Join(repoRoot, "run-neopsyke.sh")
	} else if !filepath.IsAbs(neopsykeCmd) {
		neopsykeCmd = filepath.Join(repoRoot, neopsykeCmd)
	}

	// Logic core tasks
	coreTasks := "shape-lock,feedback-carry,multi-fix"

	// Logic behavioral tasks: 3 families × 4 perturbation types
	families := []string{"ledger", "assignment", "state_machine"}
	var behavioralIDs []string
	for _, family := range families {
		for i := 1; i <= 5; i++ {
			behavioralIDs = append(behavioralIDs, fmt.Sprintf("%s_paraphrase_%02d", family, i))
		}
		for i := 1; i <= 4; i++ {
			behavioralIDs = append(behavioralIDs, fmt.Sprintf("%s_noise_%02d", family, i))
		}
		for i := 1; i <= 3; i++ {
			behavioralIDs = append(behavioralIDs, fmt.Sprintf("%s_reorder_%02d", family, i))
		}
		for i := 1; i <= 3; i++ {
			behavioralIDs = append(behavioralIDs, fmt.Sprintf("%s_repair_%02d", family, i))
		}
	}
	behavioralTasks := strings.Join(behavioralIDs, ",")

	// Run logic core
	fmt.Println("[freud] reasoning gate: logic-core")
	if opts.DryRun {
		fmt.Printf("[dry-run] %s --eval-reasoning-only --eval-reasoning-mode logic --eval-stage freud-logic-core --eval-reasoning-tasks %s\n", neopsykeCmd, coreTasks)
	} else {
		if err := runReasoningEval(neopsykeCmd, repoRoot, "freud-logic-core", coreTasks, opts.Verbose); err != nil {
			return fmt.Errorf("logic-core failed: %w", err)
		}
	}

	// Run logic behavioral
	fmt.Println("[freud] reasoning gate: logic-behavioral")
	if opts.DryRun {
		fmt.Printf("[dry-run] %s --eval-reasoning-only --eval-reasoning-mode logic --eval-stage freud-logic-behavioral --eval-reasoning-tasks %s\n", neopsykeCmd, behavioralTasks)
	} else {
		if err := runReasoningEval(neopsykeCmd, repoRoot, "freud-logic-behavioral", behavioralTasks, opts.Verbose); err != nil {
			return fmt.Errorf("logic-behavioral failed: %w", err)
		}
	}

	return nil
}

func runReasoningEval(neopsykeCmd, repoRoot, stage, tasks string, verbose int) error {
	args := []string{
		"--eval-reasoning-only",
		"--eval-reasoning-mode", "logic",
		"--no-id",
		"--eval-stage", stage,
		"--eval-reasoning-tasks", tasks,
	}

	if verbose > 0 {
		fmt.Printf("[freud] exec: %s %s\n", neopsykeCmd, strings.Join(args, " "))
	}

	cmd := exec.Command(neopsykeCmd, args...)
	cmd.Dir = repoRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	return cmd.Run()
}
