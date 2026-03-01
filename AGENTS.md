# AGENTS.md

Instructions for coding agents working in this repository (Codex, Claude, Gemini/Google, Mistral, etc.).

## Purpose
- Use this file for execution and contribution rules.
- Use `README.md` for product and runtime documentation.

## Priority
- Follow system/developer/user instructions first.
- Then follow this file.
- If instructions conflict, use the highest-priority source.

## Project Snapshot
- Language: Kotlin (JVM), Gradle Kotlin DSL.
- Main source: `src/main/kotlin/psyke`.
- Tests: `src/test/kotlin/psyke`.
- Entrypoints:
  - `./gradlew run`
  - `./run-psyke.sh`

## Environment
- Requires JDK 21+.
- Runtime needs `MISTRAL_API_KEY`.
- Optional runtime flags and env vars are documented in `README.md`.

## Working Rules
- Keep changes focused and minimal.
- Do not make unrelated refactors.
- Do not add license headers unless asked.
- Do not commit secrets, API keys, or local machine paths.
- Prefer ASCII in docs/code unless the file already uses Unicode.
- Preserve existing behavior unless the user asked for behavior changes.

## Freud Workflow (Meta-Project)
- Preferred feature-delivery path: use `freud/` for coding, validation, and triage of non-trivial changes.
- Separation rule:
  - Freud code/config/workflow assets: `freud/**`
  - Psyke runtime and tests: `src/main/kotlin/psyke/**`, `src/test/kotlin/psyke/**`
  - Freud may read/run against all Psyke files, but Freud logic stays under `freud/**`.

### Required Commands
- Stub/deterministic-first run:
  - `freud/scripts/feature-loop.sh <feature-id>`
- Live-inclusive run (only when explicitly required):
  - `freud/scripts/feature-loop.sh <feature-id> --live`
- Scenario-only run:
  - `freud/scripts/run-scenarios.sh --file freud/scenarios/v1/psyke-agent-scenarios.json`
- Dry-run inspection:
  - `freud/scripts/feature-loop.sh <feature-id> --dry-run`

### Failure Semantics (Important)
- `feature-loop.sh` runs one pass per invocation; it does not auto-fix or auto-iterate code.
- Default behavior is fail-fast between major phases:
  - On first failed step, later steps are skipped.
  - Exit code is `2` when any step fails.
- Optional `--continue-on-fail` (or `FREUD_CONTINUE_ON_FAIL=true`) runs remaining steps for diagnostics, but run status is still `fail` if any step failed.
- `run-scenarios.sh` executes all listed scenarios in one run and reports aggregate pass/fail; it does not retry failing scenarios automatically.

### Artifact Locations
- All run outputs are isolated per run under:
  - `.psyke/runs/freud/<timestamp>-<feature-id>/`
- Fast-entry artifacts (read these first):
  - `artifacts/summary-compact.md`
  - `artifacts/summary.json` (includes triage + summarizer counters)
  - `artifacts/model-summary.json` (Tier-2 optional)
  - `artifacts/model-summary.md` (Tier-2 optional)
  - `artifacts/model-summary-attempts.tsv` (Tier-2 provider health/fallback trace)
  - `artifacts/model-summary-metrics.json` (Tier-2 token and attempt counters)
  - `artifacts/freud-metrics.json` (run-level + triage + summarizer counters)
  - `artifacts/trail-index.tsv`
  - `artifacts/step-index.tsv`
  - `artifacts/anomalies.md`
  - `artifacts/codex-context.md`
- Deep-dive artifacts:
  - `artifacts/step-meta/<step>.json`
  - `artifacts/log-index/<step>.tsv`
  - `logs/<nn-step>.log`
- Run pointers:
  - `.psyke/runs/freud/latest` (symlink)
  - `.psyke/runs/freud/latest-run.txt` (absolute run dir path)

