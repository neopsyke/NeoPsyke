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
- JDK 21+
- Use the included Gradle wrapper (`./gradlew`)
- Gradle can run on JDK 21+; Kotlin and Java compilation emit Java 21-compatible bytecode.
- LLM API key is required for interactive mode and for `--eval-reasoning-mode model`:
  - Set keys for every provider used by configured cognitive roles in `llm-runtime.yaml`.
  - Example mixed setup: `GOOGLE_API_KEY` for planner/meta-reasoner and `GROQ_API_KEY` for superego/verifier/memory-advisor.
  - If `web_search.provider` is different, set that provider key too.

## Configuration
- LLM settings are centralized in `llm-runtime.yaml` (repository root).
  - Cognitive role routing is set in `cognitive_roles`:
    - `planner`, `action_verifier`, `superego`, `meta_reasoner`, `memory_advisor`
  - Each cognitive role can set independent `provider` and `model`.
  - Provider credentials/endpoints are set under `providers` (`api_key_env`, `base_url`).
  - `web_search` remains independently configurable (`provider`, `model`, optional `api_key_env`, `base_url`).
  - If a role/model is omitted, Psyke falls back to provider defaults.
  - Optional override file path: `PSYKE_LLM_CONFIG_FILE=/path/to/llm-runtime.yaml`.
  - Example:
    ```yaml
    providers:
      groq:
        api_key_env: GROQ_API_KEY
      google:
        api_key_env: GOOGLE_API_KEY

    cognitive_roles:
      planner:
        provider: google
        model: gemini-3.1-flash-lite-preview
      action_verifier:
        provider: groq
        model: openai/gpt-oss-20b
      superego:
        provider: groq
        model: openai/gpt-oss-safeguard-20b
      meta_reasoner:
        provider: google
        model: gemini-3.1-flash-lite-preview
      memory_advisor:
        provider: groq
        model: openai/gpt-oss-20b

    web_search:
      provider: mistral
      model: mistral-small-latest
    ```
- LLM auth env vars (configured by `providers.<name>.api_key_env` and optional `web_search.api_key_env`):
  - `GROQ_API_KEY`
  - `MISTRAL_API_KEY`
  - `GOOGLE_API_KEY`
- MCP/time/fetch/memory provider settings are now centralized in `mcp-runtime.yaml` (repository root).
  - Default config enables `time`, `fetch`, and `memory` in `stdio` mode with command/fallback lists.
  - Optional override file path: `PSYKE_MCP_CONFIG_FILE=/path/to/mcp-runtime.yaml`.
  - Environment variables still override YAML when present (`MCP_TIME_*`, `MCP_FETCH_*`, `MCP_MEMORY_*`, plus legacy `MCP_*_SERVER_CMD`).
  - YAML schema per capability:
    - `enabled` (`true|false`)
    - `mode` (`stdio` currently supported)
    - `provider` (label/selector for provider intent)
    - `command` (primary command string)
    - `fallback_commands` (list of command strings; first executable in `PATH` is used)
- Agent/app/eval runtime settings are centralized in `agent-runtime.yaml` (repository root).
  - Optional override file path: `PSYKE_AGENT_CONFIG_FILE=/path/to/agent-runtime.yaml`.
  - `agent` section covers loop/token/pressure/runtime knobs.
  - `app` section covers UI/runtime flags (for example dashboard enablement and port).
  - `eval` section covers eval defaults (for example stage default and raw-response cap).
  - Precedence is `CLI > env > YAML > code defaults`.
