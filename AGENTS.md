# AGENTS.md

Instructions for coding agents working in this repository (Codex, Claude, Gemini/Google, Mistral, etc.).

## Purpose
- Use this file for execution and contribution rules.
- Use `README.md` for product and runtime documentation.

## Priority
- Follow system/developer/user instructions first.
- Then follow this file.
- If instructions conflict, use the highest-priority source.
- Before adding a new feature or fixing a problem with a new heuristic make sure that tuning or adjusting 
  an existing one does not achieve the same purpose. Tuning/adjusting is preferred.
- When adding fixes/heuristics, review if a refactor would resolve the same issue in a cleaner way. We like refactors and don't worry about backwards compatibility yet, but ask the user first for approval.

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

## Agent Logic Docs Maintenance (Required)
- Keep both `AGENT_LOGIC_SUMMARY.md` and `AGENT_LOGIC_DIAGRAM.md` accurate as living runtime logic docs.
- Whenever a change affects control flow or core behavior in any of these areas, update the relevant sections in both files in the same PR/patch:
  - Ego loop orchestration, scheduler ordering, or fallback behavior.
  - Planner decision schema/flow, action verifier behavior, or parsing fallback semantics.
  - Superego review behavior and default deny/allow semantics.
  - Deliberation pressure/meta-reasoner thresholds, overrides, or forced terminal answer rules.
  - Short-term/long-term memory recall, consolidation, or confidence/disable thresholds.
  - Available action types and their execution/runtime availability behavior.
- Keep docs readable for humans (architecture and decision flow first, implementation detail second).
- Keep diagrams simple and editable (Mermaid text blocks, small focused views instead of one large graph).

## Freud Workflow (Meta-Project)
- Preferred feature-delivery path: use `freud/` for coding, validation, and triage of non-trivial changes.
- Separation rule:
  - Freud code/config/workflow assets: `freud/**`
  - Psyke runtime and tests: `src/main/kotlin/psyke/**`, `src/test/kotlin/psyke/**`
  - Freud may read/run against all Psyke files, but Freud logic stays under `freud/**`.

### Required Commands
- Stub/deterministic-first run:
  - `freud/scripts/feature-loop.sh <feature-id>`
- Deterministic reasoning PR gate only:
  - `freud/scripts/run-reasoning-pr-gate.sh`
- Live-inclusive run (only when explicitly required):
  - `freud/scripts/feature-loop.sh <feature-id> --live`
- Weak-structure live reasoning lane:
  - `freud/scripts/feature-loop.sh <feature-id> --live --config freud/config/live-weak-structure.env`
- Production-routing live reasoning lane:
  - `freud/scripts/feature-loop.sh <feature-id> --live --config freud/config/live-prod-acceptance.env`
- Resume from a specific step (skips earlier steps, preserves artifact record):
  - `freud/scripts/feature-loop.sh <feature-id> --from-step <step>`
  - Valid step names: `preflight_compile targeted_tests full_tests scenario_pack reasoning_eval_logic reasoning_eval_model memory_live_smoke`
- Scenario-only run:
  - `freud/scripts/run-scenarios.sh --file freud/scenarios/v1/psyke-agent-scenarios.json`
- Single-input live eval (pipe one input, get one answer):
  - Preferred wrapper: `freud/scripts/live-eval.sh --input <file> [--expected <file>] [--timeout <seconds>]`
  - Replay a cached run: `freud/scripts/live-eval.sh --input <file> --cache-replay <cache.jsonl>`
  - Preserve isolated Freud memory for multi-step sequences: `freud/scripts/live-eval.sh --input <file> --preserve-memory`
- Dry-run inspection:
  - `freud/scripts/feature-loop.sh <feature-id> --dry-run`

### Live Eval Memory Isolation
- `live-eval.sh` uses an isolated memory environment to avoid polluting user data:
  - pgvector namespace: `freud-eval` (user default: `psyke`)
  - Episodic logbook: `.psyke/freud-logbook.db` (user default: `.psyke/logbook.db`)
  - Metrics DB: `.psyke/freud-metrics.db` (user default: `.psyke/metrics.db`)
- By default, all Freud-isolated memory is cleared before each run (`--clear-memory-all`).
- Use `--preserve-memory` or `FREUD_LIVE_EVAL_PRESERVE_MEMORY=true` only when a live eval intentionally depends on prior isolated Freud memory.
- LLM response caching: first run records all LLM responses to a JSONL cache file; subsequent runs with `--cache-replay` replay cached responses until a hash mismatch (divergence), then switch to real LLM calls.
- Cache env vars: `PSYKE_LLM_CACHE_MODE` (`record`/`replay`/`off`), `PSYKE_LLM_CACHE_FILE`.

### Failure Semantics (Important)
- `feature-loop.sh` runs one pass per invocation; it does not auto-fix or auto-iterate code.
- Default behavior is fail-fast between major phases:
  - On first failed step, later steps are skipped.
  - Exit code is `2` when any step fails.
