package orchestrator

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// ReadJSONL reads a JSONL file, unmarshaling each line into T.
func ReadJSONL[T any](path string) ([]T, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("opening JSONL file %s: %w", path, err)
	}
	defer f.Close()

	var items []T
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 0, 1024*1024), 10*1024*1024)
	lineNum := 0

	for scanner.Scan() {
		lineNum++
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var item T
		if err := json.Unmarshal([]byte(line), &item); err != nil {
			return nil, fmt.Errorf("parsing JSONL line %d in %s: %w", lineNum, path, err)
		}
		items = append(items, item)
	}

	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("reading JSONL file %s: %w", path, err)
	}

	return items, nil
}
