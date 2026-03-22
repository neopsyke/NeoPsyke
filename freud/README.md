# Freud

`Freud` is NeoPsyke's developer workflow layer. It gives you a small set of
repeatable commands, isolated artifacts, and faster failure triage for both
deterministic checks and optional live/provider-backed evals.

## Which Command Do I Use?

| If you want to... | Run this |
|---|---|
| Validate a code change with the normal deterministic workflow | `freud/scripts/feature-loop.sh <feature-id>` |
| Run the full deterministic signoff gate before considering work complete | `freud/scripts/feature-loop.sh ci-pr` |
| Run one live prompt through the real agent | `freud/scripts/live-eval.sh --input <file>` |
| Run the advanced live reasoning suite | `freud/scripts/run-bbh-smoke.sh --lane weak-structure` or `freud/scripts/run-bbh-smoke.sh --lane prod-acceptance` |
| Orchestrate deterministic checks plus the live suite in one run | `freud/scripts/feature-loop.sh <feature-id> --live --config freud/config/live-weak-structure.env` or `...live-prod-acceptance.env` |
| Run the deterministic scenario pack only | `freud/scripts/run-scenarios.sh --file freud/scenarios/v1/neopsyke-agent-scenarios.json` |

`feature-loop.sh --live` is an orchestrator over the other live commands. It
is not a separate live system. The direct live entrypoints are:

- `freud/scripts/live-eval.sh --input ...`
- `freud/scripts/run-bbh-smoke.sh --lane ...`

## Minimum Setup

Deterministic Freud usage:

- JDK 21+
- `./gradlew` works in this repo
- no provider API keys required

Live Freud usage:

- all of the above
- provider API keys and routing config required for the lane you want to run
- live commands may spend tokens and money

By default, `freud/config/default.env` leaves live steps blank on purpose.
Live commands are enabled by choosing a live config such as:

- `freud/config/live-weak-structure.env`
- `freud/config/live-prod-acceptance.env`

## Common Workflows

### I Changed Code, What Do I Run?

Start here:

```bash
freud/scripts/feature-loop.sh my-change
```

This is the primary deterministic command. It runs the normal developer loop:

- preflight compile
- targeted tests
- full tests
- scenario pack
- deterministic reasoning gate

Before considering the work fully validated, run the deterministic signoff
gate:

```bash
freud/scripts/feature-loop.sh ci-pr
```

That is the command that should be treated as the default completion gate for
non-live validation. `./gradlew test` alone is not enough because it does not
exercise the Freud deterministic scenario pack or deterministic reasoning evals.
`--dry-run` does not count. No commit and no "fully validated" claim should
happen until non-dry `freud/scripts/feature-loop.sh ci-pr` passes.

The expected deterministic gate order is:

1. `preflight_compile`
2. `targeted_tests`
3. `full_tests`
4. `scenario_pack`
5. `reasoning_eval_logic`

### I Want Deterministic Validation Only

Use the same command:

```bash
freud/scripts/feature-loop.sh my-change
```

Narrower options:

```bash
freud/scripts/run-reasoning-pr-gate.sh
freud/scripts/run-scenarios.sh --file freud/scenarios/v1/neopsyke-agent-scenarios.json
freud/scripts/feature-loop.sh my-change --from-step scenario_pack
```

These narrower commands are for iteration speed. They are not the default
signoff gate.

### I Want One Live Smoke

Use the single-input entrypoint:

```bash
freud/scripts/live-eval.sh --input input.txt
freud/scripts/live-eval.sh --input input.txt --expected expected.txt
freud/scripts/live-eval.sh --input input.txt --cache-replay .neopsyke/runs/freud/<run>/artifacts/llm-cache.jsonl
```

This is the primary live command for direct agent checks.

### I Want The Full Live Reasoning Lane

Run the live suite directly:

```bash
freud/scripts/run-bbh-smoke.sh --lane weak-structure
freud/scripts/run-bbh-smoke.sh --lane prod-acceptance
```

