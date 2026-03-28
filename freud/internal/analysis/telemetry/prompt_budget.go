package telemetry

import (
	"fmt"
	"sort"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
)

// PromptBudgetTelemetry aggregates prompt budget allocation stats from a JSONL events file.
func PromptBudgetTelemetry(inputPath string) error {
	if !analysis.FileExists(inputPath) {
		return fmt.Errorf("event log not found: %s", inputPath)
	}

	events, err := analysis.LoadJSONLEvents(inputPath, "prompt_budget_allocation")
	if err != nil {
		return err
	}

	total := len(events)
	if total == 0 {
		fmt.Printf("No prompt_budget_allocation events found in: %s\n", inputPath)
		return nil
	}

	fallbackCount := 0
	floorViolationEvents := 0
	droppedSectionsTotal := 0
	var costs, floors []float64

	for _, e := range events {
		data := analysis.GetData(e)
		if fb, ok := analysis.GetBool(data, "single_message_fallback"); ok && fb {
			fallbackCount++
		}
		if fv, ok := analysis.GetInt(data, "floor_violation_count"); ok && fv > 0 {
			floorViolationEvents++
		}
		if ds, ok := analysis.GetInt(data, "dropped_section_count"); ok {
			droppedSectionsTotal += ds
		}
		if c, ok := data["allocated_total_cost"].(float64); ok {
			costs = append(costs, c)
		}
		if f, ok := data["reserved_floor_cost"].(float64); ok {
			floors = append(floors, f)
		}
	}

	avgCost := avg(costs)
	avgFloor := avg(floors)

	fmt.Println("Prompt Budget Telemetry")
	fmt.Printf("source: %s\n\n", inputPath)
	fmt.Println("Totals")
	fmt.Printf("- allocations: %d\n", total)
	fmt.Printf("- single_message_fallback: %d (%s%%)\n", fallbackCount, analysis.Pct(fallbackCount, total))
	fmt.Printf("- floor_violation_events: %d (%s%%)\n", floorViolationEvents, analysis.Pct(floorViolationEvents, total))
	fmt.Printf("- dropped_sections_total: %d\n", droppedSectionsTotal)
	fmt.Printf("- avg_allocated_total_cost: %.2f\n", avgCost)
	fmt.Printf("- avg_reserved_floor_cost: %.2f\n\n", avgFloor)

	// Breakdown by call_site
	fmt.Println("Breakdown by call_site")
	siteCounts := countBy(events, "call_site")
	for _, kv := range sortedCounts(siteCounts) {
		fmt.Printf("- %s: %d\n", kv.key, kv.count)
	}
	fmt.Println()

	// Breakdown by degradation_path
	fmt.Println("Breakdown by degradation_path")
	degCounts := countBy(events, "degradation_path")
	for _, kv := range sortedCounts(degCounts) {
		fmt.Printf("- %s: %d\n", kv.key, kv.count)
	}
	fmt.Println()

	// Fallback rate by call_site
	fmt.Println("Fallback rate by call_site")
	siteEvents := groupBy(events, "call_site")
	siteKeys := make([]string, 0, len(siteEvents))
	for k := range siteEvents {
		siteKeys = append(siteKeys, k)
	}
	sort.Slice(siteKeys, func(i, j int) bool {
		return len(siteEvents[siteKeys[i]]) > len(siteEvents[siteKeys[j]])
	})
	for _, site := range siteKeys {
		evts := siteEvents[site]
		t := len(evts)
		fb := 0
		fv := 0
		for _, e := range evts {
			data := analysis.GetData(e)
			if v, ok := analysis.GetBool(data, "single_message_fallback"); ok && v {
				fb++
			}
			if v, ok := analysis.GetInt(data, "floor_violation_count"); ok && v > 0 {
				fv++
			}
		}
		fmt.Printf("- %s: total=%d, fallback=%d, floor_violations=%d\n", site, t, fb, fv)
	}
	fmt.Println()

	fmt.Println("Tuning Hints")
	if fallbackCount > 0 {
		fmt.Println("- single-message fallback occurred: reduce required floors or increase max prompt budget for affected call sites.")
	}
	if floorViolationEvents > 0 {
		fmt.Println("- floor violations occurred: required floor reservation exceeds budget in some prompts; inspect degradation_path and band usage.")
	}
	if droppedSectionsTotal > 0 {
		fmt.Println("- sections were dropped: verify optional/context blocks are ordered and banded by true criticality.")
	}
	if fallbackCount == 0 && floorViolationEvents == 0 {
		fmt.Println("- no severe prompt pressure observed in this sample.")
	}

	return nil
}

func avg(vals []float64) float64 {
	if len(vals) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range vals {
		sum += v
	}
	return sum / float64(len(vals))
}

type kv struct {
	key   string
	count int
}

func countBy(events []map[string]interface{}, field string) map[string]int {
	counts := map[string]int{}
	for _, e := range events {
		data := analysis.GetData(e)
		val := analysis.GetString(data, field)
		if val == "" {
			val = "none"
		}
		counts[val]++
	}
	return counts
}

func groupBy(events []map[string]interface{}, field string) map[string][]map[string]interface{} {
	groups := map[string][]map[string]interface{}{}
	for _, e := range events {
		data := analysis.GetData(e)
		val := analysis.GetString(data, field)
		if val == "" {
			val = "unknown"
		}
		groups[val] = append(groups[val], e)
	}
	return groups
}

func sortedCounts(m map[string]int) []kv {
	var kvs []kv
	for k, v := range m {
		kvs = append(kvs, kv{k, v})
	}
	sort.Slice(kvs, func(i, j int) bool {
		return kvs[i].count > kvs[j].count
	})
	return kvs
}
