# Freud

`Freud` is NeoPsyke's developer workflow layer. It gives you a single CLI with
repeatable commands, isolated artifacts, and faster failure triage for both
deterministic checks and optional live/provider-backed evals.

## Quick Start

```bash
# Build the CLI (one time)
cd freud/cli && go build -o freud .

# Run the deterministic pipeline
./freud run my-change

# See all commands
./freud --help
```

## Which Command Do I Use?

| If you want to... | Run this |
|---|---|
| Validate a code change with the normal deterministic workflow | `./freud run <feature-id>` |
| Run the full deterministic signoff gate before considering work complete | `./freud run ci-pr` |
| Run one live prompt through the real agent | `./freud eval --input <file>` |
| Run the advanced live reasoning suite | `./freud bbh --lane low-llm` or `./freud bbh --lane high-llm` |
| Orchestrate deterministic checks plus the live suite in one run | `./freud run <feature-id> --live --lane low-llm` |
| Run only a specific pipeline step | `./freud run <feature-id> --only scenario_pack` |
| Triage a failed run | `./freud triage` |
| Generate a compact summary | `./freud summarize` |
| Package a run for LLM analysis | `./freud context-pack` |
| Inspect what would run without executing | `./freud run <feature-id> --dry-run` |

## Minimum Setup

Deterministic Freud usage:

- Go 1.21+ (for building the CLI)
- JDK 21+
- `./gradlew` works in this repo
- no provider API keys required

Live Freud usage:

- all of the above
- provider API keys and routing config required for the lane you want to run
- live commands may spend tokens and money

By default, `freud/config/freud.yaml` leaves live steps blank on purpose.
Live commands are enabled by choosing a lane profile:

- `--lane low-llm` (low-cost LLM routing)
- `--lane high-llm` (production LLM routing)

## Common Workflows

### I Changed Code, What Do I Run?

Start here:

```bash
./freud run my-change
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
./freud run ci-pr
```

That is the command that should be treated as the default completion gate for
non-live validation. `./gradlew test` alone is not enough because it does not
exercise the Freud deterministic scenario pack or deterministic reasoning evals.
`--dry-run` does not count. No commit and no "fully validated" claim should
happen until non-dry `./freud run ci-pr` passes.

The expected deterministic gate order is:

1. `preflight_compile`
2. `targeted_tests`
3. `full_tests`
4. `scenario_pack`
5. `reasoning_eval_logic`

### I Want Deterministic Validation Only

Use the same command:

```bash
./freud run my-change
```

Narrower options:

```bash
./freud run my-change --only scenario_pack
./freud run my-change --from-step scenario_pack
./freud run my-change --skip preflight_compile,targeted_tests
```

These narrower commands are for iteration speed. They are not the default
signoff gate.

### I Want One Live Smoke

Use the single-input entrypoint:

```bash
./freud eval --input input.txt
./freud eval --input input.txt --expected expected.txt
```

This is the primary live command for direct agent checks.

### I Want To Replay A Previous Run (No API Calls)

Every live eval automatically records all LLM responses. Replay them to re-run the eval for free:

```bash
# Find the cache from your last run
CACHE=$(cat .neopsyke/runs/freud/latest-run.txt)/artifacts/llm-cache.jsonl

# Replay — zero tokens, same result
./freud eval --input input.txt --cache-replay "$CACHE"
```

Replay serves cached responses as long as the messages sent to the LLM haven't changed. When they diverge (because your code changed what the agent sends), it switches to real API calls from that point forward and logs exactly where divergence happened.

