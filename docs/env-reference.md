# Environment Variable Reference

Reference of the environment variables supported by NeoPsyke. For most users, the YAML configuration files are sufficient — environment variables are primarily useful for quick overrides, CI, and containerized deployments.

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
| `NEOPSYKE_GOALS_WORKSPACE_ROOT` | `.neopsyke/goals` | Goal state and artifact persistence directory when using the bundled runtime defaults. |
| `NEOPSYKE_GOALS_MAX_ACTIVE_GOALS` | `10` | Maximum concurrently active goals. |
| `NEOPSYKE_GOALS_MAX_STEPS_PER_PLAN` | `20` | Maximum persisted steps in a goal plan. |
| `NEOPSYKE_GOALS_ACTIONS_PER_CYCLE` | `5` | Maximum goal-origin actions per goal execution cycle. |
| `NEOPSYKE_GOALS_SNAPSHOT_EVERY_N_EVENTS` | `50` | Goal snapshot cadence. |
| `NEOPSYKE_GOALS_TIMER_RESOLUTION_MS` | `5000` | Timer wakeup resolution for scheduled goals. |
| `NEOPSYKE_GOALS_CONDITION_CHECK_INTERVAL_MS` | `30000` | Poll interval for condition-based waits. |
| `NEOPSYKE_GOALS_COMPLETED_RETENTION_DAYS` | `30` | Retention window for completed goal artifacts. |
| `NEOPSYKE_GOALS_MAX_WORKSPACE_BYTES` | `10485760` | Workspace storage cap per goal root. |

---

## Ego planner

| Variable | Default | Description |
|---|---|---|
| `EGO_MAX_LOOP_STEPS` | `180` | Maximum Ego loop iterations per input. |
| `EGO_MAX_THOUGHT_PASSES` | `5` | Maximum consecutive thought passes before the planner must act. |
| `EGO_MAX_PROMPT_TOKENS` | `2400` | Planner prompt token budget. |
| `EGO_MAX_COMPLETION_TOKENS` | `900` | Planner completion token budget. |
| `EGO_MAX_THOUGHT_CHARS` | `600` | Maximum chars in one planner thought. |
| `EGO_MAX_INPUT_CHARS` | `2000` | Maximum processed input length. |
| `EGO_MAX_RUN_TOTAL_TOKENS` | `0` (disabled) | Per-run total token cap across all roles and providers. |
| `EGO_MAX_RUN_TOKENS_PER_PROVIDER` | `0` (disabled) | Per-run token cap per provider. |
| `EGO_MAX_RUN_TOKENS_PER_ROLE` | `0` (disabled) | Per-run token cap per cognitive role. |
| `EGO_MAX_PLAN_STEPS` | `6` | Maximum planner-generated steps per plan. |
| `EGO_MAX_PLAN_STEP_DESC_CHARS` | `120` | Maximum chars per generated plan-step description. |
| `EGO_MAX_PLANS_PER_INPUT` | `2` | Maximum plan generations per input. |
| `EGO_LLM_RETRY_ATTEMPTS` | `2` | Retries on transient LLM failures. |
| `EGO_ACTION_RETRY_BUDGET_NON_RETRYABLE_FAILURES` | `3` | Non-retryable action failures before giving up. |
| `EGO_ACTION_RETRY_COOLDOWN_STEPS` | `10` | Steps to wait before retrying a failed action type. |
| `EGO_ACTION_VERIFIER_ENABLED` | `false` | Enable the planner-side action-verifier LLM pass before Superego review. |
| `EGO_LOOP_DELAY_MS` | `0` | Delay between Ego loop iterations (ms). When `0` the delay is skipped entirely; `run-neopsyke.sh` defaults to `1000`. |
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
| `EGO_SUPEREGO_TWO_STAGE_SKIP_FOR_ANSWER_ACTIONS` | `true` | Skip two-stage review for terminal `contact_user` actions. |
| `EGO_SUPEREGO_TWO_STAGE_SKIP_FOR_WEB_SEARCH_ACTIONS` | `true` | Skip two-stage review for `web_search` actions. |

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
| `EGO_SCRATCHPAD_ENABLED` | `false` | Enable scratchpad (bundled YAML default is `true`). |
| `EGO_SCRATCHPAD_ACTIVATION_MIN_PLAN_STEPS` | `2` | Minimum plan steps before scratchpad activates. |
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
| `EGO_SCRATCHPAD_DIGEST_MAX_ENTRIES` | `4` | Maximum retained digest entries per session. |
| `EGO_SCRATCHPAD_DIGEST_MAX_CHARS` | `800` | Maximum chars in one session digest entry. |
| `EGO_SCRATCHPAD_DIGEST_MAX_PROMPT_TOKENS` | `160` | Prompt-token budget for digest compaction. |

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
| `NEOPSYKE_MEMORY_DEFAULT_PROVIDER` | from YAML | Override managed provider label. |
| `NEOPSYKE_MEMORY_DEFAULT_TRANSPORT` | from YAML | Override managed provider transport. |
| `NEOPSYKE_MEMORY_DEFAULT_STARTUP_TIMEOUT_MS` | `12000` | Managed provider startup timeout. |
| `NEOPSYKE_MEMORY_DEFAULT_HEALTH_TIMEOUT_MS` | `3000` | Managed provider health-check timeout. |
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
| `MCP_MEMORY_CALL_TIMEOUT_MS` | `8000` | Timeout for memory provider calls. |

