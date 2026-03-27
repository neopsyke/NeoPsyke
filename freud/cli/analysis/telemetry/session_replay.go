package telemetry

import (
	"encoding/json"
	"fmt"

	"github.com/atomitl/neopsyke/freud/cli/analysis"
)

// SessionReplayTelemetry aggregates session replay stats from a JSONL events file.
func SessionReplayTelemetry(inputPath string) error {
	if !analysis.FileExists(inputPath) {
		result := map[string]interface{}{"error": fmt.Sprintf("Event log not found: %s", inputPath)}
		data, _ := json.MarshalIndent(result, "", "  ")
		fmt.Println(string(data))
		return fmt.Errorf("event log not found: %s", inputPath)
	}

	eventTypes := map[string]bool{
		"session_channel_replay_hit": true,
		"session_channel_divergence": true,
	}
	events, err := analysis.LoadJSONLMultiEvents(inputPath, eventTypes)
	if err != nil {
		return err
	}

	if len(events) == 0 {
		result := map[string]interface{}{
			"total_replay_hits": 0,
			"total_divergences": 0,
			"channels":          map[string]interface{}{},
			"hints":             []string{"No session replay events found — this may be a record-only run."},
		}
		data, _ := json.MarshalIndent(result, "", "  ")
		fmt.Println(string(data))
		return nil
	}

	type channelStat struct {
		Hits             int         `json:"hits"`
		Divergences      int         `json:"divergences"`
		DivergencePoint  interface{} `json:"divergence_point"`
		ExpectedHash     string      `json:"expected_hash,omitempty"`
		ActualHash       string      `json:"actual_hash,omitempty"`
	}

	channelStats := map[string]*channelStat{}
	totalHits := 0
	totalDivergences := 0

	for _, e := range events {
		eType := analysis.GetString(e, "type")
		d := analysis.GetData(e)
		channel := analysis.GetString(d, "channel")
		if channel == "" {
			channel = "unknown"
		}

		if _, ok := channelStats[channel]; !ok {
			channelStats[channel] = &channelStat{}
		}

		switch eType {
		case "session_channel_replay_hit":
			channelStats[channel].Hits++
			totalHits++
		case "session_channel_divergence":
			channelStats[channel].Divergences++
			totalDivergences++
			if channelStats[channel].DivergencePoint == nil {
				channelStats[channel].DivergencePoint = d["sequence_index"]
				channelStats[channel].ExpectedHash = analysis.GetString(d, "expected_hash")
				channelStats[channel].ActualHash = analysis.GetString(d, "actual_hash")
			}
		}
	}

	var fullyReplayed, diverged []string
	for ch, s := range channelStats {
		if s.Divergences == 0 {
			fullyReplayed = append(fullyReplayed, ch)
		} else {
			diverged = append(diverged, ch)
		}
	}

	var hints []string
	for ch, s := range channelStats {
		if s.Divergences > 0 {
			hints = append(hints, fmt.Sprintf(
				"Channel '%s' diverged at seq %v: code change may have affected this data path.",
				ch, s.DivergencePoint))
		}
	}
	if len(fullyReplayed) > 0 {
		hints = append(hints, fmt.Sprintf("Channels fully replayed from cache: %s.",
			joinStrings(fullyReplayed)))
	}
	if totalHits > 0 && totalDivergences == 0 {
		hints = append(hints, "All channels served from cache: fully deterministic replay.")
	}

	result := map[string]interface{}{
		"total_replay_hits":        totalHits,
		"total_divergences":        totalDivergences,
		"fully_replayed_channels":  fullyReplayed,
		"diverged_channels":        diverged,
		"channels":                 channelStats,
		"hints":                    hints,
	}

	data, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(data))
	return nil
}

func joinStrings(ss []string) string {
	result := ""
	for i, s := range ss {
		if i > 0 {
			result += ", "
		}
		result += s
	}
	return result
}
