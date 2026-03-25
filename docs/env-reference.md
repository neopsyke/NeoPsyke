# Environment Variable Reference

Complete reference of all environment variables supported by NeoPsyke. For most users, the YAML configuration files are sufficient — environment variables are primarily useful for quick overrides, CI, and containerized deployments.

**Precedence:** environment variables > external YAML overlay > bundled YAML defaults. CLI flags are a separate layer for app behavior outside the YAML merge chain.

For configuration concepts, the loading model, and tuning guidance, see [configuration.md](configuration.md).

> **Terminology:** See the [Glossary](glossary.md) for definitions of all agent concepts used in this document.

---

## Configuration file paths

These override the default external overlay path. Bundled defaults under `config/` are always loaded first; these files are overlays.

| Variable | Default overlay path | Description |
|---|---|---|
| `NEOPSYKE_LLM_CONFIG_FILE` | `./llm-runtime.yaml` | Path to LLM runtime overlay. |
| `NEOPSYKE_AGENT_CONFIG_FILE` | `./agent-runtime.yaml` | Path to agent/app/eval runtime overlay. |
| `NEOPSYKE_MEMORY_CONFIG_FILE` | `./memory-runtime.yaml` | Path to memory provider overlay. |
| `NEOPSYKE_ID_CONFIG_FILE` | `./id-runtime.yaml` | Path to Id drive system overlay. |
| `NEOPSYKE_MCP_CONFIG_FILE` | `./mcp-runtime.yaml` | Path to MCP tool server overlay. |

---

## LLM API keys

Variable names are configured in `config/llm-runtime.yaml` under `providers.<name>.api_key_env`. Shipped defaults:

| Variable | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic API key. |
| `OPENAI_API_KEY` | OpenAI API key. |
| `GROQ_API_KEY` | Groq API key. |
| `MISTRAL_API_KEY` | Mistral API key. |
| `GOOGLE_API_KEY` | Google AI API key. |
| `OLLAMA_API_KEY` | Ollama API key (optional — only needed for authenticated remote Ollama hosts). |

---

## App and dashboard

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_DASHBOARD_ENABLED` | `true` | Enable the web dashboard. |
| `NEOPSYKE_DASHBOARD_PORT` | `8787` | Dashboard HTTP port. |

---

## Id (drive system)

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_ID_ENABLED` | from `id-runtime.yaml` | Enable autonomous internal drive impulses. |

---