---

## Built-in tools

These apply to NeoPsyke's native built-in website fetch action.

| Variable | Default | Description |
|---|---|---|
| `WEBSITE_FETCH_ENABLED` | `true` | Enable the built-in website fetch action. |
| `WEBSITE_FETCH_CALL_TIMEOUT_MS` | `8000` | Timeout for native website fetch requests. |
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
| `NEOPSYKE_TELEGRAM_ENABLED` | `true` | Enable Telegram integration. |
| `NEOPSYKE_TELEGRAM_MODE` | `polling` | Telegram ingress mode: `polling` or `webhook`. |
| `NEOPSYKE_TELEGRAM_WEBHOOK_PATH` | `/api/channels/telegram/webhook` | Telegram webhook route when webhook mode is used. |
| `TELEGRAM_BOT_TOKEN` | — | Telegram bot API token. |
| `TELEGRAM_WEBHOOK_SECRET` | — | Webhook secret for request validation. |
| `NEOPSYKE_TELEGRAM_OWNER_CHAT_ID` | — | Restrict to this chat ID. |
| `NEOPSYKE_TELEGRAM_OWNER_USER_ID` | — | Restrict to this user ID. |
| `NEOPSYKE_TELEGRAM_BOT_TOKEN_HANDLE` | `TELEGRAM_BOT_TOKEN` | Override the configured secret-handle name for the Telegram bot token. |
| `NEOPSYKE_TELEGRAM_WEBHOOK_SECRET_HANDLE` | `TELEGRAM_WEBHOOK_SECRET` | Override the configured secret-handle name for the Telegram webhook secret. |
| `NEOPSYKE_TELEGRAM_POLICY_SCOPE_ID` | `telegram-owner` | Policy scope id assigned to authorized Telegram conversations. |
| `NEOPSYKE_TELEGRAM_SESSION_ID_PREFIX` | `telegram` | Session id prefix used for Telegram chats. |
| `NEOPSYKE_TELEGRAM_REQUIRE_DIRECT_CHAT` | `true` | Require private/direct chats only. |
| `NEOPSYKE_TELEGRAM_DROP_UNAUTHORIZED_MESSAGES` | `false` | Silently drop unauthorized Telegram traffic instead of returning `403`. |
| `NEOPSYKE_TELEGRAM_POLL_TIMEOUT_SECONDS` | `25` | Long-poll timeout when using polling mode. |
| `NEOPSYKE_TELEGRAM_POLL_RETRY_DELAY_MS` | `1000` | Retry delay after polling failures. |

---