Use these when you want the frozen live reasoning matrix, not just one prompt.

If you want one orchestrated run that includes the live suite after the
deterministic checks:

```bash
freud/scripts/feature-loop.sh my-change --live --config freud/config/live-weak-structure.env
freud/scripts/feature-loop.sh my-change --live --config freud/config/live-prod-acceptance.env
```

### My Run Failed, Where Do I Look First?

For any feature-loop run:

1. `artifacts/summary.json`
2. `artifacts/summary-compact.md`
3. `artifacts/trail-index.tsv`
4. `artifacts/step-index.tsv`
5. `artifacts/step-meta/<step>.json`
6. only then `logs/<step>.log`

For one-shot live evals:

1. `artifacts/verdict.json`
2. `artifacts/summary-compact.md`
3. `logs/stderr.log`
4. `logs/neopsyke.log`

For BBH smoke lanes:

1. `artifacts/bbh-smoke-<lane>-summary.json`
2. `artifacts/bbh-smoke-<lane>-progress.json`
3. `artifacts/bbh-smoke-<lane>-results.tsv`
4. failing case directories under `bbh-cases/<lane>/`

### What Artifacts Matter Most?

Read these first:

- `artifacts/summary.json`
- `artifacts/summary-compact.md`
- `artifacts/trail-index.tsv`
- `artifacts/step-index.tsv`
- `artifacts/anomalies.md`

Live-specific:

- one-shot: `artifacts/verdict.json`, `artifacts/answer.txt`
- BBH suite: `artifacts/bbh-smoke-<lane>-summary.json`, `artifacts/bbh-smoke-<lane>-results.tsv`

For tools and scripts, prefer `.neopsyke/runs/freud/latest-run.txt` as the
convenience pointer to the newest Freud run. Timestamped run directories remain
the source of truth.

## Concurrency

Concurrent Freud runs are mostly safe now:

- `feature-loop.sh` runs can overlap
- `live-eval.sh` runs can overlap
- `run-bbh-smoke.sh` runs can overlap
- mixing those command families is fine

Why:

- each run gets its own timestamped run directory
- the shared `latest` pointers are now best-effort convenience pointers, not a hard dependency for execution

What is still shared:

- `.neopsyke/runs/freud/latest`
- `.neopsyke/runs/freud/latest-run.txt`
- `freud/latest`
- `freud/latest-run.txt`

That means the last writer wins. During concurrent runs, do not treat those
pointers as stable ownership markers for one specific run. Prefer:

- the explicit `run_dir` printed by the command
- the timestamped run directory itself
- a dedicated `FREUD_RUN_ROOT` when a tool needs strict isolation

### Important Memory Caveat

Concurrent memory-dependent live runs are still not safe.

By default, Freud live evals share these isolated-but-global memory resources:

- pgvector namespace: `freud-eval`
- episodic logbook: `.neopsyke/freud-logbook.db`
- metrics DB: `.neopsyke/freud-metrics.db`

So if two live runs use long-term memory at the same time, they can interfere
with each other by:

- clearing the same isolated memory state
- contaminating recall with another run's imprints
- mixing episodic/logbook state across runs

Practical rule:

- concurrent deterministic runs are fine
- concurrent live runs are fine when memory is off or irrelevant
- concurrent memory-dependent live runs are not safe and can contaminate each other

This is one reason the BBH live suite disables long-term MCP memory and
episodic logbook recall by default.

## Live Runs And Cost

Live commands are manual on purpose.

- `live-eval.sh` runs a single real agent turn
- `run-bbh-smoke.sh` runs a frozen multi-case live suite
- `feature-loop.sh --live` just orchestrates those live steps after the deterministic ones

Cost guidance:

- deterministic runs are the default and should be your first pass
- one-shot live eval is the cheapest live check
- BBH smoke is an advanced live suite and costs more
- live routing depends on your configured models/providers

Memory behavior:

