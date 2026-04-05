# Task Verifier Telemetry And Tuning Guide

This document explains how to monitor and tune the deterministic Task Verifier
intent/volatility heuristic introduced in `TaskVerifier.kt`.

## What To Monitor

Follow `task_verifier_review` events and aggregated `taskVerifierStats`.

### Per-event fields (`task_verifier_review`)

- `allow`
- `reason_code`
- `intent_category`
- `volatility_level`
- `volatility_score`
- `requires_external_evidence`
- `evidence_actions_available`
- `evidence_actions_dispatchable`
- `had_successful_evidence`
- `had_external_failures`
- `root_input_id`
- `session_id`
- `latest_user_turn_preview`

### Aggregates (`/api/obs/snapshot` -> `taskVerifierStats`)

- `total_reviews`
- `allow_count`
- `deny_count`
- `deny_rate`
- `requires_evidence_count`
- `graceful_allow_count`
- `graceful_allow_rate`
- `by_reason_code`
- `by_intent_category`
- `by_volatility_level`

## Reason Codes To Track

- `TASK_EVIDENCE_REQUIRED`
- `TECH_EXTERNAL_EVIDENCE_FAILURE`
- `TASK_EVIDENCE_UNAVAILABLE_GRACEFUL`

## Live Monitoring Paths

1. Dashboard snapshot API:
- `GET /api/obs/snapshot`
- Inspect `taskVerifierStats` and recent `task_verifier_review` events.

2. Event sidecar JSONL:
- Default pointer: `.neopsyke/logs/latest-events.jsonl`
- Per-run path: `.neopsyke/logs/runs/<run-id>.events.jsonl`

3. Structured logs:
- `StructuredLogSink` emits `task_verifier.review ...` lines with intent and volatility details.

## Aggregation

Task verifier telemetry is computed automatically during `freud eval` runs. The dashboard snapshot (`/api/obs/snapshot`) also exposes aggregated `taskVerifierStats`.

The telemetry reports:

- total reviews
- allow/deny rates
- requires-evidence rate
- graceful allow rate
- breakdown by `reason_code`
- breakdown by `intent_category`
- breakdown by `volatility_level`
- basic tuning hints

## Tuning Workflow

Use a staged loop and change one axis at a time.

1. Collect baseline.
- Run representative scenarios.
- Capture event sidecar and dashboard snapshot values.
- Save script output for comparison.

2. Inspect classifier quality.
- If `by_intent_category.unknown` is high, improve intent rules first.
- Do not loosen evidence thresholds until unknown share is acceptably low.

3. Inspect volatility gating.
- Track `volatile_fact` volume and deny split.
- If volatile deny is near zero while volatile volume is high, threshold may be too loose.
- If volatile deny is very high with healthy tool availability, threshold may be too strict.

4. Inspect graceful degradation behavior.
- Track `graceful_allow_rate`.
- Correlate with action availability/health and queue saturation.
- High graceful allow with healthy tools suggests classifier/threshold mismatch.
- High graceful allow with unhealthy tools indicates environment/tool issue, not heuristic issue.

5. Inspect failure mix.
- `TASK_EVIDENCE_REQUIRED` high: strictness or weak evidence acquisition.
- `TECH_EXTERNAL_EVIDENCE_FAILURE` high: tool reliability/retry path problem.
- `TASK_EVIDENCE_UNAVAILABLE_GRACEFUL` high: dispatchability/health problem.

6. Apply small changes.
- Prefer adjusting thresholds or signal sets incrementally.
- Re-run same scenario pack and compare delta.

## Suggested Guardrails

- Keep `unknown` intent category low before relaxing evidence requirements.
- Keep graceful allow non-zero but not dominant for volatile tasks.
- Avoid abrupt threshold shifts; use incremental adjustments and compare runs.
- If deny rate drops sharply, validate answer quality with scenario/eval checks.

## Practical Review Checklist (Per Run)

1. `taskVerifierStats.total_reviews` is high enough for signal quality.
2. `deny_rate` and `graceful_allow_rate` are within expected bounds.
3. `by_intent_category` is not dominated by `unknown`.
4. `by_reason_code` distribution matches environment conditions.
5. Recent `task_verifier_review` rows show coherent intent/volatility assignments.

## Files Involved

- Verifier logic: `src/main/kotlin/ai/neopsyke/agent/ego/TaskVerifier.kt`
- Event emission: `src/main/kotlin/ai/neopsyke/agent/ego/Ego.kt`
- Structured logs: `src/main/kotlin/ai/neopsyke/instrumentation/StructuredLogSink.kt`
- Dashboard aggregates: `src/main/kotlin/ai/neopsyke/dashboard/DashboardStateStore.kt`
- Go telemetry module: `freud/internal/analysis/telemetry/task_verifier.go`
