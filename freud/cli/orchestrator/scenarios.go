package orchestrator

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/atomitl/neopsyke/freud/cli/analysis"
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

	// Load manifest
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		return nil, fmt.Errorf("reading manifest %s: %w", manifestPath, err)
	}

	var manifest scenarioManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return nil, fmt.Errorf("parsing manifest %s: %w", manifestPath, err)
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

	// Extract short class name from fully-qualified selector like "ai.neopsyke.eval.FakeTest.testOne"
	// The class name is the last segment that starts with an uppercase letter.
	parts := strings.Split(selector, ".")
	className := ""
	for i := len(parts) - 1; i >= 0; i-- {
		if len(parts[i]) > 0 && parts[i][0] >= 'A' && parts[i][0] <= 'Z' {
			className = parts[i]
			break
		}
	}
	if className == "" {
		// Fallback: use last dot-separated segment
		className = parts[len(parts)-1]
	}

	// Search for class definition in test source using grep (not rg, for portability in tests)
	raw := analysis.RgSearch(fmt.Sprintf("class %s", className), testSrcDir, false, false, "")
	if len(analysis.NonEmptyLines(raw)) > 0 {
		return true
	}

	// Fallback: search for file named ClassName.kt
	matches, _ := filepath.Glob(filepath.Join(testSrcDir, "**", className+".kt"))
	if len(matches) > 0 {
		return true
	}
	// Also check directly in test src dir
	if analysis.FileExists(filepath.Join(testSrcDir, className+".kt")) {
		return true
	}

	return false
}