## Goals

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_GOALS_ENABLED` | from `agent-runtime.yaml` | Enable the goal subsystem. |
| `NEOPSYKE_GOALS_WORKSPACE_ROOT` | `~/.neopsyke/goals` | Goal state and artifact persistence directory. |

---

## Ego planner

| Variable | Default | Description |
|---|---|---|
| `EGO_MAX_LOOP_STEPS` | `180` | Maximum Ego loop iterations per input. |
| `EGO_MAX_THOUGHT_PASSES` | `5` | Maximum consecutive thought passes before the planner must act. |
| `EGO_MAX_PROMPT_TOKENS` | `2400` | Planner prompt token budget. |
| `EGO_MAX_COMPLETION_TOKENS` | `900` | Planner completion token budget. |
| `EGO_MAX_RUN_TOTAL_TOKENS` | `0` (disabled) | Per-run total token cap across all roles and providers. |
| `EGO_MAX_RUN_TOKENS_PER_PROVIDER` | `0` (disabled) | Per-run token cap per provider. |
| `EGO_MAX_RUN_TOKENS_PER_ROLE` | `0` (disabled) | Per-run token cap per cognitive role. |
| `EGO_LLM_RETRY_ATTEMPTS` | `2` | Retries on transient LLM failures. |
| `EGO_ACTION_RETRY_BUDGET_NON_RETRYABLE_FAILURES` | `3` | Non-retryable action failures before giving up. |
| `EGO_ACTION_RETRY_COOLDOWN_STEPS` | `10` | Steps to wait before retrying a failed action type. |
| `EGO_LOOP_DELAY_MS` | `0` | Delay between Ego loop iterations (ms). When `0` the delay is skipped entirely. |
| `EGO_MAX_ACTION_PAYLOAD_CHARS` | `4000` | Maximum action payload size (chars). |
| `EGO_SEARCH_RESULT_COUNT` | `5` | Number of web search results to request. |

---

## Superego

| Variable | Default | Description |
|---|---|---|
| `EGO_SUPEREGO_MAX_COMPLETION_TOKENS` | `192` | Base Superego completion token budget. |
| `EGO_SUPEREGO_DYNAMIC_COMPLETION_ENABLED` | `true` | Scale Superego budget based on prompt size and model weight. |
| `EGO_SUPEREGO_DYNAMIC_COMPLETION_HARD_MAX_TOKENS` | `640` | Absolute ceiling for dynamic scaling. |
| `EGO_SUPEREGO_DYNAMIC_PROMPT_TO_COMPLETION_RATIO` | `0.10` | Completion tokens as ratio of prompt tokens. |
| `EGO_SUPEREGO_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS` | `160` | Minimum prompt tokens before dynamic scaling activates. |
| `EGO_SUPEREGO_TWO_STAGE_REVIEW_ENABLED` | `false` | Cheap model first, escalate to stronger model on low confidence. |
| `EGO_SUPEREGO_TWO_STAGE_LOW_CONFIDENCE_THRESHOLD` | `0.60` | Confidence below which the cheap model escalates. |
| `EGO_SUPEREGO_TWO_STAGE_ESCALATE_ON_MEDIUM_POLICY_RISK` | `true` | Also escalate when policy risk is medium. |

---

## Short-term memory

| Variable | Default | Description |
|---|---|---|
| `EGO_SHORT_TERM_CONTEXT_MAX_CHARS` | `20000` | Rolling context buffer size (chars). |
| `EGO_SHORT_TERM_CONTEXT_MAX_PROMPT_TOKENS` | `384` | Token budget for short-term context in planner prompts. |

---

## Scratchpad

| Variable | Default | Description |
|---|---|---|
| `EGO_SCRATCHPAD_ENABLED` | `false` | Enable scratchpad (YAML default may differ). |
| `EGO_SCRATCHPAD_MAX_PROMPT_TOKENS` | `220` | Scratchpad prompt token budget. |
| `EGO_SCRATCHPAD_MAX_SECTIONS` | `10` | Maximum scratchpad sections. |
| `EGO_SCRATCHPAD_MAX_SECTION_CHARS` | `1200` | Maximum chars per section. |
| `EGO_SCRATCHPAD_MAX_SECTION_SUMMARY_CHARS` | `180` | Maximum chars per section summary. |
| `EGO_SCRATCHPAD_MAX_EVIDENCE_ITEMS` | `8` | Maximum evidence items tracked. |
| `EGO_SCRATCHPAD_MAX_EVIDENCE_CHARS` | `220` | Maximum chars per evidence item. |
| `EGO_SCRATCHPAD_FINAL_COMPILATION_MAX_CHARS` | `2800` | Maximum chars in final compilation. |
| `EGO_SCRATCHPAD_FINAL_PASS_REWRITE_ENABLED` | `true` | Run LLM final-pass rewrite from workspace compilation. |
| `EGO_SCRATCHPAD_FINAL_PASS_MAX_TOKENS` | `260` | Final pass completion token budget. YAML may override higher (e.g., `480`). |
| `EGO_SCRATCHPAD_FINAL_PASS_MIN_WORKSPACE_CONFIDENCE` | `0.35` | Minimum workspace confidence for final pass. |
| `EGO_SCRATCHPAD_FINAL_PASS_MIN_MODEL_CONFIDENCE` | `0.55` | Minimum model confidence for final pass. |
| `EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED` | `false` | Enable debug snapshot capture. Forced `true` by `run-neopsyke.sh`, Gradle tests, and Freud. |
| `EGO_SCRATCHPAD_MAX_ACTIVE_TASKS` | `32` | Maximum concurrent scratchpad tasks. |

---

## Meta-reasoner

| Variable | Default | Description |
|---|---|---|
| `EGO_PRESSURE_MIN_STEP` | `16` | Don't assess pressure before this step. |
| `EGO_PRESSURE_ASSESS_EVERY_STEPS` | `8` | Check pressure every N steps after minimum. |
| `EGO_PRESSURE_ASSESS_THRESHOLD` | `0.68` | Pressure level that triggers meta-reasoning. |
| `EGO_FORCE_TERMINAL_PRESSURE_THRESHOLD` | `0.98` | Pressure level that forces immediate termination. |
| `EGO_FORCE_TERMINAL_STALE_STREAK_THRESHOLD` | `8` | Consecutive stale steps that force termination. |
| `EGO_META_REASONER_COOLDOWN_STEPS` | `6` | Steps between meta-reasoner interventions. |
| `EGO_META_REASONER_MAX_TOKENS` | `512` | Meta-reasoner completion token budget. |
| `EGO_META_REASONER_DYNAMIC_COMPLETION_ENABLED` | `true` | Dynamic completion scaling for meta-reasoner. |
| `EGO_META_REASONER_DYNAMIC_COMPLETION_HARD_MAX_TOKENS` | `640` | Absolute ceiling for meta-reasoner dynamic scaling. |
| `EGO_META_REASONER_DYNAMIC_PROMPT_TO_COMPLETION_RATIO` | `0.10` | Meta-reasoner completion tokens as ratio of prompt tokens. |
| `EGO_META_REASONER_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS` | `160` | Minimum prompt tokens for meta-reasoner dynamic scaling. |

---

## Long-term memory

| Variable | Default | Description |
|---|---|---|
| `EGO_LONG_TERM_MEMORY_RECALL_MAX_ITEMS` | `4` | Maximum items per recall query. |
| `EGO_LONG_TERM_MEMORY_RECALL_MAX_CHARS` | `1200` | Maximum chars from recall results. |
| `EGO_LONG_TERM_MEMORY_PROMPT_COMPRESSION_ENABLED` | `true` | Compress dialogue context in memory advisor prompts. |
| `EGO_LONG_TERM_MEMORY_PROMPT_DIALOGUE_MAX_CHARS` | `1100` | Maximum dialogue chars in memory prompts. |
| `EGO_LONG_TERM_MEMORY_PROMPT_RECALL_MAX_CHARS` | `900` | Maximum recall chars in memory prompts. |
| `EGO_LONG_TERM_MEMORY_ASSESS_EVERY_STEPS` | `16` | Advisor assessment frequency (loop steps). |
| `EGO_LONG_TERM_MEMORY_ASSESS_COOLDOWN_STEPS` | `8` | Minimum steps between advisor assessments. |
| `EGO_LONG_TERM_MEMORY_MIN_CONFIDENCE` | `0.65` | Minimum advisor confidence to trigger imprint. |
| `EGO_LONG_TERM_MEMORY_MAX_TOKENS` | `320` | Memory advisor completion token budget. |
| `EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_ENABLED` | `true` | Dynamic completion scaling for memory advisor. |
| `EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_HARD_MAX_TOKENS` | `512` | Absolute ceiling for memory advisor dynamic scaling. |
| `EGO_LONG_TERM_MEMORY_DYNAMIC_PROMPT_TO_COMPLETION_RATIO` | `0.08` | Memory advisor completion tokens as ratio of prompt tokens. |
| `EGO_LONG_TERM_MEMORY_DYNAMIC_COMPLETION_MIN_PROMPT_TOKENS` | `160` | Minimum prompt tokens for memory advisor dynamic scaling. |
| `EGO_LONG_TERM_MEMORY_MAX_SUMMARY_CHARS` | `320` | Maximum chars in memory summary for imprint. |
| `EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_ALLOWED_ACTION` | `false` | Run advisor after every allowed action. |
| `EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_TERMINAL_ANSWER` | `true` | Run advisor when a final answer is produced. |
| `EGO_LONG_TERM_MEMORY_PARSE_FALLBACK_DISABLE_AFTER` | `2` | Disable parse fallback after N failures. |
| `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_SUMMARY_CHARS` | `16` | Minimum summary chars for recall deduplication. |
| `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_LENGTH` | `3` | Minimum token length for recall deduplication. |
| `EGO_LONG_TERM_MEMORY_RECALL_ECHO_MIN_TOKEN_COUNT` | `4` | Minimum token count for recall deduplication. |
| `EGO_LONG_TERM_MEMORY_RECALL_ECHO_TOKEN_OVERLAP_THRESHOLD` | `0.85` | Token overlap threshold for recall deduplication. |

---

## Memory provider

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_MEMORY_MODE` | `default` | Memory mode: `off`, `default` (managed pgvector), or `external`. |
| `NEOPSYKE_MEMORY_DEFAULT_COMMAND` | from YAML | Override managed provider start command. |
| `NEOPSYKE_MEMORY_DEFAULT_BASE_URL` | from YAML | Override managed provider base URL. |
| `NEOPSYKE_MEMORY_DEFAULT_BOOTSTRAP_ENABLED` | `true` | Auto-download provider jar from GitHub releases. |
| `NEOPSYKE_MEMORY_DEFAULT_RELEASE_API_URL` | GitHub release URL | Provider artifact download URL. |
| `NEOPSYKE_MEMORY_DEFAULT_DOWNLOAD_TIMEOUT_MS` | `30000` | Timeout for provider artifact download. |
| `NEOPSYKE_MEMORY_EXTERNAL_PROVIDER` | — | External provider name. |
| `NEOPSYKE_MEMORY_EXTERNAL_TRANSPORT` | — | External provider transport (`http` in v1). |
| `NEOPSYKE_MEMORY_EXTERNAL_BASE_URL` | — | External provider base URL. |
| `MEMORY_DEFAULT_NAMESPACE` | `neopsyke` | Long-term memory namespace/tenant. |
| `MEMORY_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD` | `0.93` | Provider-side semantic deduplication threshold. |
| `MEMORY_SEMANTIC_DEDUPE_MIN_CONFIDENCE` | `0.65` | Provider-side minimum confidence for deduplication. |
| `MEMORY_FACT_DEFAULT_SUBJECT` | `me` | Default subject for fact-type memories. |
| `MCP_MEMORY_CALL_TIMEOUT_MS` | same as `MCP_CALL_TIMEOUT_MS` | Timeout for memory provider calls. |

