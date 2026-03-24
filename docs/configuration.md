# Configuration Reference

NeoPsyke uses domain-grouped YAML configuration files with a layered loading model.

> **A note on tuning.** Many of these knobs interact with each other and with the specific LLM models you use. Token budgets, completion limits, pressure thresholds, and memory assessment intervals all affect agent behavior in non-obvious ways. Significant testing is needed to find good configurations for your setup. When in doubt, start with the defaults and adjust one thing at a time.

---

## Loading model

Each runtime configuration loads in this order:

1. **Bundled YAML** — canonical defaults shipped under `config/` and packaged into the application artifact.
2. **External YAML overlay** — an optional file that merges on top of the bundled defaults.
3. **Environment variable overrides** — applied on top of the merged YAML.
4. **Validation** — the loader rejects malformed or incomplete configuration early instead of silently falling back.

The external YAML is an **overlay**, not a replacement. YAML objects merge recursively by key, scalars replace bundled values, lists replace bundled lists, and `null` values are ignored for merge purposes. A small overlay file can safely change a single field without restating the entire configuration.

**Precedence:** environment variables > external YAML overlay > bundled YAML defaults. CLI flags remain a separate layer for app behavior outside the YAML merge chain.

### File resolution

Each runtime has a bundled default under `config/`, a default external overlay filename in the working directory, and an optional env var pointing to an override file anywhere:

| Bundled file | External overlay default | Env override |
|---|---|---|
| `config/agent-runtime.yaml` | `agent-runtime.yaml` | `NEOPSYKE_AGENT_CONFIG_FILE` |
| `config/id-runtime.yaml` | `id-runtime.yaml` | `NEOPSYKE_ID_CONFIG_FILE` |
| `config/llm-runtime.yaml` | `llm-runtime.yaml` | `NEOPSYKE_LLM_CONFIG_FILE` |
| `config/mcp-runtime.yaml` | `mcp-runtime.yaml` | `NEOPSYKE_MCP_CONFIG_FILE` |
| `config/memory-runtime.yaml` | `memory-runtime.yaml` | `NEOPSYKE_MEMORY_CONFIG_FILE` |

Resolution rules:

- If the override env var is set, that file **must** exist and be non-empty.
- If the default external filename exists in the working directory, it is treated as an overlay.
- If no external file exists, the bundled YAML is used alone.
- Empty files are rejected.

This means:

- **Source checkouts** can drop a local `llm-runtime.yaml` into the repo root and it will be treated as an overlay.
- **Artifact users** can point `NEOPSYKE_*_CONFIG_FILE` at any file they want.
- If neither exists, the app runs with the bundled `config/*.yaml` resources from the JAR.

### Action security policy

`action-security.yaml` is loaded from the working directory. It is not bundled or overlaid — it is the operator's policy file.

### Example overlays

Ready-to-use external overlay examples live under `examples/runtime-config/`. These are minimal fast-start overlays with the important decisions already made, not exhaustive copies of the bundled files. See `examples/runtime-config/README.md` for usage.

---

## 1. LLM Configuration (`config/llm-runtime.yaml`)

This file controls which LLM providers and models are used for each cognitive function. It is also the single source of truth for the model catalog.

### Providers

```yaml
providers:
  anthropic:
    api_key_env: ANTHROPIC_API_KEY
    base_url: https://api.anthropic.com/v1
    default_model: claude-sonnet-4-20250514
    default_web_search_model: claude-sonnet-4-20250514
  groq:
    api_key_env: GROQ_API_KEY
    base_url: https://api.groq.com/openai/v1
    default_model: openai/gpt-oss-20b
    default_web_search_model: groq/compound-mini
  google:
    api_key_env: GOOGLE_API_KEY
    base_url: https://generativelanguage.googleapis.com/v1beta/openai/
    default_model: gemini-2.5-flash
    default_web_search_model: gemini-2.5-flash
  mistral:
    api_key_env: MISTRAL_API_KEY
    base_url: https://api.mistral.ai/v1
    default_model: mistral-small-2506
    default_web_search_model: mistral-small-2506
  ollama:
    api_key_env: OLLAMA_API_KEY
    base_url: http://localhost:11434/api
    default_model: gpt-oss
    default_web_search_model: gpt-oss
  openai:
    api_key_env: OPENAI_API_KEY
    base_url: https://api.openai.com/v1
    default_model: gpt-4o-mini
    default_web_search_model: gpt-4o-mini
```

