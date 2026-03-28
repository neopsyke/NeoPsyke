# NeoPsyke

NeoPsyke is an autonomous AI agent built around a cognitive architecture inspired by Freud's structural model. It is designed to generate internal motivation, maintain durable goals and memory, do useful work through a full agent runtime, and route actions through explicit governance.

The system is organized around three core modules:

- **Id** -- generates motivation and proactive behavior through bounded internal drives.
- **Ego** -- plans, reasons, and mediates between motivation, reality, and constraints.
- **Superego** -- judges intentions and enforces governance, safety, and self-control.

LLMs power the internal cognitive roles. Around that core, NeoPsyke provides short-term, long-term, and episodic memory, durable and recurring goals, and a layered security model built into the runtime.

> **Status:** Experimental. The architecture is real and implemented, but the project is under active development. Expect rough edges.

## Why this exists

The starting point was simple: by creating an orchestration program using conventional software methods, it may be possible to build an internally motivated control loop that leverages existing LLM technology. Not to create consciousness, and not to claim AGI, but to test whether a useful autonomous agent can be organized around distinct internal functions for motivation, planning, and governance.

The choice of Freud's model is not presented as a claim that psychoanalysis is scientifically complete or literally true. It is used as an operational decomposition: the Id, Ego, and Superego provide a familiar and compact way to describe three different jobs inside an agent:

- generate motivation,
- mediate between motivation and reality,
- and enforce governance and self-control.

The project is not trying to be:

- a chat interface over an LLM
- a generic tool-calling agent
- a conventional workflow engine with cognitive terminology layered on top

It is trying to build a useful, capable cognitive architecture with clear internal boundaries:

- perception is distinct from control
- interpretation is distinct from transport
- internal drives are distinct from user requests
- decision-making is separate from execution
- durable goals are separate from ephemeral turns
- judgment and safety are first-class architectural functions

Just as importantly, the goal is not a minimal theoretical demonstration. If the core mechanism were the only objective, it could be explored in a much smaller and simpler project. NeoPsyke is an attempt to embed that mechanism in a genuinely useful agent runtime.

## Why Kotlin?

Most AI agent projects default to Python. This one uses Kotlin, and the choice is deliberate. Type safety and the compiler give coding agents a tight feedback loop -- errors are caught at compile time, not at runtime. Kotlin's sealed hierarchies and data classes are a natural fit for modeling cognitive types. Coroutines provide structured concurrency for an inherently concurrent architecture. And pragmatically: I have deeper experience in Kotlin than in production-grade Python, and trying to write well-architected Python was costing more time than the language choice was worth. The architecture mattered more than the ecosystem default. See [docs/why-kotlin.md](docs/why-kotlin.md) for the full reasoning.

## Quickstart

### Prerequisites

- JDK 21+
- At least one LLM API key (Anthropic, OpenAI, Groq, Mistral, Google, or local Ollama)
- Docker (optional but recommended, for long-term vector memory via pgvector)

### 1. Clone and build

```bash
git clone https://github.com/neopsyke/neopsyke.git
cd neopsyke
./gradlew installDist
```

### 2. Configure LLM access

NeoPsyke ships with bundled defaults under `config/`. To customize, copy a ready-made overlay or create a minimal one:

```bash
cp examples/runtime-config/llm-runtime.external.example.yaml llm-runtime.yaml
```

Set your API keys as environment variables:

```bash
export GOOGLE_API_KEY="..."
# Supported: ANTHROPIC_API_KEY, GROQ_API_KEY, GOOGLE_API_KEY, MISTRAL_API_KEY, OPENAI_API_KEY, OLLAMA_API_KEY
```

The overlay merges on top of the bundled defaults -- you only need to specify the fields you want to change. Each cognitive role (`planner`, `superego_primary`, `superego_escalation`, `meta_reasoner`, `memory_advisor`) can use a different provider and model.

### 3. (Optional) Start the memory backend

For persistent long-term vector memory:

```bash
docker compose up -d
```

This starts a PostgreSQL + pgvector instance. NeoPsyke will auto-bootstrap its managed memory provider on first run. Without this, long-term memory is disabled but everything else works.