---

## MCP tools

| Variable | Default | Description |
|---|---|---|
| `MCP_CALL_TIMEOUT_MS` | `8000` | Timeout for MCP tool calls (ms). |
| `MCP_TIME_ENABLED` | from YAML | Enable MCP time tool. |
| `MCP_TIME_MODE` | from YAML | Time tool mode (`stdio`). |
| `MCP_TIME_PROVIDER` | from YAML | Time tool provider label. |
| `MCP_TIME_SERVER_CMD` | from YAML | Override time tool command. |
| `WEBSITE_FETCH_ENABLED` | from YAML | Enable website fetch tool. |
| `WEBSITE_FETCH_MODE` | from YAML | Fetch tool mode (`stdio` or `native`). |
| `WEBSITE_FETCH_PROVIDER` | from YAML | Fetch tool provider label. |
| `WEBSITE_FETCH_SERVER_CMD` | from YAML | Override fetch tool command. |
| `WEBSITE_FETCH_MAX_CHARS` | `4000` | Maximum chars fetched from websites. |

---

## Web search

| Variable | Default | Description |
|---|---|---|
| `MISTRAL_WEBSEARCH_AGENT_ID` | — | Mistral web-search agent ID. If omitted, an ephemeral agent is created per run. |

---

## Microsoft Graph (email)

