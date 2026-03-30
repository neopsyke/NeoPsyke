package orchestrator

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

const gradlePrimeMarker = ".build-cache-primed"

// PrimeGradleHome best-effort primes an isolated Gradle home with wrapper
// distributions and build plugins so first-use evals are less fragile.
func PrimeGradleHome(repoRoot, gradleHome string, verbose int) {
	if gradleHome == "" || repoRoot == "" {
		return
	}

	if err := os.MkdirAll(gradleHome, 0o755); err != nil {
		return
	}

	localDists := filepath.Join(gradleHome, "wrapper", "dists")
	homeDists := filepath.Join(os.Getenv("HOME"), ".gradle", "wrapper", "dists")
	if !hasGradleDist(localDists) && hasGradleDist(homeDists) {
		_ = os.MkdirAll(localDists, 0o755)
		matches, _ := filepath.Glob(filepath.Join(homeDists, "gradle-*-bin"))
		for _, match := range matches {
			_ = copyDir(match, filepath.Join(localDists, filepath.Base(match)))
		}
	}

	marker := filepath.Join(gradleHome, gradlePrimeMarker)
	if _, err := os.Stat(marker); err == nil {
		return
	}

	gradlew := filepath.Join(repoRoot, "gradlew")
	if _, err := os.Stat(gradlew); err != nil {
		return
	}

	if verbose > 0 {
		fmt.Printf("[freud] priming isolated Gradle home: %s\n", gradleHome)
	}
	cmd := exec.Command(gradlew, "--no-daemon", "--no-problems-report", "compileKotlin", "compileTestKotlin")
	cmd.Dir = repoRoot
	cmd.Env = append(os.Environ(), "GRADLE_USER_HOME="+gradleHome)
	cmd.Stdout = nil
	cmd.Stderr = nil
	if err := cmd.Run(); err == nil {
		_ = os.WriteFile(marker, []byte("ok\n"), 0o644)
	}
}

func hasGradleDist(root string) bool {
	matches, _ := filepath.Glob(filepath.Join(root, "gradle-*-bin"))
	return len(matches) > 0
}

func copyDir(src, dst string) error {
	entries, err := os.ReadDir(src)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dst, 0o755); err != nil {
		return err
	}
	for _, entry := range entries {
		srcPath := filepath.Join(src, entry.Name())
		dstPath := filepath.Join(dst, entry.Name())
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if entry.IsDir() {
			if err := copyDir(srcPath, dstPath); err != nil {
				return err
			}
			continue
		}
		data, err := os.ReadFile(srcPath)
		if err != nil {
			return err
		}
		if err := os.WriteFile(dstPath, data, info.Mode()); err != nil {
			return err
		}
	}
	return nil
}
