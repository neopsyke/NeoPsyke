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
- `freud/scripts/feature-loop.sh`: End-to-end workflow runner.
- `freud/scripts/run-scenarios.sh`: Runs versioned scenario packs.
- `freud/scripts/triage-run.sh`: Summarize anomalies from run logs.
- `freud/scripts/codex-context-pack.sh`: Build compact context for follow-up agent work.
- `freud/scripts/cheap-summarizer-adapter.sh`: Tier-2 optional cheap-model run summarizer.
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
```

Artifacts are saved under `.psyke/runs/freud/<timestamp>-<feature_id>/` by default:
- `artifacts/summary.json`
- `artifacts/failures.json`
- `artifacts/anomalies.json`
- `artifacts/anomalies.md`
- `artifacts/run-config.json`
- `artifacts/model-summary.json` (Tier-2, optional)
- `artifacts/model-summary.md` (Tier-2, optional)
- `artifacts/model-summary-attempts.tsv` (Tier-2 provider attempts)
- `artifacts/model-summary-metrics.json` (Tier-2 usage metrics)
- `artifacts/freud-metrics.json` (run-level counters, triage counters, summarizer counters)
- `artifacts/trail.jsonl`
- `artifacts/trail-index.tsv`
- `artifacts/step-index.tsv`
- `artifacts/step-meta/*.json`
- `artifacts/codex-context.md`
- `logs/*.log`

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
- `FREUD_SUMMARIZER_CMD` (optional external model summarizer)
- `FREUD_SUMMARIZER_PROVIDER` (`auto|openai|groq|mistral|mock|none`)
- `FREUD_SUMMARIZER_MODEL`
- `FREUD_SUMMARIZER_BASE_URL` (optional override)
- `FREUD_SUMMARIZER_ENABLE_FALLBACK` (`true|false`)
- `FREUD_SUMMARIZER_FALLBACK_ORDER` (default: `openai,mistral,groq`)
- `FREUD_SUMMARIZER_HEALTHCHECK_TIMEOUT_SEC` (default: `6`)
- `FREUD_SUMMARIZER_TOKEN_LIMIT` (default: `1000000`)
- `FREUD_RUN_ROOT`
- `FREUD_GRADLE_USER_HOME` (optional; isolated Gradle cache)

## Design Rules
- Keep deterministic checks first and cheap.
- Keep live/provider checks optional and explicit.
- Keep artifacts compact and machine-readable.
- Keep project-specific commands in `freud/config/*.env`, not hardcoded in scripts.

## Summarization Policy
- Default path is non-LLM summarization (`freud/scripts/summarize-run.sh`) over indexed artifacts.
- If LLM summarization is needed, use a cheaper model adapter via `FREUD_SUMMARIZER_CMD` and feed it compact files first (`summary.json`, `step-index.tsv`, `trail-index.tsv`, `anomalies.json`), not full logs.
- Reserve Codex for implementation/debug decisions, not first-pass log condensation.

### Tier-2 Adapter Quick Setup
```bash
# Example: OpenAI cheap summarizer
export OPENAI_API_KEY=...
export FREUD_SUMMARIZER_PROVIDER=openai
export FREUD_SUMMARIZER_MODEL=gpt-5-nano

# Example: Groq cheap summarizer
export GROQ_API_KEY=...
export FREUD_SUMMARIZER_PROVIDER=groq
export FREUD_SUMMARIZER_MODEL=openai/gpt-oss-20b

# Example: Mistral cheap summarizer
export MISTRAL_API_KEY=...
export FREUD_SUMMARIZER_PROVIDER=mistral
export FREUD_SUMMARIZER_MODEL=mistral-small-latest
```

The default `FREUD_SUMMARIZER_CMD` in this repo is:
- `freud/scripts/cheap-summarizer-adapter.sh --if-configured`

Default provider is `auto`, with this selection order when keys exist:
1. `openai` (`gpt-5-nano`)
2. `mistral` (`mistral-small-latest`)
3. `groq` (`openai/gpt-oss-20b`)

Tier-2 runs automatically and safely skips only when no supported API key is configured.
Before each provider call, Freud runs a lightweight healthcheck (`/models`) and falls back to the next provider on timeout/unavailability.
Fallback attempts are recorded in:
- `artifacts/model-summary-attempts.tsv`
Summarizer usage counters are recorded in:
- `artifacts/model-summary-metrics.json` (per run)
- `.freud/metrics/summarizer-usage.json` (cumulative ledger)
- `artifacts/freud-metrics.json` and `artifacts/summary.json` (surfaced per run for low-token triage)

When cumulative usage reaches the token limit (default: `1,000,000`), Freud emits a warning and skips further Tier-2 model calls.
Freud also emits a `summarizer_budget_limit` event in `artifacts/trail-index.tsv` to make limit hits easy to trace.