This is the recommended workflow for iterative development: record once, then replay while tuning policies, scoring, post-processing, or telemetry. See [LLM response cache](../docs/evaluation.md#llm-response-cache-record--replay) for the full reference.

### I Want The Full Live Reasoning Lane

Run the live suite directly:

```bash
./freud bbh --lane low-llm
./freud bbh --lane high-llm
```

Use these when you want the frozen live reasoning matrix, not just one prompt.

If you want one orchestrated run that includes the live suite after the
deterministic checks:

```bash
./freud run my-change --live --lane low-llm
./freud run my-change --live --lane high-llm
```

### My Run Failed, Where Do I Look First?

For any pipeline run:

1. `artifacts/summary.json`
2. `artifacts/summary-compact.md`
3. `artifacts/trail-index.tsv`
4. `artifacts/step-index.tsv`
5. `artifacts/step-meta/<step>.json`
6. only then `logs/<step>.log`

Or use the built-in triage:

```bash
./freud triage           # anomaly detection on latest run
./freud summarize        # compact summary of latest run
./freud context-pack     # package for LLM analysis
```

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

Concurrent Freud runs are only partly safe.

Not safe in the same checkout/worktree:

- `./freud run ...` (invokes Gradle)
- raw `./gradlew ...` runs

Reason:

- those commands invoke Gradle/Kotlin compilation or tests against the same checkout
- overlapping Gradle-backed runs can collide on shared `build/` outputs and Kotlin/Gradle daemon state
- if parallel validation is needed, use separate git worktrees or separate clones

Conditionally safe in the same checkout:

- `./freud eval` runs can overlap
- `./freud bbh` runs can overlap
- mixing those live command families is fine only when they are not overlapping with any Gradle-backed command

Always safe:

- `./freud triage`, `./freud summarize`, `./freud context-pack` (read-only analysis)
- `--dry-run` inspection commands

Why the live-only case can work:

- each run gets its own timestamped run directory
- the shared `latest` pointers are now best-effort convenience pointers, not a hard dependency for execution

What is still shared:

- `.neopsyke/runs/freud/latest`
- `.neopsyke/runs/freud/latest-run.txt`

That means the last writer wins. During concurrent runs, do not treat those
pointers as stable ownership markers for one specific run. Prefer:

- the explicit `run_dir` printed by the command
- the timestamped run directory itself
- `-o project.run_root=<path>` when a tool needs strict isolation

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

- concurrent deterministic/Gradle-backed runs are not fine in one checkout
- concurrent live runs are fine when memory is off or irrelevant
- concurrent memory-dependent live runs are not safe and can contaminate each other

This is one reason the BBH live suite disables long-term vector memory and
episodic logbook recall by default.

## Live Runs And Cost

Live commands are manual on purpose.

- `./freud eval` runs a single real agent turn
- `./freud bbh` runs a frozen multi-case live suite
- `./freud run --live` just orchestrates those live steps after the deterministic ones

Cost guidance:

- deterministic runs are the default and should be your first pass
- one-shot live eval is the cheapest live check
- **use `--cache-replay` to re-run live evals without API calls** — record once, iterate for free
- BBH smoke is an advanced live suite and costs more
- live routing depends on your configured models/providers

Memory behavior:

- one-shot `./freud eval` uses isolated Freud memory by default
- BBH smoke disables long-term vector memory and episodic logbook recall by default so the suite measures reasoning rather than memory side-effects

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
- BBH smoke additionally disables long-term vector memory and episodic logbook recall by default

## Configuration

Freud uses a single YAML config file with optional lane profile overlays.

Config resolution order:

1. `--config` CLI flag, if set
2. `FREUD_CONFIG` env var, if set
3. `freud/config/freud.yaml` (default)

Precedence: CLI flag (`-o key=val`) > env var > profile overlay (`--lane`) > YAML config > built-in defaults.

The config file (`freud/config/freud.yaml`) contains:

- **project**: name, run_root, retention_days, gradle_home
- **pipeline**: ordered list of steps with names, commands, and live_only flags
- **live_eval**: timeout, preserve_memory, goals_enabled, llm_config_file
- **bbh**: prompts_file, answers_file, min_pass_rate, max_timeouts
- **runtime**: continue_on_fail, scratchpad_debug, id_enabled
- **telemetry**: enabled

Lane profiles (`freud/config/profiles/low-llm.yaml`, `high-llm.yaml`) override specific config keys for live evaluation:

```bash
# Use low-cost LLM routing
./freud run my-change --live --lane low-llm

# Override a single config key for this run
./freud run my-change -o live_eval.timeout=60

# Use a completely custom config
./freud run my-change -c /path/to/custom.yaml
```

Secrets (`MISTRAL_API_KEY` etc.) are never in config files — they pass through from the host environment.

## Layout

- `freud/cli/`: Go CLI source (Cobra + Viper)
  - `cmd/`: subcommand implementations (run, eval, bbh, test-replay-eval, triage, summarize, context-pack)
  - `config/`: config loading, schema, validation, env var mapping
  - `dispatch/`: subprocess helpers for delegating to shell scripts
  - `analysis/`: native Go analysis modules (ported from Python)
- `freud/config/freud.yaml`: default YAML configuration
- `freud/config/profiles/`: lane profile overlays (low-llm.yaml, high-llm.yaml)
- `freud/scripts/`: shell scripts (delegated to by CLI in Phase 1)
- `freud/py/`: legacy Python data-processing helpers (superseded by Go analysis modules)
- `freud/tests/`: BATS and pytest coverage for the shell scripts and Python modules

## Testing The CLI Itself

```bash
cd freud/cli && go test ./... -v
```

## Design Rules

- deterministic checks first
- live/provider checks explicit and optional
- one CLI, one config file, clear subcommands
- `./freud eval` is the primary live entrypoint
- `./freud bbh` is the advanced live suite
- `./freud run --live` orchestrates the above, it does not replace them
