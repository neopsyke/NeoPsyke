# Getting Started

This guide walks through installation, first run, and real examples of what NeoPsyke can do today.

For the full configuration reference, see [configuration.md](configuration.md).

---

## Prerequisites

- **JDK 21+** — NeoPsyke targets Java 21 bytecode.
- **At least one LLM API key** — Anthropic, OpenAI, Groq, Mistral, Google, or local Ollama. Multiple providers can be used simultaneously for different cognitive roles.
- **Docker** (optional but recommended) — for long-term vector memory via PostgreSQL + pgvector.

## Install and build

```bash
git clone https://github.com/neopsyke/neopsyke.git
cd neopsyke
./gradlew installDist
```

This compiles the project and produces a standalone distribution under `build/install/neopsyke/`.

## Configure LLM access

NeoPsyke ships with bundled default configuration under `config/`. To customize, create a local overlay file at the repository root named `llm-runtime.yaml`. This file merges on top of the bundled defaults — you only need to specify the fields you want to change.

The fastest way to start is to copy one of the ready-made examples:

```bash
cp examples/runtime-config/llm-runtime.external.example.yaml llm-runtime.yaml
```

Or create a minimal overlay that changes just the provider and model for each cognitive role:

```yaml
cognitive_roles:
  planner:
    provider: google
    model: gemini-2.5-flash
  superego_primary:
    provider: google
    model: gemini-2.5-flash
  meta_reasoner:
    provider: google
    model: gemini-2.5-flash
  memory_advisor:
    provider: google
    model: gemini-2.5-flash

web_search:
  provider: google
  model: gemini-2.5-flash
```

Provider definitions (base URLs, API key env var names, default models) are already in the bundled `config/llm-runtime.yaml`. Your overlay inherits them automatically.

Set your API keys as environment variables:

```bash
export GOOGLE_API_KEY="..."
# Supported: ANTHROPIC_API_KEY, GROQ_API_KEY, GOOGLE_API_KEY, MISTRAL_API_KEY, OPENAI_API_KEY, OLLAMA_API_KEY
```

## (Optional) Start the memory backend

For persistent long-term vector memory:

```bash
docker compose up -d
```

This starts a PostgreSQL + pgvector instance. NeoPsyke will auto-bootstrap its managed memory provider on first run — you do not need to configure the provider separately.

Without Docker, long-term memory is disabled but everything else works normally. You can also set `mode: off` in `memory-runtime.yaml` to explicitly disable it.

## Run

```bash
./run-neopsyke
```

NeoPsyke starts in interactive mode. On startup it will:

1. Build the project if needed (first run only).
2. Run **provider health checks** for each configured cognitive role endpoint.
3. Start the managed memory provider if configured.
4. Start the web dashboard at `http://localhost:8787`.

### Provider health checks

Before accepting input, NeoPsyke verifies that all configured LLM providers are reachable. Each check includes DNS resolution for the provider host and a short authenticated HTTP probe (`GET /models`).

- If a **required configured cognitive-role endpoint** is unavailable (the endpoints assigned to `planner`, `action_verifier`, `superego`, `meta_reasoner`, or `memory_advisor` in the active LLM config), NeoPsyke prints a clear error and exits early.
- If a provider is **degraded** (e.g., rate limiting), NeoPsyke logs a warning but continues.
- **Transient failures** (timeouts) are retried once before deciding the final state.
- The `meta_reasoner_fallback` role is treated as **optional**: if its health check fails after retry, NeoPsyke disables it for the run and continues with the primary meta-reasoner.
- For `--eval-memory-live` mode, the long-term memory provider is also preflighted and must pass for the eval to start.

You can now interact with the agent through the **web dashboard** (recommended) or via the dashboard chat API.

The terminal is reserved for runtime control commands only (e.g., `exit`).

### Useful launch options

```bash
# Explicit log level
./run-neopsyke --log-level info

# Disable the loop delay for faster local interaction
./run-neopsyke --no-delay

# Disable the Id (no autonomous impulses)
./run-neopsyke --no-id

# Run without goals
NEOPSYKE_GOALS_ENABLED=false ./run-neopsyke
```

---

## What you can do

The following examples show real interactions you can have with the agent today.

### Conversation

NeoPsyke supports multi-turn dialogue. The planner reasons over your message, short-term context, episodic recall, and long-term memory to form a response.

```
You: What are some benefits of using Kotlin over Java for backend development?

NeoPsyke: [plans a response using available knowledge, answers directly]
```

If the planner decides it needs more information, it will use available tools before answering.

### Web search

Ask something that requires current information:

```
You: What were the main announcements at KotlinConf 2025?

NeoPsyke: [executes web_search → reviews results → synthesizes answer]
```

The agent decides autonomously whether a web search is needed. Search results pass through prompt injection defense before reaching the planner.

