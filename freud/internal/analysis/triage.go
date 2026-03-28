package analysis

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var triagePatterns = []struct {
	ID    string
	Regex string
}{
	{"planner_output_repaired", "planner_output_repaired|output_repaired"},
	{"parse_failures", "non-parseable|failed to parse|parse fallback|parse error|parse failure"},
	{"forced_terminal", "forced terminal|forced_terminal_answer"},
	{"queue_saturation", "queue full|queue_saturation|step limit reached"},
	{"policy_denials", "action denied|denied by superego|policy denied"},
	{"provider_failures", "timeout|unavailable|provider check failed"},
	{"cache_divergence", "llm_cache_divergence"},
}

var ignoredSignalRegexes = []*regexp.Regexp{
	regexp.MustCompile(`(?i)scenario_id=\S+\s+selector=\S+$`),
	regexp.MustCompile(`(?i)status=pass\b`),
	regexp.MustCompile(`(?i)scenarios_total=.*scenarios_failed=0$`),
	regexp.MustCompile(`(?i)BUILD SUCCESSFUL`),
	regexp.MustCompile(`(?i)actionable tasks:`),
}

type patternResult struct {
	ID     string
	Count  int
	Sample string
}

// Triage runs heuristic triage on a run directory.
// Returns the path to the generated anomalies.json.
func Triage(runDir string, topN int) (string, error) {
	if !DirExists(runDir) {
		return "", fmt.Errorf("run directory does not exist: %s", runDir)
	}

	logsDir := filepath.Join(runDir, "logs")
	artifactDir := filepath.Join(runDir, "artifacts")
	if err := os.MkdirAll(artifactDir, 0o755); err != nil {
		return "", err
	}
	summaryJSON := filepath.Join(artifactDir, "summary.json")

	// Record patterns
	var results []patternResult
	for _, p := range triagePatterns {
		r := recordPattern(p.ID, p.Regex, logsDir)
		results = append(results, r)
	}

	// Write pattern-counts.tsv
	var tsvLines []string
	for _, r := range results {
		tsvLines = append(tsvLines, fmt.Sprintf("%s\t%d\t%s", r.ID, r.Count, r.Sample))
	}
	os.WriteFile(filepath.Join(artifactDir, "pattern-counts.tsv"),
		[]byte(strings.Join(tsvLines, "\n")+"\n"), 0o644)

	// Top signals & pressure
	var topLines, pressureLines []string
	firstFailLine := ""

	if DirExists(logsDir) {
		rawTop := RgSearch("error|exception|failed|warning", logsDir, true, true, ":> Task :")
		topLines = filterIgnoredSignals(NonEmptyLines(rawTop))
		if len(topLines) > topN {
			topLines = topLines[:topN]
		}

		rawPressure := RgSearch(`decision_pressure=[0-9]+\.[0-9]+`, logsDir, false, true, "")
		pressureLines = NonEmptyLines(rawPressure)

		rawFail := RgSearch("error|exception|failed|traceback|assert", logsDir, true, true, ":> Task :")
		failLines := filterIgnoredSignals(NonEmptyLines(rawFail))
		if len(failLines) > 0 {
			firstFailLine = failLines[0]
		}
	}

	os.WriteFile(filepath.Join(artifactDir, "top-signals.tsv"),
		[]byte(strings.Join(topLines, "\n")+"\n"), 0o644)
	os.WriteFile(filepath.Join(artifactDir, "pressure-signals.tsv"),
		[]byte(strings.Join(pressureLines, "\n")+"\n"), 0o644)

	// First failing trace snippet
	firstFailureText := ""
	if firstFailLine != "" {
		parts := strings.SplitN(firstFailLine, ":", 3)
		if len(parts) >= 2 {
			fileRef := parts[0]
			lineStr := parts[1]
			if FileExists(fileRef) {
				if lineNum, err := strconv.Atoi(lineStr); err == nil {
					ctx := contextAround(fileRef, lineNum, 2)
					firstFailureText = firstFailLine + "\n---\n" + ctx
				} else {
					firstFailureText = firstFailLine
				}
			} else {
				firstFailureText = firstFailLine
			}
		} else {
			firstFailureText = firstFailLine
		}
	}
	os.WriteFile(filepath.Join(artifactDir, "first-failing-trace.txt"),
		[]byte(firstFailureText), 0o644)

	// Pressure analysis
	maxPressureVal := ""
	maxPressureRef := ""
	firstPressure075 := ""
	firstPressure090 := ""

	pressureRe := regexp.MustCompile(`decision_pressure=([0-9]+\.[0-9]+)`)
	for _, line := range pressureLines {
		m := pressureRe.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		value := m[1]
		if maxPressureVal == "" || compareGT(value, maxPressureVal) {
			maxPressureVal = value
			maxPressureRef = line
		}
		if firstPressure075 == "" && compareGE(value, 0.75) {
			firstPressure075 = line
		}
		if firstPressure090 == "" && compareGE(value, 0.90) {
			firstPressure090 = line
		}
	}

	// Write anomalies.json
	generatedAt := UTCNowISO()
	anomaliesPath := filepath.Join(artifactDir, "anomalies.json")

	var patternEntries []string
	for _, r := range results {
		patternEntries = append(patternEntries, fmt.Sprintf(
			`    {"id":"%s","count":%d,"sample":"%s"}`,
			JSONEscape(r.ID), r.Count, JSONEscape(r.Sample)))
	}

	var topSignalEntries []string
	for _, line := range topLines {
		if line != "" {
			topSignalEntries = append(topSignalEntries, fmt.Sprintf(`    "%s"`, JSONEscape(line)))
		}
	}

	jsonLines := []string{
		"{",
		fmt.Sprintf(`  "generated_at": "%s",`, generatedAt),
		fmt.Sprintf(`  "run_dir": "%s",`, JSONEscape(runDir)),
		`  "pattern_counts": [`,
		strings.Join(patternEntries, ",\n"),
		"  ],",
		`  "pressure": {`,
		fmt.Sprintf(`    "max": {"value": "%s", "ref": "%s"},`, JSONEscape(maxPressureVal), JSONEscape(maxPressureRef)),
		fmt.Sprintf(`    "first_ge_075": "%s",`, JSONEscape(firstPressure075)),
		fmt.Sprintf(`    "first_ge_090": "%s"`, JSONEscape(firstPressure090)),
		"  },",
		fmt.Sprintf(`  "first_failing_trace": "%s",`, JSONEscape(firstFailureText)),
		`  "top_signals": [`,
		strings.Join(topSignalEntries, ",\n"),
		"  ]",
		"}",
	}
	os.WriteFile(anomaliesPath, []byte(strings.Join(jsonLines, "\n")+"\n"), 0o644)

	// Write anomalies.md
	runStatus := ExtractJSONString(summaryJSON, "status")
	md := []string{
		"# Anomaly Triage",
		"",
		fmt.Sprintf("- Run dir: `%s`", runDir),
		fmt.Sprintf("- Generated at: `%s`", generatedAt),
		"",
		"## Signal Counts",
	}

	if runStatus == "pass" {
		md = append(md, "",
			"Interpretation for pass runs:",
			"- These counts are informational signal hits, not failures by themselves.",
			"- On deterministic scenario packs, some hits can reflect exercised behaviors described in passing scenario output.",
			"")
	} else if runStatus == "fail" {
		md = append(md, "",
			"Interpretation for fail runs:",
			"- These counts are heuristic signal hits to guide triage, not a final diagnosis on their own.",
			"- Prioritize the first failed step and failing log references above these aggregate counts.",
			"")
	}

	for _, r := range results {
		md = append(md, fmt.Sprintf("- `%s`: %d", r.ID, r.Count))
		if r.Sample != "" {
			md = append(md, fmt.Sprintf("  sample: `%s`", r.Sample))
		}
	}

	md = append(md, "", "## Pressure Spikes")
	if maxPressureVal != "" {
		md = append(md, fmt.Sprintf("- max decision pressure: `%s`", maxPressureVal))
		md = append(md, fmt.Sprintf("  ref: `%s`", maxPressureRef))
	} else {
		md = append(md, "- max decision pressure: `none`")
	}
	if firstPressure075 != "" {
		md = append(md, fmt.Sprintf("- first >= 0.75: `%s`", firstPressure075))
	} else {
		md = append(md, "- first >= 0.75: none")
	}
	if firstPressure090 != "" {
		md = append(md, fmt.Sprintf("- first >= 0.90: `%s`", firstPressure090))
	} else {
		md = append(md, "- first >= 0.90: none")
	}

	md = append(md, "", "## First Failing Trace Snippet")
	if firstFailureText != "" {
		md = append(md, "```text", firstFailureText, "```")
	} else {
		md = append(md, "- none")
	}

	md = append(md, "", "## Top Signals")
	if len(topLines) > 0 {
		for _, line := range topLines {
			if line != "" {
				md = append(md, fmt.Sprintf("- `%s`", line))
			}
		}
	} else {
		md = append(md, "- none")
	}

	os.WriteFile(filepath.Join(artifactDir, "anomalies.md"),
		[]byte(strings.Join(md, "\n")+"\n"), 0o644)

	return anomaliesPath, nil
}