### Standard Debug Sequence
1. Open `artifacts/summary.json` to identify first failing step and overall status.
2. Open `artifacts/trail-index.tsv` for event timeline and sequence.
3. Open `artifacts/step-index.tsv` for warnings/errors/line references by step.
4. Open `artifacts/step-meta/<failing-step>.json` for command, timing, counts, and first refs.
5. Only then inspect `logs/<step>.log` and `artifacts/log-index/<step>.tsv`.
6. Re-run Freud after fixes; compare new run against prior run using `run-index.json` and `summary.json`.

### Scenario Pack Rules
- Scenario manifest is JSON:
  - `freud/scenarios/v1/psyke-agent-scenarios.json`
- Add/update deterministic scenarios whenever behavior changes in agent loop policies, fallback, memory recall, or convergence behavior.
- Keep scenario selectors aligned with `src/test/kotlin/psyke/eval/AgentScenarioPackTest.kt`.

### Configuration Rules
- Keep project-specific commands in `freud/config/*.env`.
- Do not hardcode Psyke-specific commands in generic `freud/scripts/*.sh`.
- Default adapter file:
  - `freud/config/default.env`
- Optional override:
  - `FREUD_CONFIG=/path/to/adapter.env`
- Tier-2 summarizer knobs (optional):
  - `FREUD_SUMMARIZER_PROVIDER`, `FREUD_SUMMARIZER_MODEL`, `FREUD_SUMMARIZER_BASE_URL`
  - Default is `FREUD_SUMMARIZER_PROVIDER=auto` (uses first available key/provider).
  - Fallback and healthcheck knobs:
    - `FREUD_SUMMARIZER_ENABLE_FALLBACK`
    - `FREUD_SUMMARIZER_FALLBACK_ORDER`
    - `FREUD_SUMMARIZER_HEALTHCHECK_TIMEOUT_SEC`
    - `FREUD_SUMMARIZER_TOKEN_LIMIT` (default `1000000`)

### Token and Summarization Policy
- Use cost-tiered summarization:
  1. Indexed heuristics first (`summary-compact.md`, `trail-index.tsv`, `step-index.tsv`, `anomalies.json`).
  2. Optional cheap-model summarization via `FREUD_SUMMARIZER_CMD` (default adapter: `freud/scripts/cheap-summarizer-adapter.sh --if-configured`).
  3. Codex deep analysis and code edits last.
- Tier-2 adapter uses provider healthcheck + failover by default; review `artifacts/model-summary-attempts.tsv` when summarization fails.
- Tier-2 token usage ledger lives at `.freud/metrics/summarizer-usage.json`; once token limit is reached, Tier-2 emits warning and skips model calls.
- Token-limit hits are indexed in `artifacts/trail-index.tsv` as event `summarizer_budget_limit`.
- Avoid pasting full logs in prompts unless strictly needed.
- When handing off to another agent, provide artifact paths first, not raw log dumps.
- For standardized agent instructions, start from:
  - `freud/templates/agent-operator-template.md`

## Build and Test
- Preferred full verification:
  - `./gradlew test`
- For faster iteration, run targeted tests first, then full tests if core/shared code changed.
- If you cannot run tests, clearly state that in your final summary.
- Test execution policy for coding agents:
  - Fast local unit/integration tests with deterministic stubs are allowed in the default `./gradlew test` suite.
  - Tests that require real network calls, real provider APIs, or consume paid external tokens must be manual-only and run only when explicitly requested.

## Code Style
- Follow existing Kotlin style and package structure.
- Prefer small, explicit functions and descriptive names.
- Use existing abstractions (`SensoryCortex`, `MotorCortex`, `SuperegoGatekeeper`, instrumentation hooks) instead of duplicating logic.
- Keep logging and metrics instrumentation consistent with existing patterns.

## Change Summary Format
- When done, report:
  - What changed.
  - Why it changed.
  - How it was validated (tests/commands).
  - Any risks or follow-ups.

## Tool-Specific Files
- If a tool-specific instruction file exists (for example `CLAUDE.md`), keep it thin and aligned with this file.
- Avoid duplicating long instruction sets across multiple files.
