# Freud

`Freud` is NeoPsyke's developer workflow layer. It gives you a single CLI with
repeatable commands, isolated artifacts, and faster failure triage for both
deterministic checks and optional live/provider-backed evals.

## Quick Start

```bash
# Build the CLI as ./freud/bin/freud (one time, from repo root)
./freud/bootstrap.sh

# Run the deterministic pipeline
./freud/bin/freud run my-change

# See all commands
./freud/bin/freud --help
```

`bootstrap.sh` creates `freud/bin/`, builds the CLI, and prints the next
commands to run. The preferred local binary path is `./freud/bin/freud`.

## Which Command Do I Use?

| If you want to... | Run this |
|---|---|
| Validate a code change with the normal deterministic workflow | `./freud/bin/freud run <feature-id>` |
| Run the full deterministic signoff gate before considering work complete | `./freud/bin/freud run ci-pr` |
| Run one live prompt through the real agent | `./freud/bin/freud eval --input <file>` |
| Run the advanced live reasoning suite | `./freud/bin/freud bbh --lane low-llm` or `./freud/bin/freud bbh --lane high-llm` |
| Orchestrate deterministic checks plus the live suite in one run | `./freud/bin/freud run <feature-id> --live --lane low-llm` |
| Run only a specific pipeline step | `./freud/bin/freud run <feature-id> --only scenario_pack` |
| Replay a recorded session | `./freud/bin/freud eval --session-replay <run-dir>` |
| Test session replay determinism (E2E) | `./freud/bin/freud test-replay-eval` |
| Test interactive session replay (E2E) | `./freud/bin/freud test-replay-interactive` |
| Triage a failed run | `./freud/bin/freud triage` |
| Generate a compact summary | `./freud/bin/freud summarize` |
| Package a run for LLM analysis | `./freud/bin/freud context-pack` |
| Inspect what would run without executing | `./freud/bin/freud run <feature-id> --dry-run` |

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
./freud/bin/freud run my-change
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
./freud/bin/freud run ci-pr
```

That is the command that should be treated as the default completion gate for
non-live validation. `./gradlew test` alone is not enough because it does not
exercise the Freud deterministic scenario pack or deterministic reasoning evals.
`--dry-run` does not count. No commit and no "fully validated" claim should
happen until non-dry `./freud/bin/freud run ci-pr` passes.

The expected deterministic gate order is:

1. `preflight_compile`
2. `targeted_tests`
3. `full_tests`
4. `scenario_pack`
5. `reasoning_eval_logic`

### I Want Deterministic Validation Only

Use the same command:

```bash
./freud/bin/freud run my-change
```

Narrower options:

```bash
./freud/bin/freud run my-change --only scenario_pack
./freud/bin/freud run my-change --from-step scenario_pack
./freud/bin/freud run my-change --skip preflight_compile,targeted_tests
```

These narrower commands are for iteration speed. They are not the default
signoff gate.

### I Want One Live Smoke

Use the single-input entrypoint:

```bash
./freud/bin/freud eval --input input.txt
./freud/bin/freud eval --input input.txt --expected expected.txt
```

This is the primary live command for direct agent checks.

### I Want To Replay A Previous Run (No API Calls)

Every live eval automatically records all LLM responses. Replay them to re-run the eval for free:

```bash
# Find the run dir from the run index
tail -1 .neopsyke/runs/freud/run-index.tsv

# Replay using the cache from that run
./freud/bin/freud eval --input input.txt --cache-replay <run-dir>/artifacts/llm-cache.jsonl
```

Or replay a full recorded session (all channels, not just LLM):

```bash
./freud/bin/freud eval --session-replay <run-dir>
```

Replay serves cached responses as long as the messages sent to the LLM haven't changed. When they diverge (because your code changed what the agent sends), it switches to real API calls from that point forward and logs exactly where divergence happened.

This is the recommended workflow for iterative development: record once, then replay while tuning policies, scoring, post-processing, or telemetry. See [LLM response cache](../docs/evaluation.md#llm-response-cache-record--replay) for the full reference.

### I Want The Full Live Reasoning Lane

Run the live suite directly:

```bash
./freud/bin/freud bbh --lane low-llm
./freud/bin/freud bbh --lane high-llm
```

Use these when you want the frozen live reasoning matrix, not just one prompt.

If you want one orchestrated run that includes the live suite after the
deterministic checks:

```bash
./freud/bin/freud run my-change --live --lane low-llm
./freud/bin/freud run my-change --live --lane high-llm
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
./freud/bin/freud triage           # anomaly detection on latest run
./freud/bin/freud summarize        # compact summary of latest run
./freud/bin/freud context-pack     # package for LLM analysis
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

### Finding Previous Runs

All runs are indexed in `.neopsyke/runs/freud/run-index.tsv` (append-only, concurrent-safe):

