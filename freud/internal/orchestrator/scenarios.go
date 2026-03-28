package orchestrator

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
)

// ScenariosOpts configures a scenario pack run.
type ScenariosOpts struct {
	ManifestFile   string
	DryRun         bool
	GradleUserHome string
	RepoRoot       string
	Verbose        int
}

// ScenariosResult holds the output of a scenario pack run.
type ScenariosResult struct {
	Total    int
	Passed   int
	Failed   int
	ExitCode int
}

type scenarioManifest struct {
	Scenarios []scenarioEntry `json:"scenarios"`
}

type scenarioEntry struct {
	ID          string `json:"id"`
	Selector    string `json:"selector"`
	Description string `json:"description"`
}

// RunScenarios runs the scenario pack from a manifest file.
// Replaces run-scenarios.sh.
func RunScenarios(opts ScenariosOpts) (*ScenariosResult, error) {
	repoRoot := opts.RepoRoot
	if repoRoot == "" {
		var err error
		repoRoot, err = findRepoRoot()
		if err != nil {
			return nil, err
		}
	}

	manifestPath := ResolveAbsPath(opts.ManifestFile, repoRoot)

	PrimeGradleHome(repoRoot, ResolveAbsPath(opts.GradleUserHome, repoRoot), opts.Verbose)

	manifest, err := loadScenarioManifest(manifestPath)
	if err != nil {
		return nil, err
	}

	result := &ScenariosResult{Total: len(manifest.Scenarios)}

	testSrcDir := filepath.Join(repoRoot, "src", "test", "kotlin")

	for _, scenario := range manifest.Scenarios {
		fmt.Printf("scenario_id=%s selector=%s\n", scenario.ID, scenario.Selector)

		// Validate selector exists in test source
		if !selectorExists(scenario.Selector, testSrcDir) {
			fmt.Printf("status=fail description=%s (selector not found in test source)\n", scenario.Description)
			result.Failed++
			continue
		}

		if opts.DryRun {
			fmt.Printf("status=dry_run description=%s\n", scenario.Description)
			result.Passed++
			continue
		}

		// Run gradle test
		gradleArgs := []string{":test", "--tests", scenario.Selector, "--console=plain"}
		cmd := exec.Command(filepath.Join(repoRoot, "gradlew"), gradleArgs...)
		cmd.Dir = repoRoot
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr

		if opts.GradleUserHome != "" {
			cmd.Env = append(os.Environ(), "GRADLE_USER_HOME="+ResolveAbsPath(opts.GradleUserHome, repoRoot))
		}

		if opts.Verbose > 0 {
			fmt.Printf("[freud] exec: ./gradlew %s\n", strings.Join(gradleArgs, " "))
		}

		if err := cmd.Run(); err != nil {
			fmt.Printf("status=fail description=%s\n", scenario.Description)
			result.Failed++
		} else {
			fmt.Printf("status=pass description=%s\n", scenario.Description)
			result.Passed++
		}
	}

	fmt.Printf("scenarios_total=%d scenarios_passed=%d scenarios_failed=%d\n",
		result.Total, result.Passed, result.Failed)

	if result.Failed > 0 {
		result.ExitCode = 2
	}
	return result, nil
}

// selectorExists checks if a test selector (package.Class.method or Class) exists in test source.
func selectorExists(selector, testSrcDir string) bool {
	if !analysis.DirExists(testSrcDir) {
		return false
	}
	if strings.Contains(selector, "*") {
		return false
	}

	classPart := selector
	methodPart := ""
	if idx := strings.LastIndex(selector, "."); idx >= 0 {
		classPart = selector[:idx]
		methodPart = selector[idx+1:]
	}
	classShort := classPart
	if idx := strings.LastIndex(classPart, "."); idx >= 0 {
		classShort = classPart[idx+1:]
	}
	if classShort == "" || methodPart == "" || classShort == methodPart {
		return false
	}

	raw := analysis.RgSearch(
		fmt.Sprintf(`fun[[:space:]]+%s[[:space:]]*\(`, methodPart),
		testSrcDir,
		false,
		false,
		"",
	)
	for _, file := range analysis.NonEmptyLines(raw) {
		filePath := file
		if idx := strings.Index(file, ":"); idx >= 0 {
			filePath = file[:idx]
		}
		classRaw := analysis.RgSearch(
			fmt.Sprintf(`(class|object)[[:space:]]+%s\b`, classShort),
			filePath,
			false,
			false,
			"",
		)
		if len(analysis.NonEmptyLines(classRaw)) > 0 {
			return true
		}
	}
	return false
}

func loadScenarioManifest(manifestPath string) (scenarioManifest, error) {
	if strings.HasSuffix(manifestPath, ".json") {
		data, err := os.ReadFile(manifestPath)
		if err != nil {
			return scenarioManifest{}, fmt.Errorf("reading manifest %s: %w", manifestPath, err)
		}
		var manifest scenarioManifest
		if err := json.Unmarshal(data, &manifest); err != nil {
			return scenarioManifest{}, fmt.Errorf("parsing manifest %s: %w", manifestPath, err)
		}
		return manifest, nil
	}

	file, err := os.Open(manifestPath)
	if err != nil {
		return scenarioManifest{}, fmt.Errorf("reading manifest %s: %w", manifestPath, err)
	}
	defer file.Close()

	var manifest scenarioManifest
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.TrimSpace(line) == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "\t", 3)
		if len(parts) < 2 {
			return scenarioManifest{}, fmt.Errorf("invalid TSV scenario line: %q", line)
		}
		entry := scenarioEntry{
			ID:       parts[0],
			Selector: parts[1],
		}
		if len(parts) == 3 {
			entry.Description = parts[2]
		}
		manifest.Scenarios = append(manifest.Scenarios, entry)
	}
	if err := scanner.Err(); err != nil {
		return scenarioManifest{}, err
	}
	return manifest, nil
}