## Google Workspace

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_GOOGLE_WORKSPACE_ENABLED` | `false` | Enable native Google Workspace OAuth and observe actions. |
| `NEOPSYKE_GOOGLE_TOKEN_STORE_DIR` | `.neopsyke/auth/google` | Local encrypted credential store. |
| `NEOPSYKE_GOOGLE_ALLOWED_OWNER_EMAIL` | — | Restrict OAuth authorization to this email address. |
| `NEOPSYKE_GOOGLE_PUBLIC_BASE_URL` | — | Public base URL used for OAuth callback construction. |
| `NEOPSYKE_GOOGLE_OAUTH_START_PATH` | `/api/channels/google/oauth/start` | OAuth start endpoint path. |
| `NEOPSYKE_GOOGLE_OAUTH_CLIENT_ID_HANDLE` | `GOOGLE_OAUTH_CLIENT_ID` | Secret-handle name for Google OAuth client id. |
| `NEOPSYKE_GOOGLE_OAUTH_CLIENT_SECRET_HANDLE` | `GOOGLE_OAUTH_CLIENT_SECRET` | Secret-handle name for Google OAuth client secret. |
| `NEOPSYKE_GOOGLE_OAUTH_STATE_SIGNING_SECRET_HANDLE` | `GOOGLE_OAUTH_STATE_SIGNING_SECRET` | Secret-handle name for OAuth state signing. |
| `NEOPSYKE_GOOGLE_OAUTH_TOKEN_ENCRYPTION_SECRET_HANDLE` | `GOOGLE_OAUTH_TOKEN_ENCRYPTION_SECRET` | Secret-handle name for local token encryption. |
| `NEOPSYKE_GOOGLE_OAUTH_CALLBACK_PATH` | `/api/channels/google/oauth/callback` | OAuth callback endpoint path. |
| `NEOPSYKE_GOOGLE_OAUTH_AUTH_BASE_URL` | `https://accounts.google.com/o/oauth2/v2/auth` | OAuth authorization endpoint. |
| `NEOPSYKE_GOOGLE_OAUTH_TOKEN_BASE_URL` | `https://oauth2.googleapis.com/token` | OAuth token endpoint. |
| `NEOPSYKE_GOOGLE_OAUTH_REQUIRE_PKCE` | `true` | Require PKCE for the native OAuth flow. |
| `NEOPSYKE_GOOGLE_OAUTH_REQUIRE_REFRESH_TOKEN` | `true` | Require a refresh token. |
| `NEOPSYKE_GOOGLE_OAUTH_STATE_TTL_SECONDS` | `600` | OAuth state-token TTL. |
| `NEOPSYKE_GOOGLE_SCOPES` | from YAML | Override requested Google OAuth scopes. |

---