func recordPattern(id, regex, logsDir string) patternResult {
	if !DirExists(logsDir) {
		return patternResult{ID: id}
	}
	raw := RgSearch(regex, logsDir, true, true, "")
	lines := NonEmptyLines(raw)
	sample := ""
	if len(lines) > 0 {
		sample = lines[0]
	}
	return patternResult{ID: id, Count: len(lines), Sample: sample}
}

func filterIgnoredSignals(lines []string) []string {
	var result []string
	for _, line := range lines {
		ignored := false
		for _, re := range ignoredSignalRegexes {
			if re.MatchString(line) {
				ignored = true
				break
			}
		}
		if !ignored {
			result = append(result, line)
		}
	}
	return result
}

func contextAround(filepath string, lineNum, context int) string {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return ""
	}
	allLines := strings.Split(string(data), "\n")
	start := lineNum - 1 - context
	if start < 0 {
		start = 0
	}
	end := lineNum + context
	if end > len(allLines) {
		end = len(allLines)
	}
	var numbered []string
	for i := start; i < end; i++ {
		numbered = append(numbered, fmt.Sprintf("  %d\t%s", i+1, allLines[i]))
	}
	return strings.Join(numbered, "\n")
}

func compareGT(a, b string) bool {
	af, err1 := strconv.ParseFloat(a, 64)
	bf, err2 := strconv.ParseFloat(b, 64)
	if err1 != nil || err2 != nil {
		return false
	}
	return af > bf
}

func compareGE(a string, threshold float64) bool {
	af, err := strconv.ParseFloat(a, 64)
	if err != nil {
		return false
	}
	return af >= threshold
}
