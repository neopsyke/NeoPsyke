# Freud Rollout Ledger

Compact ledger for live-eval and live-lane rollout work. Append new entries instead of rewriting history.

## Latest Status
- Current branch: `evals_std`
- Last rollout-related commits:
  - `64e1164` `fix(freud-live): harden rollout wrapper and shutdown path`
  - `0e1745d` `fix(freud-live): keep stdout answer-only for eval scoring`
  - `4410001` `feat(freud): add BBH smoke progress artifacts`
- Current blockers:
  - weak live lane has real failures to inspect before prod lane
  - cache telemetry for direct record runs still appears inconsistent with the recorded cache file in at least one passing direct run

## Run Matrix

| ID | Command | Live | Lane / Models | Replay | Preserve Memory | Result | Notes |
|---|---|---|---|---|---|---|---|
| R1 | `./gradlew test` | no | local tests only | no | n/a | pass | Full Gradle suite passed |
| R2 | `bats freud/tests/test_reasoning_pr_gate.bats freud/tests/test_bbh_smoke.bats freud/tests/test_live_eval.bats` | no | shell tests with stubs | no | n/a | pass | Re-run after wrapper changes |
| R3 | `freud/scripts/feature-loop.sh reasoning-matrix --dry-run --config freud/config/live-weak-structure.env` | no | dry-run wiring | no | n/a | pass | Non-live config wiring |
| R4 | `freud/scripts/feature-loop.sh reasoning-matrix --dry-run --live --config freud/config/live-weak-structure.env` | no | dry-run live-step wiring | no | n/a | pass | `reasoning_eval_model` included |
| R5 | `freud/scripts/live-eval.sh --input /tmp/freud-live-smoke-input.txt --timeout 120` | yes | direct live-eval, prod routing | no | no | pass | Final passing run after wrapper/bootstrap/runtime fixes |
| R6 | `freud/scripts/live-eval.sh --input /tmp/freud-live-smoke-input.txt --cache-replay <recorded-cache>` | yes | direct live-eval, replay path | yes | no | pass | `4/4` cached calls, `0` divergence |
| R7 | `freud/scripts/live-eval.sh --input /tmp/freud-live-smoke-input.txt --timeout 120 --preserve-memory` | yes | direct live-eval, prod routing | no | yes | pass | Preserve-memory wrapper path validated |
| R8 | `freud/scripts/feature-loop.sh reasoning-matrix --live --config freud/config/live-weak-structure.env --from-step reasoning_eval_model` | yes | weak live lane | no | no | fail | Valid completed BBH lane with real weak-model failures |

## Live Routing Used

### Direct live-eval runs (`R5`, `R6`, `R7`)
- Planner: `groq / openai/gpt-oss-120b`
- Action verifier: `openai / gpt-4o-mini`
- Superego primary: `openai / gpt-4o-mini`
- Superego escalation: `openai / gpt-4.1-mini`
- Memory advisor: `openai / gpt-4.1-mini`

### Weak live lane (`R8`)
- Config: [live-weak-structure.env](/Users/victor.toral/atomitl/ai/psyke2/psyke/freud/config/live-weak-structure.env)
- Planner: `groq / openai/gpt-oss-20b`
- Meta-reasoner: `groq / openai/gpt-oss-20b`
- Action verifier: `openai / gpt-4o-mini`
- Superego primary: `openai / gpt-4o-mini`
- Superego escalation: `openai / gpt-4.1-mini`
- Memory advisor: `openai / gpt-4.1-mini`

## Key Artifact Runs

### Direct live-eval record run
- Run: [20260315T014540Z-live-eval-fqmuHG](/Users/victor.toral/atomitl/ai/psyke2/psyke/.psyke/runs/freud/20260315T014540Z-live-eval-fqmuHG)
- Verdict: pass
- Answer: `{"status":"ok","mode":"freud-live-smoke"}`
- Notes:
  - `llm-cache.jsonl` exists and contains recorded calls
  - no structured-output downgrade warnings found

### Direct live-eval replay run
- Run: [20260315T014707Z-live-eval-I1c0SB](/Users/victor.toral/atomitl/ai/psyke2/psyke/.psyke/runs/freud/20260315T014707Z-live-eval-I1c0SB)
- Verdict: pass
- Replay stats:
  - `total_calls=4`
  - `cached_calls=4`
  - `real_calls=0`
  - `divergence_count=0`

### Direct live-eval preserve-memory run
- Run: [20260315T014724Z-live-eval-0ynEVn](/Users/victor.toral/atomitl/ai/psyke2/psyke/.psyke/runs/freud/20260315T014724Z-live-eval-0ynEVn)
- Verdict: pass

### Weak live lane
- Run: [20260315T015648Z-reasoning-matrix](/Users/victor.toral/atomitl/ai/psyke2/psyke/.psyke/runs/freud/20260315T015648Z-reasoning-matrix)
- Summary: [bbh-smoke-weak-structure-summary.json](/Users/victor.toral/atomitl/ai/psyke2/psyke/.psyke/runs/freud/20260315T015648Z-reasoning-matrix/artifacts/bbh-smoke-weak-structure-summary.json)
- Progress artifact shape validated via:
  - [bbh-smoke-weak-structure-progress.json](/Users/victor.toral/atomitl/ai/psyke2/psyke/.psyke/runs/freud/20260315T015648Z-reasoning-matrix/artifacts/bbh-smoke-weak-structure-progress.json)
- Result:
  - `24` total
  - `21` passed
  - `3` failed
  - `1` timeout
  - `0` schema downgrades
  - exact match: `87.50%`

## Confirmed Failures / Findings

### Wrapper/runtime issues already fixed
- `live-eval.sh` originally used user-home Gradle wrapper state and failed in sandboxed rollout runs.
- `run-psyke.sh` bootstrap Gradle path needed `--no-daemon` for sandbox-safe live bootstrap.
- `freud-live` originally crashed after answer delivery due to closed sensory channel shutdown.
- BBH/live scoring was originally invalid because launcher chatter leaked onto stdout.

### Remaining weak-lane failures from `R8`
- `shuffle_02`
  - expected: `b`
  - actual: `a`
- `deduction_02`
  - expected: `ava`
  - actual: `"ava"`
- `deduction_04`
  - status: `timeout`

## Next Suggested Steps
1. Inspect the three weak-lane failures and decide whether `deduction_02` should be normalized further or treated as a strict formatting miss.
2. Only after that, run `prod-acceptance-live`.
3. If weak/prod results are acceptable, save initial BBH baseline artifacts and wire `FREUD_BBH_BASELINE_FILE`.
