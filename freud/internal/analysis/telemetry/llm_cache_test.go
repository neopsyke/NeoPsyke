package telemetry

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLLMCacheTelemetryToFile(t *testing.T) {
	dir := t.TempDir()

	// Create fixture events.jsonl
	events := []string{
		`{"type":"llm_cache_hit","data":{"actor":"planner","sequence_index":0}}`,
		`{"type":"llm_cache_hit","data":{"actor":"planner","sequence_index":1}}`,
		`{"type":"llm_cache_miss","data":{"actor":"superego","sequence_index":2}}`,
	}
	eventsPath := filepath.Join(dir, "events.jsonl")
	os.WriteFile(eventsPath, []byte(strings.Join(events, "\n")+"\n"), 0o644)

	outputPath := filepath.Join(dir, "cache-stats.json")
	err := LLMCacheTelemetryToFile(eventsPath, outputPath)
	if err != nil {
		t.Fatalf("LLMCacheTelemetryToFile failed: %v", err)
	}

	data, err := os.ReadFile(outputPath)
	if err != nil {
		t.Fatalf("reading output: %v", err)
	}

	var result map[string]interface{}
	json.Unmarshal(data, &result)

	if result["total_calls"].(float64) != 3 {
		t.Errorf("expected 3 total calls, got %v", result["total_calls"])
	}
	if result["cached_calls"].(float64) != 2 {
		t.Errorf("expected 2 cached calls, got %v", result["cached_calls"])
	}
	if result["real_calls"].(float64) != 1 {
		t.Errorf("expected 1 real call, got %v", result["real_calls"])
	}
}

func TestLLMCacheTelemetryToFileDivergence(t *testing.T) {
	dir := t.TempDir()

	events := []string{
		`{"type":"llm_cache_hit","data":{"actor":"planner"}}`,
		`{"type":"llm_cache_divergence","data":{"actor":"planner","call_site":"decide","sequence_index":1}}`,
		`{"type":"llm_cache_miss","data":{"actor":"planner"}}`,
	}
	eventsPath := filepath.Join(dir, "events.jsonl")
	os.WriteFile(eventsPath, []byte(strings.Join(events, "\n")+"\n"), 0o644)

	outputPath := filepath.Join(dir, "cache-stats.json")
	LLMCacheTelemetryToFile(eventsPath, outputPath)

	data, _ := os.ReadFile(outputPath)
	var result map[string]interface{}
	json.Unmarshal(data, &result)

	if result["divergence_count"].(float64) != 1 {
		t.Errorf("expected 1 divergence, got %v", result["divergence_count"])
	}
	if result["divergence_actor"] != "planner" {
		t.Errorf("expected divergence_actor=planner, got %v", result["divergence_actor"])
	}
}

func TestLLMCacheTelemetryToFileMissing(t *testing.T) {
	err := LLMCacheTelemetryToFile("/nonexistent", "/tmp/out.json")
	if err == nil {
		t.Error("expected error for missing file")
	}
}

func TestSessionReplayTelemetryToFile(t *testing.T) {
	dir := t.TempDir()

	events := []string{
		`{"type":"session_channel_replay_hit","data":{"channel":"signals"}}`,
		`{"type":"session_channel_replay_hit","data":{"channel":"signals"}}`,
		`{"type":"session_channel_replay_hit","data":{"channel":"memory-recall"}}`,
	}
	eventsPath := filepath.Join(dir, "events.jsonl")
	os.WriteFile(eventsPath, []byte(strings.Join(events, "\n")+"\n"), 0o644)

	outputPath := filepath.Join(dir, "session-stats.json")
	err := SessionReplayTelemetryToFile(eventsPath, outputPath)
	if err != nil {
		t.Fatalf("SessionReplayTelemetryToFile failed: %v", err)
	}

	data, _ := os.ReadFile(outputPath)
	var result map[string]interface{}
	json.Unmarshal(data, &result)

	if result["total_replay_hits"].(float64) != 3 {
		t.Errorf("expected 3 hits, got %v", result["total_replay_hits"])
	}
	if result["total_divergences"].(float64) != 0 {
		t.Errorf("expected 0 divergences, got %v", result["total_divergences"])
	}
}

func TestSessionReplayTelemetryToFileDivergence(t *testing.T) {
	dir := t.TempDir()

	events := []string{
		`{"type":"session_channel_replay_hit","data":{"channel":"signals"}}`,
		`{"type":"session_channel_divergence","data":{"channel":"signals","sequence_index":1,"expected_hash":"abc","actual_hash":"def"}}`,
	}
	eventsPath := filepath.Join(dir, "events.jsonl")
	os.WriteFile(eventsPath, []byte(strings.Join(events, "\n")+"\n"), 0o644)

	outputPath := filepath.Join(dir, "session-stats.json")
	SessionReplayTelemetryToFile(eventsPath, outputPath)

	data, _ := os.ReadFile(outputPath)
	var result map[string]interface{}
	json.Unmarshal(data, &result)

	if result["total_divergences"].(float64) != 1 {
		t.Errorf("expected 1 divergence, got %v", result["total_divergences"])
	}
}