### 4. Run

```bash
./run-neopsyke doctor
./run-neopsyke
```

The agent starts in interactive mode with a web dashboard at `http://localhost:8787`. Open the dashboard to begin a conversation.

## How it works

```
                    +-----------+
                    |    Id     |  Drives accumulate pressure over time.
                    +-----+-----+  When urgent, impulses are sent to the Ego.
                          |
                          v
User input -----> SensoryCortex -----> Ego (Planner + Attention)
                          ^              |
                          |              +---> DecisionVerifier (is this ready?)
                   Feedback as           +---> Superego (is this allowed?)
                   new stimuli           +---> MotorCortex (execute action)
                          |                        |
                          +------------------------+
                          |
                    MemorySystem (short-term + long-term + episodic)
                    GoalManager (durable, recurring, scheduled goals)
```
> For a full reference of all agent concepts and terminology, see the [Glossary](docs/glossary.md).

### Canonical cognitive sequence

Every interaction follows this chain:

1. A **stimulus** reaches the `SensoryCortex` -- a user message, an Id impulse, a goal signal, tool feedback, or a timer cue. Each stimulus carries security metadata: who sent it, through what channel, and its trust level.
2. The system appraises the stimulus into a typed **percept** (request, observation, feedback, state-change, or drive activation).
3. The percept is attached to an existing **cognitive thread** or starts a new one. Cognitive threads persist across time and can suspend, resume, and accumulate context.
4. The cognitive thread emits one or more **opportunity** items for the Ego's attention.
5. The **Ego** attends to the highest-priority opportunity and forms an **intention** -- a candidate course of action.
6. The **DecisionVerifier** checks whether the intention is grounded, sufficient, and ready to commit -- or whether more reasoning steps are needed.
7. The **Superego** judges the candidate intention against policy and safety constraints.
8. If allowed, the **MotorCortex** executes it through discovered action plugins.
9. Execution feedback re-enters the system as new typed stimuli, closing the loop.

Throughout this sequence, the **MemorySystem** provides context: short-term memory maintains rolling conversation state, episodic memory offers narrative recall, and a long-term memory advisor periodically consolidates important information into durable vector storage.

The Ego runs in a bounded loop (configurable, default 180 steps per input) to prevent runaway reasoning. A meta-reasoner monitors decision pressure and intervenes if the planning chain stalls or loops.

## Capabilities

**Conversation and reasoning.** Multi-turn dialogue with planning, bounded deliberation, meta-reasoning under decision pressure, and a scratchpad workspace for intermediate working state.

**Autonomous motivation.** The Id maintains internal drives that accumulate pressure over time. When a drive crosses a threshold and the Ego is idle, the system can initiate proactive behavior. The agent is driven by generic internal prompts rather than narrowly predefined tasks, so it has to decide what deserves attention and what kind of work to pursue. Governance and action outcomes feed back into motivation: successful actions can discharge pressure, while denied or failed paths do not.

**Durable goals.** Goals are first-class persistent objects with lifecycle management (`CREATED`, `PLANNING`, `ACTIVE`, `BLOCKED`, `SUSPENDED`, `COMPLETED`, `FAILED`). They can be triggered by schedules, events, polling, manual activation, or async resume. This is where NeoPsyke places explicit scheduled work: tasks that genuinely need reliability, timing, or frequency. Each goal can carry an execution plan with steps and acceptance criteria, or act as a standing monitor or recurring synthesis task. Goals also track novelty to avoid re-alerting on already-seen information.

**Three-tier memory.** Short-term memory maintains rolling context per conversation with automatic compaction. Episodic memory records narrative events in a SQLite FTS5 store with configurable retention. Long-term memory uses pgvector for semantic recall and imprint, guided by an LLM-based advisor that periodically assesses what is worth consolidating.

**Web search and browsing.** Configurable web search (Groq, Mistral, or Google providers) and website content fetching, with prompt-injection defense applied to all external content before it reaches the planner.

**Human-in-the-loop action control.** High-impact actions can be staged, reviewed, authorized, denied, and recorded through the dashboard instead of being executed immediately.

