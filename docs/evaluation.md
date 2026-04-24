# Evaluation and Testing

NeoPsyke uses a multi-layered testing and evaluation approach, from fast unit tests to end-to-end cognitive loop validation. The evaluation infrastructure is still evolving, and better, more comprehensive evals are one of the most impactful areas for contribution.

> **Terminology:** See the [Glossary](glossary.md) for definitions of all agent concepts used in this document.

> **Token-saving tip:** Use [LLM response cache (record / replay)](#llm-response-cache-record--replay) to run live evals repeatedly without API calls. Record once, replay for free.

---

## Quick reference

```bash
# Unit tests only (fast, no LLM calls)
./gradlew test

# Bootstrap Freud once per clone
./freud/bootstrap.sh

# Full validation gate (required for PRs)
./freud/bin/freud run signoff-gate

# Deterministic reasoning eval only
./run-neopsyke --eval-reasoning-only

# Memory live eval (requires pgvector backend)
./run-neopsyke --eval-memory-live
```

---

## Test layers

### 1. Unit tests (`./gradlew test`)

Standard JUnit tests covering individual components: planner parsing, Superego policy logic, memory compaction, assignment state machines, action validation, security models, configuration loading, etc.

These are fast, deterministic, and require no API keys or external services.

**Important:** `./gradlew test` alone is not sufficient for signoff. It does not cover the Freud scenario pack or reasoning eval gates.

### 2. Freud validation pipeline

Freud is the project's validation orchestrator. The harness lives under `freud/`, with the CLI entrypoint in `freud/cli/`, shared Go packages in `freud/internal/`, and the built local binary at `./freud/bin/freud`.

Normal deterministic workflow:

```bash
./freud/bin/freud run my-change
```

This executes five phases in order:

| Phase | What it does |
|---|---|
| `preflight_compile` | Fast build check — catches compilation errors before running tests. |
| `targeted_tests` | Focused agent test subset for quicker failure signal during iteration. |
| `full_tests` | Full Gradle test suite. |
| `scenario_pack` | Deterministic agent behavior scenarios that exercise the cognitive loop end-to-end. |
| `reasoning_eval_logic` | Deterministic logic gate tests — no external LLM calls. |

Signoff gate:

```bash
./freud/bin/freud run signoff-gate
```

This trims the deterministic gate to the non-redundant final signoff gate:

| Phase | What it does |
|---|---|
| `preflight_compile` | Fast build check. |
| `full_tests` | Full Gradle test suite. |
| `scenario_pack` | Deterministic cognitive-loop scenarios. |
| `reasoning_eval_logic` | Deterministic logic gate tests. |

That `signoff-gate` command is the required non-live signoff gate.

### Cognitive runtime phase gates

The cognitive-runtime completion work uses named deterministic Freud gates so each phase can be validated against a narrower acceptance seam before the full signoff gate:

```bash
./freud/bin/freud run cognitive-runtime-p0-tests
./freud/bin/freud run cognitive-runtime-p1-foundation
./freud/bin/freud run cognitive-runtime-p2-opportunities
./freud/bin/freud run cognitive-runtime-p3-intentions
./freud/bin/freud run cognitive-runtime-p4-feedback
./freud/bin/freud run cognitive-runtime-p5-assignments-scratchpad
./freud/bin/freud run cognitive-runtime-p6-policy-control
./freud/bin/freud run cognitive-runtime-p7-convergence
```

Current deterministic gate shape:

| Gate | Steps |
|---|---|
| `cognitive-runtime-p0-tests` | `preflight_compile`, `targeted_tests` |
| `cognitive-runtime-p1-*` through `cognitive-runtime-p7-*` | `preflight_compile`, `full_tests`, `scenario_pack`, `reasoning_eval_logic` |

These phase gates do not replace the final `signoff-gate`; they exist to make the architecture-completion loop enforceable phase by phase.

### 3. Scenario pack (`freud/scenarios/v1/`)

A JSON-based pack of deterministic scenarios that test specific agent behaviors:

- Does the planner produce the correct action type for a given input?
- Does the Superego correctly deny a disallowed action?
- Does the assignment system handle lifecycle transitions correctly?
- Does the feedback loop work when actions are denied?

Scenarios are evaluated deterministically using recorded LLM responses, not live API calls.

### 4. Reasoning eval — logic mode

Deterministic tests that verify structural properties of planner output without making LLM calls:

- **shape-lock** — Does the planner output conform to the expected JSON schema?
- **feedback-carry** — Does the planner correctly incorporate denial feedback into the next queued continuation?
- **multi-fix** — Can the planner recover from multiple consecutive failures?

The logic eval also includes a 45-case behavioral/perturbation pack that tests edge cases and boundary conditions.

```bash
# Run specific logic tasks
./run-neopsyke --eval-reasoning-only --eval-reasoning-tasks shape-lock,multi-fix

# Run all logic tasks
./run-neopsyke --eval-reasoning-only --eval-reasoning-mode logic
```

### 5. Reasoning eval — model mode

Live LLM-backed reasoning tests using a frozen BBH-style smoke slice (24 cases). These require API keys and make real provider calls.

```bash
# Live reasoning eval with real LLM calls
./freud/bin/freud bbh --live --lane low-llm
./freud/bin/freud bbh --live --lane high-llm
```

This mode is for manual validation during development, not for CI.

### 6. Live reasoning lanes

More comprehensive live evaluation using the Freud BBH smoke harness:

```bash
# Lower-cost live lane
./freud/bin/freud bbh --live --lane low-llm

# Production-routing live lane
./freud/bin/freud bbh --live --lane high-llm

# Full orchestrated run: deterministic + live
./freud/bin/freud run my-change --live --lane low-llm
```

Live lanes disable long-term memory and episodic logbook recall by default so they measure reasoning quality, not memory effects.

### 7. Memory live eval

Tests the real memory pipeline end-to-end: LLM memory advisor → Hippocampus imprint → vector recall. Requires the pgvector backend running.

```bash
./run-neopsyke --eval-memory-live

# Run specific memory tasks
./run-neopsyke --eval-memory-live --eval-memory-tasks user-preference-color,assignment-constraint-timezone
```

### 8. Freud live eval (single-input)

For testing individual inputs against the real agent:

```bash
./freud/bin/freud eval --live --input input.txt
./freud/bin/freud eval --live --input input.txt --expected expected.txt --timeout 120
```

Provider-backed Freud evals require `--live`. Ordinary live evals still write
the usual logs and artifacts, but replay material is only generated when you
also pass `--record`.

For the cognitive-runtime completion run, phase closure also requires recorded live validation:

```bash
./freud/bin/freud eval --live --record --lane low-llm --input freud/evals/cognitive-runtime/phase-1-thread-foundation.txt --timeout 120
./freud/bin/freud bbh --live --lane low-llm --record
```

When a recorded live eval fails:

```bash
./freud/bin/freud eval --live --session-replay <run-dir>
./freud/bin/freud eval --live --input <same-input> --cache-replay <run-dir>/artifacts/llm-cache.jsonl
```

`eval --session-replay <run-dir>` auto-detects replay cache from either `session/llm-cache.jsonl` or `artifacts/llm-cache.jsonl`, so recorded runs remain replayable even when the cache file lives under artifacts. Use session replay to reproduce the full run-path deterministically and cache replay to isolate prompt/LLM divergence at the first changed call.

---

## LLM response cache (record / replay)

Live evals cost tokens. Every cognitive role in the loop (Planner, Superego, Meta-Reasoner, Action Verifier, Memory Advisor) makes at least one LLM call per cycle, and a single input can trigger multiple cycles. For iterative development this adds up fast.

The LLM response cache solves this: record one live run, then replay it as many times as needed with zero API calls — until your code changes actually affect what the agent sends to the LLM.

### How it works

1. **Record mode** — Every LLM response is captured to a JSONL file, one entry per call. Each entry stores a sequence index, a SHA-256 hash of the request messages, the model name, the full response content, finish reason, and token usage.

2. **Replay mode** — The cache file is loaded at startup. On each LLM call, the system compares the current request's message hash against the cached entry at the same sequence index:
   - **Hash matches** → the cached response is returned instantly, no API call.
   - **Hash mismatch (divergence)** → the cache switches to **passthrough mode** and all subsequent calls go to the real LLM. The divergence point (sequence index, actor, call site) is logged as a telemetry event.
   - **Index exhausted** → same as mismatch; passthrough kicks in.

3. **Passthrough is sticky** — once divergence is detected, the rest of the run uses real LLM calls. There is no attempt to re-sync with the cache. This is deliberate: partial cache hits with silent mismatches would produce misleading results.

### Quick start

```bash
# 1. Record a baseline run (makes real LLM calls, saves responses)
./freud/bin/freud eval --live --record --input input.txt --expected expected.txt --timeout 120

# 2. Find the cache file from that run
CACHE=$(tail -1 .neopsyke/runs/freud/run-index.tsv | cut -f3)/artifacts/llm-cache.jsonl

# 3. Replay — zero API calls if nothing changed
./freud/bin/freud eval --live --input input.txt --expected expected.txt --cache-replay "$CACHE"
```

### When to use replay

| Scenario | What to do |
|---|---|
| Iterating on Superego policies | Record once, replay while tuning policy rules. Divergence only happens if your policy change alters what the Planner sees next. |
| Refactoring prompt assembly | Replay detects immediately whether your refactor changed the actual messages sent to the LLM. No divergence = safe refactor. |
| Tuning scoring or post-processing | Replay is free here — scoring happens after LLM calls, so the cache always hits 100%. |
| Debugging a specific agent behavior | Replay the exact same LLM responses to reproduce the behavior deterministically. |
| CI-like regression checks | Record a set of baseline caches. Replay them after code changes. Divergence = something changed that affects LLM interaction. |

### What a cache file looks like

Each line is a JSON object:

```json
{"seq":0,"hash":"a3f8...","actor":"planner","call_site":"PlannerPromptRunner","model":"claude-sonnet-4-20250514","content":"...","finish_reason":"end_turn","prompt_tokens":1842,"completion_tokens":312,"total_tokens":2154}
{"seq":1,"hash":"e7b2...","actor":"superego","call_site":"SuperegoReviewer","model":"claude-sonnet-4-20250514","content":"...","finish_reason":"end_turn","prompt_tokens":956,"completion_tokens":87,"total_tokens":1043}
```

The `seq` field is the global call order across all cognitive roles. The `hash` is what replay uses to detect divergence.

### Cache telemetry

After a replay run, inspect the cache performance:

LLM cache telemetry is computed automatically during `freud eval` runs and written to `artifacts/llm-cache-stats.json` in the run directory. The dashboard snapshot (`/api/obs/snapshot`) also exposes live LLM call stats.

Example output:

```json
{
  "total_calls": 4,
  "cached_calls": 4,
  "real_calls": 0,
  "hit_rate_percent": 100.0,
  "divergence_count": 0,
  "divergence_point": null,
  "hits_by_actor": {
    "planner": 1,
    "superego": 1,
    "meta_reasoner": 1
  },
  "hints": ["All calls served from cache: fully deterministic replay."]
}
```

A run with divergence looks like:

```json
{
  "total_calls": 6,
  "cached_calls": 2,
  "real_calls": 4,
  "hit_rate_percent": 33.33,
  "divergence_count": 1,
  "divergence_point": 2,
  "divergence_actor": "superego",
  "divergence_call_site": "SuperegoReviewer",
  "hints": ["Divergence at seq 2 (superego/SuperegoReviewer): code change may have affected this call path."]
}
```

This tells you exactly which cognitive role and call site were first affected by your code change.

### Environment variables

| Variable | Values | Description |
|---|---|---|
| `NEOPSYKE_LLM_CACHE_MODE` | `record`, `replay`, `off` | Controls caching behavior. Default: `off`. |
| `NEOPSYKE_LLM_CACHE_FILE` | path | JSONL file to write (record) or read (replay). |

You rarely need to set these directly — `./freud/bin/freud eval` handles them via the `--cache-replay` flag. But they are available for custom scripts or manual runs.

### Manual usage (without `freud eval`)

```bash
# Record
NEOPSYKE_LLM_CACHE_MODE=record \
NEOPSYKE_LLM_CACHE_FILE=my-cache.jsonl \
./run-neopsyke

# Replay
NEOPSYKE_LLM_CACHE_MODE=replay \
NEOPSYKE_LLM_CACHE_FILE=my-cache.jsonl \
./run-neopsyke
```

### Token savings in practice

A typical single-input eval with the full cognitive loop (Planner → Superego → Action Verifier → Meta-Reasoner) uses 4–8 LLM calls per cycle. With multi-cycle inputs or assignment execution, a single run can make 10–20+ calls. At replay, all of those calls are served from cache until divergence.

During iterative development — where you might run the same eval 10–20 times while tuning a feature — replay eliminates the cost of all runs after the first recording, as long as your changes do not affect the messages sent to the LLM. Changes to scoring logic, telemetry, dashboard rendering, prompt budget allocation, or post-processing are completely free to test.

---

## Eval output and artifacts

| Artifact | Location |
|---|---|
| Reasoning eval runs | `.neopsyke/evals/reasoning/runs/` |
| Reasoning eval history | `.neopsyke/evals/reasoning/history.jsonl` |
| Memory eval runs | `.neopsyke/evals/memory-live/runs/` |
| Memory eval history | `.neopsyke/evals/memory-live/history.jsonl` |
| Run logs | `.neopsyke/logs/runs/` |
| Event sidecar | `.neopsyke/logs/latest-events.jsonl` |
| Freud artifacts | `.neopsyke/runs/freud/<timestamp>-<feature-id>/` |

---

## Telemetry and diagnostics

NeoPsyke emits structured instrumentation events to a per-run sidecar JSONL file. These events are the primary data source for understanding agent behavior beyond pass/fail eval results.

### Grounding gate telemetry

The `grounding_gate_review` event captures how the grounding gate evaluates candidate `contact_user` actions:

- `grounding_required`
- `evidence_gathered`
- `evidence_failed_technically`
- `evidence_unavailable`
- `forced_terminal`
- `reason_code` (`GROUNDING_EVIDENCE_REQUIRED`, `TECH_GROUNDING_EVIDENCE_FAILURE`, `GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL`)

Aggregate from the event sidecar:

Grounding gate telemetry is computed automatically during `freud eval` runs. The dashboard snapshot (`/api/obs/snapshot`) also exposes aggregated `groundingGateStats` counters and rates.

### Prompt budget telemetry

The `prompt_budget_allocation` event is emitted when prompts are assembled for each cognitive role (`planner_prompt`, `superego_prompt`, `meta_reasoner_prompt`). The event payload includes:

- Budget and cost estimates (`max_tokens`, `estimated_total_cost`, `allocated_total_cost`, `reserved_floor_cost`)
- Degradation path (what was dropped to fit the budget)
- Fallback/floor pressure (`single_message_fallback`, `floor_violation_count`, `dropped_section_count`)
- Per-band rollup (`bands.required_core|required_context|optional`)

Aggregate from the event sidecar:

Prompt budget telemetry is computed automatically during `freud eval` runs. The dashboard snapshot exposes aggregated `promptBudgetStats`.

The dashboard snapshot exposes aggregated `promptBudgetStats`.

For detailed tuning workflows, see `PROMPT_BUDGET_TUNING_GUIDE.md` and `PROMPT_BUDGET_RUN_DIAGNOSTICS.md`.

### Live dashboard observability

The dashboard (`/dashboard`) provides live observability during interactive runs: deferred-intention chains, thread/opportunity/intention state, LLM call details, Superego decisions, memory operations, queue states, and scratchpad snapshots. All events are streamed as typed SSE events.

---

## Current limitations

The eval infrastructure today is useful but incomplete. Major gaps include:

- **No systematic behavioral regression suite.** The scenario pack covers specific cases, but there is no broad test of "does the agent still behave reasonably across a diverse set of tasks?"
- **No adversarial security eval.** Prompt injection defense is tested with deterministic checks, but there is no red-team harness that systematically probes the agent with adversarial inputs.
- **No multi-turn conversation eval.** Current evals are single-input. Multi-turn coherence, context management, and memory interaction across turns are not formally tested.
- **No assignment lifecycle eval.** Assignment creation, execution, blocking, resumption, and recurring activation are tested in unit tests but not in end-to-end evals with real LLM reasoning.
- **No Id feedback loop eval.** The closed loop between motivation, governance, and drive satisfaction is not formally measured.
- **No cost/efficiency benchmarking.** Token usage per task type, provider cost per cognitive role, and total cost per interaction are tracked in metrics but not evaluated against baselines.
- **Memory advisor quality is unmeasured.** The advisor's consolidation decisions (what to save, what to skip) are not evaluated against ground truth.
- **No cross-model comparison framework.** Swapping models in cognitive roles changes behavior, but there is no systematic way to compare configurations.

---

## Directions for contributors

The following are high-impact areas where evaluation work would significantly improve the project:

### Behavioral regression suite

Build a diverse set of 50–100 representative tasks (factual questions, multi-step reasoning, web research, summarization, creative writing) with expected behavior baselines. Run them with LLM caching so results are reproducible. Track pass/fail rates across changes.

### Adversarial prompt injection testing

Create a red-team harness that:
- Injects instructions into web search results and fetched pages.
- Tests whether the Superego correctly denies actions influenced by injected content.
- Tests whether provenance tracking prevents internal drive impersonation.
- Measures false positive and false negative rates.

### Multi-turn conversation coherence

Design conversation sequences that test:
- Context retention across turns.
- Memory compaction quality (does the summary preserve the right information?).
- Long-term memory recall relevance.
- Episodic recall usefulness.

### Assignment lifecycle integration tests

End-to-end tests that create assignments via natural language, verify plan generation, execute steps, handle blocking conditions, and verify completion. Include recurring assignments and timer-based assignments.

### Id–Ego–Superego interaction evals

Measure the closed motivation-governance loop:
- Does the drive reset when an impulse-driven action succeeds?
- Does pressure continue to build when actions are denied?
- Does backoff work correctly after repeated denials?
- Does the system recover when backoff expires?

### Cost and efficiency benchmarking

Build a standard task set and measure:
- Total tokens per task.
- Token distribution across cognitive roles.
- Cost per task at different model configurations.
- Quality vs. cost trade-offs for different provider assignments.

### Memory advisor evaluation

Create ground-truth datasets for:
- "This information should be consolidated" (true positives).
- "This is ephemeral and should not be saved" (true negatives).
- Measure precision and recall of the advisor's recommendations.

### Configuration sensitivity analysis

Systematically vary key tuning knobs (`max_loop_steps`, `pressure_threshold`, `assess_every_steps`, etc.) and measure their effect on:
- Answer quality.
- Token usage.
- Convergence speed.
- False denial rates.

This would produce a tuning guide grounded in data rather than intuition.

---

## Running evals in CI

GitHub pull requests run only the fast non-live path:

```bash
./freud/bin/freud run signoff-gate
```

This covers the deterministic signoff gate only. Live reasoning lanes remain manual-only to avoid API key requirements and cost in CI.
