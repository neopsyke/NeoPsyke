# NeoPsyke Kotlin app

Standalone Kotlin JVM app using Gradle with:
- Pluggable LLM client (`ChatModelClient`)
- Interactive Ego agent loop (inputs, thoughts, actions)
- Extensible input abstraction (`SensoryCortex`)
- Superego action gatekeeper (policy/safety review)
- Action executor (`MotorCortex`) with startup-discovered action plugins (`ServiceLoader`)
  - built-in plugins: `web_search`, `answer`, `mcp_time`, `website_fetch`, `email_send` (disabled by default unless configured)

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
  - Example mixed setup: `OPENAI_API_KEY` for planner/meta-reasoner and `GROQ_API_KEY` for superego/verifier/memory-advisor.
  - If `web_search.provider` is different, set that provider key too.

## Configuration
- LLM settings are centralized in `llm-runtime.yaml` (repository root).
  - Cognitive role routing is set in `cognitive_roles`:
    - `planner`, `action_verifier`, `superego`, `meta_reasoner`, optional `meta_reasoner_fallback`, `memory_advisor`
  - Each cognitive role can set independent `provider` and `model`.
  - Provider credentials/endpoints are set under `providers` (`api_key_env`, `base_url`).
  - `web_search` remains independently configurable (`provider`, `model`, optional `api_key_env`, `base_url`).
  - Optional `model_catalog` can list per-provider ROI profiles (`tier`, `token_weight`, optional input/output cost metadata). NeoPsyke uses `token_weight` to scale dynamic completion budgets for superego and memory-advisor.
  - If a role/model is omitted, NeoPsyke falls back to provider defaults.
  - Optional override file path: `NEOPSYKE_LLM_CONFIG_FILE=/path/to/llm-runtime.yaml`.
  - Example:
    ```yaml
    providers:
      groq:
        api_key_env: GROQ_API_KEY
      google:
        api_key_env: GOOGLE_API_KEY
      openai:
        api_key_env: OPENAI_API_KEY

    cognitive_roles:
      planner:
        provider: openai
        model: gpt-4o-mini
      action_verifier:
        provider: groq
        model: openai/gpt-oss-20b
      superego:
        provider: openai
        model: gpt-4o-mini
      meta_reasoner:
        provider: openai
        model: gpt-4o-mini
      meta_reasoner_fallback:
        provider: openai
        model: gpt-5-mini
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
  - `OPENAI_API_KEY`
- OpenAI moderation is available as a standalone utility (`moderateWithOpenAi` / `OpenAiModerationClient`) using `omni-moderation-latest`; it is not auto-wired into cognitive-role chat calls.
- MCP/time/fetch/memory provider settings are now centralized in `mcp-runtime.yaml` (repository root).
  - Default config enables `time`, `website_fetch`, and `memory` in `stdio` mode with command/fallback lists.
  - Optional override file path: `NEOPSYKE_MCP_CONFIG_FILE=/path/to/mcp-runtime.yaml`.
  - Environment variables still override YAML when present (`MCP_TIME_*`, `WEBSITE_FETCH_*`, `MCP_MEMORY_*`, plus legacy `MCP_*_SERVER_CMD`).
  - YAML schema per capability:
    - `enabled` (`true|false`)
    - `mode` (`stdio` currently supported)
    - `provider` (label/selector for provider intent)
    - `command` (primary command string)
    - `fallback_commands` (list of command strings; first executable in `PATH` is used)
- Microsoft Graph email action (`email_send`) is configured via environment variables:
  - `MS_GRAPH_EMAIL_ENABLED` (`true|false`, default `false`)
  - `MS_GRAPH_TENANT_ID`
  - `MS_GRAPH_CLIENT_ID`
  - `MS_GRAPH_CLIENT_SECRET`
  - `MS_GRAPH_SCOPE` (optional, default `https://graph.microsoft.com/.default`)
  - `MS_GRAPH_DEFAULT_SENDER` (optional fallback mailbox if payload omits sender)
  - `MS_GRAPH_ALLOWED_RECIPIENT_DOMAINS` (optional comma-separated domain allowlist)
  - `MS_GRAPH_AUTH_BASE_URL` (optional, default `https://login.microsoftonline.com`)
  - `MS_GRAPH_BASE_URL` (optional, default `https://graph.microsoft.com/v1.0`)
