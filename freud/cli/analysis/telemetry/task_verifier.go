package telemetry

import (
	"fmt"

	"github.com/atomitl/neopsyke/freud/cli/analysis"
)

// TaskVerifierTelemetry aggregates task verifier review stats from a JSONL events file.
func TaskVerifierTelemetry(inputPath string) error {
	if !analysis.FileExists(inputPath) {
		return fmt.Errorf("event log not found: %s", inputPath)
	}

	events, err := analysis.LoadJSONLEvents(inputPath, "task_verifier_review")
	if err != nil {
		return err
	}

	total := len(events)
	if total == 0 {
		fmt.Printf("No task_verifier_review events found in: %s\n", inputPath)
		return nil
	}

	allowCount := 0
	denyCount := 0
	requiresCount := 0
	gracefulCount := 0
	unknownIntentCount := 0
	volatileCount := 0
	volatileDenyCount := 0

	for _, e := range events {
		data := analysis.GetData(e)
		allow, hasAllow := analysis.GetBool(data, "allow")

		if hasAllow && allow {
			allowCount++
		}
		if hasAllow && !allow {
			denyCount++
		}
		if req, ok := analysis.GetBool(data, "requires_external_evidence"); ok && req {
			requiresCount++
		}
		if hasAllow && allow && analysis.GetString(data, "reason_code") == "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL" {
			gracefulCount++
		}

		intent := analysis.GetString(data, "intent_category")
		if intent == "unknown" {
			unknownIntentCount++
		}
		if intent == "volatile_fact" {
			volatileCount++
			if hasAllow && !allow {
				volatileDenyCount++
			}
		}
	}

	fmt.Println("Task Verifier Telemetry")
	fmt.Printf("source: %s\n\n", inputPath)
	fmt.Println("Totals")
	fmt.Printf("- reviews: %d\n", total)
	fmt.Printf("- allow: %d (%s%%)\n", allowCount, analysis.Pct(allowCount, total))
	fmt.Printf("- deny: %d (%s%%)\n", denyCount, analysis.Pct(denyCount, total))
	fmt.Printf("- requires_external_evidence: %d (%s%%)\n", requiresCount, analysis.Pct(requiresCount, total))
	fmt.Printf("- graceful_allows: %d (%s%%)\n", gracefulCount, analysis.Pct(gracefulCount, total))
	fmt.Printf("- unknown_intent: %d (%s%%)\n", unknownIntentCount, analysis.Pct(unknownIntentCount, total))
	fmt.Printf("- volatile_intent: %d (%s%%)\n", volatileCount, analysis.Pct(volatileCount, total))
	fmt.Printf("- volatile_denies: %d\n\n", volatileDenyCount)

	// Breakdown by reason_code
	fmt.Println("Breakdown by reason_code")
	reasonCounts := countBy(events, "reason_code")
	for _, kv := range sortedCounts(reasonCounts) {
		fmt.Printf("- %s: %d\n", kv.key, kv.count)
	}
	fmt.Println()

	// Breakdown by intent_category
	fmt.Println("Breakdown by intent_category")
	intentCounts := countBy(events, "intent_category")
	for _, kv := range sortedCounts(intentCounts) {
		fmt.Printf("- %s: %d\n", kv.key, kv.count)
	}
	fmt.Println()

	// Breakdown by volatility_level
	fmt.Println("Breakdown by volatility_level")
	volCounts := countBy(events, "volatility_level")
	for _, kv := range sortedCounts(volCounts) {
		fmt.Printf("- %s: %d\n", kv.key, kv.count)
	}
	fmt.Println()

	fmt.Println("Tuning Hints")
	if unknownIntentCount > 0 {
		fmt.Println("- unknown intent observed: review prompts and add deterministic intent rules before lowering volatility thresholds.")
	}
	if gracefulCount > 0 {
		fmt.Println("- graceful allows occurred: inspect action capability health to reduce under-verified volatile answers.")
	}
	if volatileCount > 0 {
		fmt.Printf("- volatile deny rate: %s%% (target depends on tool availability and product posture).\n",
			analysis.Pct(volatileDenyCount, volatileCount))
	}
	if denyCount == 0 {
		fmt.Println("- no denies recorded: verify volatile scenarios are still covered by tests/evals.")
	}

	return nil
}
