package analysis

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Summarize generates a compact summary for a run directory.
// Returns the path to summary-compact.md.
func Summarize(runDir string) (string, error) {
	if !DirExists(runDir) {
		return "", fmt.Errorf("run directory does not exist: %s", runDir)
	}

	artifactDir := filepath.Join(runDir, "artifacts")
	summaryJSON := filepath.Join(artifactDir, "summary.json")
	stepsFile := filepath.Join(artifactDir, "step-index.tsv")
	trailFile := filepath.Join(artifactDir, "trail.jsonl")
	trailIndexFile := filepath.Join(artifactDir, "trail-index.tsv")
	runConfigFile := filepath.Join(artifactDir, "run-config.json")
	stepMetaDir := filepath.Join(artifactDir, "step-meta")
	freudMetricsJSON := filepath.Join(artifactDir, "freud-metrics.json")
	summaryMD := filepath.Join(artifactDir, "summary-compact.md")
	summaryCompactJSON := filepath.Join(artifactDir, "summary-compact.json")
	anomaliesMD := filepath.Join(artifactDir, "anomalies.md")

	featureID := ExtractJSONString(summaryJSON, "feature_id")
	runID := ExtractJSONString(summaryJSON, "run_id")
	status := ExtractJSONString(summaryJSON, "status")
	mode := ExtractJSONString(summaryJSON, "mode")
	stepsFailed := ExtractJSONNumber(summaryJSON, "steps_failed")
	failedTestCount := ExtractJSONNumber(summaryJSON, "failed_test_count")

	// First failed step
	firstFailedStep := ""
	if FileExists(stepsFile) {
		data, _ := os.ReadFile(stepsFile)
		lines := strings.Split(string(data), "\n")
		for _, line := range lines[1:] { // skip header
			cols := strings.Split(line, "\t")
			if len(cols) >= 2 && cols[1] == "fail" {
				firstFailedStep = cols[0]
				break
			}
		}
	}

	// Trail event count
	trailEvents := 0
	if FileExists(trailFile) {
		trailEvents = CountLines(trailFile)
	}

	// First warning
	topWarningLine := ""
	topSignalsPath := filepath.Join(artifactDir, "top-signals.tsv")
	if FileExists(topSignalsPath) {
		raw := RgSearch("warning", topSignalsPath, true, true, "")
		lines := NonEmptyLines(raw)
		if len(lines) > 0 {
			topWarningLine = lines[0]
		}
	}

	// Build summary-compact.md
	or := func(s, fallback string) string {
		if s == "" {
			return fallback
		}
		return s
	}

	md := []string{
		"# Freud Compact Summary",
		"",
		fmt.Sprintf("- run_id: `%s`", or(runID, "unknown")),
		fmt.Sprintf("- feature_id: `%s`", or(featureID, "unknown")),
		fmt.Sprintf("- status: `%s`", or(status, "unknown")),
		fmt.Sprintf("- mode: `%s`", or(mode, "unknown")),
		fmt.Sprintf("- steps_failed: `%s`", or(stepsFailed, "0")),
		fmt.Sprintf("- failed_test_count: `%s`", or(failedTestCount, "0")),
		fmt.Sprintf("- first_failed_step: `%s`", or(firstFailedStep, "none")),
		fmt.Sprintf("- trail_events: `%d`", trailEvents),
		"",
		"## Quick Files",
		fmt.Sprintf("- `%s`", filepath.Join(artifactDir, "summary.json")),
		fmt.Sprintf("- `%s`", filepath.Join(artifactDir, "failures.json")),
		fmt.Sprintf("- `%s`", filepath.Join(artifactDir, "anomalies.json")),
		fmt.Sprintf("- `%s`", runConfigFile),
		fmt.Sprintf("- `%s`", freudMetricsJSON),
		fmt.Sprintf("- `%s`", filepath.Join(artifactDir, "step-index.tsv")),
		fmt.Sprintf("- `%s`", trailIndexFile),
		fmt.Sprintf("- `%s`", filepath.Join(artifactDir, "trail.jsonl")),
		fmt.Sprintf("- `%s/`", stepMetaDir),
		"",
	}

	if FileExists(stepsFile) {
		md = append(md, "## Step Index Preview", "```text")
		md = append(md, HeadLines(stepsFile, 14)...)
		md = append(md, "```", "")
	}

	if FileExists(trailIndexFile) {
		md = append(md, "## Trail Index Preview", "```text")
		md = append(md, HeadLines(trailIndexFile, 14)...)
		md = append(md, "```", "")
	}

	if FileExists(freudMetricsJSON) {
		md = append(md, "## Freud Metrics Preview")
		md = append(md, HeadLines(freudMetricsJSON, 40)...)
		md = append(md, "")
	}

	if topWarningLine != "" {
		md = append(md, "## First Warning Ref",
			fmt.Sprintf("- `%s`", topWarningLine), "")
	}

	if FileExists(anomaliesMD) {
		md = append(md, "## Triage Preview")
		md = append(md, HeadLines(anomaliesMD, 40)...)
	}

	os.WriteFile(summaryMD, []byte(strings.Join(md, "\n")+"\n"), 0o644)

	// Build summary-compact.json
	sfVal := or(stepsFailed, "0")
	ftcVal := or(failedTestCount, "0")
	jsonLines := []string{
		"{",
		fmt.Sprintf(`  "run_id": "%s",`, JSONEscape(runID)),
		fmt.Sprintf(`  "feature_id": "%s",`, JSONEscape(featureID)),
		fmt.Sprintf(`  "status": "%s",`, JSONEscape(status)),
		fmt.Sprintf(`  "mode": "%s",`, JSONEscape(mode)),
		fmt.Sprintf(`  "steps_failed": %s,`, sfVal),
		fmt.Sprintf(`  "failed_test_count": %s,`, ftcVal),
		fmt.Sprintf(`  "first_failed_step": "%s",`, JSONEscape(firstFailedStep)),
		fmt.Sprintf(`  "trail_events": %d`, trailEvents),
		"}",
	}
	os.WriteFile(summaryCompactJSON, []byte(strings.Join(jsonLines, "\n")+"\n"), 0o644)

	return summaryMD, nil
}
