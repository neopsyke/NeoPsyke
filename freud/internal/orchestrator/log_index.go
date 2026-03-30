package orchestrator

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
)

// LogCounts holds aggregate counts from a step log.
type LogCounts struct {
	Lines         int
	Warnings      int
	Errors        int
	Pressure      int
	FirstWarning  string
	FirstError    string
	FirstPressure string
}

// IndexStepLog scans a step log and writes a categorized TSV index.
// Returns aggregate counts for use in step-index.tsv.
func IndexStepLog(stepName, stepLogPath, logIndexDir string) (*LogCounts, error) {
	counts := &LogCounts{}

	if !analysis.FileExists(stepLogPath) {
		return counts, nil
	}

	counts.Lines = analysis.CountLines(stepLogPath)

	os.MkdirAll(logIndexDir, 0o755)
	indexPath := filepath.Join(logIndexDir, stepName+".tsv")
	indexFile, err := os.Create(indexPath)
	if err != nil {
		return counts, fmt.Errorf("creating log index: %w", err)
	}
	defer indexFile.Close()

	fmt.Fprintln(indexFile, "category\tline\tpreview")

	type catPattern struct {
		category string
		pattern  string
	}
	categories := []catPattern{
		{"warning", "warning"},
		{"error", "error|exception|failed"},
		{"pressure", "decision_pressure"},
		{"queue", "queue full|queue_saturation|step limit"},
		{"parse", "parse error|parse failure|non-parseable"},
	}

	for _, cat := range categories {
		raw := analysis.RgSearch(cat.pattern, stepLogPath, true, true, "")
		lines := analysis.NonEmptyLines(raw)

		for _, line := range lines {
			preview := line
			if len(preview) > 200 {
				preview = preview[:200]
			}
			fmt.Fprintf(indexFile, "%s\t%s\t%s\n",
				cat.category,
				analysis.TSVEscape(extractLineRef(line)),
				analysis.TSVEscape(preview))
		}

		switch cat.category {
		case "warning":
			counts.Warnings = len(lines)
			if len(lines) > 0 {
				counts.FirstWarning = extractLineRef(lines[0])
			}
		case "error":
			counts.Errors = len(lines)
			if len(lines) > 0 {
				counts.FirstError = extractLineRef(lines[0])
			}
		case "pressure":
			counts.Pressure = len(lines)
			if len(lines) > 0 {
				counts.FirstPressure = extractLineRef(lines[0])
			}
		}
	}

	return counts, nil
}

// extractLineRef extracts the file:linenum prefix from a ripgrep match line.
func extractLineRef(line string) string {
	parts := strings.SplitN(line, ":", 3)
	if len(parts) >= 2 {
		return parts[0] + ":" + parts[1]
	}
	return line
}
