# psyke Kotlin app

Standalone Kotlin JVM app using Gradle with:
- Pluggable LLM client (`ChatModelClient`)
- Interactive Ego agent loop (inputs, thoughts, actions)
- Extensible input abstraction (`SensoryCortex`)
- Superego action gatekeeper (policy/safety review)
- Action executor (`MotorCortex`) for `web_search`, `answer`, `mcp_time`, and `mcp_fetch`

## For Coding Agents
- Repository-wide coding-agent instructions live in `AGENTS.md`.
- Keep `README.md` as the human-facing guide for setup, runtime, and architecture.
- If you add tool-specific files (for example `CLAUDE.md`), keep them short and aligned with `AGENTS.md`.

## Requirements
- JDK 23+
- Use the included Gradle wrapper (`./gradlew`)
- Kotlin currently emits bytecode up to Java 21; the build uses a JDK 23 toolchain while targeting 21-compatible bytecode for both Kotlin and Java.
- `MISTRAL_API_KEY` is required for interactive mode and for `--eval-reasoning-mode model`.

## Configuration
- Set `MISTRAL_API_KEY` to your API token.
- Optional:
  - `MISTRAL_EGO_MODEL` (default: `mistral-small-latest`)
  - `MISTRAL_SUPEREGO_MODEL` (default: same as Ego model)
  - `PSYKE_DASHBOARD_ENABLED` (default: `true`)
  - `PSYKE_DASHBOARD_PORT` (default: `8787`)
  - `EGO_MAX_LOOP_STEPS` (default: `180`)
  - `EGO_MAX_THOUGHT_PASSES` (default: `5`)
  - `EGO_MAX_PROMPT_TOKENS` (default: `2400`)
  - `EGO_MAX_COMPLETION_TOKENS` (default: `900`)
  - `EGO_LOOP_DELAY_MS` (default app value: `0`)
  - `EGO_MAX_MEMORY_CHARS` (default: `12000`)
  - `EGO_MAX_MEMORY_PROMPT_TOKENS` (default: `256`)
  - `EGO_MAX_ACTION_PAYLOAD_CHARS` (default: `4000`)
  - `EGO_SEARCH_RESULT_COUNT` (default: `5`)
  - `MCP_TIME_SERVER_CMD` (default: `uvx mcp-server-time`)
  - `MCP_FETCH_SERVER_CMD` (default: `uvx mcp-server-fetch`)
  - `MCP_MEMORY_SERVER_CMD` (optional; when set, enables Ego internal memory recall/imprint via MCP; Psyke starts it as a child process)
  - `MISTRAL_WEBSEARCH_AGENT_ID` (optional; if omitted, Psyke creates an ephemeral Mistral web-search agent per run)
  - `MCP_CALL_TIMEOUT_MS` (default: `8000`)
  - `MCP_MEMORY_CALL_TIMEOUT_MS` (default: same as `MCP_CALL_TIMEOUT_MS`)
  - `MCP_FETCH_MAX_CHARS` (default: `4000`)
  - `EGO_MEMORY_RECALL_MAX_ITEMS` (default: `4`)
  - `EGO_MEMORY_RECALL_MAX_CHARS` (default: `1200`)
  - `EGO_PRESSURE_MIN_STEP` (default: `16`)
  - `EGO_PRESSURE_ASSESS_EVERY_STEPS` (default: `8`)
  - `EGO_PRESSURE_ASSESS_THRESHOLD` (default: `0.68`)
  - `EGO_META_REASONER_COOLDOWN_STEPS` (default: `6`)
  - `EGO_META_REASONER_MAX_TOKENS` (default: `120`)
  - `EGO_MEMORY_CONSOLIDATION_EVERY_STEPS` (default: `8`)
  - `EGO_MEMORY_CONSOLIDATION_COOLDOWN_STEPS` (default: `4`)
  - `EGO_MEMORY_CONSOLIDATION_MIN_CONFIDENCE` (default: `0.65`)
  - `EGO_MEMORY_CONSOLIDATION_MAX_TOKENS` (default: `180`)
  - `EGO_MEMORY_CONSOLIDATION_MAX_SUMMARY_CHARS` (default: `320`)
  - `PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS` (reasoning eval raw-thought capture cap; default: unlimited)

## Run
```bash
export MISTRAL_API_KEY=your_token
./gradlew run
```

Enable trace logs:
```bash
./gradlew run -Dorg.slf4j.simpleLogger.defaultLogLevel=trace
```

Log `gradlew run` output to a file:
```bash
mkdir -p .psyke/logs
export PSYKE_LOG_FILE="$PWD/.psyke/logs/gradle-run.log"
./gradlew run -Dorg.slf4j.simpleLogger.defaultLogLevel=info
```

## Simpler Entrypoint (No `gradle run`)
Use the local launcher:
```bash
export MISTRAL_API_KEY=your_token
./run-psyke.sh
```

Run deterministic reasoning self-eval (no MotorCortex actions, no baseline comparison):
```bash
./run-psyke.sh --eval-reasoning-only
```

Reasoning eval options:
```bash
./run-psyke.sh --eval-reasoning-only --eval-reasoning-mode logic  # default; no external LLM calls
./run-psyke.sh --eval-reasoning-only --eval-reasoning-mode model  # uses MISTRAL_API_KEY
./run-psyke.sh --eval-reasoning-only --eval-stage 2026-02-28
./run-psyke.sh --eval-reasoning-only --eval-reasoning-max-attempts 5
./run-psyke.sh --eval-reasoning-only --eval-reasoning-tasks shape-lock,multi-fix  # logic mode tasks
./run-psyke.sh --eval-reasoning-only --log-level trace  # explicit override (already default for this mode)
```