- one-shot `live-eval.sh` uses isolated Freud memory by default
- BBH smoke disables long-term MCP memory and episodic logbook recall by default so the suite measures reasoning rather than memory side-effects

## Common Failure Modes

Freud tries to classify failures clearly. The main classes to expect are:

- `app/test failure`: compile/test/assertion failures in deterministic steps
- `stale selector`: scenario manifest points at a test selector that no longer exists
- `local bootstrap/runtime failure`: Gradle startup, filesystem, sandbox, or local process bootstrapping failed before the eval really ran
- `provider/model failure`: auth, routing, quota, rate limit, or upstream model/provider failure
- `live eval scoring failure`: the live run completed but the answer missed the expected score or exact match

If a BBH lane reports a runtime/bootstrap or provider/model failure, treat that
as an infrastructure/run issue first, not a reasoning regression.

## Memory Isolation

Freud live eval runs do not touch the user's default memory state.

| Resource | Freud live eval | User default |
|---|---|---|
| pgvector namespace | `freud-eval` | `neopsyke` |
| Episodic logbook | `.neopsyke/freud-logbook.db` | `.neopsyke/logbook.db` |
| Metrics DB | `.neopsyke/freud-metrics.db` | `.neopsyke/metrics.db` |

Defaults:

- `--clear-memory-all` is applied automatically
- use `--preserve-memory` only when a sequence intentionally depends on prior isolated Freud memory
- `--expected` uses normalized exact matching, not substring containment
- BBH smoke additionally disables long-term MCP memory and episodic logbook recall by default

## Configuration

`feature-loop.sh` reads config from:

1. `FREUD_CONFIG`, if set
2. `freud/config/default.env`

Important overrides:

- `FREUD_TARGETED_TEST_CMD`
- `FREUD_FULL_TEST_CMD`
- `FREUD_SCENARIO_PACK_CMD`
- `FREUD_REASONING_EVAL_LOGIC_CMD`
- `FREUD_REASONING_EVAL_MODEL_CMD`
- `FREUD_MEMORY_SMOKE_CMD`
- `FREUD_LIVE_EVAL_TIMEOUT`
- `FREUD_LIVE_EVAL_PRESERVE_MEMORY`
- `FREUD_BBH_MIN_PASS_RATE_PERCENT`
- `FREUD_BBH_MAX_TIMEOUTS`
- `FREUD_BBH_PRESERVE_MEMORY`
- `FREUD_BBH_MCP_MEMORY_ENABLED`
- `FREUD_BBH_LOGBOOK_ENABLED`
- `FREUD_RUN_ROOT`
- `FREUD_GRADLE_USER_HOME`

Project-specific command wiring belongs in `freud/config/*.env`, not inside the
generic scripts.

## Layout

- `freud/scripts/feature-loop.sh`: deterministic-first orchestrator
- `freud/scripts/live-eval.sh`: primary one-shot live entrypoint
- `freud/scripts/run-bbh-smoke.sh`: advanced live reasoning suite
- `freud/scripts/run-scenarios.sh`: deterministic scenario runner
- `freud/scripts/run-reasoning-pr-gate.sh`: deterministic reasoning gate
- `freud/py/`: Python data-processing helpers
- `freud/tests/`: BATS and pytest coverage for the workflow tooling

## Testing The Workflow Itself

### BATS

```bash
brew install bats-core
bats freud/tests/
```

### pytest

```bash
python3 -m venv freud/.venv
freud/.venv/bin/pip install pytest
PYTHONPATH=. freud/.venv/bin/pytest freud/tests/test_*_py.py freud/tests/test_common.py -v
```

## Design Rules

- deterministic checks first
- live/provider checks explicit and optional
- a small number of clear entrypoints beats many overlapping wrappers
- `live-eval.sh` is the primary live entrypoint
- `run-bbh-smoke.sh` is the advanced live suite
- `feature-loop.sh --live` orchestrates the above, it does not replace them
