# NeoPsyke Scenario Pack v1

This pack is a deterministic, end-to-end regression layer for agent-loop behavior.

## Why this exists
Existing NeoPsyke evals already cover:
- Reasoning retry/feedback contract behavior (logic harness)
- Model reasoning tasks (ledger/assignment/state-machine)
- Live long-term memory persistence/recall checks

The scenario pack adds deterministic agent-loop coverage that is hard to infer from reasoning-only evals:
- policy denial and alternative-action recovery
- fallback behavior after repeated external failures
- memory recall injection into planner context
- per-request task-workspace prompt injection + cleanup
- forced terminal convergence under repeated planner model errors
- unavailable-action rejection and recovery with available actions

## Source of truth
- Manifest: `freud/scenarios/v1/neopsyke-agent-scenarios.json`
- Tests: `src/test/kotlin/ai/neopsyke/eval/AgentScenarioPackTest.kt`