### Website content

Ask the agent to read a specific webpage:

```
You: Summarize the content of https://kotlinlang.org/docs/coroutines-overview.html

NeoPsyke: [executes website_fetch → reads content → produces summary]
```

### Multi-step reasoning

For complex questions, the agent uses multiple planning steps, a scratchpad for intermediate state, and meta-reasoning to detect when it is going in circles:

```
You: Compare the trade-offs between using coroutines vs virtual threads for
     high-concurrency JVM services. Consider performance, debugging, and
     ecosystem maturity.

NeoPsyke: [plans → searches for recent benchmarks → fetches relevant pages →
           builds scratchpad with evidence → synthesizes comparative answer]
```

The Ego loop runs up to 180 steps per input (configurable). A meta-reasoner monitors decision pressure and intervenes if the planning chain stalls or loops.

### Learning and memory

NeoPsyke can remember things across conversations:

```
You: My preferred programming language is Kotlin, and I work mostly on
     backend services. Remember this.

NeoPsyke: [reflects → memory advisor assesses → imprints to long-term memory]
```

In future conversations, long-term memory recall surfaces relevant prior context automatically.

Episodic memory records a narrative log of interactions, searchable with full-text search and retained for 90 days by default.

### Autonomous behavior (the Id)

When the Id is enabled and the agent is idle, internal drives accumulate pressure over time. Two drives are active by default:

- **user-interaction** — drive to proactively reach out to the user.
- **learn-something** — drive to acquire new knowledge.

When a drive crosses its urgency threshold, the Id emits an impulse to the Ego. The Ego plans a response (e.g., a web search to learn something, or a message to the user), and the Superego reviews whether the proposed action is allowed.

If the action succeeds and satisfies the need, the drive resets. If denied, the drive continues to build pressure (with backoff after repeated denials).

You do not need to do anything to trigger this — it happens autonomously when the agent is not busy with user requests.

### Goals

Goals are durable, persistent objectives that survive across sessions. You create them through natural conversation:

**Research goal (one-shot execution):**

```
You: I need you to research the current state of WebAssembly support in Kotlin.
     Find out what tools exist, what the limitations are, and write a summary.

NeoPsyke: [creates a goal → generates a plan with steps → executes:
           web search → fetch relevant pages → synthesize findings →
           produce summary → complete goal]
```

**Monitoring goal (recurring):**

```
You: Check the Kotlin blog every morning for new posts and let me know
     if anything is published.

NeoPsyke: [creates a recurring goal with a daily schedule →
           on each trigger: fetches the blog → checks for new content →
           contacts you only if something new is found]
```

Recurring goals require explicit approval in the action control panel before they are created.

**Scheduled goal with a deadline:**

```
You: Remind me to review the quarterly metrics report by Friday at 5pm.

NeoPsyke: [creates a goal with a timer wait condition →
           when the deadline approaches: contacts you with the reminder]
```

**Standing audit goal:**

```
You: Keep an eye on our GitHub repository's open issues. If any critical
     bugs are reported, summarize them for me.

NeoPsyke: [creates a monitoring goal → periodically checks →
           reports only on new critical issues, tracking what it has
           already seen via novelty fingerprints]
```

Goals go through the full security model: the `goal_operation` action is reviewed by the Superego, and recurring goals require staging and approval. Goal state is persisted to disk and survives restarts.

You can check goal status through the dashboard or by asking the agent directly.

### Reflection

The agent can explicitly capture lessons and observations for future reference:

```
You: We just figured out that the timeout on the memory provider needs to be
     at least 12 seconds for large imprints. Save this for future reference.

NeoPsyke: [executes reflect action → saves to episodic memory with keywords →
           optionally imprints to long-term memory if the advisor recommends it]
```

### The dashboard

Open `http://localhost:8787` in your browser:

- **Conversations** — chat interface with session management.
- **Observability** (`/dashboard`) — live view of the cognitive loop: queue states, thought chains, LLM calls, Superego decisions, memory operations, and scratchpad state.
- **Action Control** (`/action-control`) — review staged actions, approve or deny them, and inspect execution receipts.

---

> **Terminology:** See the [Glossary](glossary.md) for definitions of all agent concepts used in this document.

## What to explore next

- [Configuration reference](configuration.md) — YAML files, tuning knobs, and metrics/observability.
- [Environment variable reference](env-reference.md) — complete list of all ~100 supported env vars.
- [Evaluation](evaluation.md) — how to run tests and evals, and where to contribute.
- [Telegram setup](telegram-setup.md) — bot token, webhook/polling, owner-only filtering.
- [Security model](security.md) — trust model, policy enforcement, action lifecycle.
- [Architecture notes](../AGENT_LOGIC_SUMMARY.md) — the current runtime logic reference.
