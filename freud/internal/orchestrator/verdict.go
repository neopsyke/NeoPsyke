package orchestrator

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
)

// Verdict represents the result of a live eval run.
type Verdict struct {
	Verdict        string `json:"verdict"`
	Detail         string `json:"detail"`
	FailureClass   string `json:"failure_class"`
	ExitCode       int    `json:"exit_code"`
	DurationSec    int    `json:"duration_seconds"`
	CacheMode      string `json:"cache_mode"`
	CacheFile      string `json:"cache_file"`
	TimeoutSec     int    `json:"timeout_seconds"`
	PreserveMemory bool   `json:"preserve_memory"`
	RunDir         string `json:"run_dir"`
	ArtifactsDir   string `json:"artifacts_dir"`
	LogsDir        string `json:"logs_dir"`
	AnswerFile     string `json:"answer_file"`
	InputFile      string `json:"input_file"`
}

var bootstrapPatterns = regexp.MustCompile(
	`(?i)(Gradle could not start|Could not create service|Operation not permitted|` +
		`Permission denied|Address already in use|No such file|command not found|` +
		`not executable|Unable to start|failed to launch|bootstrap|sandbox)`)

var providerPatterns = regexp.MustCompile(
	`(?i)(401|403|429|rate limit|quota|authentication|unauthorized|forbidden|` +
		`api key|provider unavailable|model unavailable|service unavailable|` +
		`bad gateway|upstream|provider error)`)

// ClassifyFailure determines the failure class from exit code and log contents.
func ClassifyFailure(exitCode int, stderrFile, appLogFile string) string {
	if exitCode == 2 {
		return "timeout"
	}

	// Search stderr and app log for patterns
	var logContent string
	if data, err := os.ReadFile(stderrFile); err == nil {
		logContent += string(data)
	}
	if data, err := os.ReadFile(appLogFile); err == nil {
		logContent += string(data)
	}

	if bootstrapPatterns.MatchString(logContent) {
		return "local_runtime_bootstrap_failure"
	}
	if providerPatterns.MatchString(logContent) {
		return "provider_model_failure"
	}

	return "live_eval_process_failure"
}

// ScoreVerdict evaluates the run result and produces a Verdict.
func ScoreVerdict(exitCode int, expectedFile, answerFile, stderrFile, appLogFile string) Verdict {
	v := Verdict{
		Verdict:  "unknown",
		ExitCode: exitCode,
	}

	if exitCode == 0 {
		if expectedFile != "" {
			expectedData, err1 := os.ReadFile(expectedFile)
			actualData, err2 := os.ReadFile(answerFile)

			if err1 == nil && err2 == nil {
				expected := NormalizeAnswer(string(expectedData), false)
				actual := NormalizeAnswer(string(actualData), false)

				if expected == actual {
					v.Verdict = "pass"
					v.Detail = "answer matches expected"
				} else {
					v.Verdict = "fail"
					v.Detail = fmt.Sprintf("answer mismatch: expected=%q, actual=%q", expected, actual)
					v.FailureClass = "live_eval_scoring_failure"
					v.ExitCode = 1
				}
			} else {
				v.Verdict = "error"
				v.Detail = "failed to read expected or answer file"
				v.FailureClass = "live_eval_process_failure"
			}
		} else {
			v.Verdict = "pass"
			v.Detail = "answer delivered (no expected file)"
		}
	} else if exitCode == 2 {
		v.Verdict = "timeout"
		v.Detail = "evaluation timed out"
		v.FailureClass = "timeout"
	} else {
		v.Verdict = "error"
		v.FailureClass = ClassifyFailure(exitCode, stderrFile, appLogFile)
		v.Detail = fmt.Sprintf("process exited with code %d, class=%s", exitCode, v.FailureClass)
	}

	return v
}

// WriteVerdictJSON writes verdict.json to the artifacts directory.
func WriteVerdictJSON(v Verdict, artifactsDir string) error {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(artifactsDir, "verdict.json"), append(data, '\n'), 0o644)
}

// ReadVerdictJSON reads and parses a verdict.json file.
func ReadVerdictJSON(artifactsDir string) (*Verdict, error) {
	data, err := os.ReadFile(filepath.Join(artifactsDir, "verdict.json"))
	if err != nil {
		return nil, err
	}
	var v Verdict
	if err := json.Unmarshal(data, &v); err != nil {
		return nil, err
	}
	return &v, nil
}

// SchemaDowngradeCount searches logs for structured-output schema downgrade patterns.
func SchemaDowngradeCount(logsDir string) int {
	if !analysis.DirExists(logsDir) {
		return 0
	}
	pattern := `Structured-output schema adapted.*schema=(ego_planner_decision|meta_reasoner_assessment).*(strict downgraded to false|JSON schema output disabled)`
	raw := analysis.RgSearch(pattern, logsDir, false, false, "")
	lines := analysis.NonEmptyLines(raw)
	return len(lines)
}

// BuildVerdictDetail creates a human-readable detail string for BBH cases.
func BuildVerdictDetail(status, expected, actual string) string {
	switch status {
	case "pass":
		return "exact match"
	case "timeout":
		return "evaluation timed out"
	case "schema_downgrade":
		return "structured-output schema downgraded"
	case "scoring_failure":
		return fmt.Sprintf("mismatch: expected=%q actual=%q", expected, actual)
	default:
		return status
	}
}

// ReadAnswerFile reads and trims the contents of an answer file.
func ReadAnswerFile(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}
