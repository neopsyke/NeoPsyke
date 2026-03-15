# Freud

`Freud` is Psyke's developer workflow layer. It sits on top of the app and gives you repeatable test runs, compact artifacts, fast failure triage, and optional live/provider-backed evals without turning every check into an ad hoc shell session.

If you're new to the project, the short version is:
- Psyke runtime code lives in `src/main/kotlin/psyke`
- Freud lives in `freud/`
- You usually start with `freud/scripts/feature-loop.sh <feature-id>`
- Freud runs cheap deterministic checks first, then optional live checks, and stores everything under `.psyke/runs/freud/`

## Why Freud Exists
Without Freud, validating changes tends to fragment into:
- one-off `gradle` commands
- manual scenario runs
- ad hoc live prompts
- scattered logs with poor comparability

Freud standardizes that into one workflow with a few practical advantages:
- deterministic-first validation to catch most regressions cheaply
- structured artifacts for failures, warnings, timing, and summaries
- explicit live/provider-backed lanes instead of accidental paid runs
- replayable single-input live evals via LLM cache record/replay
- a stable place to compare runs over time

This directory is intentionally separate from Psyke runtime code so workflow logic does not leak into the app itself.

## Start Here

### Simplest Manual Run
If you only want the default developer workflow:

```bash
freud/scripts/feature-loop.sh my-change
```

That runs the standard deterministic sequence for the current repository and writes a run under `.psyke/runs/freud/<timestamp>-my-change/`.

### Common Manual Modes
Use these when you want something narrower or more explicit:

```bash
# Show what Freud would run without executing it
freud/scripts/feature-loop.sh my-change --dry-run

# Resume from a later step after fixing something
freud/scripts/feature-loop.sh my-change --from-step reasoning_eval_logic

# Run the deterministic reasoning PR gate only
freud/scripts/run-reasoning-pr-gate.sh

# Run the scenario pack only
freud/scripts/run-scenarios.sh --file freud/scenarios/v1/psyke-agent-scenarios.json
```

### Live Manual Modes
Use these only when you intentionally want provider-backed validation:

```bash
# Enable live steps in the normal feature loop
freud/scripts/feature-loop.sh my-change --live

# Weak-structure reasoning lane
freud/scripts/feature-loop.sh my-change --live --config freud/config/live-weak-structure.env

# Production-routing reasoning lane
freud/scripts/feature-loop.sh my-change --live --config freud/config/live-prod-acceptance.env
```

## Core Concepts

### 1. Feature Loop
`freud/scripts/feature-loop.sh` is the main orchestrator. It runs named steps such as compile, tests, scenarios, deterministic reasoning evals, and optional live evals.

### 2. Single-Input Live Eval
`freud/scripts/live-eval.sh` is the preferred wrapper for one-shot live/provider-backed checks. It uses Psyke's lower-level `--freud-live` mode but adds:
- isolated memory paths
- cache record/replay
- stable artifacts
- triage and summary generation
- startup provider preflight, including one retry for transient health-check failures and soft-disable of optional `meta_reasoner_fallback` if it is unavailable

### 3. Reasoning Matrix
For Psyke, Freud currently owns a 3-lane reasoning setup:
- deterministic logic gate
- weak live reasoning lane
- production live reasoning lane

### 4. Compact Artifacts
Every run writes machine-readable artifacts so you can inspect failures without scraping stdout manually.

## Quick Commands
```bash
# Default deterministic workflow
freud/scripts/feature-loop.sh add-verifier

# Deterministic reasoning PR gate only
freud/scripts/run-reasoning-pr-gate.sh

# Single-input live eval
freud/scripts/live-eval.sh --input test-input.txt --timeout 120

# Replay a prior live eval from recorded LLM calls
freud/scripts/live-eval.sh --input test-input.txt \
  --cache-replay .psyke/runs/freud/latest/artifacts/llm-cache.jsonl

# Preserve isolated Freud memory across a sequence when needed
freud/scripts/live-eval.sh --input test-input.txt --preserve-memory

# Validate against an expected answer
freud/scripts/live-eval.sh --input test-input.txt --expected expected-answer.txt
```

## What Gets Stored
Freud artifacts live under `.psyke/runs/freud/<timestamp>-<feature-id>/` by default.

Read these first:
- `artifacts/summary.json`
- `artifacts/summary-compact.md`
- `artifacts/trail-index.tsv`
- `artifacts/step-index.tsv`
- `artifacts/anomalies.md`

Important supporting artifacts:
- `artifacts/run-config.json`
- `artifacts/freud-metrics.json`
- `artifacts/step-meta/*.json`
- `artifacts/context-pack.md`
- `logs/*.log`

Live BBH reasoning lanes also write:
- `artifacts/bbh-smoke-<lane>-summary.json`
- `artifacts/bbh-smoke-<lane>-summary.md`
- `artifacts/bbh-smoke-<lane>-progress.json`
- `artifacts/bbh-smoke-<lane>-progress.md`
- `artifacts/bbh-smoke-<lane>-results.tsv`

Live one-shot evals write under `.psyke/runs/freud/<timestamp>-live-eval/`:
- `artifacts/answer.txt`
- `artifacts/verdict.json`
- `artifacts/llm-cache.jsonl`
- `artifacts/cache-stats.json`
- `artifacts/input.txt`
- `logs/events.jsonl`
- `logs/psyke.log`