Each provider declares: the environment variable name for its API key (not the key itself), a base URL, and default model names. Provider base URLs and default models live in YAML, not in Kotlin code.

Supported providers: `anthropic`, `groq`, `google`, `mistral`, `ollama`, `openai`. All use an OpenAI-compatible chat completions API. `OLLAMA_API_KEY` is optional — it only matters for authenticated remote Ollama hosts.

If a cognitive role references an invalid or missing provider, loading fails with a direct error.

### Cognitive roles

```yaml
cognitive_roles:
  planner:
    provider: groq
    model: openai/gpt-oss-120b
  action_verifier:
    provider: openai
    model: gpt-4o-mini
  superego_primary:
    provider: openai
    model: gpt-4o-mini
  superego_escalation:
    provider: openai
    model: gpt-4.1-mini
  meta_reasoner:
    provider: groq
    model: openai/gpt-oss-120b
  meta_reasoner_fallback:
    provider: openai
    model: gpt-5-mini
  memory_advisor:
    provider: openai
    model: gpt-4.1-mini
```

Each cognitive role can use a different provider and model. This allows cost optimization — use cheaper/faster models for high-frequency low-stakes roles and stronger models for planning.

| Role | What it does |
|---|---|
| `planner` | Main reasoning and planning LLM. Forms thoughts, proposes actions, generates answers. |
| `action_verifier` | Checks whether a candidate action is grounded, sufficient, and ready to commit. |
| `superego_primary` | Reviews proposed actions against safety directives and policy. |
| `superego_escalation` | Stronger model used when two-stage review escalates on low confidence or medium policy risk. |
| `meta_reasoner` | Intervenes when the planning chain stalls or loops. Classifies chain health. |
| `meta_reasoner_fallback` | Optional fallback if the primary meta-reasoner is unavailable. Treated as optional at startup. |
| `memory_advisor` | Periodically assesses whether current context should be consolidated into long-term memory. |

Partial external overlay files inherit bundled provider definitions automatically. A minimal overlay can change just the planner without restating all other roles:

```yaml
# Example: change only the planner
cognitive_roles:
  planner:
    provider: anthropic
    model: claude-sonnet-4-20250514
```

### Web search

```yaml
web_search:
  provider: groq
  model: groq/compound-mini
```

Web search is configured independently from cognitive roles. Supported providers: `groq`, `mistral`, `google`, `openai`.

### Model catalog (optional)

```yaml
model_catalog:
  openai:
    - model: gpt-4o-mini
      tier: mid
      token_weight: 1.0
      context_window: 128000
  groq:
    - model: openai/gpt-oss-20b
      tier: low
      token_weight: 0.3
      context_window: 32000
```

The model catalog is YAML-only and supports source-review timestamps through `metadata_updated_at`. `token_weight` is used to scale dynamic completion budgets for the superego and memory advisor. Lower weight = more generous budget (the model is cheaper, so the system can afford more tokens).

### Tuning notes

- **Planner model quality matters most.** The planner drives all reasoning. A weak planner produces poor plans regardless of other settings.
- **Superego can use a cheaper model** for `superego_primary` since its task is narrower (policy review, not open-ended reasoning). Use a stronger model for `superego_escalation` to catch edge cases the primary misses.
- **Meta-reasoner fires infrequently** (only under pressure), so cost is usually low regardless of model choice.
- **Memory advisor fires periodically** (every N steps). A cheap model works if you accept occasional suboptimal consolidation decisions.

---

## 2. Agent Configuration (`config/agent-runtime.yaml`)

This is the largest configuration file, covering the Ego planner, Superego, memory system, meta-reasoner, episodic logbook, goals, integrations, and runtime behavior.

### Planner (`agent.planner`)

