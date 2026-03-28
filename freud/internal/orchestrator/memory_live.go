package orchestrator

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// MemoryLiveSmokeOpts configures the memory live smoke step.
type MemoryLiveSmokeOpts struct {
	TaskIDs        []string
	Stage          string
	MaxAttempts    int
	NeopsykeCmd    string
	RunRootAbs     string
	GradleUserHome string
	LLMConfigFile  string
	RepoRoot       string
	Verbose        int
	Progress       ProgressReporter
}

// MemoryLiveSmokeResult holds the memory live smoke outcome.
type MemoryLiveSmokeResult struct {
	ExitCode int
}

// MemoryLiveSmoke runs a focused live memory evaluation against a small task set.
func MemoryLiveSmoke(opts MemoryLiveSmokeOpts) (*MemoryLiveSmokeResult, error) {
	repoRoot := opts.RepoRoot
	if repoRoot == "" {
		var err error
		repoRoot, err = findRepoRoot()
		if err != nil {
			return nil, err
		}
	}

	neopsykeCmd := opts.NeopsykeCmd
	if neopsykeCmd == "" {
		neopsykeCmd = filepath.Join(repoRoot, "run-neopsyke.sh")
	} else if !filepath.IsAbs(neopsykeCmd) {
		neopsykeCmd = filepath.Join(repoRoot, neopsykeCmd)
	}

	taskIDs := opts.TaskIDs
	if len(taskIDs) == 0 {
		taskIDs = []string{"user-preference-color"}
	}

	stage := opts.Stage
	if stage == "" {
		stage = "freud-memory-live-smoke"
	}

	maxAttempts := opts.MaxAttempts
	if maxAttempts < 1 {
		maxAttempts = 1
	}

	PrimeGradleHome(repoRoot, ResolveAbsPath(opts.GradleUserHome, repoRoot), opts.Verbose)

	args := []string{
		"--eval-memory-live",
		"--no-id",
		"--eval-stage", stage,
		"--eval-memory-tasks", strings.Join(taskIDs, ","),
		"--eval-memory-max-attempts", fmt.Sprintf("%d", maxAttempts),
	}
	if opts.Verbose > 0 {
		fmt.Printf("[freud] exec: %s %s\n", neopsykeCmd, strings.Join(args, " "))
	}
	opts.Progress.Emit(ProgressUpdate{
		Phase:   "run",
		Status:  "start",
		Message: fmt.Sprintf("tasks=%d stage=%s", len(taskIDs), stage),
	})

	cmd := exec.Command(neopsykeCmd, args...)
	cmd.Dir = repoRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Env = os.Environ()
	if opts.LLMConfigFile != "" {
		cmd.Env = append(cmd.Env, "NEOPSYKE_LLM_CONFIG_FILE="+ResolveAbsPath(opts.LLMConfigFile, repoRoot))
	}
	if opts.GradleUserHome != "" {
		cmd.Env = append(cmd.Env, "GRADLE_USER_HOME="+ResolveAbsPath(opts.GradleUserHome, repoRoot))
	}

	if err := cmd.Run(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			opts.Progress.Emit(ProgressUpdate{
				Phase:   "run",
				Status:  "fail",
				Message: fmt.Sprintf("exit=%d", exitErr.ExitCode()),
			})
			return &MemoryLiveSmokeResult{ExitCode: exitErr.ExitCode()}, nil
		}
		return nil, err
	}
	opts.Progress.Emit(ProgressUpdate{
		Phase:   "run",
		Status:  "pass",
		Message: fmt.Sprintf("tasks=%d", len(taskIDs)),
	})
	return &MemoryLiveSmokeResult{ExitCode: 0}, nil
}
