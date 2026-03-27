package telemetry

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/atomitl/neopsyke/freud/cli/analysis"
)

type channelStat struct {
	Hits            int         `json:"hits"`
	Divergences     int         `json:"divergences"`
	DivergencePoint interface{} `json:"divergence_point"`
	ExpectedHash    string      `json:"expected_hash,omitempty"`
	ActualHash      string      `json:"actual_hash,omitempty"`
}

// sessionReplayResult computes session replay telemetry and returns the result.
func sessionReplayResult(inputPath string) (map[string]interface{}, error) {
	if !analysis.FileExists(inputPath) {
		return nil, fmt.Errorf("event log not found: %s", inputPath)
	}

	eventTypes := map[string]bool{
		"session_channel_replay_hit": true,
		"session_channel_divergence": true,
	}
	events, err := analysis.LoadJSONLMultiEvents(inputPath, eventTypes)
	if err != nil {
		return nil, err
	}

	if len(events) == 0 {
		return map[string]interface{}{
			"total_replay_hits": 0,
			"total_divergences": 0,
			"channels":          map[string]interface{}{},
			"hints":             []string{"No session replay events found — this may be a record-only run."},
		}, nil
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

	return map[string]interface{}{
		"total_replay_hits":       totalHits,
		"total_divergences":       totalDivergences,
		"fully_replayed_channels": fullyReplayed,
		"diverged_channels":       diverged,
		"channels":                channelStats,
		"hints":                   hints,
	}, nil
}

// SessionReplayTelemetry aggregates session replay stats and prints to stdout.
func SessionReplayTelemetry(inputPath string) error {
	result, err := sessionReplayResult(inputPath)
	if err != nil {
		errResult := map[string]interface{}{"error": err.Error()}
		data, _ := json.MarshalIndent(errResult, "", "  ")
		fmt.Println(string(data))
		return err
	}
	data, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(data))
	return nil
}

// SessionReplayTelemetryToFile aggregates session replay stats and writes to outputPath.
func SessionReplayTelemetryToFile(inputPath, outputPath string) error {
	result, err := sessionReplayResult(inputPath)
	if err != nil {
		return err
	}
	data, _ := json.MarshalIndent(result, "", "  ")
	return os.WriteFile(outputPath, append(data, '\n'), 0o644)
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