| Key | Default | Description |
|---|---|---|
| `max_loop_steps_per_input` | `180` | Maximum Ego loop iterations per user input. Safety bound against runaway reasoning. |
| `max_thought_passes` | `5` | Maximum consecutive thought passes before the planner must propose an action or answer. |
| `max_thought_chars` | `600` | Maximum length of a single thought. |
| `max_input_chars` | `2000` | Maximum user input length processed. |
| `max_action_payload_chars` | `4000` | Maximum action payload size. |
| `max_action_summary_chars` | `180` | Summary context included with every action for Superego review. |
| `max_prompt_tokens` | `2400` | Token budget for planner prompts. |
| `max_completion_tokens` | `900` | Token budget for planner completions. |
| `llm_retry_attempts` | `2` | Retries on transient LLM failures. |
| `max_run_total_tokens` | `0` | Per-run total token cap (0 = unlimited). |
| `max_run_tokens_per_provider` | `0` | Per-run token cap per provider (0 = unlimited). |
| `max_run_tokens_per_role` | `0` | Per-run token cap per cognitive role (0 = unlimited). |
| `max_plan_steps` | `6` | Maximum steps in a goal execution plan. |
| `max_plans_per_input` | `2` | Maximum plan generations per input. |
| `action_retry_budget_non_retryable_failures` | `3` | How many non-retryable action failures before giving up. |
| `action_retry_cooldown_steps` | `10` | Steps to wait before retrying a failed action type. |
| `action_verifier_enabled` | `false` | Enable the DecisionVerifier LLM call before Superego review. |

**Tuning notes:**
- `max_loop_steps_per_input` is your main safety valve. Start with the default (180) and lower it if you find the agent spending too many tokens on simple requests.
- `max_thought_passes` limits how long the agent can "think" before it must act. Lower values force faster convergence but may reduce answer quality for complex questions.
- Token caps (`max_run_total_tokens`, etc.) are disabled by default. Enable them for cost control during experimentation.

### Superego (`agent.superego`)

| Key | Default | Description |
|---|---|---|
| `max_completion_tokens` | `192` | Base token budget for superego review. |
| `dynamic_completion_enabled` | `true` | Scale superego budget based on prompt size and model token weight. |
| `dynamic_completion_hard_max_tokens` | `640` | Absolute ceiling for dynamic scaling. |
| `dynamic_prompt_to_completion_ratio` | `0.10` | Completion tokens as a ratio of prompt tokens. |
| `dynamic_completion_min_prompt_tokens` | `160` | Minimum prompt tokens before dynamic scaling kicks in. |
| `two_stage_review_enabled` | `false` | Use a cheap model first, escalate to a stronger model on low confidence. |
| `two_stage_low_confidence_threshold` | `0.60` | Confidence below which the cheap model escalates. |
| `two_stage_escalate_on_medium_policy_risk` | `true` | Also escalate when policy risk is medium. |
| `two_stage_skip_for_contact_user_actions` | `true` | Skip two-stage for `contact_user` actions (low risk). |
| `two_stage_skip_for_web_search_actions` | `true` | Skip two-stage for `web_search` actions (low risk). |

**Tuning notes:**
- Two-stage review can reduce costs for high-volume low-risk actions while maintaining strong review for sensitive ones.
- Dynamic completion scaling ties the superego budget to prompt complexity. With cheap models (`token_weight < 1.0`), the budget is scaled up proportionally.

### Memory (`agent.memory`)

#### Short-term memory

| Key | Default | Description |
|---|---|---|
| `max_short_term_context_chars` | `20000` | Rolling context buffer size. Older turns are compacted into a summary. |
| `max_short_term_context_prompt_tokens` | `384` | Token budget for short-term context in planner prompts. |

#### Long-term memory

| Key | Default | Description |
|---|---|---|
| `long_term_memory_recall_max_items` | `4` | Maximum items returned per recall query. |
| `long_term_memory_recall_max_chars` | `1200` | Maximum characters from recall results. |
| `long_term_memory_assess_every_steps` | `16` | How often the memory advisor runs (in Ego loop steps). |
| `long_term_memory_assess_cooldown_steps` | `8` | Minimum steps between advisor assessments. |
| `long_term_memory_min_confidence` | `0.65` | Minimum advisor confidence to trigger an imprint. |
| `long_term_memory_force_assess_on_terminal_answer` | `true` | Always run the advisor when a final answer is produced. |
| `long_term_memory_force_assess_on_allowed_action` | `false` | Run the advisor after every allowed action. |
| `long_term_memory_prompt_compression_enabled` | `true` | Compress dialogue context in memory advisor prompts. |

