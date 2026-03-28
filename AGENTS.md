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
- This is an unreleased prototype. All changes should prioritize architectural clarity over backwards compatibility. Rename freely, restructure fearlessly, and never add compatibility shims or aliases for old names. Clean breaks are always preferred.

## Project Snapshot
- Language: Kotlin (JVM), Gradle Kotlin DSL.
- Main source: `src/main/kotlin/ai/neopsyke`.
- Tests: `src/test/kotlin/ai/neopsyke`.
- Entrypoints:
  - `./gradlew run`
  - `./run-neopsyke`

## Environment
- Requires JDK 21+.
- Runtime needs `MISTRAL_API_KEY`.
- Optional runtime flags and env vars are documented in `README.md`.

## Branch Naming
Use the format `<type>/<short-description>` with lowercase kebab-case:

| Pattern | Example |
|---|---|
| `feat/<description>` | `feat/memory-recall-boost` |
| `fix/<description>` | `fix/planner-null-fallback` |
| `refactor/<description>` | `refactor/prompt-budget` |
| `docs/<description>` | `docs/freud-workflow` |
| `test/<description>` | `test/scenario-pack-coverage` |
| `chore/<description>` | `chore/gradle-upgrade` |
| `ci/<description>` | `ci/add-lint-step` |

- Keep descriptions short (2-4 words).
- Use the same type prefixes as commit messages (see below).