- Optional `--continue-on-fail` (or `FREUD_CONTINUE_ON_FAIL=true`) runs remaining steps for diagnostics, but run status is still `fail` if any step failed.
- `run-scenarios.sh` executes all listed scenarios in one run and reports aggregate pass/fail; it does not retry failing scenarios automatically.

### Artifact Locations
- Feature-loop run outputs are isolated per run under:
  - `.psyke/runs/freud/<timestamp>-<feature-id>/`
- Live-eval run outputs under:
  - `.psyke/runs/freud/<timestamp>-live-eval/`
  - Includes: `artifacts/answer.txt`, `artifacts/verdict.json`, `artifacts/cache-stats.json`, `artifacts/llm-cache.jsonl` (record mode)
- BBH smoke aggregate artifacts are written under the active Freud run:
  - `artifacts/bbh-smoke-<lane>-summary.json`
  - `artifacts/bbh-smoke-<lane>-summary.md`
  - `artifacts/bbh-smoke-<lane>-results.tsv`
- Fast-entry artifacts (read these first):
  - `artifacts/summary-compact.md`
  - `artifacts/summary.json` (includes triage counters)
  - `artifacts/freud-metrics.json` (run-level + triage counters)
  - `artifacts/trail-index.tsv`
  - `artifacts/step-index.tsv`
  - `artifacts/anomalies.md`
  - `artifacts/context-pack.md`
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

### Architecture
- Orchestration (feature-loop.sh, run-scenarios.sh) remains in Bash.
- Data-processing scripts (triage, summarize, context-pack, telemetry) are implemented in Python (`freud/py/`) and invoked via thin shell wrappers.
- Python modules use stdlib only (no external dependencies). Tests use pytest.
- Shell wrappers set `PYTHONPATH` and `exec python3 -m freud.py.<module>`.

### Configuration Rules
- Keep project-specific commands in `freud/config/*.env`.
- Do not hardcode Psyke-specific commands in generic `freud/scripts/*.sh`.
- Do not commit local machine paths in Freud configs or docs. Resolve repo-local files relative to the config/script location or repo root.
- Prefer `freud/scripts/live-eval.sh` for any single-input live/provider-backed Freud check. Treat raw `./run-psyke.sh --freud-live` as a lower-level debugging path or implementation primitive.
- Default adapter file:
  - `freud/config/default.env`
- Optional override:
  - `FREUD_CONFIG=/path/to/adapter.env`
- Live reasoning lane configs:
  - `freud/config/live-weak-structure.env`
  - `freud/config/live-prod-acceptance.env`
- Frozen LLM routing snapshots for live lanes:
  - `freud/config/llm-weak-structure.yaml`
  - `freud/config/llm-prod-acceptance.yaml`

### Reasoning Eval Matrix
- Freud owns the reasoning eval matrix for Psyke:
  - `logic-gate`: deterministic PR gate via `run-reasoning-pr-gate.sh`
  - `weak-structure-live`: manual live lane using weaker planner/meta-reasoner routing
  - `prod-acceptance-live`: manual live lane using frozen production routing
- `reasoning_eval_logic` in the default feature loop runs two deterministic passes:
  - logic core (`shape-lock`, `feedback-carry`, `multi-fix`)
  - logic behavioral pack (45 deterministic perturbation tasks)
- `reasoning_eval_model` remains the live/manual lane and runs the BBH-style smoke suite through `freud/scripts/run-bbh-smoke.sh`.
- BBH/live reasoning wrappers should call `freud/scripts/live-eval.sh`, which in turn uses `./run-psyke.sh --freud-live`.
- Strict JSON support for planner/meta-reasoner is a hard requirement in live lanes; any structured-output downgrade is treated as a lane failure.
### Summarization Policy
- Use heuristic summarization: indexed artifacts first (`summary-compact.md`, `trail-index.tsv`, `step-index.tsv`, `anomalies.json`), then AI deep analysis and code edits last.
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

## Architecture Patterns (Required)

These patterns encode lessons from post-implementation reviews.
Violating them will cause the same class of bugs to recur.

### Per-Input State Reset
Any component that holds **per-input-loop state** (counters, step indices,
flags that track progress within one user turn) must expose a
`resetForNewInput()` method. `Ego` calls it on every component at the end
of the input loop.

- Correct: `MemoryCoordinator.resetForNewInput()` resets `lastConsolidationStep`.
- Wrong: relying on the orchestrator to remember to zero-out individual fields.
- When adding a new counter/index to a class, ask: "Does this need to reset each turn?"
  If yes, add it to that class's `resetForNewInput()` and verify the call site in `Ego`.

### Thread Safety for Shared Mutable State
- Sink registries and callback lists that are read/written from multiple threads
  must use `CopyOnWriteArrayList`, not `ArrayList` or `mutableListOf()`.