**External integrations.** Telegram bot support, Google Workspace read/observe integrations for Gmail and Calendar via OAuth with PKCE, and staged email sending through Microsoft Graph.

**Action plugin system.** Actions are discovered at startup via Java `ServiceLoader`. Built-in actions cover user communication (`contact_user`, `resolution_draft`), web research (`web_search`, `website_fetch`), internal reflection and memory capture (`reflect_internal`, `reflect_evidence`), goal management (`goal_operation`), outbound email (`email_send`), and Google Workspace observation (`gmail_observe_search`, `gmail_observe_message`, `calendar_observe_events`). New actions can be added by implementing the plugin interface and registering via `ServiceLoader`.

**Web dashboard.** Chat interface, observability dashboard with runtime metrics, and an action control panel for reviewing staged actions and approvals -- all served from a local web server.

## Security model

Security is not an afterthought or a moderation layer bolted on top. It is a structural part of the cognitive loop, enforced at every stage from ingress to execution.

### Action lifecycle

The traditional agent model is `plan → review → execute`. NeoPsyke replaces this with a richer lifecycle:

```
observe → prepare → stage → authorize → commit → record
```

High-impact actions are not just "tool calls." They follow explicit lifecycle stages: actions can be direct-committed (low risk, opted in per action), staged for operator approval (higher risk), or denied outright. Authorization decisions are durable artifacts stored in SQLite, not conversational memories that can be forgotten or hallucinated.

### Distributed policy enforcement

The Superego is central to governance, but it is not the sole enforcement boundary. Hard policy belongs in deterministic code, distributed across the cognitive pipeline:

- **`SensoryCortex`** enforces stimulus-level policy: channel authentication, identity normalization, initial trust/provenance assignment, and early rejection of invalid input.
- **Cognitive thread construction** enforces thread-level policy: root trust scope, policy scope, and visible action-family bounds.
- **Opportunity construction** enforces opportunity-level policy: which next moves are actually available, which lifecycle transitions are permitted, and which options are removed due to provenance or limits.
- **`Ego`** selects among already policy-shaped opportunities. It cannot widen the action surface it receives.
- **`SuperegoDeterministicConscience`** enforces hard policy invariants on intended action progression through deterministic code rules.
- **`SuperegoReviewEngine`** handles contextual judgment within policy bounds, optionally using an LLM with two-stage escalation support.
- **`MotorCortex`** refuses to execute any commit that lacks a valid authorization artifact or violates final execution constraints.

This means that by the time the Ego forms an intention, the action surface has already been narrowed by multiple independent policy layers. The Superego reviews what remains, and the MotorCortex enforces the final guard.

### Trust model

Every stimulus entering the system carries explicit security metadata:

- **Principal role** -- who sent it: `OWNER`, `SYSTEM_INTERNAL`, `APPROVED_AUTOMATION`, `EXTERNAL_PARTICIPANT`, `UNAUTHENTICATED_EXTERNAL`, or `ADMIN_CONTROL`.
- **Channel identity** -- through what provider, surface (direct/group/shared), and transport class.
- **Instruction trust** -- whether the content is `TRUSTED_INSTRUCTION` or `UNTRUSTED_INSTRUCTION`. Only the owner's approved channels produce trusted instructions.

Internal drives and user messages are never allowed to impersonate each other. This separation is enforced structurally, not by prompt.

### Prompt injection defense

All external content (web search results, fetched pages, tool outputs) passes through a deterministic sanitization pipeline before reaching the planner.

### Action payload validation

Action payloads are validated for:

- Secret exfiltration attempts
- PII leakage patterns
- Inline secret material
- Sensitive URLs, localhost, and internal network access
- Sensitive endpoint paths and query parameters

### Denial feedback

Denied actions are not silently discarded. The denial reason is fed back into the Ego for replanning. This keeps work moving instead of dead-ending -- the agent can pursue alternatives rather than failing silently.

### Operator visibility

Staged actions, approvals, denials, and execution receipts are durably recorded and visible through the dashboard. The system maintains an inspectable ledger of what was proposed, what was allowed, and what was executed. Currently approvals are only available through the Action Control dashboard (work in progress). 