Ongoing rollout notes now live in [ROLLOUT_LEDGER.md](/Users/victor.toral/atomitl/ai/psyke2/psyke/freud/ROLLOUT_LEDGER.md).

## Live Eval

Prefer `live-eval.sh` for any single-input Freud live/provider-backed check. It wraps the raw `./run-psyke.sh --freud-live` path and keeps stdout focused on the final agent answer.

### Record / Replay
- First run without `--cache-replay` records LLM calls to `artifacts/llm-cache.jsonl`
- Replay run uses `--cache-replay <file>`
- Cached responses are matched by call order and message hash
- On divergence, replay falls back to real provider calls for the remaining sequence

This is useful for:
- reproducing a live failure cheaply
- separating runtime bugs from provider variance
- inspecting whether a prompt/control-flow change altered the call graph

### Memory Isolation
Freud live eval runs do not touch the user's default memory state.

| Resource | Freud live eval | User default |
|---|---|---|
| pgvector namespace | `freud-eval` | `psyke` |
| Episodic logbook | `.psyke/freud-logbook.db` | `.psyke/logbook.db` |
| Metrics DB | `.psyke/freud-metrics.db` | `.psyke/metrics.db` |

Defaults:
- `--clear-memory-all` is applied automatically
- use `--preserve-memory` only when a sequence intentionally depends on prior isolated Freud memory
- `--expected` uses normalized exact matching, not substring containment

### Exit Codes
- `0`: answer delivered and accepted
- `1`: answer rejected or runtime error
- `2`: timeout

## Psyke-Specific Reasoning Lanes

### Deterministic Reasoning Gate
`freud/scripts/run-reasoning-pr-gate.sh`

Purpose:
- cheap PR-level regression coverage
- deterministic retry/repair logic checks

Content:
- logic core tasks
- 45 behavioral/perturbation deterministic tasks

### Weak Live Lane
`freud/config/live-weak-structure.env`

Purpose:
- check whether Psyke structure still works when planner/meta-reasoner are weaker

### Production Live Lane
`freud/config/live-prod-acceptance.env`

Purpose:
- validate the actual shipped routing

### BBH Smoke Runner
`freud/scripts/run-bbh-smoke.sh`

Purpose:
- run a small frozen reasoning slice through live providers
- track exact-match score, schema downgrades, timeouts, and progress

## Configuration
`feature-loop.sh` reads config from:
1. `FREUD_CONFIG`, if set
2. `freud/config/default.env`

Important overrides:
- `FREUD_TARGETED_TEST_CMD`
- `FREUD_FULL_TEST_CMD`
- `FREUD_SCENARIO_PACK_CMD`
- `FREUD_REASONING_EVAL_LOGIC_CMD`
- `FREUD_REASONING_EVAL_MODEL_CMD`
- `FREUD_MEMORY_SMOKE_CMD`
- `FREUD_LIVE_EVAL_TIMEOUT`
- `FREUD_LIVE_EVAL_PRESERVE_MEMORY`
- `FREUD_BBH_MIN_PASS_RATE_PERCENT`
- `FREUD_BBH_MAX_TIMEOUTS`
- `FREUD_BBH_PRESERVE_MEMORY`
- `FREUD_RUN_ROOT`
- `FREUD_GRADLE_USER_HOME`

Project-specific command wiring belongs in `freud/config/*.env`, not inside generic scripts.

## Layout
- `freud/scripts/feature-loop.sh`: end-to-end workflow runner
- `freud/scripts/run-scenarios.sh`: scenario pack runner
- `freud/scripts/live-eval.sh`: one-shot live eval wrapper
- `freud/scripts/run-reasoning-pr-gate.sh`: deterministic reasoning gate
- `freud/scripts/run-bbh-smoke.sh`: live BBH-style smoke runner
- `freud/scripts/helpers.sh`: shared Bash helpers
- `freud/py/`: Python data-processing helpers
- `freud/config/default.env`: Psyke adapter defaults
- `freud/config/live-weak-structure.env`: weak live lane
- `freud/config/live-prod-acceptance.env`: prod live lane
- `freud/config/adapter.example.env`: reusable template for other projects
- `freud/scenarios/v1/*.json`: versioned scenario packs
- `freud/tests/`: BATS and pytest coverage for the workflow tooling

## Testing the Workflow Itself

### BATS
```bash
brew install bats-core
bats freud/tests/
```

### pytest
```bash
python3 -m venv freud/.venv
freud/.venv/bin/pip install pytest
PYTHONPATH=. freud/.venv/bin/pytest freud/tests/test_*_py.py freud/tests/test_common.py -v
```

## Design Rules
- deterministic checks first
- live/provider checks explicit and optional
- compact machine-readable artifacts by default
- isolated Gradle state for Freud runs when configured
- `live-eval.sh` preferred over raw `--freud-live` for Freud-managed live checks

## Summarization Policy
- Use indexed artifacts first
- Use model-assisted summarization only after the cheap artifact pass
- Keep Codex for debugging, implementation, and decisions, not first-pass log condensation
