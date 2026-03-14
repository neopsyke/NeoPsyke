# freud

`freud` is a workflow meta-project for faster feature iteration loops:

1. Add a feature.
2. Run deterministic verification first.
3. Run deterministic scenario pack.
4. Collect compact artifacts.
5. Triage failures quickly.
6. Re-run only what failed.

This directory is intentionally separated from Psyke runtime code (`src/main/kotlin/psyke`).

## Layout
- `freud/scripts/feature-loop.sh`: End-to-end workflow runner (Bash orchestration).
- `freud/scripts/run-scenarios.sh`: Runs versioned scenario packs (Bash).
- `freud/scripts/helpers.sh`: Shared Bash helper functions (json_escape, extract_json_*, etc.).
- `freud/scripts/triage-run.sh`: Thin wrapper → `freud/py/triage.py`.
- `freud/scripts/summarize-run.sh`: Thin wrapper → `freud/py/summarize.py`.
- `freud/scripts/context-pack.sh`: Thin wrapper → `freud/py/context_pack.py`.
- `freud/scripts/prompt-budget-telemetry.sh`: Thin wrapper → `freud/py/telemetry/prompt_budget.py`.
- `freud/scripts/task-verifier-telemetry.sh`: Thin wrapper → `freud/py/telemetry/task_verifier.py`.
- `freud/scripts/live-eval.sh`: Single-input live eval with LLM caching and memory isolation.
- `freud/py/`: Python implementations of data-processing scripts (stdlib only).
- `freud/py/telemetry/llm_cache.py`: LLM cache hit/miss/divergence telemetry analyzer.
- `freud/config/default.env`: Project adapter defaults for this repository.
- `freud/config/adapter.example.env`: Template for reuse in other projects.
- `freud/templates/feature-brief.md`: Small, reusable feature brief template.
- `freud/templates/agent-operator-template.md`: Standard prompt template for any coding agent.
- `freud/scenarios/v1/*.json`: Versioned scenario packs.

## Quick Start
```bash
# Deterministic/stub-first loop
freud/scripts/feature-loop.sh add-verifier

# Live steps enabled (if configured)
freud/scripts/feature-loop.sh add-verifier --live

# Dry run to inspect planned commands only
freud/scripts/feature-loop.sh add-verifier --dry-run

# Single-input live eval (record LLM responses)
freud/scripts/live-eval.sh --input test-input.txt --timeout 120

# Replay with cached LLM responses
freud/scripts/live-eval.sh --input test-input.txt \
  --cache-replay .psyke/runs/freud/latest/artifacts/llm-cache.jsonl

# With expected answer validation
freud/scripts/live-eval.sh --input test-input.txt --expected expected-answer.txt
```

Artifacts are saved under `.psyke/runs/freud/<timestamp>-<feature_id>/` by default:
- `artifacts/summary.json`
- `artifacts/failures.json`
- `artifacts/anomalies.json`
- `artifacts/anomalies.md`
- `artifacts/run-config.json`
- `artifacts/freud-metrics.json` (run-level counters, triage counters)
- `artifacts/trail.jsonl`
- `artifacts/trail-index.tsv`
- `artifacts/step-index.tsv`
- `artifacts/step-meta/*.json`
- `artifacts/context-pack.md`
- `logs/*.log`

## Live Eval

`live-eval.sh` pipes a single input to Psyke via `--freud-live` mode, captures the answer on stdout, and runs triage/telemetry on the result.

### LLM Response Caching
- First run (no `--cache-replay`): sets `PSYKE_LLM_CACHE_MODE=record` and saves all LLM responses to `artifacts/llm-cache.jsonl`.
- Replay run (`--cache-replay <file>`): sets `PSYKE_LLM_CACHE_MODE=replay`. Cached responses are matched by sequence position and verified by SHA-256 hash of the message list. On mismatch (divergence), all subsequent calls use real LLM.
- Cache telemetry is written to `artifacts/cache-stats.json` (hit rate, divergence point, per-actor breakdown).

### Memory Isolation
Live eval runs use an isolated memory environment so user data is never touched:

| Resource | Freud live eval | User default |
|---|---|---|
| pgvector namespace | `freud-eval` | `psyke` |
| Episodic logbook | `.psyke/freud-logbook.db` | `.psyke/logbook.db` |
| Metrics DB | `.psyke/freud-metrics.db` | `.psyke/metrics.db` |

All freud memory is cleared automatically before each run via `--clear-memory-all`.

### Artifacts
Live eval runs are saved under `.psyke/runs/freud/<timestamp>-live-eval/`:
- `artifacts/answer.txt` — agent's answer
- `artifacts/verdict.json` — pass/fail/timeout with timing
- `artifacts/llm-cache.jsonl` — recorded LLM responses (record mode)
- `artifacts/cache-stats.json` — cache hit/miss/divergence stats
- `artifacts/input.txt` — copy of the input
- `logs/events.jsonl` — full instrumentation event stream
- `logs/psyke.log` — application log

### Exit Codes
- `0` — answer delivered (and accepted if `--expected` provided)
- `1` — answer delivered but rejected, or error
- `2` — timeout

## Configuration
`feature-loop.sh` reads config from:
1. `FREUD_CONFIG` env var path (if set)
2. `freud/config/default.env`

You can override any command via environment variables before running:
- `FREUD_TARGETED_TEST_CMD`
- `FREUD_FULL_TEST_CMD`
- `FREUD_SCENARIO_PACK_CMD`
- `FREUD_REASONING_EVAL_LOGIC_CMD`
- `FREUD_REASONING_EVAL_MODEL_CMD`
- `FREUD_MEMORY_SMOKE_CMD`
- `FREUD_RUN_ROOT`
- `FREUD_GRADLE_USER_HOME` (optional; isolated Gradle cache)

## Design Rules
- Keep deterministic checks first and cheap.
- Keep live/provider checks optional and explicit.
- Keep artifacts compact and machine-readable.
- Keep project-specific commands in `freud/config/*.env`, not hardcoded in scripts.

## Testing

### BATS (shell wrapper integration tests)
```bash
# Install bats (one-time)
brew install bats-core

# Run all BATS tests
bats freud/tests/

# Run a specific test file
bats freud/tests/test_helpers.bats
```

### pytest (Python module unit tests)
```bash
# Create venv and install pytest (one-time)
python3 -m venv freud/.venv
freud/.venv/bin/pip install pytest

# Run all Python tests
PYTHONPATH=. freud/.venv/bin/pytest freud/tests/test_*_py.py freud/tests/test_common.py -v
```

Test structure:
- `freud/tests/helpers/` — shared BATS setup, function sourcing utilities
- `freud/tests/fixtures/` — sample artifacts, logs, JSONL events for BATS integration tests
- `freud/tests/conftest.py` — shared pytest fixtures (run directories)
- `freud/tests/test_*.bats` — BATS tests for shell wrappers and Bash-only logic
- `freud/tests/test_*_py.py` — pytest tests for Python modules

## Summarization Policy
- Summarization uses non-LLM heuristics (`freud/scripts/summarize-run.sh`) over indexed artifacts.
- Reserve Codex for implementation/debug decisions, not first-pass log condensation.