### Current limitations

Prompt injection defense is heuristic, not a full sandbox.
Plugins run in-process as trusted code -- third-party connector isolation is designed but not yet implemented.
Dashboard approval assumes local owner trust.

These boundaries are documented and tracked.

For the full implementation details, see [docs/security.md](docs/security.md).

## Non-goals

NeoPsyke does not try to:

- **Implement human psychology.** The Freudian model is an engineering scaffold, not a claim about how minds work.
- **Claim consciousness or AGI.** The system processes natural language and generates motivation, but makes no claim about subjective experience.
- **Replace established agent frameworks.** This is an experiment in a specific architectural direction, not a general-purpose agent SDK.
- **Be production-ready today.** The security model is serious but the project is experimental. Deploy at your own risk.
- **Support untrusted multi-tenant operation.** The current trust model assumes a single owner-operator.

Some omissions are deliberate architectural decisions rather than missing checklist items:

- **Use sub-agents before trust boundaries are ready.** Delegation is intentionally excluded for now because the current security model is not yet strong enough around trust propagation, authority boundaries, and containment.
- **Push the Ego into full async processing before the main loop is stable.** Broader async Ego execution is being deferred until the core cognitive loop is more stable across the current feature set.

**Current risks and limitations:**

- LLM quality directly affects planning and reasoning quality. The architecture mitigates but cannot eliminate model errors.
- Long-running goal execution depends on external service availability and correct action contracts. Goals still needs a lot of testing and tuning.
- The memory advisor's consolidation decisions are LLM-dependent and not yet formally evaluated.
- The Id's drive model is simple by design (configurable state machine, not learned behavior). This mirrors the structural role of drives in the original model -- complexity is expected to emerge from the interaction between modules, not from the drive source itself.
- The dashboard is basic and suitable only for local monitoring and debugging. It's not yet a production-ready multi-user tool.
- Prompt injection defense is heuristic, not a full sandbox, and plugins currently run in-process as trusted code.
- Much testing and tuning is still required in the agent all-around.
- The evals are still minimal and could use much improvement and expansion.

## Testing and evaluation

```bash
# Unit tests
./gradlew test

# Bootstrap Freud once per clone
./freud/bootstrap.sh

# Full validation gate (used for PRs)
./freud/bin/freud run ci-pr
```

The project uses a multi-phase validation pipeline called **Freud**. The harness
home lives under `freud/`, with the Cobra entrypoint at `freud/cli/`, shared Go
packages under `freud/internal/`, and the local built binary at `./freud/bin/freud`.

The normal deterministic developer loop is:

1. **Preflight compile** -- fast build check
2. **Targeted tests** -- focused agent test subset for quick failure signal
3. **Full test suite** -- all JUnit tests
4. **Scenario pack** -- deterministic agent behavior scenarios (`freud/scenarios/v1/`) that exercise the cognitive loop end-to-end
5. **Reasoning eval** -- logic gate tests (shape-lock, feedback-carry, multi-fix) that verify the planner produces structurally correct decisions

The `ci-pr` signoff gate trims that to the non-redundant final gate:

1. **Preflight compile**
2. **Full test suite**
3. **Scenario pack**
4. **Reasoning eval**

Live evaluation lanes (`--lane low-llm`, `--lane high-llm`) and memory live eval are available for deeper validation during development.

## Configuration

NeoPsyke ships with bundled default configuration under `config/`. Local overlay files in the working directory merge on top of the bundled defaults -- you only need to specify the fields you want to change.

| Bundled default | Local overlay | Env override | Controls |
|---|---|---|---|
| `config/llm-runtime.yaml` | `llm-runtime.yaml` | `NEOPSYKE_LLM_CONFIG_FILE` | LLM providers, models, cognitive role routing, web search |
| `config/agent-runtime.yaml` | `agent-runtime.yaml` | `NEOPSYKE_AGENT_CONFIG_FILE` | Planner limits, superego budget, memory caps, dashboard port |
| `config/memory-runtime.yaml` | `memory-runtime.yaml` | `NEOPSYKE_MEMORY_CONFIG_FILE` | Long-term memory provider (managed pgvector / external / off) |
| `config/id-runtime.yaml` | `id-runtime.yaml` | `NEOPSYKE_ID_CONFIG_FILE` | Drive system (needs, growth rates, thresholds, cooldowns) |
| -- | `action-security.yaml` | -- | Per-action commit policy (direct/staged/denied) |