- Agent/app/eval runtime settings are centralized in `agent-runtime.yaml` (repository root).
  - Optional override file path: `NEOPSYKE_AGENT_CONFIG_FILE=/path/to/agent-runtime.yaml`.
  - `agent` is domain-grouped and mirrors `AgentConfig` ownership:
    - `agent.planner.*`
    - `agent.superego.*`
    - `agent.memory.*` and `agent.memory.scratchpad.*`
    - `agent.meta_reasoner.*`
    - `agent.logbook.*`
    - `agent.runtime.*` (loop/pending queues/search/mcp timeout/fetch cap)
  - Legacy flat `agent.*` runtime keys are not supported.
  - `app` section covers UI/runtime flags (for example dashboard enablement and port).
  - `eval` section covers eval defaults (for example stage default and raw-response cap).
  - Precedence is `CLI > env > YAML > code defaults`.
- Id runtime settings are centralized in `id-runtime.yaml` (repository root).
  - Optional override file path: `NEOPSYKE_ID_CONFIG_FILE=/path/to/id-runtime.yaml`.
  - `id.enabled` toggles autonomous internal drive impulses.
  - `id.max_consecutive_denials` controls when denial backoff is applied.
  - Id emits at most one pending impulse lifecycle at a time; a new impulse is not fired until the prior lifecycle is finalized.
  - Id-origin deterministic policy is enforced in Superego (not action plugins):
    - direct Id-origin `answer` is denied by default
    - internal/evidence actions (`web_search`, `website_fetch`, `mcp_time`, `answer_draft`) are allowed to proceed to normal review/execution flow.
