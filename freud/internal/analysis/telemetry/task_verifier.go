package telemetry

import (
	"fmt"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
)

// GroundingGateTelemetry aggregates grounding gate review stats from a JSONL events file.
func GroundingGateTelemetry(inputPath string) error {
	if !analysis.FileExists(inputPath) {
		return fmt.Errorf("event log not found: %s", inputPath)
	}

	events, err := analysis.LoadJSONLEvents(inputPath, "grounding_gate_review")
	if err != nil {
		return err
	}

	total := len(events)
	if total == 0 {
		fmt.Printf("No grounding_gate_review events found in: %s\n", inputPath)
		return nil
	}

	allowCount := 0
	denyCount := 0
	groundingRequiredCount := 0
	evidenceGatheredCount := 0
	evidenceFailedCount := 0
	evidenceUnavailableCount := 0
	forcedTerminalCount := 0

	for _, e := range events {
		data := analysis.GetData(e)
		allow, hasAllow := analysis.GetBool(data, "allow")

		if hasAllow && allow {
			allowCount++
		}
		if hasAllow && !allow {
			denyCount++
		}
		if req, ok := analysis.GetBool(data, "grounding_required"); ok && req {
			groundingRequiredCount++
		}
		if gathered, ok := analysis.GetBool(data, "evidence_gathered"); ok && gathered {
			evidenceGatheredCount++
		}
		if failed, ok := analysis.GetBool(data, "evidence_failed_technically"); ok && failed {
			evidenceFailedCount++
		}
		if unavail, ok := analysis.GetBool(data, "evidence_unavailable"); ok && unavail {
			evidenceUnavailableCount++
		}
		if ft, ok := analysis.GetBool(data, "forced_terminal"); ok && ft {
			forcedTerminalCount++
		}
	}

	fmt.Println("Grounding Gate Telemetry")
	fmt.Printf("source: %s\n\n", inputPath)
	fmt.Println("Totals")
	fmt.Printf("- reviews: %d\n", total)
	fmt.Printf("- allow: %d (%s%%)\n", allowCount, analysis.Pct(allowCount, total))
	fmt.Printf("- deny: %d (%s%%)\n", denyCount, analysis.Pct(denyCount, total))
	fmt.Printf("- grounding_required: %d (%s%%)\n", groundingRequiredCount, analysis.Pct(groundingRequiredCount, total))
	fmt.Printf("- evidence_gathered: %d (%s%%)\n", evidenceGatheredCount, analysis.Pct(evidenceGatheredCount, total))
	fmt.Printf("- evidence_failed_technically: %d (%s%%)\n", evidenceFailedCount, analysis.Pct(evidenceFailedCount, total))
	fmt.Printf("- evidence_unavailable: %d (%s%%)\n", evidenceUnavailableCount, analysis.Pct(evidenceUnavailableCount, total))
	fmt.Printf("- forced_terminal: %d\n\n", forcedTerminalCount)

	// Breakdown by reason_code
	fmt.Println("Breakdown by reason_code")
	reasonCounts := countBy(events, "reason_code")
	for _, kv := range sortedCounts(reasonCounts) {
		fmt.Printf("- %s: %d\n", kv.key, kv.count)
	}
	fmt.Println()

	fmt.Println("Tuning Hints")
	if evidenceUnavailableCount > 0 {
		fmt.Println("- evidence unavailable observed: inspect action capability health to reduce unverified volatile answers.")
	}
	if denyCount == 0 {
		fmt.Println("- no denies recorded: verify volatile scenarios are still covered by tests/evals.")
	}

	return nil
}
