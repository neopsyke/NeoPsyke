package orchestrator

import (
	"bufio"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// CreateRunDir creates a timestamped run directory with the given suffix pattern.
// subdirs are created inside the run directory (e.g., "logs", "artifacts", "state").
func CreateRunDir(runRoot, suffix string, subdirs []string) (string, error) {
	if err := os.MkdirAll(runRoot, 0o755); err != nil {
		return "", fmt.Errorf("creating run root %s: %w", runRoot, err)
	}

	ts := time.Now().UTC().Format("20060102T150405Z")
	pattern := ts + "-" + suffix + "-"
	runDir, err := os.MkdirTemp(runRoot, pattern)
	if err != nil {
		return "", fmt.Errorf("creating run dir: %w", err)
	}

	for _, sub := range subdirs {
		if err := os.MkdirAll(filepath.Join(runDir, sub), 0o755); err != nil {
			return "", fmt.Errorf("creating subdir %s: %w", sub, err)
		}
	}

	return runDir, nil
}

// RunIndexEntry represents one line in the run index.
type RunIndexEntry struct {
	Timestamp string
	FeatureID string
	RunDir    string
	Status    string
}

// AppendRunIndex appends an entry to the run-index.tsv file in the run root.
// Uses O_APPEND for atomic concurrent writes.
func AppendRunIndex(runRoot string, entry RunIndexEntry) error {
	indexPath := filepath.Join(runRoot, "run-index.tsv")

	f, err := os.OpenFile(indexPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return fmt.Errorf("opening run index: %w", err)
	}
	defer f.Close()

	line := fmt.Sprintf("%s\t%s\t%s\t%s\n",
		entry.Timestamp, entry.FeatureID, entry.RunDir, entry.Status)
	_, err = f.WriteString(line)
	return err
}

// LatestRunDir reads the last entry from run-index.tsv and returns the run directory.
// Optionally filters by featureID if non-empty.
func LatestRunDir(featureID ...string) (string, error) {
	root, err := findRepoRoot()
	if err != nil {
		return "", err
	}

	runRoot := filepath.Join(root, ".neopsyke", "runs", "freud")
	indexPath := filepath.Join(runRoot, "run-index.tsv")

	f, err := os.Open(indexPath)
	if err != nil {
		return "", fmt.Errorf("no run index found at %s", indexPath)
	}
	defer f.Close()

	filter := ""
	if len(featureID) > 0 && featureID[0] != "" {
		filter = featureID[0]
	}

	// Read all lines, keep the last matching one
	var lastDir string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) < 3 {
			continue
		}
		if filter != "" && cols[1] != filter {
			continue
		}
		lastDir = cols[2]
	}

	if lastDir == "" {
		if filter != "" {
			return "", fmt.Errorf("no run found for feature %q in run index", filter)
		}
		return "", fmt.Errorf("run index is empty")
	}

	if !filepath.IsAbs(lastDir) {
		lastDir = filepath.Join(root, lastDir)
	}
	return lastDir, nil
}

// ResolveAbsPath resolves a possibly-relative path against repoRoot.
func ResolveAbsPath(path, repoRoot string) string {
	if filepath.IsAbs(path) {
		return path
	}
	return filepath.Join(repoRoot, path)
}

// CleanupOldRuns deletes run directories older than retentionDays.
// If dirPrefix is empty, all dirs in runRoot are candidates.
// If dirPrefix is non-empty, only dirs containing that prefix are candidates.
// Returns the number of directories cleaned up.
func CleanupOldRuns(runRoot string, retentionDays int, dirPrefix string) (int, error) {
	if retentionDays <= 0 {
		return 0, nil
	}

	entries, err := os.ReadDir(runRoot)
	if err != nil {
		return 0, nil // non-fatal
	}

	cutoff := time.Now().Add(-time.Duration(retentionDays) * 24 * time.Hour)
	cleaned := 0

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		name := entry.Name()

		if dirPrefix != "" && !strings.Contains(name, dirPrefix) {
			continue
		}

		info, err := entry.Info()
		if err != nil {
			continue
		}
		if info.ModTime().After(cutoff) {
			continue
		}

		dirPath := filepath.Join(runRoot, name)

		// Best-effort pgvector namespace cleanup
		nsFile := filepath.Join(dirPath, "state", "pgvector-namespace.txt")
		if data, err := os.ReadFile(nsFile); err == nil {
			ns := strings.TrimSpace(string(data))
			if ns != "" {
				cleanupPgvectorNamespace(ns)
			}
		}

		if err := os.RemoveAll(dirPath); err == nil {
			cleaned++
		}
	}

	return cleaned, nil
}

// CleanupAllOldRuns cleans up all run dirs older than retentionDays regardless of prefix.
func CleanupAllOldRuns(runRoot string, retentionDays int) (int, error) {
	return CleanupOldRuns(runRoot, retentionDays, "")
}

func cleanupPgvectorNamespace(namespace string) {
	baseURL := os.Getenv("NEOPSYKE_MEMORY_DEFAULT_BASE_URL")
	if baseURL == "" {
		baseURL = "http://localhost:6333"
	}
	url := baseURL + "/collections/" + namespace

	req, err := http.NewRequest("DELETE", url, nil)
	if err != nil {
		return
	}

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return
	}
	resp.Body.Close()
}