- Optional:
  - `PSYKE_LLM_CONFIG_FILE` (optional; path to LLM runtime YAML, default: `./llm-runtime.yaml`)
  - `PSYKE_MCP_CONFIG_FILE` (optional; path to MCP runtime YAML, default: `./mcp-runtime.yaml`)
  - `PSYKE_AGENT_CONFIG_FILE` (optional; path to agent/app/eval runtime YAML, default: `./agent-runtime.yaml`)
  - `PSYKE_DASHBOARD_ENABLED` (default: `true`)
  - `PSYKE_DASHBOARD_PORT` (default: `8787`)
  - `EGO_MAX_LOOP_STEPS` (default: `180`)
  - `EGO_MAX_THOUGHT_PASSES` (default: `5`)
  - `EGO_MAX_PROMPT_TOKENS` (default: `2400`)
  - `EGO_MAX_COMPLETION_TOKENS` (default: `900`)
  - `EGO_MAX_RUN_TOTAL_TOKENS` (default: `0`, disabled)
  - `EGO_MAX_RUN_TOKENS_PER_PROVIDER` (default: `0`, disabled)
  - `EGO_MAX_RUN_TOKENS_PER_ROLE` (default: `0`, disabled)
  - `EGO_LLM_RETRY_ATTEMPTS` (default: `2`)
  - `EGO_SUPEREGO_MAX_COMPLETION_TOKENS` (default: `192`)
  - `EGO_LOOP_DELAY_MS` (default app value: `0`)
  - `EGO_SHORT_TERM_CONTEXT_MAX_CHARS` (default: `20000`)
  - `EGO_SHORT_TERM_CONTEXT_MAX_PROMPT_TOKENS` (default: `384`)
  - `EGO_MAX_ACTION_PAYLOAD_CHARS` (default: `4000`)
  - `EGO_SEARCH_RESULT_COUNT` (default: `5`)
  - `MCP_TIME_SERVER_CMD` (optional env override for YAML time command)
  - `MCP_FETCH_SERVER_CMD` (optional env override for YAML fetch command)
  - `MCP_MEMORY_SERVER_CMD` (optional env override for YAML memory command)
  - `MCP_TIME_MODE` / `MCP_FETCH_MODE` / `MCP_MEMORY_MODE` (optional env override for YAML mode)
  - `MCP_TIME_PROVIDER` / `MCP_FETCH_PROVIDER` / `MCP_MEMORY_PROVIDER` (optional env override for YAML provider)
  - `MCP_TIME_ENABLED` / `MCP_FETCH_ENABLED` / `MCP_MEMORY_ENABLED` (optional env override for YAML enabled flag)
  - `MISTRAL_WEBSEARCH_AGENT_ID` (optional when `web_search.provider=mistral`; if omitted, Psyke creates an ephemeral Mistral web-search agent per run)
  - `MCP_CALL_TIMEOUT_MS` (default: `8000`)
  - `MCP_MEMORY_CALL_TIMEOUT_MS` (default: same as `MCP_CALL_TIMEOUT_MS`)
  - `MCP_FETCH_MAX_CHARS` (default: `4000`)
  - `EGO_LONG_TERM_MEMORY_RECALL_MAX_ITEMS` (default: `4`)
  - `EGO_LONG_TERM_MEMORY_RECALL_MAX_CHARS` (default: `1200`)
  - `EGO_PRESSURE_MIN_STEP` (default: `16`)
  - `EGO_PRESSURE_ASSESS_EVERY_STEPS` (default: `8`)
  - `EGO_PRESSURE_ASSESS_THRESHOLD` (default: `0.68`)
  - `EGO_FORCE_TERMINAL_PRESSURE_THRESHOLD` (default: `0.98`)
  - `EGO_FORCE_TERMINAL_STALE_STREAK_THRESHOLD` (default: `8`)
  - `EGO_META_REASONER_COOLDOWN_STEPS` (default: `6`)
  - `EGO_META_REASONER_MAX_TOKENS` (default: `120`)
  - `EGO_LONG_TERM_MEMORY_ASSESS_EVERY_STEPS` (default: `16`)
  - `EGO_LONG_TERM_MEMORY_ASSESS_COOLDOWN_STEPS` (default: `8`)
  - `EGO_LONG_TERM_MEMORY_MIN_CONFIDENCE` (default: `0.65`)
  - `EGO_LONG_TERM_MEMORY_MAX_TOKENS` (default: `180`)
  - `EGO_LONG_TERM_MEMORY_MAX_SUMMARY_CHARS` (default: `320`)
  - `EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_ALLOWED_ACTION` (default: `false`)
  - `EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_TERMINAL_ANSWER` (default: `true`)
  - `EGO_LONG_TERM_MEMORY_PARSE_FALLBACK_DISABLE_AFTER` (default: `2`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_SUMMARY_CHARS` (default: `16`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_LENGTH` (default: `3`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_COUNT` (default: `4`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_TOKEN_OVERLAP_THRESHOLD` (default: `0.85`)
  - `PSYKE_AUTO_START_PGVECTOR` (optional; when `true`, launcher runs `docker compose up -d pgvector` if needed)
  - `MEMORY_DEFAULT_NAMESPACE` (optional; memory MCP namespace/tenant default, launcher defaults to `psyke`)
  - `MEMORY_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD` (memory server; default: `0.93`)
  - `MEMORY_SEMANTIC_DEDUPE_MIN_CONFIDENCE` (memory server; default: `0.65`)
  - `MEMORY_FACT_DEFAULT_SUBJECT` (memory server; default: `user`)
  - `PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS` (reasoning eval raw-thought capture cap; default: unlimited)

## Run
```bash
export GROQ_API_KEY=your_token
export MISTRAL_API_KEY=your_token  # required when web_search.provider=mistral
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
export GROQ_API_KEY=your_token
export MISTRAL_API_KEY=your_token  # required when web_search.provider=mistral
./run-psyke.sh
```

Run deterministic reasoning self-eval (no MotorCortex actions, no baseline comparison):
```bash
./run-psyke.sh --eval-reasoning-only
```

Reasoning eval options:
```bash
./run-psyke.sh --eval-reasoning-only --eval-reasoning-mode logic  # default; no external LLM calls
./run-psyke.sh --eval-reasoning-only --eval-reasoning-mode model  # uses provider API key (GROQ_API_KEY by default)
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

Memory live eval (real-world, no mocks):
```bash
export GROQ_API_KEY=your_token
# either set in mcp-runtime.yaml (preferred) or override here:
export MCP_MEMORY_SERVER_CMD='your-memory-mcp-server-command'
./run-psyke.sh --eval-memory-live
```

Memory live eval options:
```bash
./run-psyke.sh --eval-memory-live --eval-stage 2026-02-28
./run-psyke.sh --eval-memory-live --eval-memory-max-attempts 3
./run-psyke.sh --eval-memory-live --eval-memory-tasks user-preference-color,project-constraint-timezone
```

Memory live eval output:
- Per-run detailed JSON in `.psyke/evals/memory-live/runs/`.
- Append-only trend history in `.psyke/evals/memory-live/history.jsonl`.
- Uses real `LlmLongTermMemoryAdvisor` + real `McpHippocampus` imprint/recall calls.
- Tags each saved item with a unique run session marker to reduce cross-run collision.
- Main run log focuses on memory eval flow (`[eval.memory] ...`).

Manual DB-backed memory server eval (not part of default `test`):
```bash
# uses live PostgreSQL/pgvector configured via PGVECTOR_DB_* env vars
./gradlew :mcp-memory-pgvector:memoryDbEval
```
This eval verifies:
- semantic dedupe (`write_mode=dedupe_if_similar`)
- fact upsert supersession (`write_mode=upsert_fact`)
- namespace isolation (no cross-tenant mixing)

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
- `run-psyke.sh` also bootstraps `:mcp-memory-pgvector:fatJar` when the memory MCP jar is missing or stale.
- After bootstrap, execution is direct (`build/install/psyke/bin/psyke`) without `gradle run`.
- You do not run memory MCP separately if memory command config is set correctly (from `mcp-runtime.yaml` or `MCP_MEMORY_SERVER_CMD` override); Psyke launches it on demand.
- Default log level in `run-psyke.sh` is `warning`.
- Launcher logs are written to per-run files in `.psyke/logs/runs/`.
- `.psyke/logs/latest.log` always points to the newest run log.
- `.psyke/logs/latest-events.jsonl` always points to the newest event sidecar.
- `.psyke/logs/latest-run.env` stores `PSYKE_LOG_RUN_ID`, `PSYKE_LOG_FILE`, `PSYKE_EVENT_LOG_FILE`, and start time for the current run.
- Old run logs are auto-pruned; retention defaults to 30 files (`PSYKE_LOG_RETENTION`).
- If pgvector is not running, launcher prints a startup tip: `docker compose up -d pgvector`.
- Set `PSYKE_AUTO_START_PGVECTOR=true` to auto-start pgvector from the launcher when required.
- Launcher sets `MEMORY_DEFAULT_NAMESPACE=psyke` unless already set, so Psyke memory stays isolated by default.
- Memory MCP write tools support `write_mode`: `append`, `dedupe_if_similar`, `upsert_fact`.
- `upsert_fact` supports `fact_subject`, `fact_key`, `fact_value`, `fact_versioned_at` and keeps only one active value per `(namespace, subject, key)`.
- Memory MCP tools accept optional `namespace` (or `tenant`/`workspace`) argument for explicit multi-client isolation.
- Default loop delay in `run-psyke.sh` is `1000ms` (`--no-delay` or `--loop-delay-ms 0` disables it).
- `PSYKE_LOG_LEVEL` can still provide a default if `--log-level` is omitted.
- `PSYKE_LOG_DIR` overrides the log directory (default: `.psyke/logs`).
- `PSYKE_EVENT_LOG_FILE` overrides the event sidecar path (used by eval modes).
- By default the launcher persists metrics to `.psyke/metrics.db` (override with `PSYKE_METRICS_DB`).
  - A runtime defaults file is auto-created on first metrics use at `.psyke/runtime-defaults.yaml` (override path with `PSYKE_RUNTIME_DEFAULTS_FILE`).
  - If `PSYKE_METRICS_DB` is not set, the app uses `metrics_db` from that defaults file.
  - Metrics are now fail-safe at client level (LLM/web-search clients emit usage through persistent metrics observer even outside the normal app runner path).
  - If SQLite metrics init fails, Psyke falls back to `.psyke/metrics-fallback.jsonl` so usage events are still persisted.

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
- When memory capability is enabled/configured (via `mcp-runtime.yaml` or env override), Ego also runs internal `Hippocampus` memory recall per thought/input planning step (not a MotorCortex action).
- Ego tracks a `decision_pressure` signal to detect circular thought chains and increase convergence pressure.
- A separate MetaReasoner LLM call runs periodically under pressure to classify chain health (`continue`, `continue_with_constraints`, `finalize_now`, `request_tool_then_finalize`).
- A separate `MemoryConsolidationAdvisor` LLM call can run every N steps (default 8) and after allowed actions to decide if durable memory should be persisted.
- If consolidation says yes with enough confidence, Psyke generates a concise summary and writes it through `Hippocampus` imprint (MCP memory if configured).
- If no inputs are pending, thoughts and actions are scheduled by urgency (`high`, `medium`, `low`).
- Every proposed action includes a context summary capped at 180 chars.
- MotorCortex runs a startup capability smoke test and emits `action_capabilities` instrumentation.
- Ego planner receives runtime `available_action_types` and avoids proposing unavailable actions.
- `web_search` is supported for both providers:
  - `mistral`: Conversations web-search integration (agent-backed)
  - `groq`: Groq web search via Chat Completions (`browser_search` tool for standard models, or built-in search behavior for `groq/compound*` models)
- Superego validates every action against directives before execution.
- If denied, denial reason (<=180 chars) is pushed back as a new thought.

## Superego directives
- Defined in code at `psyke.agent.superego.SuperegoPolicy`.
- Structured as:
  - general directives (always included)
  - action-specific directives selected by action type (`answer`, `web_search`, `mcp_time`, `mcp_fetch`)
- Superego prompt includes `general + action-specific(current_action)` to reduce token usage.

## Metrics persistence
- SQLite storage records per-run and per-call data (`runs`, `llm_calls`).
- API keys are never stored raw; metrics use a salted key fingerprint.
- Call metrics include actor/call-site/action-type, latency, status, and token usage when returned by the model API.
- Instrumentation health is persisted per run, including dropped instrumentation events and queue-saturation hits.
- Superego token usage is tracked separately for both current run and persistent totals (still included in overall totals).
- Memory metrics are persisted per run and as persistent totals: recall attempts/hits/failures/truncation/latency/chars, consolidation assessments/save recommendations, and imprint attempts/success/failures/latency/chars.

## Provider status checks
- Before interactive mode, Psyke runs provider health checks for each configured cognitive role endpoint.
- Before live/model eval modes, Psyke runs a provider health check for the planner endpoint.
- Checks include DNS resolution for the provider host and a short authenticated HTTP probe (`GET /models`).
- If provider is unavailable, Psyke prints a clear error to both stderr/stdout-facing output and logs, then exits early.
- If provider is degraded (for example, rate limiting), Psyke logs and prints a warning but continues.
- For `--eval-memory-live`, Psyke also preflights the memory MCP provider (connect + tool listing) and fails early if required recall/write-like tools are missing or startup fails.
