package analysis

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// ContextPack generates a context-pack.md for run triage.
// Returns the path to context-pack.md.
func ContextPack(runDir string) (string, error) {
	if !DirExists(runDir) {
		return "", fmt.Errorf("run directory does not exist: %s", runDir)
	}

	root, _ := RepoRoot()
	artifactDir := filepath.Join(runDir, "artifacts")
	summaryJSON := filepath.Join(artifactDir, "summary.json")
	stepsFile := filepath.Join(artifactDir, "steps.tsv")
	stepIndexFile := filepath.Join(artifactDir, "step-index.tsv")
	anomaliesMD := filepath.Join(artifactDir, "anomalies.md")
	trailFile := filepath.Join(artifactDir, "trail.jsonl")
	trailIndexFile := filepath.Join(artifactDir, "trail-index.tsv")
	runConfigFile := filepath.Join(artifactDir, "run-config.json")
	stepMetaDir := filepath.Join(artifactDir, "step-meta")
	freudMetricsJSON := filepath.Join(artifactDir, "freud-metrics.json")
	compactSummaryMD := filepath.Join(artifactDir, "summary-compact.md")
	runIndexMD := filepath.Join(artifactDir, "run-index.md")
	contextMD := filepath.Join(artifactDir, "context-pack.md")

	featureID := ExtractJSONString(summaryJSON, "feature_id")
	runID := ExtractJSONString(summaryJSON, "run_id")
	status := ExtractJSONString(summaryJSON, "status")
	mode := ExtractJSONString(summaryJSON, "mode")
	stepsFailed := ExtractJSONNumber(summaryJSON, "steps_failed")
	stepsTotal := ExtractJSONNumber(summaryJSON, "steps_total")

	or := func(s, fallback string) string {
		if s == "" {
			return fallback
		}
		return s
	}

	md := []string{
		"# Freud Context Pack",
		"",
		"Read this file first when triaging a Freud run. It provides a run snapshot,",
		"ordered artifact paths (cheapest first), failure details, and triage highlights.",
		"",
		"## Run Snapshot",
		fmt.Sprintf("- feature_id: `%s`", or(featureID, "unknown")),
		fmt.Sprintf("- run_id: `%s`", or(runID, "unknown")),
		fmt.Sprintf("- status: `%s`", or(status, "unknown")),
		fmt.Sprintf("- mode: `%s`", or(mode, "unknown")),
		fmt.Sprintf("- steps_failed: `%s` / `%s`", or(stepsFailed, "0"), or(stepsTotal, "0")),
		fmt.Sprintf("- run_dir: `%s`", runDir),
		"",
		"## Start Here (Low Token)",
		fmt.Sprintf("- `%s`", compactSummaryMD),
		fmt.Sprintf("- `%s`", runIndexMD),
		fmt.Sprintf("- `%s`", summaryJSON),
		fmt.Sprintf("- `%s`", filepath.Join(artifactDir, "anomalies.json")),
		fmt.Sprintf("- `%s`", runConfigFile),
		fmt.Sprintf("- `%s`", freudMetricsJSON),
		fmt.Sprintf("- `%s`", stepIndexFile),
		fmt.Sprintf("- `%s`", trailIndexFile),
		fmt.Sprintf("- `%s`", trailFile),
		fmt.Sprintf("- `%s/`", stepMetaDir),
		"",
		"## Failed Steps",
	}

	if FileExists(stepsFile) {
		data, _ := os.ReadFile(stepsFile)
		failedCount := 0
		for _, line := range strings.Split(string(data), "\n") {
			cols := strings.Split(line, "\t")
			if len(cols) >= 4 && cols[1] == "fail" {
				failedCount++
				md = append(md, fmt.Sprintf("- `%s` (`%ss`) log: `%s`", cols[0], cols[2], cols[3]))
			}
		}
		if failedCount == 0 {
			md = append(md, "- none")
		}
	} else {
		md = append(md, "- steps.tsv missing")
	}
	md = append(md, "")

	// Trail preview
	md = append(md, "## Trail Preview")
	if FileExists(trailFile) {
		md = append(md, "```jsonl")
		md = append(md, HeadLines(trailFile, 12)...)
		md = append(md, "```")
	} else {
		md = append(md, "- trail.jsonl missing")
	}
	md = append(md, "")

	// Trail index preview
	md = append(md, "## Trail Index Preview")
	if FileExists(trailIndexFile) {
		md = append(md, "```text")
		md = append(md, HeadLines(trailIndexFile, 20)...)
		md = append(md, "```")
	} else {
		md = append(md, "- trail-index.tsv missing")
	}
	md = append(md, "")

	// Working tree diff
	md = append(md, "## Working Tree Diff")
	diffFailed := false
	diffFiles := ""
	if root != "" {
		cmd := exec.Command("git", "-C", root, "diff", "--name-only")
		out, err := cmd.Output()
		if err != nil {
			diffFailed = true
		} else {
			diffFiles = strings.TrimSpace(string(out))
		}
	} else {
		diffFailed = true
	}

	if diffFailed {
		md = append(md, "- git diff unavailable")
	} else if diffFiles != "" {
		md = append(md, "```text")
		lines := strings.Split(diffFiles, "\n")
		if len(lines) > 120 {
			lines = lines[:120]
		}
		md = append(md, lines...)
		md = append(md, "```")
	} else {
		md = append(md, "- clean working tree")
	}
	md = append(md, "")

	// Triage snapshot
	md = append(md, "## Triage Snapshot")
	if FileExists(anomaliesMD) {
		md = append(md, HeadLines(anomaliesMD, 120)...)
	} else {
		md = append(md, "- anomalies.md missing")
	}
	md = append(md, "")

	fid := or(featureID, "<feature-id>")
	md = append(md,
		"## How To Use",
		"1. Check the Run Snapshot above for pass/fail and which steps broke.",
		"2. Read files from **Start Here** in order (they are sorted cheapest-first).",
		"3. For each failed step, read its `step-meta/<step>.json` for command, timing, and first error ref.",
		"4. Only open raw `logs/<step>.log` if the indexed artifacts above are not enough.",
		fmt.Sprintf("5. After fixing, re-run: `./freud run %s`", fid),
	)

	os.WriteFile(contextMD, []byte(strings.Join(md, "\n")+"\n"), 0o644)
	return contextMD, nil
}
