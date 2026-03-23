# Prompt Budget Tuning Guide

This guide explains how to monitor and tune the contract-based `PromptBudgetAllocator`.

## Goals
- Keep required planner/policy signal present under pressure.
- Avoid silent prompt collapse.
- Detect systemic budget pressure early (`single_message_fallback`, floor violations).

## Where telemetry is emitted
`prompt_budget_allocation` events are emitted at:
- `planner_prompt`
- `action_verifier_prompt`
- `superego_prompt`
- `meta_reasoner_prompt`
- `legacy_web_search_prompt`

## Event fields to monitor
Core pressure signals:
- `single_message_fallback` (bool): severe pressure, allocator had to keep one section only.
- `floor_violation_count` (int): reserved required floors could not all be preserved.
- `degradation_path` (string): ordered trim path, e.g. `trim_optional,trim_required_context`.
- `dropped_section_count` (int): how many sections ended at zero.

Budget/cost signals:
- `max_tokens`
- `estimated_total_cost`
- `allocated_total_cost`
- `reserved_floor_cost`
- `bands.required_core|required_context|optional` summary map

## Aggregated views
Dashboard snapshot (`/api/obs/snapshot`) includes `promptBudgetStats`:
- `total_allocations`
- `single_message_fallback_count`
- `single_message_fallback_rate`
- `floor_violation_events`
- `dropped_sections_total`
- `by_call_site`
- `by_degradation_path`

Offline log aggregation:
```bash
freud/scripts/prompt-budget-telemetry.sh
freud/scripts/prompt-budget-telemetry.sh .neopsyke/logs/runs/<run-id>.events.jsonl
```

## Recommended thresholds
Use these as initial guardrails per call site:
- `single_message_fallback_rate`: target `< 0.5%`, investigate at `>= 1%`.
- `floor_violation_events / total_allocations`: target `0%`, investigate any non-zero sustained signal.
- `trim_required_core` in `degradation_path`: should be rare; investigate if frequent.

## Tuning workflow
1. Segment by call site (`planner_prompt`, `superego_prompt`, etc.).
2. Check severe pressure first:
   - `single_message_fallback`
   - `floor_violation_count`
3. If severe pressure exists:
   - increase budget (`PlannerConfig.maxPromptTokens`) OR
   - lower required floors (`floorTokens`) for non-critical sections.
4. If only optional/context trimming is high:
   - reclassify section bands (optional vs required_context) by real criticality.
   - lower floor values for bulky context blocks.
5. Re-run tests and targeted eval scenarios; compare telemetry deltas.

## Safe tuning order
1. Reduce `floorTokens` on `REQUIRED_CONTEXT` sections first.
2. Reclassify noisy sections from `REQUIRED_CONTEXT` to `OPTIONAL`.
3. Increase prompt budget only if necessary.
4. Change `REQUIRED_CORE` floors last (highest regression risk).

## Regression checks after tuning
- Planner still receives trigger and schema context.
- Superego still receives directives + candidate action + last user message.
- No increase in empty/invalid model outputs.
- No sustained rise in `single_message_fallback`.

## Notes
- This allocator intentionally favors deterministic degradation over implicit heuristic trimming.
- `single_message_fallback` is a safety valve, not a normal operating mode.
