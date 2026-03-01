# Agent Operator Template (Freud)

Use this template to instruct any coding agent (Codex, Claude, Gemini, Mistral, etc.) to execute the Freud workflow consistently.

## How To Use
1. Copy this template into your prompt to the coding agent.
2. Fill all placeholder fields (`<...>`).
3. Keep file scope and acceptance checks explicit.
4. Ask the agent to run Freud, triage artifacts, implement fixes, and re-run until all checks pass.

## Template

```text
Role:
You are a coding agent working in this repository. Follow AGENTS.md and use the Freud workflow as the preferred path.

Feature:
- feature_id: <feature-id-slug>
- goal: <one-sentence outcome>

Acceptance Checks:
     1. Propose a deterministic behavior check
     2. Propose a live test or scenario check (can use real llm calls, can be added to existing evals)
     3. Make a "no regressions / no critical anomalies check"

Constraints:
- Keep changes scoped to this feature.
- Try to keep main ego logic easy to understand/read in code. Simple is always better, as long as desired functionality is achieved.
- Do not add unrelated refactors. If truly needed ask for permission first.
- Preserve existing behavior unless required by new feature and acceptance checks.

Execution Plan:
1. Implement the smallest useful slice for this feature.
2. Run Freud:
   freud/scripts/feature-loop.sh <feature-id-slug>
3. If run fails, triage in this order:
   a) .psyke/runs/freud/latest-run.txt
   b) artifacts/summary.json
   c) artifacts/model-summary.json (if present)
   d) artifacts/model-summary.md (if present)
   e) artifacts/model-summary-attempts.tsv (if present)
   f) artifacts/model-summary-metrics.json (if present)
   g) artifacts/freud-metrics.json (if present)
   h) artifacts/trail-index.tsv
   i) artifacts/step-index.tsv
   j) artifacts/step-meta/<failing-step>.json
   k) artifacts/anomalies.md
   l) artifacts/codex-context.md
   m) logs/<step>.log only if needed
4. Apply minimal fixes.
5. Re-run Freud with the same feature id.
6. Repeat until all acceptance checks pass.

Scenario Coverage:
- If behavior changes are non-trivial, update:
  - freud/scenarios/v1/psyke-agent-scenarios.json
  - src/test/kotlin/psyke/eval/AgentScenarioPackTest.kt

Run Modes:
- Default: stub/deterministic only.
- Use --live only when explicitly requested:
  freud/scripts/feature-loop.sh <feature-id-slug> --live

Output Format Required:
1. What changed.
2. Why it changed.
3. Validation performed (commands + outcomes).
4. Remaining risks or follow-ups.

Token Efficiency Rules:
- Read compact artifacts first; avoid full logs unless required.
- Share file paths and line references, not large raw dumps.
- Use targeted tests/scenarios before full-suite reruns when possible.

If anything is unclear or appears incorrect, ask for clarification.

At the end of the feature implementation, perform an evaluation on Freud, 
and if it's feature have been useful to reduce both time, effort and token usage 
while implementing the new feature. If not, propose upgrades/changes. 

```
