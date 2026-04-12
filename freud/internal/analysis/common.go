package analysis

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// JSONEscape escapes a string for safe embedding inside a JSON string literal.
func JSONEscape(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, `"`, `\"`)
	value = strings.ReplaceAll(value, "\n", `\n`)
	value = strings.ReplaceAll(value, "\r", "")
	value = strings.ReplaceAll(value, "\t", `\t`)
	return value
}

// TSVEscape collapses newlines, carriage returns and tabs to spaces.
func TSVEscape(value string) string {
	value = strings.ReplaceAll(value, "\n", " ")
	value = strings.ReplaceAll(value, "\r", " ")
	value = strings.ReplaceAll(value, "\t", " ")
	return value
}

// ExtractJSONString extracts a string value from a simple JSON file using regex.
func ExtractJSONString(filepath string, key string) string {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return ""
	}
	pattern := regexp.MustCompile(`^\s*"` + regexp.QuoteMeta(key) + `"\s*:\s*"(.*)"\s*,?\s*$`)
	for _, line := range strings.Split(string(data), "\n") {
		if m := pattern.FindStringSubmatch(line); m != nil {
			return m[1]
		}
	}
	return ""
}

// ExtractJSONNumber extracts an integer value from a simple JSON file using regex.
func ExtractJSONNumber(filepath string, key string) string {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return ""
	}
	pattern := regexp.MustCompile(`^\s*"` + regexp.QuoteMeta(key) + `"\s*:\s*([0-9]+)\s*,?\s*$`)
	for _, line := range strings.Split(string(data), "\n") {
		if m := pattern.FindStringSubmatch(line); m != nil {
			return m[1]
		}
	}
	return ""
}

// Pct returns a percentage string with 2 decimal places.
func Pct(numerator, denominator int) string {
	if denominator == 0 {
		return "0.00"
	}
	return fmt.Sprintf("%.2f", float64(numerator)*100.0/float64(denominator))
}

// CountLines counts non-empty lines in a file.
func CountLines(filepath string) int {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return 0
	}
	count := 0
	for _, line := range strings.Split(string(data), "\n") {
		if strings.TrimSpace(line) != "" {
			count++
		}
	}
	return count
}

// HeadLines returns the first n lines of a file.
func HeadLines(filepath string, n int) []string {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return nil
	}
	lines := strings.Split(string(data), "\n")
	if len(lines) > n {
		lines = lines[:n]
	}
	return lines
}

// ReadFileText reads an entire file as text.
func ReadFileText(filepath string) string {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return ""
	}
	return string(data)
}

// NonEmptyLines splits text into lines and filters out empty ones.
func NonEmptyLines(text string) []string {
	var result []string
	for _, line := range strings.Split(text, "\n") {
		if line != "" {
			result = append(result, line)
		}
	}
	return result
}

// RgSearch runs ripgrep and returns raw output.
func RgSearch(pattern, directory string, caseInsensitive, lineNumbers bool, invertMatch string) string {
	args := []string{}
	if caseInsensitive {
		args = append(args, "-i")
	}
	if lineNumbers {
		args = append(args, "-n", "-H")
	}
	args = append(args, "-e", pattern, directory)

	cmd := exec.Command("rg", args...)
	output, err := cmd.Output()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			if exitErr.ExitCode() == 1 {
				// No matches — normal
				return ""
			}
		}
		return ""
	}

	result := string(output)

	if invertMatch != "" && result != "" {
		cmd2 := exec.Command("rg", "-v", invertMatch)
		cmd2.Stdin = strings.NewReader(result)
		out2, err := cmd2.Output()
		if err == nil {
			result = string(out2)
		}
	}

	return result
}

// WriteJSONFile writes a map as formatted JSON to a file.
func WriteJSONFile(filePath string, obj interface{}) error {
	dir := filepath.Dir(filePath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(obj, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filePath, append(data, '\n'), 0o644)
}

// UTCNowISO returns current UTC time as ISO 8601 string.
func UTCNowISO() string {
	return time.Now().UTC().Format("2006-01-02T15:04:05Z")
}

// LoadJSONLEvents reads a JSONL file and returns events matching the given type.
func LoadJSONLEvents(path string, eventType string) ([]map[string]interface{}, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	var events []map[string]interface{}
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 0, 1024*1024), 10*1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var obj map[string]interface{}
		if err := json.Unmarshal([]byte(line), &obj); err != nil {
			continue
		}
		if t, ok := obj["type"].(string); ok && t == eventType {
			events = append(events, obj)
		}
	}
	return events, scanner.Err()
}

// LoadJSONLMultiEvents reads a JSONL file and returns events matching any of the given types.
func LoadJSONLMultiEvents(path string, eventTypes map[string]bool) ([]map[string]interface{}, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	var events []map[string]interface{}
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 0, 1024*1024), 10*1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var obj map[string]interface{}
		if err := json.Unmarshal([]byte(line), &obj); err != nil {
			continue
		}
		if t, ok := obj["type"].(string); ok && eventTypes[t] {
			events = append(events, obj)
		}
	}
	return events, scanner.Err()
}

// FileExists checks if a file exists and is not a directory.
func FileExists(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return !info.IsDir()
}

// DirExists checks if a directory exists.
func DirExists(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return info.IsDir()
}

// RepoRoot finds the repository root by walking up to find .git.
func RepoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, ".git")); err == nil {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("not inside a git repository")
		}
		dir = parent
	}
}

// GetData extracts the "data" sub-map from an event.
func GetData(event map[string]interface{}) map[string]interface{} {
	if data, ok := event["data"].(map[string]interface{}); ok {
		return data
	}
	return map[string]interface{}{}
}

// GetString extracts a string value from a map.
func GetString(m map[string]interface{}, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

// GetBool extracts a bool value from a map.
func GetBool(m map[string]interface{}, key string) (bool, bool) {
	v, ok := m[key].(bool)
	return v, ok
}

// GetInt extracts an int value from a JSON-decoded map (which uses float64).
func GetInt(m map[string]interface{}, key string) (int, bool) {
	if v, ok := m[key].(float64); ok {
		return int(v), true
	}
	return 0, false
}