## Action Control

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_ACTION_CONTROL_ENABLED` | `true` | Enable durable action staging and authorization. |
| `NEOPSYKE_ACTION_CONTROL_DB_PATH` | `.neopsyke/action-control.db` | SQLite path for action-control state. |
| `NEOPSYKE_ACTION_SECURITY_POLICY_FILE` | `action-security.yaml` | Policy file used by the action authorization layer. |
| `NEOPSYKE_ACTION_CONTROL_AUTH_TTL_MS` | `86400000` | Default authorization TTL. |
| `NEOPSYKE_ACTION_CONTROL_MAX_INSPECT_RESULTS` | `200` | Max rows returned by inspect endpoints. |
| `NEOPSYKE_ACTION_CONTROL_AUTONOMOUS_WORKER_ENABLED` | `true` | Enable background processing of runnable `READY` staged actions. |
| `NEOPSYKE_ACTION_CONTROL_AUTONOMOUS_WORKER_POLL_MS` | `500` | Autonomous worker poll interval. |
| `NEOPSYKE_ACTION_CONTROL_AUTONOMOUS_WORKER_BATCH_SIZE` | `16` | Max claims per autonomous worker poll cycle. |
| `NEOPSYKE_ACTION_CONTROL_OBSERVE_PER_TYPE_PER_ROOT_INPUT` | `10` | Per-root-input cap for observe-style actions of the same type. |
| `NEOPSYKE_ACTION_CONTROL_CONTACT_USER_PER_ROOT_INPUT` | `5` | Per-root-input cap for `contact_user` deliveries. |
| `NEOPSYKE_ACTION_CONTROL_REFLECTION_FAMILY_PER_ROOT_INPUT` | `2` | Per-root-input cap across reflection-family actions. |
| `NEOPSYKE_ACTION_CONTROL_REFLECT_EVIDENCE_PER_ROOT_INPUT` | `1` | Per-root-input cap specifically for `reflect_evidence`. |
| `NEOPSYKE_ACTION_CONTROL_GOAL_OPERATION_PER_ROOT_INPUT` | `3` | Per-root-input cap for `goal_operation` actions. |
| `NEOPSYKE_ACTION_CONTROL_COMMIT_PRIVATE_PER_TYPE_PER_ROOT_INPUT` | `3` | Per-root-input cap for private side-effecting commits of the same type. |
| `NEOPSYKE_ACTION_CONTROL_COMMIT_STATEFUL_PER_TYPE_PER_ROOT_INPUT` | `2` | Per-root-input cap for stateful side-effecting commits of the same type. |
| `NEOPSYKE_ACTION_CONTROL_COMMIT_PUBLIC_PER_TYPE_PER_ROOT_INPUT` | `1` | Per-root-input cap for public side-effecting commits of the same type. |
| `NEOPSYKE_ACTION_CONTROL_CONTROL_PLANE_PER_TYPE_PER_ROOT_INPUT` | `2` | Per-root-input cap for control-plane actions of the same type. |

---

## Connectors

These apply to third-party connector action loading. They are separate from the native built-in website fetch config.

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_CONNECTORS_ENABLED` | `false` | Enable connector action loading. |
| `NEOPSYKE_CONNECTORS_CATALOG_PATH` | `connectors/catalog` | Curated connector catalog path. |
| `NEOPSYKE_CONNECTORS_STATE_DIR` | `.neopsyke/connectors` | Local installed/enabled connector state directory. |
| `NEOPSYKE_CONNECTORS_FAIL_CLOSED` | `true` | Skip invalid/incomplete connectors. |
| `NEOPSYKE_CONNECTORS_PINNING_ENABLED` | `true` | Require pinned tool-description digests. |
| `NEOPSYKE_CONNECTORS_STARTUP_TIMEOUT_MS` | `5000` | Connector capability discovery timeout. |
| `NEOPSYKE_CONNECTORS_HEALTH_TIMEOUT_MS` | `5000` | Connector health-check timeout. |
| `NEOPSYKE_CONNECTORS_CALL_TIMEOUT_MS` | `8000` | Connector capability call timeout. |
| `NEOPSYKE_CONNECTORS_ALLOWED_IDS` | — | Comma-separated connector allowlist. |
| `NEOPSYKE_CONNECTORS_ENABLED_BUNDLES` | — | Comma-separated curated bundle ids. |
| `NEOPSYKE_CONNECTORS_ALLOW_THIRD_PARTY` | `false` | Explicit opt-in for curated third-party connectors. |

---

## Inner Voice

| Variable | Default | Description |
|---|---|---|
| `NEOPSYKE_INNER_VOICE_ENABLED` | `true` | Enable inner-voice event capture. |
| `NEOPSYKE_INNER_VOICE_MAX_CONTENT_CHARS` | `500` | Max chars per inner-voice event. |
| `NEOPSYKE_INNER_VOICE_MAX_EVENTS_PER_SESSION` | `100` | Max retained inner-voice events per session. |

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
| `NEOPSYKE_EVAL_STAGE` | from YAML / CLI | Default stage label for eval runs when CLI `--eval-stage` is omitted. |
| `NEOPSYKE_LLM_CACHE_MODE` | `off` | LLM response caching: `record`, `replay`, or `off`. |
| `NEOPSYKE_LLM_CACHE_FILE` | — | Path to JSONL cache file for LLM response caching. |