**Tuning notes:**
- `assess_every_steps` and `cooldown_steps` control how frequently the memory advisor fires. Lower values catch more, but increase LLM costs.
- `min_confidence` is the advisor's self-reported confidence threshold. Lower it to save more aggressively, raise it to be more selective.
- Recall echo thresholds (`recall_echo_*`) control deduplication of recalled items against short-term context. These prevent the planner from seeing the same information twice.

#### Scratchpad (`agent.memory.scratchpad`)

The scratchpad is an ephemeral per-request notebook for structured intermediate state (sections, evidence, progress tracking). It is destroyed when the root request resolves.

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable the scratchpad for multi-step reasoning. |
| `activation_min_plan_steps` | `2` | Minimum plan steps before the scratchpad activates. |
| `max_sections` | `10` | Maximum scratchpad sections. |
| `max_evidence_items` | `8` | Maximum evidence items tracked. |
| `final_pass_rewrite_enabled` | `true` | Run a final LLM pass to rewrite the answer from scratchpad compilation. |
| `final_pass_min_workspace_confidence` | `0.35` | Minimum workspace confidence for the final pass to trigger. |

### Meta-reasoner (`agent.meta_reasoner`)

The meta-reasoner is a deliberation engine that fires when decision pressure builds up in the Ego loop. It classifies chain health and can force convergence.

| Key | Default | Description |
|---|---|---|
| `deliberation_pressure_assessment_min_step` | `16` | Don't assess pressure before this step. |
| `deliberation_pressure_assessment_every_steps` | `8` | Check pressure every N steps after the minimum. |
| `deliberation_pressure_assessment_threshold` | `0.68` | Pressure level that triggers meta-reasoning. |
| `cooldown_steps` | `6` | Steps between meta-reasoner interventions. |
| `forced_terminal_pressure_threshold` | `0.98` | Pressure level that forces immediate termination. |
| `forced_terminal_stale_streak_threshold` | `8` | Consecutive stale steps that force termination. |

**Tuning notes:**
- These thresholds directly affect how long the agent "thinks" before converging. Lower pressure thresholds = faster convergence, potentially at the cost of answer quality.
- `forced_terminal_*` thresholds are hard safety limits. The agent will terminate the loop regardless of progress.

### Episodic logbook (`agent.logbook`)

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable episodic memory logging. |
| `retention_days` | `90` | How long episodic entries are retained. |
| `max_entries_per_query` | `20` | Maximum entries returned per search. |
| `use_llm_summarizer` | `false` | Use LLM to generate episode summaries (vs. deterministic). |

### Goals (`agent.goals`)

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable the goal subsystem. |
| `workspace_root` | `~/.neopsyke/goals` | Where goal state and artifacts are persisted. |

### Integrations (`agent.native_integrations`)

#### Telegram (`agent.native_integrations.telegram`)

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable Telegram integration. |
| `mode` | `polling` | Ingress mode: `polling` or `webhook`. |
| `bot_token_handle` | `TELEGRAM_BOT_TOKEN` | Env var name containing the bot token. |
| `webhook_secret_handle` | `TELEGRAM_WEBHOOK_SECRET` | Env var name for webhook secret validation. |
| `owner_chat_id` | — | Restrict to this chat ID (env override: `NEOPSYKE_TELEGRAM_OWNER_CHAT_ID`). |
| `owner_user_id` | — | Restrict to this user ID (env override: `NEOPSYKE_TELEGRAM_OWNER_USER_ID`). |
| `require_direct_chat` | `true` | Only accept private direct messages. |

### Runtime (`agent.runtime`)

| Key | Default | Description |
|---|---|---|
| `loop_delay_ms` | `0` | Delay between Ego loop iterations. Use `run-neopsyke.sh` default of `1000ms` for interactive use, `0` for evals. |
| `max_pending_thoughts` | `64` | Maximum queued thoughts. |
| `max_pending_actions` | `32` | Maximum queued actions. |
| `max_pending_inputs` | `32` | Maximum queued inputs. |
| `search_result_count` | `5` | Number of web search results to request. |
| `mcp_call_timeout_ms` | `8000` | Timeout for MCP tool calls. |
| `fetch_max_chars` | `4000` | Maximum characters fetched from websites. |