| Variable | Default | Description |
|---|---|---|
| `MS_GRAPH_EMAIL_ENABLED` | `false` | Enable email send action. |
| `MS_GRAPH_TENANT_ID` | — | Azure AD tenant ID. |
| `MS_GRAPH_CLIENT_ID` | — | Application (client) ID. |
| `MS_GRAPH_CLIENT_SECRET` | — | Client secret. |
| `MS_GRAPH_SCOPE` | `https://graph.microsoft.com/.default` | OAuth scope. |
| `MS_GRAPH_DEFAULT_SENDER` | — | Fallback sender mailbox. |
| `MS_GRAPH_ALLOWED_RECIPIENT_DOMAINS` | — | Comma-separated domain allowlist. |
| `MS_GRAPH_AUTH_BASE_URL` | `https://login.microsoftonline.com` | Azure AD auth endpoint. |
| `MS_GRAPH_BASE_URL` | `https://graph.microsoft.com/v1.0` | Graph API endpoint. |

---

## Telegram

| Variable | Default | Description |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | — | Telegram bot API token. |
| `TELEGRAM_WEBHOOK_SECRET` | — | Webhook secret for request validation. |
| `NEOPSYKE_TELEGRAM_OWNER_CHAT_ID` | — | Restrict to this chat ID. |
| `NEOPSYKE_TELEGRAM_OWNER_USER_ID` | — | Restrict to this user ID. |

---

## Logging and metrics

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_LOG_LEVEL` | `warning` | Default log level (via `run-neopsyke.sh`). |
| `NEOPSYKE_LOG_DIR` | `.neopsyke/logs` | Log directory. |
| `NEOPSYKE_LOG_RETENTION` | `30` | Maximum number of run log files retained. |
| `NEOPSYKE_METRICS_DB` | `.neopsyke/metrics.db` | SQLite metrics database path. |
| `NEOPSYKE_RUNTIME_DEFAULTS_FILE` | `.neopsyke/runtime-defaults.yaml` | Runtime defaults file path. |
| `NEOPSYKE_EVENT_LOG_FILE` | — | Override event sidecar path (used by eval modes). |
| `NEOPSYKE_LOGBOOK_DB_PATH` | `.neopsyke/logbook.db` | Episodic logbook SQLite path. |

---

## Eval

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_EVAL_MAX_RAW_RESPONSE_CHARS` | unlimited | Reasoning eval raw-thought capture cap. |
| `NEOPSYKE_LLM_CACHE_MODE` | `off` | LLM response caching: `record`, `replay`, or `off`. |
| `NEOPSYKE_LLM_CACHE_FILE` | — | Path to JSONL cache file for LLM response caching. |
