# Grounding Gate Telemetry And Tuning Guide

This document explains how to monitor and tune grounding-gate behavior.

## What To Monitor

Follow `grounding_gate_review` events and aggregated `groundingGateStats`.

### Per-event fields (`grounding_gate_review`)

- `allow`
- `reason_code`
- `grounding_required`
- `evidence_gathered`
- `evidence_failed_technically`
- `evidence_unavailable`
- `forced_terminal`
- `root_input_id`
- `session_id`

### Aggregates (`/api/obs/snapshot` -> `groundingGateStats`)

- `total_reviews`
- `allow_count`
- `deny_count`
- `deny_rate`
- `grounding_required_count`
- `evidence_gathered_count`
- `evidence_failed_technically_count`
- `evidence_unavailable_count`
- `forced_terminal_count`
- `by_reason_code`

## Reason Codes To Track

- `GROUNDING_EVIDENCE_REQUIRED`
- `TECH_GROUNDING_EVIDENCE_FAILURE`
- `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`

## Live Monitoring Paths

1. Dashboard snapshot API:
- `GET /api/obs/snapshot`
- Inspect `groundingGateStats` and recent `grounding_gate_review` events.

2. Event sidecar JSONL:
- Default pointer: `.neopsyke/logs/latest-events.jsonl`
- Per-run path: `.neopsyke/logs/runs/<run-id>.events.jsonl`

3. Structured logs:
- `StructuredLogSink` emits `grounding_gate.review ...` lines with gate decision details.

## Aggregation

Grounding gate telemetry is computed automatically during `freud eval` runs. The dashboard snapshot (`/api/obs/snapshot`) also exposes aggregated `groundingGateStats`.

The telemetry reports:

- total reviews
- allow/deny rates
- grounding-required rate
- evidence-unavailable rate
- breakdown by `reason_code`
- basic tuning hints

## Tuning Workflow

Use a staged loop and change one axis at a time.

1. Collect baseline.
- Run representative scenarios.
- Capture event sidecar and dashboard snapshot values.
- Save script output for comparison.

2. Inspect graceful degradation behavior.
- Track evidence-unavailable allows.
- Correlate with action availability/health and queue saturation.
- High unavailable allows with healthy tools suggests routing/surface mismatch.
- High unavailable allows with unhealthy tools indicates environment/tool issue.

3. Inspect failure mix.
- `GROUNDING_EVIDENCE_REQUIRED` high: missing evidence acquisition before answer.
- `TECH_GROUNDING_EVIDENCE_FAILURE` high: tool reliability/retry path problem.
- `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL` high: dispatchability/health problem.

4. Apply small changes.
- Prefer adjusting thresholds or signal sets incrementally.
- Re-run same scenario pack and compare delta.

## Suggested Guardrails

- Keep evidence-unavailable allows non-zero but not dominant.
- Avoid abrupt threshold shifts; use incremental adjustments and compare runs.
- If deny rate drops sharply, validate answer quality with scenario/eval checks.

## Practical Review Checklist (Per Run)

1. `groundingGateStats.total_reviews` is high enough for signal quality.
2. `deny_rate` and evidence-unavailable allow rate are within expected bounds.
3. `by_reason_code` distribution matches environment conditions.
4. Recent `grounding_gate_review` rows show coherent gate outcomes.

## Files Involved

- Gate logic: `src/main/kotlin/ai/neopsyke/agent/ego/DecisionVerifier.kt`
- Event emission: `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt`
- Structured logs: `src/main/kotlin/ai/neopsyke/instrumentation/StructuredLogSink.kt`
- Dashboard aggregates: `src/main/kotlin/ai/neopsyke/dashboard/DashboardStateStore.kt`
- Go telemetry module: `freud/internal/analysis/telemetry/task_verifier.go`