### App (`app`)

| Key | Default | Description |
|---|---|---|
| `dashboard_enabled` | `true` | Enable the web dashboard. |
| `dashboard_port` | `8787` | Dashboard port. |

---

## 3. Memory Provider (`config/memory-runtime.yaml`)

Controls the long-term memory backend.

### Modes

- **`default`** — NeoPsyke manages a `neopsyke-pgvector-memory` provider over HTTP. The provider jar is auto-bootstrapped from GitHub releases. Requires Docker for PostgreSQL + pgvector (`docker compose up -d`).
- **`external`** — Point NeoPsyke at a compatible external HTTP provider you manage.
- **`off`** — No long-term memory. Everything else works normally.

```yaml
mode: default

defaultProvider:
  provider: neopsyke-pgvector-memory
  transport: http
  baseUrl: http://127.0.0.1:7841
  command: "java -jar .neopsyke/providers/neopsyke-pgvector-memory/current/neopsyke-pgvector-memory-all.jar --transport=http --port=7841"
  startupTimeoutMs: 12000
  healthTimeoutMs: 3000
  bootstrapEnabled: true
  namespace: neopsyke
```

The managed provider owns Docker-based pgvector startup internally. NeoPsyke does not provision PostgreSQL itself.

### External provider contract

External HTTP providers must implement NeoPsyke's versioned `v1` contract:

- `GET /v1/health`
- `GET /v1/metrics`
- `POST /v1/recall`
- `POST /v1/imprint`
- `POST /v1/admin/forget`
- `POST /v1/admin/reset`

---

## 4. Id Configuration (`config/id-runtime.yaml`)

Controls the autonomous drive system.

```yaml
id:
  enabled: true
  pulse_interval_ms: 5000
  trigger_threshold: 0.5
  max_consecutive_denials: 5
  backoff_pulses: 10
  max_pending_impulses: 1
```

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable autonomous internal drives. |
| `pulse_interval_ms` | `5000` | How often the Id checks drive urgency. |
| `trigger_threshold` | `0.5` | Urgency level at which an impulse is emitted. |
| `max_consecutive_denials` | `5` | Consecutive Superego denials before backoff. |
| `backoff_pulses` | `10` | Pulses to skip during backoff. |
| `max_pending_impulses` | `1` | Only one impulse lifecycle at a time. |

### Needs

Each need is individually configurable:

```yaml
  needs:
    user-interaction:
      enabled: true
      description: "Drive to proactively contact the user"
      growth_rate: 0.005
      satisfaction_decay: 0.8
      cooldown_pulses: 5
      convergence: contact_user
      prompt: "I feel a drive to proactively reach out to the user..."
      response_curve:
        type: sigmoid
        steepness: 10.0
        midpoint: 0.5
      satisfaction_effects_any_of:
        - user_message_delivered
      activity_decay:
        input_received: 0.15
        contact_delivered: 0.10
```

| Key | Description |
|---|---|
| `growth_rate` | How fast the need's urgency increases per pulse. |
| `satisfaction_decay` | Multiplier applied to urgency when the need is satisfied. |
| `cooldown_pulses` | Minimum pulses between impulses for this need. |
| `convergence` | What action type satisfies this need (`contact_user`, `internalize`). |
| `response_curve` | How urgency maps to trigger readiness. Types: `sigmoid`, `power`, `logarithmic`. |
| `satisfaction_effects_any_of` | Events that satisfy this need. |
| `activity_decay` | Passive urgency reduction from agent activity (even without direct satisfaction). |

**Tuning notes:**
- `growth_rate` and `trigger_threshold` together determine how quickly the agent becomes proactive. Low growth rate + high threshold = very patient. High growth rate + low threshold = frequently proactive.
- `response_curve` shapes the urgency-to-readiness mapping. Sigmoid creates a sharp transition around the midpoint. Power law creates gradual escalation. Logarithmic saturates quickly.
- The Id is intentionally simple. Complexity is expected to emerge from the interaction between Id, Ego, and Superego, not from the drive mechanism itself.

---

## 5. MCP Tools (`config/mcp-runtime.yaml`)

Controls Model Context Protocol tool servers.

