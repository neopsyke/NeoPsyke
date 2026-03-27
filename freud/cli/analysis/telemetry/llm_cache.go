package telemetry

import (
	"encoding/json"
	"fmt"

	"github.com/atomitl/neopsyke/freud/cli/analysis"
)

// LLMCacheTelemetry aggregates LLM cache hit/miss/divergence stats from a JSONL events file.
func LLMCacheTelemetry(inputPath string) error {
	if !analysis.FileExists(inputPath) {
		fmt.Printf(`{"error": "Event log not found: %s"}`, inputPath)
		fmt.Println()
		return fmt.Errorf("event log not found: %s", inputPath)
	}

	eventTypes := map[string]bool{
		"llm_cache_hit":        true,
		"llm_cache_miss":       true,
		"llm_cache_divergence": true,
	}
	events, err := analysis.LoadJSONLMultiEvents(inputPath, eventTypes)
	if err != nil {
		return err
	}

	var hits, misses, divergences []map[string]interface{}
	for _, e := range events {
		switch analysis.GetString(e, "type") {
		case "llm_cache_hit":
			hits = append(hits, e)
		case "llm_cache_miss":
			misses = append(misses, e)
		case "llm_cache_divergence":
			divergences = append(divergences, e)
		}
	}

	totalCached := len(hits)
	totalReal := len(misses)
	totalCalls := totalCached + totalReal
	hitRate := 0.0
	if totalCalls > 0 {
		hitRate = float64(totalCached) / float64(totalCalls) * 100
	}

	var divergencePoint interface{}
	divergenceActor := ""
	divergenceCallSite := ""
	if len(divergences) > 0 {
		data := analysis.GetData(divergences[0])
		if v, ok := data["sequence_index"]; ok {
			divergencePoint = v
		}
		divergenceActor = analysis.GetString(data, "actor")
		divergenceCallSite = analysis.GetString(data, "call_site")
	}

	// Hits by actor
	actorHits := map[string]int{}
	for _, e := range hits {
		actor := analysis.GetString(analysis.GetData(e), "actor")
		if actor == "" {
			actor = "unknown"
		}
		actorHits[actor]++
	}

	// Hints
	var hints []string
	if divergencePoint != nil {
		hints = append(hints, fmt.Sprintf(
			"Divergence at seq %v (%s/%s): code change may have affected this call path.",
			divergencePoint, divergenceActor, divergenceCallSite))
	}
	if totalCalls > 0 && totalCached == 0 {
		hints = append(hints, "No cache hits: this may be a first run (record mode) or the cache file is empty.")
	}
	if totalCalls > 0 && hitRate == 100.0 {
		hints = append(hints, "All calls served from cache: fully deterministic replay.")
	}

	result := map[string]interface{}{
		"total_calls":          totalCalls,
		"cached_calls":         totalCached,
		"real_calls":           totalReal,
		"hit_rate_percent":     round2(hitRate),
		"divergence_count":     len(divergences),
		"divergence_point":     divergencePoint,
		"divergence_actor":     divergenceActor,
		"divergence_call_site": divergenceCallSite,
		"hits_by_actor":        actorHits,
		"hints":                hints,
	}

	data, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(data))
	return nil
}

func round2(f float64) float64 {
	return float64(int(f*100+0.5)) / 100
}