```
timestamp       feature_id    run_dir                  status
20260327T034201Z  ci-pr       /full/path/to/run1       pass
20260327T034500Z  live-eval   /full/path/to/run2       pass
```

Commands like `./freud/bin/freud triage` with no args read the last line automatically.
Agents can grep for their feature ID to find their run.

## Concurrency

Concurrent Freud runs are only partly safe.

Not safe in the same checkout/worktree:

- `./freud/bin/freud run ...` (invokes Gradle)
- raw `./gradlew ...` runs

Reason:

- those commands invoke Gradle/Kotlin compilation or tests against the same checkout
- overlapping Gradle-backed runs can collide on shared `build/` outputs and Kotlin/Gradle daemon state
- if parallel validation is needed, use separate git worktrees or separate clones

Conditionally safe in the same checkout:

- `./freud/bin/freud eval` runs can overlap
- `./freud/bin/freud bbh` runs can overlap
- mixing those live command families is fine only when they are not overlapping with any Gradle-backed command

Always safe:

- `./freud/bin/freud triage`, `./freud/bin/freud summarize`, `./freud/bin/freud context-pack` (read-only analysis)
- `--dry-run` inspection commands

Why the live-only case can work:

- each run gets its own timestamped run directory
- `run-index.tsv` uses atomic append (O_APPEND) — concurrent writers don't corrupt each other
- the run dir printed in command output is always the source of truth

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

- `./freud/bin/freud eval` runs a single real agent turn
- `./freud/bin/freud bbh` runs a frozen multi-case live suite
- `./freud/bin/freud run --live` just orchestrates those live steps after the deterministic ones

Cost guidance:

- deterministic runs are the default and should be your first pass
- one-shot live eval is the cheapest live check
- **use `--cache-replay` to re-run live evals without API calls** — record once, iterate for free
- BBH smoke is an advanced live suite and costs more
- live routing depends on your configured models/providers

Memory behavior:

- one-shot `./freud/bin/freud eval` uses isolated Freud memory by default
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
- **pipeline**: ordered list of steps (shell commands or built-in by name)
- **live_eval**: timeout, preserve_memory, goals_enabled, llm_config_file
- **scenarios**: manifest_file
- **bbh**: prompts_file, answers_file, min_pass_rate, max_timeouts
- **runtime**: continue_on_fail, scratchpad_debug, id_enabled
- **telemetry**: enabled

Lane profiles (`freud/config/profiles/low-llm.yaml`, `high-llm.yaml`) override specific config keys for live evaluation:

```bash
# Use low-cost LLM routing
./freud/bin/freud run my-change --live --lane low-llm

# Override a single config key for this run
./freud/bin/freud run my-change -o live_eval.timeout=60

# Use a completely custom config
./freud/bin/freud run my-change -c /path/to/custom.yaml
```

Secrets (`MISTRAL_API_KEY` etc.) are never in config files — they pass through from the host environment.

## Layout

- `freud/cli/`: Cobra entrypoint and subcommands
- `freud/internal/config/`: config loading, schema, validation
- `freud/internal/orchestrator/`: pipeline orchestration, live eval, BBH, session replay, scenarios, reasoning gate
- `freud/internal/analysis/`: native Go analysis modules (triage, summarize, context-pack, telemetry)
- `freud/config/freud.yaml`: default YAML configuration
- `freud/config/profiles/`: lane profile overlays (low-llm.yaml, high-llm.yaml)
- `freud/legacy/scripts/`: legacy shell scripts (no longer called by the CLI)
- `freud/legacy/py/`: legacy Python modules (superseded by Go analysis modules)
- `freud/tests/`: legacy BATS and pytest tests (superseded by Go tests)
- `freud/bin/`: local built binaries created by `bootstrap.sh`

## Building

```bash
# From repo root
./freud/bootstrap.sh

# Run the default fast CLI/unit tests
cd freud && go test ./... -v

# Optional mocked command-path CLI E2Es
cd freud && go test -tags=cli_e2e ./cli -v

# Cross-compile for Linux
cd freud && GOOS=linux GOARCH=amd64 go build -o ./bin/freud-linux ./cli
```

`go build` produces an executable binary with no runtime dependencies — no Go, Python, or shell interpreter needed to run it.

## Design Rules

- deterministic checks first
- live/provider checks explicit and optional
- one CLI, one config file, clear subcommands
- `./freud/bin/freud eval` is the primary live entrypoint
- `./freud/bin/freud bbh` is the advanced live suite
- `./freud/bin/freud run --live` orchestrates the above, it does not replace them
- pipeline steps without a `cmd` are built-in (dispatched to native Go by name)
- run history in `run-index.tsv` (append-only, concurrent-safe) — no symlinks or pointer files