```yaml
time:
  enabled: true
  mode: stdio
  provider: mcp-time
  command: "npx -y @anthropic/mcp-time"
  fallback_commands:
    - "uvx mcp-server-time"
    - "npx -y mcp-server-time"

website_fetch:
  enabled: true
  mode: native
  provider: native-jvm
```

Each tool can use `stdio` mode (external process) or `native` mode (in-process JVM). The `fallback_commands` list is tried in order if the primary command is not found in `PATH`.

---

## 6. Action Security Policy (`action-security.yaml`)

Operator-managed, not agent-writable. Controls which actions may commit directly, which require staging, and which are denied.

```yaml
policyVersion: v1

actions:
  contact_user:
    directCommitEnabled: true
    autonomousCommitEnabled: true
  goal_operation:
    directCommitEnabled: true
    autonomousCommitEnabled: true
    recurringRequiresApproval: true
  email_send:
    directCommitEnabled: false
    autonomousCommitEnabled: false
  reflect:
    directCommitEnabled: true
    autonomousCommitEnabled: true

publicCommit:
  enabledTargets: []
```

| Key | Description |
|---|---|
| `directCommitEnabled` | Action can be executed immediately after Superego approval (no staging). |
| `autonomousCommitEnabled` | Action can be committed by the autonomous background worker (for Id-driven actions). |
| `recurringRequiresApproval` | Recurring variants of this action require explicit operator approval. |
| `publicCommit.enabledTargets` | Connector/account targets allowed for public-facing commits. Empty = all public commits denied. |

**Security note:** Direct commit must be explicitly opted in per action. The default for unregistered actions is to require staging.

---

## 7. Metrics and observability

NeoPsyke persists runtime metrics to a SQLite database at `.neopsyke/metrics.db` (override with `NEOPSYKE_METRICS_DB`).

### What is tracked

- **Per-run data:** run ID, provider, model, start/end time, total calls, token usage, denied actions, error counts.
- **Per-call data:** actor, call site, action type, latency, status, prompt/completion token counts.
- **Superego metrics:** token usage tracked separately for current run and persistent totals.
- **Memory metrics:** recall attempts/hits/failures/truncation/latency, consolidation assessments/recommendations, imprint attempts/success/failures/latency.
- **Instrumentation health:** dropped events and queue-saturation hits per run.

### Security

API keys are never stored raw. Metrics use a **salted key fingerprint** (SHA-256 with a per-installation salt stored at `.neopsyke/metrics.salt`).

### Fail-safe persistence

Metrics collection is fail-safe at the client level — LLM and web-search clients emit usage through a persistent metrics observer even outside the normal app runner path. If SQLite initialization fails, NeoPsyke falls back to `.neopsyke/metrics-fallback.jsonl` so usage events are still persisted.

### Runtime defaults

A runtime defaults file is auto-created at `.neopsyke/runtime-defaults.yaml` on first metrics use (override with `NEOPSYKE_RUNTIME_DEFAULTS_FILE`). If `NEOPSYKE_METRICS_DB` is not set, the app uses `metrics_db` from that defaults file.

### Logging

- Run logs are written to per-run files in `.neopsyke/logs/runs/`.
- `.neopsyke/logs/latest.log` symlinks to the newest run log.
- `.neopsyke/logs/latest-events.jsonl` symlinks to the newest event sidecar.
- `.neopsyke/logs/latest-run.env` stores run metadata (`NEOPSYKE_LOG_RUN_ID`, `NEOPSYKE_LOG_FILE`, etc.).
- Old run logs are auto-pruned; retention defaults to 30 files (`NEOPSYKE_LOG_RETENTION`).

### Dashboard observability

The dashboard (`/dashboard`) provides live observability using async instrumentation (`InstrumentationBus`) with pluggable sinks:

- Queue states, loop progression, thought/action flow, Superego decisions, and LLM events are streamed as typed SSE events.
- The scratchpad drawer fetches full debug snapshots on demand.
- API namespaces: `/api/chat/*` (chat control plane), `/api/obs/*` (observability).

---

## Environment variables

For the most commonly used environment variables, see the tables throughout this document. For the **complete reference** of all ~100 supported variables, see [env-reference.md](env-reference.md).