Precedence: environment variables > external YAML overlay > bundled YAML defaults.

Each cognitive role (planner, superego primary/escalation, action verifier, meta-reasoner, memory advisor) can be independently routed to different LLM providers and models, allowing cost and quality optimization per function. Supported providers: Anthropic, Groq, Google, Mistral, OpenAI, Ollama.

## Docs map

| Document | Description |
|---|---|
| [docs/glossary.md](docs/glossary.md) | Grouped reference for all agent concepts and terminology |
| [docs/getting-started.md](docs/getting-started.md) | Installation, first run, and real usage examples |
| [docs/configuration.md](docs/configuration.md) | Full configuration reference (YAML files, env vars, tuning) |
| [docs/evaluation.md](docs/evaluation.md) | Testing layers, eval pipeline, and contributor directions |
| [docs/env-reference.md](docs/env-reference.md) | Complete environment variable reference |
| [docs/glossary.md](docs/glossary.md) | Core runtime terms and architecture vocabulary |
| [docs/security.md](docs/security.md) | Security model: trust, policy enforcement, action lifecycle |
| [docs/tuning/PROMPT_BUDGET_RUN_DIAGNOSTICS.md](docs/tuning/PROMPT_BUDGET_RUN_DIAGNOSTICS.md) | How to inspect prompt-budget run diagnostics |
| [docs/tuning/PROMPT_BUDGET_TUNING_GUIDE.md](docs/tuning/PROMPT_BUDGET_TUNING_GUIDE.md) | Prompt-budget tuning guidance |
| [docs/tuning/TASK_VERIFIER_TUNING_GUIDE.md](docs/tuning/TASK_VERIFIER_TUNING_GUIDE.md) | Task-verifier tuning guidance |
| [docs/why-kotlin.md](docs/why-kotlin.md) | Why Kotlin instead of Python |
| [docs/telegram-setup.md](docs/telegram-setup.md) | Telegram bot setup (webhook/polling, owner filtering) |
| `AGENTS.md` | Instructions for coding agents working in this repository |
| `AGENT_LOGIC_SUMMARY.md` | Current runtime logic reference |
| `AGENT_LOGIC_DIAGRAM.md` | Visual flow of the current agent loop |
| [examples/runtime-config/](examples/runtime-config/) | Ready-to-use external overlay examples for fast start |

## Project structure

```
config/                # Bundled runtime YAML defaults (packaged into artifact)
examples/
  runtime-config/      # Ready-to-use external overlay examples
src/main/kotlin/ai/neopsyke/
  agent/
    id/              # Drive system (Id)
    ego/             # Planning, attention, orchestration (Ego)
    superego/        # Governance, policy review (Superego)
    cortex/motor/    # Action execution (MotorCortex)
    memory/          # Short-term, long-term (Hippocampus), episodic (Logbook)
    goal/            # Durable goal lifecycle and execution
    model/           # Domain models (security, cognitive, actions)
    support/         # Prompt injection defense, text security, payload validation
  config/            # Runtime configuration loaders
  llm/               # LLM provider clients (Anthropic, OpenAI, Groq, Mistral, Google, Ollama)
  integrations/      # Telegram, Google Workspace, email
  dashboard/         # Web UI and API server
  instrumentation/   # Event bus and telemetry sinks
  metrics/           # SQLite metrics persistence
  eval/              # Evaluation harness
```

## Contributing

NeoPsyke is experimental and under active development. Contributions, feedback, and ideas are welcome.

- Open an issue for bugs, questions, or feature proposals.
- Read `AGENTS.md` for coding conventions and validation requirements.
- All PRs go through the Freud validation pipeline.

## License

Apache License 2.0. See [LICENSE](LICENSE).