## Commit Messages
Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>
```

- **type** (required): `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`.
- **scope** (optional but encouraged): the module or area touched — e.g. `memory`, `planner`, `ego`, `superego`, `freud`, `logbook`.
- **summary**: imperative mood, lowercase, no trailing period, under 72 characters.
- **Breaking changes**: use `!` after scope — `feat(planner)!: remove legacy action format` — or add a `BREAKING CHANGE:` footer.

Good:
- `feat(memory): add episodic recall confidence threshold`
- `fix(planner): handle null action in fallback path`
- `refactor(ego): simplify reset-for-new-input flow`
- `test(scenarios): cover multi-fix convergence case`

Bad:
- `updated stuff` (no type, vague)
- `Fix bug` (no scope, no detail, capitalized)
- `feat: memory recall confidence threshold added for episodic memory to improve quality of recalls` (too long)

## Working Rules
- Keep changes focused and minimal.
- Do not make unrelated refactors.
- Do not commit secrets, API keys, or local machine paths.
- Prefer ASCII in docs/code unless the file already uses Unicode.
- Preserve existing behavior unless the user asked for behavior changes.
- When fixing tests, always prioritize understanding the feature and making sure the root
  cause is addressed instead of making the test just pass.
- If you find a failing test, flaky test, even if unrelated to current changes
  make sure to find the root cause and fix it. Every work session must end with all
  tests running and stable. 

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
  - NeoPsyke runtime and tests: `src/main/kotlin/ai/neopsyke/**`, `src/test/kotlin/ai/neopsyke/**`
  - Freud may read/run against all NeoPsyke files, but Freud logic stays under `freud/**`.

### Required Commands

Bootstrap Freud first: `./freud/bootstrap.sh`

- Preferred local binary path: `./freud/bin/freud`
- Freud harness home stays under the top-level `freud/` directory.

- Default deterministic completion/signoff gate:
  - `./freud/bin/freud run ci-pr`
  - This runs, in order: `preflight_compile`, `full_tests`, `scenario_pack`, `reasoning_eval_logic`
  - No commit, no "done", and no claim of full validation until this command has been run non-dry and has passed.
- Stub/deterministic-first run:
  - `./freud/bin/freud run <feature-id>`
- Live-inclusive run (only when explicitly required):
  - `./freud/bin/freud run <feature-id> --live --lane low-llm`
- Low-cost LLM live reasoning lane:
  - `./freud/bin/freud run <feature-id> --live --lane low-llm`
- Production-routing live reasoning lane:
  - `./freud/bin/freud run <feature-id> --live --lane high-llm`
- Resume from a specific step (skips earlier steps, preserves artifact record):
  - `./freud/bin/freud run <feature-id> --from-step <step>`
  - Valid step names: `preflight_compile targeted_tests full_tests scenario_pack reasoning_eval_logic reasoning_eval_model memory_live_smoke test_replay_eval`
- Run only a specific step:
  - `./freud/bin/freud run <feature-id> --only <step>`
- Single-input live eval (pipe one input, get one answer):
  - `./freud/bin/freud eval --input <file> [--expected <file>] [--timeout <seconds>]`
  - Replay a cached run: `./freud/bin/freud eval --input <file> --cache-replay <cache.jsonl>`
  - Record a session for replay: `./freud/bin/freud eval --input <file> --record-session`
- Session replay:
  - `./freud/bin/freud test-replay-eval [--input <file>] [--timeout <seconds>]`
  - E2E test: records a live-eval, replays it, asserts all LLM calls cached and zero divergences.
  - For standalone replay of a recorded session: `./freud/bin/freud eval --session-replay <run-dir>`
- BBH reasoning smoke suite:
  - `./freud/bin/freud bbh --lane low-llm`
  - `./freud/bin/freud bbh --lane high-llm`
- Run analysis on a completed run:
  - `./freud/bin/freud triage [run_dir]` — anomaly detection (defaults to latest run)
  - `./freud/bin/freud summarize [run_dir]` — compact summary
  - `./freud/bin/freud context-pack [run_dir]` — package for LLM analysis
- Dry-run inspection:
  - `./freud/bin/freud run <feature-id> --dry-run`
- Override any config key for one run:
  - `./freud/bin/freud run <feature-id> -o live_eval.timeout=60`

### Live Eval Per-Run Isolation
- Each `./freud/bin/freud eval` run gets fully isolated persistent state inside its run directory:
  - `$RUN_DIR/state/logbook.db` (episodic memory)
  - `$RUN_DIR/state/metrics.db` (usage metrics)
  - `$RUN_DIR/state/action-control.db` (action staging)
  - pgvector namespace: `freud-eval-{run-id}` (unique per run)
- Every run starts with a clean state directory. No `--clear-memory-all` needed.
- Parallel live-eval runs are safe: each has its own directory and namespace.
- User data (namespace `neopsyke`, `.neopsyke/logbook.db`, `.neopsyke/metrics.db`) is never touched.
- Run directories older than `retention_days` (default 3, configurable in `freud/config/freud.yaml`) are auto-deleted after each run. pgvector namespaces for deleted runs are cleaned up best-effort.
- LLM response caching: first run records all LLM responses to a JSONL cache file; subsequent runs with `--cache-replay` replay cached responses until a hash mismatch (divergence), then switch to real LLM calls. The LLM cache hash strips volatile tokens (UUIDs, timestamps) to prevent false divergence.
- Cache env vars: `NEOPSYKE_LLM_CACHE_MODE` (`record`/`replay`/`off`), `NEOPSYKE_LLM_CACHE_FILE`.

### Session Recording and Replay
- Record all non-deterministic inputs during a live-eval or interactive session for deterministic replay.
- Recording captures 6 channels: signals, LLM calls, memory recall, logbook recall, web search results, action control decisions.
- Each channel uses hash-based divergence detection. On mismatch, that channel switches to passthrough (live) independently.
- Record: `./freud/bin/freud eval --input <file> --record-session` or `./run-neopsyke --record-session`
- Replay: `./freud/bin/freud eval --session-replay <run-dir>`
- E2E test: `./freud/bin/freud test-replay-eval`
- Session recording files live in `$RUN_DIR/session/`:
  - `signals.jsonl`, `llm-cache.jsonl`, `memory-recall.jsonl`, `logbook-recall.jsonl`, `web-results.jsonl`, `action-control.jsonl`, `session-manifest.json`
- Session replay telemetry: `artifacts/session-replay-stats.json` (per-channel hit/divergence counts)
- Env vars: `NEOPSYKE_SESSION_RECORDING_MODE` (`record`/`replay`/`off`), `NEOPSYKE_SESSION_RECORDING_DIR`.

### Failure Semantics (Important)
- `./freud/bin/freud run` runs one pass per invocation; it does not auto-fix or auto-iterate code.
- Default behavior is fail-fast between major phases:
  - On first failed step, later steps are skipped.
  - Exit code is `2` when any step fails.
- Optional `--continue-on-fail` (or `runtime.continue_on_fail: true` in config) runs remaining steps for diagnostics, but run status is still `fail` if any step failed.
- Scenario pack executes all listed scenarios in one run and reports aggregate pass/fail; it does not retry failing scenarios automatically.

### Concurrency Policy (Important)
- Never run overlapping Gradle-backed commands in the same checkout/worktree.
- Treat all of these as Gradle-backed and therefore not parallel-safe in one checkout:
  - raw `./gradlew ...`
  - `./freud/bin/freud run ...`
- `./freud/bin/freud run --live` is also not parallel-safe with other Gradle-backed commands because it still runs the deterministic Gradle phases before the live steps.
- If parallel validation is needed, use separate git worktrees or separate clones so each run has its own `build/` outputs and Gradle/Kotlin state.
- Safe to overlap in the same checkout:
  - artifact/log inspection
  - `--dry-run` inspection commands
  - `./freud/bin/freud triage`, `./freud/bin/freud summarize`, `./freud/bin/freud context-pack`
- Conditionally safe to overlap in the same checkout:
  - `./freud/bin/freud eval ...`
  - `./freud/bin/freud bbh ...`
- The live commands above may overlap only when all of these are true:
  - they are not running at the same time as any Gradle-backed command
  - you do not rely on shared `latest` pointers as stable ownership markers because the last writer wins
- `./freud/bin/freud eval` runs are parallel-safe by default: each run has fully isolated per-run state (logbook, metrics, action-control DBs in `$RUN_DIR/state/`, unique pgvector namespace).

### Artifact Locations
- Feature-loop run outputs are isolated per run under:
  - `.neopsyke/runs/freud/<timestamp>-<feature-id>/`
- Live-eval run outputs under:
  - `.neopsyke/runs/freud/<timestamp>-live-eval/`
  - Includes: `artifacts/answer.txt`, `artifacts/verdict.json`, `artifacts/cache-stats.json`, `artifacts/session-replay-stats.json` (replay mode)
  - Per-run state: `state/logbook.db`, `state/metrics.db`, `state/action-control.db`, `state/pgvector-namespace.txt`
  - Session recording (when `--record-session`): `session/signals.jsonl`, `session/llm-cache.jsonl`, `session/memory-recall.jsonl`, `session/logbook-recall.jsonl`, `session/web-results.jsonl`, `session/action-control.jsonl`, `session/session-manifest.json`
- BBH smoke aggregate artifacts are written under the active Freud run:
  - `artifacts/bbh-smoke-<lane>-summary.json`
  - `artifacts/bbh-smoke-<lane>-summary.md`
  - `artifacts/bbh-smoke-<lane>-progress.json`
  - `artifacts/bbh-smoke-<lane>-progress.md`
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
- Run index (append-only, concurrent-safe):
  - `.neopsyke/runs/freud/run-index.tsv` (columns: timestamp, feature_id, run_dir, status)
  - `./freud/bin/freud triage` with no args reads the last line automatically
  - Agents can grep for their feature ID to find their specific run

### Standard Debug Sequence
1. Open `artifacts/summary.json` to identify first failing step and overall status.
2. Open `artifacts/trail-index.tsv` for event timeline and sequence.
3. Open `artifacts/step-index.tsv` for warnings/errors/line references by step.
4. Open `artifacts/step-meta/<failing-step>.json` for command, timing, counts, and first refs.
5. Only then inspect `logs/<step>.log` and `artifacts/log-index/<step>.tsv`.
6. Re-run Freud after fixes; compare new run against prior run using `run-index.json` and `summary.json`.

### Scenario Pack Rules
- Scenario manifest is JSON:
  - `freud/scenarios/v1/neopsyke-agent-scenarios.json`
- Add/update deterministic scenarios whenever behavior changes in agent loop policies, fallback, memory recall, or convergence behavior.
- Keep scenario selectors aligned with `src/test/kotlin/ai/neopsyke/eval/AgentScenarioPackTest.kt`.

### Architecture
- The unified Freud CLI is a Go binary built from `freud/cli/` with Cobra + Viper.
- Shared Go libraries live under `freud/internal/` (`analysis`, `config`, `orchestrator`).
- All orchestration is native Go. Pipeline steps are either shell commands (e.g. `./gradlew test`) or built-in functions dispatched by name (e.g. `scenario_pack`, `reasoning_eval_logic`).
- Analysis subcommands (`triage`, `summarize`, `context-pack`) are native Go.
- The shell scripts in `freud/legacy/scripts/` and Python modules in `freud/legacy/py/` are legacy code, no longer called by the CLI.
- Configuration is a single YAML file (`freud/config/freud.yaml`) with optional profile overlays (`freud/config/profiles/*.yaml`). Precedence: CLI flag > env var > profile > YAML > built-in defaults.

### Configuration Rules
- All Freud configuration lives in `freud/config/freud.yaml`.
- Do not commit local machine paths in Freud configs or docs. Resolve repo-local files relative to the repo root.
- Prefer `./freud/bin/freud eval` for any single-input live/provider-backed Freud check. Treat raw `./run-neopsyke --freud-live` as a lower-level debugging path or implementation primitive.
- Default config file:
  - `freud/config/freud.yaml`
- Optional override:
  - `./freud/bin/freud run <id> -c /path/to/custom.yaml`
  - Or: `FREUD_CONFIG=/path/to/custom.yaml`
- Live reasoning lane profiles:
  - `freud/config/profiles/low-llm.yaml` (low-cost LLM routing)
  - `freud/config/profiles/high-llm.yaml` (production LLM routing)
- Frozen LLM routing snapshots for live lanes:
  - `freud/config/llm-weak-structure.yaml`
  - `freud/config/llm-prod-acceptance.yaml`

### Reasoning Eval Matrix
- Freud owns the reasoning eval matrix for NeoPsyke:
  - `logic-gate`: deterministic PR gate (step `reasoning_eval_logic`)
  - `low-llm`: live lane using lower-cost planner/meta-reasoner routing
  - `high-llm`: live lane using frozen production routing
- `reasoning_eval_logic` in the default feature loop runs two deterministic passes:
  - logic core (`shape-lock`, `feedback-carry`, `multi-fix`)
  - logic behavioral pack (45 deterministic perturbation tasks)
- `reasoning_eval_model` remains the live/manual lane and runs the BBH-style smoke suite via `./freud/bin/freud bbh --lane <name>`.
- Strict JSON support for planner/meta-reasoner is a hard requirement in live lanes; any structured-output downgrade is treated as a lane failure.
- `test_replay_eval` is an optional live step that records a live-eval, replays it, and asserts all LLM calls were cached and zero session channel divergences. Also available as standalone command: `./freud/bin/freud test-replay-eval`.

### Summarization Policy
- Use heuristic summarization: indexed artifacts first (`summary-compact.md`, `trail-index.tsv`, `step-index.tsv`, `anomalies.json`), then AI deep analysis and code edits last.
- Avoid pasting full logs in prompts unless strictly needed.
- When handing off to another agent, provide artifact paths first, not raw log dumps.
- For standardized agent instructions, start from:
  - `freud/templates/agent-operator-template.md`

## Build and Test
- Build the Freud CLI: `./freud/bootstrap.sh`
- Default completion/signoff verification:
  - `./freud/bin/freud run ci-pr`
- Expected deterministic gate coverage/order:
  - `preflight_compile`
  - `full_tests`
  - `scenario_pack`
  - `reasoning_eval_logic`
- `./freud/bin/freud run ci-pr --dry-run` is inspection only. It does not count as validation.
- No commit, no signoff, and no final "validated" report until non-dry `./freud/bin/freud run ci-pr` passes.
- `./gradlew test` is not sufficient for signoff. It covers the Kotlin/JVM test
  suite, but it does not cover the Freud deterministic scenario pack or the
  deterministic reasoning eval gate.
- For faster iteration, targeted subsets are fine:
  - `./gradlew test`
  - specific `./gradlew :test --tests ...`
  - `./freud/bin/freud run <id> --only scenario_pack --dry-run`
  - `./freud/bin/freud run <id> --from-step scenario_pack`
- Before reporting work as fully validated on a non-trivial change, use the
  deterministic Freud gate and report validation based on that result.
- If you cannot run tests, clearly state that in your final summary.
- Test execution policy for coding agents:
  - Fast local unit/integration tests with deterministic stubs are allowed in the default `./gradlew test` suite.
  - Tests that require real network calls, real provider APIs, or consume paid external tokens must be manual-only and run only when explicitly requested.
  - `cd freud && go test ./...` should stay fast and non-live by default.
  - Optional mocked CLI command-path tests live behind `cd freud && go test -tags=cli_e2e ./cli`.
  - Real replay validation belongs to explicit Freud commands/live steps such as `./freud/bin/freud test-replay-eval`, not the default Go test suite.

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

### No LLM-Relayed Internal Identifiers
Never trust the LLM to relay internal identifiers. If it's resolvable from
object metadata or scope, resolve it in the execution layer. LLMs hallucinate
and mangle UUIDs; the action handler must derive system-internal references
(artifact IDs, scope keys, etc.) from the action's own metadata
(`rootInputId`, `conversationContext`) rather than parsing them from the
planner payload.

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

### Zero Compiler Warnings Policy
The build must produce **zero** Kotlin compiler warnings. Before opening a PR,
run `./gradlew compileKotlin compileTestKotlin` and verify no `w:` lines appear.
Common pitfalls and their fixes:

- **Deprecated `JsonNode.asText(defaultValue)`** — use `node.path("key").asText()`
  (returns `""` for missing nodes) or `.asText().ifEmpty { fallback }` when a
  non-empty fallback is needed. For null-default cases use
  `if (node.isTextual) node.asText() else null`.
- **`@JsonProperty` on data-class constructor params** — always use the explicit
  target `@param:JsonProperty("snake_name")` to silence KT-73255.
- **`ObjectMapper.configure(MapperFeature, Boolean)`** — use the builder API:
  `JsonMapper.builder(factory).enable(MapperFeature.X).disable(DeserializationFeature.Y).build()`.
- When a new Jackson or Kotlin deprecation surfaces, fix it immediately rather
  than suppressing the warning.

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