- Fields that can be set from one thread and read from another must be `@Volatile`.
- Never use a plain `var` or `MutableList` for anything touched by both the agent
  loop thread and a registration/configuration thread.
- Canonical example: `InstrumentationBus` uses `CopyOnWriteArrayList` for sinks;
  `MetricsEventSink.instrumentation` is `@Volatile`.

### Late-Binding / Setter Injection
Components that receive a dependency after construction (e.g. metrics hooks wired
by the runtime, not the constructor) must follow the setter-injection pattern:

```kotlin
@Volatile private var myDep: MyDep? = null
fun setMyDep(dep: MyDep) { myDep = dep }
```

Do not capture such dependencies in the primary constructor — the wiring happens
after instantiation, so constructor capture will silently hold `null`.

### LLM Caller Standard Pattern
Every class that calls an LLM (`Superego`, `MetaReasoner`, `LongTermMemoryAdvisor`,
and any future caller) must follow all three sub-rules:

1. **Retry loop**
   ```kotlin
   val attempts = maxOf(1, config.planner.llmRetryAttempts)
   for (attempt in 1..attempts) {
       try { response = modelClient.chat(...); break }
       catch (ex: Exception) {
           if (attempt < attempts) logger.warn(ex) { "... retrying (attempt $attempt/$attempts)" }
           else logger.warn(ex) { "... failed after $attempts attempts" }
       }
   }
   ```
2. **Required-field validation** — after JSON deserialization, check every required
   field for `null`/blank before using it. Log a warning and return the safe
   fallback if any required field is missing.
3. **Safe fallback** — on exhaustion or parse failure, return a well-defined
   fallback value (never throw or return `null` from a non-nullable return type).

Do not add a new LLM caller without all three sub-rules in place.

### Named Constants for Numeric Thresholds
Magic numbers (character limits, ratios, step counts, token caps) must be
extracted to `const val` entries in the `companion object` of the class that
owns the logic. Never scatter bare literals across multiple methods.

```kotlin
companion object {
    const val TURN_CONTENT_MAX_CHARS: Int = 700
    const val SUMMARY_CAP_RATIO: Double = 0.60
}
```

Reference only the named constant inside the class body. This makes threshold
changes a one-line edit with a clear name at the call site.
If a threshold should be runtime-tunable, expose it as a domain config knob
(`PlannerConfig` / `MemoryConfig` / etc.) with a named default.

### Domain-Grouped Configuration
`AgentConfig` is a **container** of domain sub-configs + infrastructure fields.
Do not add new fields directly to `AgentConfig`.

| Sub-config | Owns |
|---|---|
| `PlannerConfig` | loop limits, token caps, retry attempts |
| `SuperegoConfig` | superego-specific completion limits |
| `MemoryConfig` | short/long-term memory knobs, MCP timeouts |
| `MetaReasonerConfig` | deliberation pressure thresholds, cooldown, max tokens |
| `LogbookConfig` | episodic memory: enabled, summary/keyword limits, retention days, DB path, episodic recall limits, LLM summarizer toggle |

When adding a new knob, put it in the matching sub-config. If none fits, create
a new sub-config and add it as a field on `AgentConfig`. Access paths follow the
pattern `config.<domain>.<field>` (e.g. `config.planner.llmRetryAttempts`).

## Code Style
- Follow existing Kotlin style and package structure.
- Prefer small, explicit functions and descriptive names.
- Use existing abstractions (`SensoryCortex`, `MotorCortex`, `SuperegoGatekeeper`, instrumentation hooks) instead of duplicating logic.
- Keep logging and metrics instrumentation consistent with existing patterns.

## Logging Practices (Required)
- Log for diagnosis, not verbosity: every warning/error must include enough context to locate the failing path quickly.
- Include stable keys in log messages for machine filtering:
  - operation/tool name
  - namespace/tenant or caller scope (when applicable)
  - attempt number for retries
  - bounded input metadata (length/count), never raw sensitive payloads by default
- Use consistent outcome logs for external calls:
  - start (optional at debug)
  - success (debug/info with latency + status)
  - failure (warn/error with exception + key context)
- For retries, log both:
  - intermediate retryable failures (`warn` with `attempt x/y`)
  - terminal failure after max attempts (`warn`/`error` with total attempts)
- Never log secrets or tokens (API keys, Authorization headers, credentials).
- Prefer structured metrics for high-volume signals; reserve high-cardinality details for debug logs.
- If a component has health checks, ensure the health-check failure message distinguishes dependency class (network, auth, storage, transport) and includes actionable next step text.

## Change Summary Format
- When done, report:
  - What changed.
  - Why it changed.
  - How it was validated (tests/commands).
  - Any risks or follow-ups.

## Tool-Specific Files
- If a tool-specific instruction file exists (for example `CLAUDE.md`), keep it thin and aligned with this file.
- Avoid duplicating long instruction sets across multiple files.