- Optional:
  - `NEOPSYKE_LLM_CONFIG_FILE` (optional; path to LLM runtime YAML, default: `./llm-runtime.yaml`)
  - `NEOPSYKE_MCP_CONFIG_FILE` (optional; path to MCP runtime YAML, default: `./mcp-runtime.yaml`)
  - `NEOPSYKE_AGENT_CONFIG_FILE` (optional; path to agent/app/eval runtime YAML, default: `./agent-runtime.yaml`)
  - `NEOPSYKE_ID_CONFIG_FILE` (optional; path to Id runtime YAML, default: `./id-runtime.yaml`)
  - `NEOPSYKE_ID_ENABLED` (default from `id-runtime.yaml`)
  - `NEOPSYKE_DASHBOARD_ENABLED` (default: `true`)
  - `NEOPSYKE_DASHBOARD_PORT` (default: `8787`)
  - `EGO_MAX_LOOP_STEPS` (default: `180`)
  - `EGO_MAX_THOUGHT_PASSES` (default: `5`)
  - `EGO_MAX_PROMPT_TOKENS` (default: `2400`)
  - `EGO_MAX_COMPLETION_TOKENS` (default: `900`)
  - `EGO_MAX_RUN_TOTAL_TOKENS` (default: `0`, disabled)
  - `EGO_MAX_RUN_TOKENS_PER_PROVIDER` (default: `0`, disabled)
  - `EGO_MAX_RUN_TOKENS_PER_ROLE` (default: `0`, disabled)
  - `EGO_LLM_RETRY_ATTEMPTS` (default: `2`)
  - `EGO_ACTION_RETRY_BUDGET_NON_RETRYABLE_FAILURES` (default: `3`)
  - `EGO_ACTION_RETRY_COOLDOWN_STEPS` (default: `10`)
  - `EGO_SUPEREGO_MAX_COMPLETION_TOKENS` (default: `192`)
  - `EGO_SUPEREGO_DYNAMIC_COMPLETION_ENABLED` (default: `true`)
  - `EGO_SUPEREGO_DYNAMIC_COMPLETION_HARD_MAX_TOKENS` (default: `640`)
  - `EGO_SUPEREGO_DYNAMIC_PROMPT_TO_COMPLETION_RATIO` (default: `0.10`)
  - `EGO_SUPEREGO_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS` (default: `160`)
  - `EGO_SUPEREGO_TWO_STAGE_REVIEW_ENABLED` (default: `false`; when enabled, cheap model first then escalate)
  - `EGO_SUPEREGO_TWO_STAGE_LOW_CONFIDENCE_THRESHOLD` (default: `0.70`)
  - `EGO_SUPEREGO_TWO_STAGE_ESCALATE_ON_MEDIUM_POLICY_RISK` (default: `true`)
  - `EGO_LOOP_DELAY_MS` (default app value: `0`)
  - `EGO_SHORT_TERM_CONTEXT_MAX_CHARS` (default: `20000`)
  - `EGO_SHORT_TERM_CONTEXT_MAX_PROMPT_TOKENS` (default: `384`)
  - `EGO_SCRATCHPAD_ENABLED` (default: `false`)
  - `EGO_SCRATCHPAD_MAX_PROMPT_TOKENS` (default: `220`)
  - `EGO_SCRATCHPAD_MAX_SECTIONS` (default: `10`)
  - `EGO_SCRATCHPAD_MAX_SECTION_CHARS` (default: `1200`)
  - `EGO_SCRATCHPAD_MAX_SECTION_SUMMARY_CHARS` (default: `180`)
  - `EGO_SCRATCHPAD_MAX_EVIDENCE_ITEMS` (default: `8`)
  - `EGO_SCRATCHPAD_MAX_EVIDENCE_CHARS` (default: `220`)
  - `EGO_SCRATCHPAD_FINAL_COMPILATION_MAX_CHARS` (default: `2800`)
  - `EGO_SCRATCHPAD_FINAL_PASS_REWRITE_ENABLED` (default: `true`)
  - `EGO_SCRATCHPAD_FINAL_PASS_MAX_TOKENS` (default: `260`)
  - `EGO_SCRATCHPAD_FINAL_PASS_MIN_WORKSPACE_CONFIDENCE` (default: `0.35`)
  - `EGO_SCRATCHPAD_FINAL_PASS_MIN_MODEL_CONFIDENCE` (default: `0.55`)
  - `EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED` (default: `false`; forced to `true` by `./run-neopsyke.sh`, Gradle tests, and Freud scripts)
  - `EGO_SCRATCHPAD_MAX_ACTIVE_TASKS` (default: `32`)
  - `EGO_MAX_ACTION_PAYLOAD_CHARS` (default: `4000`)
  - `EGO_SEARCH_RESULT_COUNT` (default: `5`)
  - `MCP_TIME_SERVER_CMD` (optional env override for YAML time command)
  - `WEBSITE_FETCH_SERVER_CMD` (optional env override for YAML fetch command)
  - `MCP_MEMORY_SERVER_CMD` (optional env override for YAML memory command)
  - `MCP_TIME_MODE` / `WEBSITE_FETCH_MODE` / `MCP_MEMORY_MODE` (optional env override for YAML mode)
  - `MCP_TIME_PROVIDER` / `WEBSITE_FETCH_PROVIDER` / `MCP_MEMORY_PROVIDER` (optional env override for YAML provider)
  - `MCP_TIME_ENABLED` / `WEBSITE_FETCH_ENABLED` / `MCP_MEMORY_ENABLED` (optional env override for YAML enabled flag)
  - `MISTRAL_WEBSEARCH_AGENT_ID` (optional when `web_search.provider=mistral`; if omitted, NeoPsyke creates an ephemeral Mistral web-search agent per run)
  - `MCP_CALL_TIMEOUT_MS` (default: `8000`)
  - `MCP_MEMORY_CALL_TIMEOUT_MS` (default: same as `MCP_CALL_TIMEOUT_MS`)
  - `WEBSITE_FETCH_MAX_CHARS` (default: `4000`)
  - `EGO_LONG_TERM_MEMORY_RECALL_MAX_ITEMS` (default: `4`)
  - `EGO_LONG_TERM_MEMORY_RECALL_MAX_CHARS` (default: `1200`)
  - `EGO_LONG_TERM_MEMORY_PROMPT_COMPRESSION_ENABLED` (default: `true`)
  - `EGO_LONG_TERM_MEMORY_PROMPT_DIALOGUE_MAX_CHARS` (default: `1100`)
  - `EGO_LONG_TERM_MEMORY_PROMPT_RECALL_MAX_CHARS` (default: `900`)
  - `EGO_PRESSURE_MIN_STEP` (default: `16`)
  - `EGO_PRESSURE_ASSESS_EVERY_STEPS` (default: `8`)
  - `EGO_PRESSURE_ASSESS_THRESHOLD` (default: `0.68`)
  - `EGO_FORCE_TERMINAL_PRESSURE_THRESHOLD` (default: `0.98`)
  - `EGO_FORCE_TERMINAL_STALE_STREAK_THRESHOLD` (default: `8`)
  - `EGO_META_REASONER_COOLDOWN_STEPS` (default: `6`)
  - `EGO_META_REASONER_MAX_TOKENS` (default: `512`)
  - `EGO_META_REASONER_DYNAMIC_COMPLETION_ENABLED` (default: `true`)
  - `EGO_META_REASONER_DYNAMIC_COMPLETION_HARD_MAX_TOKENS` (default: `640`)
  - `EGO_META_REASONER_DYNAMIC_PROMPT_TO_COMPLETION_RATIO` (default: `0.10`)
  - `EGO_META_REASONER_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS` (default: `160`)
  - `EGO_LONG_TERM_MEMORY_ASSESS_EVERY_STEPS` (default: `16`)
  - `EGO_LONG_TERM_MEMORY_ASSESS_COOLDOWN_STEPS` (default: `8`)
  - `EGO_LONG_TERM_MEMORY_MIN_CONFIDENCE` (default: `0.65`)
  - `EGO_LONG_TERM_MEMORY_MAX_TOKENS` (default: `320`)
  - `EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_ENABLED` (default: `true`)
  - `EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_HARD_MAX_TOKENS` (default: `512`)
  - `EGO_LONG_TERM_MEMORY_DYNAMIC_PROMPT_TO_COMPLETION_RATIO` (default: `0.08`)
  - `EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS` (default: `160`)
  - `EGO_LONG_TERM_MEMORY_MAX_SUMMARY_CHARS` (default: `320`)
  - `EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_ALLOWED_ACTION` (default: `false`)
  - `EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_TERMINAL_ANSWER` (default: `true`)
  - `EGO_LONG_TERM_MEMORY_PARSE_FALLBACK_DISABLE_AFTER` (default: `2`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_SUMMARY_CHARS` (default: `16`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_LENGTH` (default: `3`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_COUNT` (default: `4`)
  - `EGO_LONG_TERM_MEMORY_RECALL_ECHO_TOKEN_OVERLAP_THRESHOLD` (default: `0.85`)
  - `NEOPSYKE_AUTO_START_PGVECTOR` (optional; when `true`, launcher runs `docker compose up -d pgvector` if needed)
  - `MEMORY_DEFAULT_NAMESPACE` (optional; memory MCP namespace/tenant default, launcher defaults to `neopsyke`)
  - `MEMORY_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD` (memory server; default: `0.93`)
  - `MEMORY_SEMANTIC_DEDUPE_MIN_CONFIDENCE` (memory server; default: `0.65`)
  - `MEMORY_FACT_DEFAULT_SUBJECT` (memory server; default: `me`)
  - `NEOPSYKE_EVAL_MAX_RAW_RESPONSE_CHARS` (reasoning eval raw-thought capture cap; default: unlimited)
  - `NEOPSYKE_LLM_CACHE_MODE` (optional; `record`, `replay`, or `off`; default: `off`)
  - `NEOPSYKE_LLM_CACHE_FILE` (optional; path to JSONL cache file for LLM response caching)
  - `NEOPSYKE_LOGBOOK_DB_PATH` (optional; override episodic logbook SQLite path; default: `.neopsyke/logbook.db`)

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
mkdir -p .neopsyke/logs
export NEOPSYKE_LOG_FILE="$PWD/.neopsyke/logs/gradle-run.log"
./gradlew run -Dorg.slf4j.simpleLogger.defaultLogLevel=info
```

## Simpler Entrypoint (No `gradle run`)
Use the local launcher:
```bash
export GROQ_API_KEY=your_token
export MISTRAL_API_KEY=your_token  # required when web_search.provider=mistral
./run-neopsyke.sh
```

Run deterministic reasoning self-eval (no MotorCortex actions, no baseline comparison):
```bash
./run-neopsyke.sh --eval-reasoning-only
```

Reasoning eval options:
```bash
./run-neopsyke.sh --eval-reasoning-only --eval-reasoning-mode logic  # default; no external LLM calls
./run-neopsyke.sh --eval-reasoning-only --eval-reasoning-mode model  # uses provider API key (GROQ_API_KEY by default)
./run-neopsyke.sh --eval-reasoning-only --eval-stage 2026-02-28
./run-neopsyke.sh --eval-reasoning-only --eval-reasoning-max-attempts 5
./run-neopsyke.sh --eval-reasoning-only --eval-reasoning-tasks shape-lock,multi-fix  # logic mode tasks
./run-neopsyke.sh --eval-reasoning-only --log-level trace  # explicit override (already default for this mode)
```

Reasoning eval output:
- Per-run detailed JSON in `.neopsyke/evals/reasoning/runs/`.
- Append-only trend history in `.neopsyke/evals/reasoning/history.jsonl`.
- Default eval mode is `logic`, which uses a deterministic local harness to score retry/feedback loop behavior.
- `model` mode keeps the prior real-LLM task set for optional model-inclusive checks.
- If `--eval-stage` is omitted, stage defaults to current UTC date.
- Main run log focuses on eval flow (`[eval.reasoning] ...`) and full model thought text blocks (`thought.begin`/`thought.end`).
- Metadata-rich instrumentation events (including `llm.call`) are written to a per-run sidecar JSONL file.

Freud live eval (single-input, STDIN/STDOUT, with LLM response caching):
```bash
# Preferred wrapper for one-shot live/provider-backed evals:
freud/scripts/live-eval.sh --input input.txt --expected expected.txt --timeout 120
freud/scripts/live-eval.sh --input input.txt --cache-replay .neopsyke/runs/freud/latest/artifacts/llm-cache.jsonl
freud/scripts/live-eval.sh --input input.txt --preserve-memory

# Lower-level debugging path if you are working on the wrapper itself:
echo "What time is it?" | NEOPSYKE_LLM_CACHE_MODE=record NEOPSYKE_LLM_CACHE_FILE=.neopsyke/cache.jsonl \
  ./run-neopsyke.sh --freud-live --freud-live-timeout 120
echo "What time is it?" | NEOPSYKE_LLM_CACHE_MODE=replay NEOPSYKE_LLM_CACHE_FILE=.neopsyke/cache.jsonl \
  ./run-neopsyke.sh --freud-live
```

Freud live eval notes:
- Prefer `freud/scripts/live-eval.sh` for Freud-managed live checks. It wraps the raw `--freud-live` mode, sets isolated memory paths, captures artifacts, and handles record/replay.
- `--freud-live` reads all stdin upfront, submits as a single input, outputs the answer to stdout, then exits.
- `--freud-live-timeout N` sets a timeout in seconds (default: 120). Exit code 2 on timeout.
- `live-eval.sh` automatically uses isolated memory (`freud-eval` pgvector namespace, `.neopsyke/freud-logbook.db`, `.neopsyke/freud-metrics.db`) and clears it before each run by default.
- Use `--preserve-memory` or `FREUD_LIVE_EVAL_PRESERVE_MEMORY=true` only when an eval sequence intentionally depends on prior isolated Freud memory.
- `live-eval.sh --expected` uses normalized exact matching, not substring containment.
- LLM cache uses sequential matching with SHA-256 hash validation: the Nth LLM call is matched to the Nth cache entry, verified by message hash. On mismatch, replay stops and real LLM is used for all subsequent calls.

Freud reasoning eval matrix:
```bash
# Default deterministic feature loop (includes logic-core + logic-behavioral reasoning gate)
freud/scripts/feature-loop.sh reasoning-matrix

# Deterministic reasoning PR gate only
freud/scripts/run-reasoning-pr-gate.sh

# Manual weak-structure live lane
freud/scripts/feature-loop.sh reasoning-matrix --live --config freud/config/live-weak-structure.env

# Manual production-routing live lane
freud/scripts/feature-loop.sh reasoning-matrix --live --config freud/config/live-prod-acceptance.env
```

Freud reasoning lane notes:
- `reasoning_eval_logic` now runs two deterministic passes: the existing logic core and a 45-case behavioral/perturbation logic pack.
- `reasoning_eval_model` is reserved for manual live reasoning checks and currently runs a frozen 24-case BBH-style smoke slice.
- The live reasoning lane always routes through `freud/scripts/live-eval.sh`, which invokes the lower-level `./run-neopsyke.sh --freud-live` path for each case.
- `FREUD_BBH_PRESERVE_MEMORY=true` is available if a future live reasoning sequence needs shared isolated memory across cases. The current BBH slice should keep the default isolated-per-case behavior.
- The live lane configs intentionally do not hardcode local machine paths. They resolve repo-local YAML snapshots relative to the config directory so the committed setup stays portable across machines.
- GitHub pull requests run only the fast non-live path: `freud/scripts/feature-loop.sh ci-pr`, plus Freud's own BATS and pytest suites. Live lanes remain manual-only.

Memory live eval (real-world, no mocks):
```bash
export GROQ_API_KEY=your_token
# either set in mcp-runtime.yaml (preferred) or override here:
export MCP_MEMORY_SERVER_CMD='your-memory-mcp-server-command'
./run-neopsyke.sh --eval-memory-live
```

Memory live eval options:
```bash
./run-neopsyke.sh --eval-memory-live --eval-stage 2026-02-28
./run-neopsyke.sh --eval-memory-live --eval-memory-max-attempts 3
./run-neopsyke.sh --eval-memory-live --eval-memory-tasks user-preference-color,goal-constraint-timezone
```

Memory live eval output:
- Per-run detailed JSON in `.neopsyke/evals/memory-live/runs/`.
- Append-only trend history in `.neopsyke/evals/memory-live/history.jsonl`.
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
./run-neopsyke.sh --log-level info
```

Disable the default interactive delay for faster local/manual loops:
```bash
./run-neopsyke.sh --no-delay
```

Notes:
- `run-neopsyke.sh` bootstraps `installDist` once if needed.
- `run-neopsyke.sh` also bootstraps `:mcp-memory-pgvector:fatJar` when the memory MCP jar is missing or stale.
- After bootstrap, execution is direct (`build/install/neopsyke/bin/neopsyke`) without `gradle run`.
- You do not run memory MCP separately if memory command config is set correctly (from `mcp-runtime.yaml` or `MCP_MEMORY_SERVER_CMD` override); NeoPsyke launches it on demand.
- Default log level in `run-neopsyke.sh` is `warning`.
- Launcher logs are written to per-run files in `.neopsyke/logs/runs/`.
- `.neopsyke/logs/latest.log` always points to the newest run log.
- `.neopsyke/logs/latest-events.jsonl` always points to the newest event sidecar.
- `.neopsyke/logs/latest-run.env` stores `NEOPSYKE_LOG_RUN_ID`, `NEOPSYKE_LOG_FILE`, `NEOPSYKE_EVENT_LOG_FILE`, and start time for the current run.
- Old run logs are auto-pruned; retention defaults to 30 files (`NEOPSYKE_LOG_RETENTION`).
- If pgvector is not running, launcher prints a startup tip: `docker compose up -d pgvector`.
- Set `NEOPSYKE_AUTO_START_PGVECTOR=true` to auto-start pgvector from the launcher when required.
- Launcher sets `MEMORY_DEFAULT_NAMESPACE=neopsyke` unless already set, so NeoPsyke memory stays isolated by default.
- Memory MCP write tools support `write_mode`: `append`, `dedupe_if_similar`, `upsert_fact`.
- `upsert_fact` supports `fact_subject`, `fact_key`, `fact_value`, `fact_versioned_at` and keeps only one active value per `(namespace, subject, key)`.
- NeoPsyke's own long-term memory writes stamp the subject/reference as `me` so durable memories are attributed to the agent rather than the user.
- Memory MCP tools accept optional `namespace` (or `tenant`/`workspace`) argument for explicit multi-client isolation.
- Default loop delay in `run-neopsyke.sh` is `1000ms` (`--no-delay` or `--loop-delay-ms 0` disables it).
- `NEOPSYKE_LOG_LEVEL` can still provide a default if `--log-level` is omitted.
- `NEOPSYKE_LOG_DIR` overrides the log directory (default: `.neopsyke/logs`).
- `NEOPSYKE_EVENT_LOG_FILE` overrides the event sidecar path (used by eval modes).
- By default the launcher persists metrics to `.neopsyke/metrics.db` (override with `NEOPSYKE_METRICS_DB`).
  - A runtime defaults file is auto-created on first metrics use at `.neopsyke/runtime-defaults.yaml` (override path with `NEOPSYKE_RUNTIME_DEFAULTS_FILE`).
  - If `NEOPSYKE_METRICS_DB` is not set, the app uses `metrics_db` from that defaults file.
  - Metrics are now fail-safe at client level (LLM/web-search clients emit usage through persistent metrics observer even outside the normal app runner path).
  - If SQLite metrics init fails, NeoPsyke falls back to `.neopsyke/metrics-fallback.jsonl` so usage events are still persisted.

Then use the terminal prompt for runtime control commands only:
```text
control> exit
```

## Realtime dashboard
- Enabled by default and served locally on `http://127.0.0.1:8787/`.
- Split pages:
  - Conversations (primary): `http://127.0.0.1:8787/`
  - Observability dashboard: `http://127.0.0.1:8787/dashboard`
- Uses async instrumentation (`InstrumentationBus`) with pluggable sinks:
  - `StructuredLogSink` (logs)
  - `DashboardStateStore` + SSE stream (UI)
- Queue, loop, thought/action flow, Superego in/out, and LLM events are streamed as typed events.
- Thought Chain / Timeline includes non-snapshot events, including `llm_raw_response` payloads.
- Queue snapshots are used for queue panels/counts, not shown as timeline items.
- API namespaces:
  - Chat control plane: `/api/chat/*` (`sessions`, `messages`, session-scoped SSE)
  - Observability: `/api/obs/*` (`snapshot`, global `events`, `workspace`)
- Scratchpad drawer (Action Flow -> `Scratchpad`) fetches full debug snapshots on demand from `/api/obs/workspace` and `/api/obs/workspace/{rootId}`.
- Full workspace snapshots use two-lane instrumentation: lightweight `scratchpad_head` is streamed live; heavy `scratchpad_debug_snapshot` is captured server-side and excluded from SSE broadcasting.
- If dashboard bind fails, app continues running without the dashboard server.

## Loop behavior
- Priority order: incoming inputs first.
- Inputs are ingested through `SensoryCortex` (extensible input-source abstraction).
- Input priority levels: `low` (1), `medium` (2, default), `high` (3).
- Interactive runtime uses an async multiplexer input source:
  - built-in stdin source is control-only (currently supports `exit`; other text is ignored as chat input)
  - chat API submissions (`/api/chat/sessions/{id}/messages`) are also ingested as high-priority signals
- `MemoryStore` keeps bounded rolling memory and compacts older turns into a summary as it nears capacity.
- Memory summary included in Ego/Superego prompts is token-capped to stay within LLM context budgets.
- Optional Scratchpad (`EGO_SCRATCHPAD_ENABLED=true`) keeps an ephemeral per-request notebook (index + summaries + evidence).
- Scratchpad is independent from short-term and long-term memory and is destroyed when the root request resolves; when queues drain, active scratchpads are cleared but per-session scratchpad digests are preserved for subsequent turns.
- Terminal `answer` actions can run an optional LLM final-pass rewrite from the workspace compilation; rewrite is gated by workspace confidence and finalizer model confidence.
- When memory capability is enabled/configured (via `mcp-runtime.yaml` or env override), Ego also runs internal `Hippocampus` memory recall per thought/input planning step (not a MotorCortex action).
- Ego tracks a `decision_pressure` signal to detect circular thought chains and increase convergence pressure.
- A separate MetaReasoner LLM call runs periodically under pressure to classify chain health (`continue`, `continue_with_constraints`, `finalize_now`, `request_tool_then_finalize`).
- A separate `MemoryConsolidationAdvisor` LLM call can run every N steps (default 8) and after allowed actions to decide if durable memory should be persisted.
- If consolidation says yes with enough confidence, NeoPsyke generates a concise summary and writes it through `Hippocampus` imprint (MCP memory if configured).
- If no inputs are pending, thoughts and actions are scheduled by urgency (`high`, `medium`, `low`).
- Every proposed action includes a context summary capped at 180 chars.
- MotorCortex runs a startup capability smoke test and emits `action_capabilities` instrumentation.
- Ego planner receives runtime `available_action_types` and avoids proposing unavailable actions.
- `web_search` provider support:
  - `mistral`: Conversations web-search integration (agent-backed)
  - `groq`: Groq web search via Chat Completions (`browser_search` tool for standard models, or built-in search behavior for `groq/compound*` models)
  - `google`: Gemini web search via OpenAI-compatible Chat Completions
  - `openai`: not currently implemented for `web_search` runtime (supported for cognitive roles only)
- Superego validates every action against directives before execution.
- If denied, denial reason (<=180 chars) is pushed back as a new thought.

## Superego directives
- Defined in code at `ai.neopsyke.agent.superego.SuperegoPolicy`.
- Structured as:
  - general directives (always included)
  - action-specific directives selected by action type (`answer`, `web_search`, `mcp_time`, `website_fetch`)
- Superego prompt includes `general + action-specific(current_action)` to reduce token usage.

## Metrics persistence
- SQLite storage records per-run and per-call data (`runs`, `llm_calls`).
- API keys are never stored raw; metrics use a salted key fingerprint.
- Call metrics include actor/call-site/action-type, latency, status, and token usage when returned by the model API.
- Instrumentation health is persisted per run, including dropped instrumentation events and queue-saturation hits.
- Superego token usage is tracked separately for both current run and persistent totals (still included in overall totals).
- Memory metrics are persisted per run and as persistent totals: recall attempts/hits/failures/truncation/latency/chars, consolidation assessments/save recommendations, and imprint attempts/success/failures/latency/chars.

## Task Verifier Telemetry And Tuning
- `task_verifier_review` events now include:
  - `intent_category`, `volatility_level`, `volatility_score`
  - `requires_external_evidence`
  - `evidence_actions_available`, `evidence_actions_dispatchable`
  - `had_successful_evidence`, `had_external_failures`
  - `reason_code` (`TASK_EVIDENCE_REQUIRED`, `TECH_EXTERNAL_EVIDENCE_FAILURE`, `TASK_EVIDENCE_UNAVAILABLE_GRACEFUL`)
- Dashboard snapshot (`/api/obs/snapshot`) exposes aggregated `taskVerifierStats` counters and rates.
- For run-log aggregation from sidecar JSONL:
  - `freud/scripts/task-verifier-telemetry.sh`
  - default input: `.neopsyke/logs/latest-events.jsonl`
  - example: `freud/scripts/task-verifier-telemetry.sh .neopsyke/logs/runs/<run-id>.events.jsonl`

## Prompt Budget Telemetry And Tuning
- `prompt_budget_allocation` events are emitted when prompts are assembled for:
  - planner (`call_site=planner_prompt`)
  - planner action verifier (`call_site=action_verifier_prompt`)
  - superego (`call_site=superego_prompt`)
  - meta reasoner (`call_site=meta_reasoner_prompt`)
  - legacy prompt web search (`call_site=legacy_web_search_prompt`)
- Event payload includes:
  - budget and cost estimates (`max_tokens`, `estimated_total_cost`, `allocated_total_cost`, `reserved_floor_cost`)
  - degradation path (`degradation_path`)
  - fallback/floor pressure (`single_message_fallback`, `floor_violation_count`, `dropped_section_count`)
  - per-band rollup (`bands.required_core|required_context|optional`)
- Dashboard snapshot (`/api/obs/snapshot`) exposes aggregated `promptBudgetStats`.
- Detailed tuning workflow: `PROMPT_BUDGET_TUNING_GUIDE.md`.
- Runbook for live `./run-neopsyke.sh` diagnostics: `PROMPT_BUDGET_RUN_DIAGNOSTICS.md`.
- For run-log aggregation from sidecar JSONL:
  - `freud/scripts/prompt-budget-telemetry.sh`
  - default input: `.neopsyke/logs/latest-events.jsonl`
  - example: `freud/scripts/prompt-budget-telemetry.sh .neopsyke/logs/runs/<run-id>.events.jsonl`

## Provider status checks
- Before interactive mode, NeoPsyke runs provider health checks for each configured cognitive role endpoint.
- Before live/model eval modes, NeoPsyke runs a provider health check for the planner endpoint.
- Checks include DNS resolution for the provider host and a short authenticated HTTP probe (`GET /models`).
- Transient unavailable probe results such as timeouts are retried once before NeoPsyke decides the final provider state.
- If a required provider is unavailable, NeoPsyke prints a clear error to both stderr/stdout-facing output and logs, then exits early.
- If provider is degraded (for example, rate limiting), NeoPsyke logs and prints a warning but continues.
- In interactive and `--freud-live` startup, `meta_reasoner_fallback` is treated as optional: if its health check still fails after retry, NeoPsyke logs a warning, disables that fallback for the run, and continues with the primary meta-reasoner.
- For `--eval-memory-live`, NeoPsyke also preflights the memory MCP provider (connect + tool listing) and fails early if required recall/write-like tools are missing or startup fails.