Reasoning eval output:
- Per-run detailed JSON in `.psyke/evals/reasoning/runs/`.
- Append-only trend history in `.psyke/evals/reasoning/history.jsonl`.
- Default eval mode is `logic`, which uses a deterministic local harness to score retry/feedback loop behavior.
- `model` mode keeps the prior real-LLM task set for optional model-inclusive checks.
- If `--eval-stage` is omitted, stage defaults to current UTC date.
- Main run log focuses on eval flow (`[eval.reasoning] ...`) and full model thought text blocks (`thought.begin`/`thought.end`).
- Metadata-rich instrumentation events (including `llm.call`) are written to a per-run sidecar JSONL file.

Set a specific log level via parameter:
```bash
./run-psyke.sh --log-level info
```

Disable the default interactive delay for faster local/manual loops:
```bash
./run-psyke.sh --no-delay
```

Notes:
- `run-psyke.sh` bootstraps `installDist` once if needed.
- After bootstrap, execution is direct (`build/install/psyke/bin/psyke`) without `gradle run`.
- You do not run memory MCP separately if `MCP_MEMORY_SERVER_CMD` is set correctly; Psyke launches it on demand. `uv/uvx` is only needed when your command uses it.
- Default log level in `run-psyke.sh` is `warning`.
- Launcher logs are written to per-run files in `.psyke/logs/runs/`.
- `.psyke/logs/latest.log` always points to the newest run log.
- `.psyke/logs/latest-events.jsonl` always points to the newest event sidecar.
- `.psyke/logs/latest-run.env` stores `PSYKE_LOG_RUN_ID`, `PSYKE_LOG_FILE`, `PSYKE_EVENT_LOG_FILE`, and start time for the current run.
- Old run logs are auto-pruned; retention defaults to 30 files (`PSYKE_LOG_RETENTION`).
- Default loop delay in `run-psyke.sh` is `1000ms` (`--no-delay` or `--loop-delay-ms 0` disables it).
- `PSYKE_LOG_LEVEL` can still provide a default if `--log-level` is omitted.
- `PSYKE_LOG_DIR` overrides the log directory (default: `.psyke/logs`).
- `PSYKE_EVENT_LOG_FILE` overrides the event sidecar path (used by reasoning eval mode).
- By default the launcher persists metrics to `.psyke/metrics.db` (override with `PSYKE_METRICS_DB`).

Then interact:
```text
you> hello
you> search for latest kotlin release notes
you> exit
```

## Realtime dashboard
- Enabled by default and served locally on `http://127.0.0.1:8787/`.
- Uses async instrumentation (`InstrumentationBus`) with pluggable sinks:
  - `StructuredLogSink` (logs)
  - `DashboardStateStore` + SSE stream (UI)
- Queue, loop, thought/action flow, Superego in/out, and LLM events are streamed as typed events.
- Thought Chain / Timeline includes non-snapshot events, including `llm_raw_response` payloads.
- Queue snapshots are used for queue panels/counts, not shown as timeline items.
- If dashboard bind fails, app continues running without the dashboard server.

## Loop behavior
- Priority order: incoming inputs first.
- Inputs are ingested through `SensoryCortex` (extensible input-source abstraction).
- Input priority levels: `low` (1), `medium` (2, default), `high` (3).
- The built-in stdin source always submits with `high` priority.
- `MemoryStore` keeps bounded rolling memory and compacts older turns into a summary as it nears capacity.
- Memory summary included in Ego/Superego prompts is token-capped to stay within LLM context budgets.
- When `MCP_MEMORY_SERVER_CMD` is configured, Ego also runs internal `Hippocampus` memory recall per thought/input planning step (not a MotorCortex action).
- Ego tracks a `decision_pressure` signal to detect circular thought chains and increase convergence pressure.
- A separate MetaReasoner LLM call runs periodically under pressure to classify chain health (`continue`, `continue_with_constraints`, `finalize_now`, `request_tool_then_finalize`).
- A separate `MemoryConsolidationAdvisor` LLM call can run every N steps (default 8) and after allowed actions to decide if durable memory should be persisted.
- If consolidation says yes with enough confidence, Psyke generates a concise summary and writes it through `Hippocampus` imprint (MCP memory if configured).
- If no inputs are pending, thoughts and actions are scheduled by urgency (`high`, `medium`, `low`).
- Every proposed action includes a context summary capped at 180 chars.
- MotorCortex runs a startup capability smoke test and emits `action_capabilities` instrumentation.
- Ego planner receives runtime `available_action_types` and avoids proposing unavailable actions.
- Superego validates every action against directives before execution.
- If denied, denial reason (<=180 chars) is pushed back as a new thought.

## Superego directives
- Loaded at startup from `src/main/resources/superego/directives.txt`.
- Use one directive per line.
- Empty lines and lines starting with `#` are ignored.

## Metrics persistence
- SQLite storage records per-run and per-call data (`runs`, `llm_calls`).
- API keys are never stored raw; metrics use a salted key fingerprint.
- Call metrics include actor/call-site/action-type, latency, status, and token usage when returned by the model API.
- Instrumentation health is persisted per run, including dropped instrumentation events and queue-saturation hits.
- Superego token usage is tracked separately for both current run and persistent totals (still included in overall totals).
- Memory metrics are persisted per run and as persistent totals: recall attempts/hits/failures/truncation/latency/chars, consolidation assessments/save recommendations, and imprint attempts/success/failures/latency/chars.
