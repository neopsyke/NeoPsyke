# psyke Kotlin app

Standalone Kotlin JVM app using Gradle with:
- Pluggable LLM client (`ChatModelClient`)
- Interactive Ego agent loop (inputs, thoughts, actions)
- Extensible input abstraction (`SensoryCortex`)
- Superego action gatekeeper (policy/safety review)
- Action executor (`MotorCortex`) for `web_search` and `answer`

## Requirements
- JDK 23+
- Use the included Gradle wrapper (`./gradlew`)
- Kotlin currently emits bytecode up to Java 21; the build uses a JDK 23 toolchain while targeting 21-compatible bytecode for both Kotlin and Java.

## Configuration
- Set `MISTRAL_API_KEY` to your API token.
- Optional:
  - `MISTRAL_EGO_MODEL` (default: `mistral-small-latest`)
  - `MISTRAL_SUPEREGO_MODEL` (default: same as Ego model)
  - `PSYKE_DASHBOARD_ENABLED` (default: `true`)
  - `PSYKE_DASHBOARD_PORT` (default: `8787`)
  - `EGO_MAX_LOOP_STEPS` (default: `18`)
  - `EGO_MAX_THOUGHT_PASSES` (default: `5`)
  - `EGO_MAX_PROMPT_TOKENS` (default: `2400`)
  - `EGO_MAX_COMPLETION_TOKENS` (default: `900`)
  - `EGO_LOOP_DELAY_MS` (default app value: `0`)
  - `EGO_MAX_MEMORY_CHARS` (default: `12000`)
  - `EGO_MAX_MEMORY_PROMPT_TOKENS` (default: `256`)
  - `EGO_MAX_ACTION_PAYLOAD_CHARS` (default: `4000`)
  - `EGO_SEARCH_RESULT_COUNT` (default: `5`)

## Run
```bash
export MISTRAL_API_KEY=your_token
./gradlew run
```

Enable trace logs:
```bash
./gradlew run -Dorg.slf4j.simpleLogger.defaultLogLevel=trace
```

Log `gradlew run` output to a file:
```bash
mkdir -p .psyke/logs
export PSYKE_LOG_FILE="$PWD/.psyke/logs/gradle-run.log"
./gradlew run -Dorg.slf4j.simpleLogger.defaultLogLevel=info
```

## Simpler Entrypoint (No `gradle run`)
Use the local launcher:
```bash
export MISTRAL_API_KEY=your_token
./run-psyke.sh
```

Set a specific log level via parameter:
```bash
./run-psyke.sh --log-level info
```

Disable the default interactive delay for faster local/manual loops:
```bash
./run-psyke.sh --no-delay
```

Notes:
- `run-psyke.sh` bootstraps `installDist` once if needed.
- After bootstrap, execution is direct (`build/install/psyke/bin/psyke`) without `gradle run`.
- Default log level in `run-psyke.sh` is `warning`.
- Launcher logs are written to per-run files in `.psyke/logs/runs/`.
- `.psyke/logs/latest.log` always points to the newest run log.
- `.psyke/logs/latest-run.env` stores `PSYKE_LOG_RUN_ID`, `PSYKE_LOG_FILE`, and start time for the current run.
- Old run logs are auto-pruned; retention defaults to 30 files (`PSYKE_LOG_RETENTION`).
- Default loop delay in `run-psyke.sh` is `1000ms` (`--no-delay` or `--loop-delay-ms 0` disables it).
- `PSYKE_LOG_LEVEL` can still provide a default if `--log-level` is omitted.
- `PSYKE_LOG_DIR` overrides the log directory (default: `.psyke/logs`).
- By default the launcher persists metrics to `.psyke/metrics.db` (override with `PSYKE_METRICS_DB`).

Then interact:
```text
you> hello
you> search for latest kotlin release notes
you> exit
```

## Realtime dashboard
- Enabled by default and served locally on `http://127.0.0.1:8787/`.
- Uses async instrumentation (`InstrumentationBus`) with pluggable sinks:
  - `StructuredLogSink` (logs)
  - `DashboardStateStore` + SSE stream (UI)
- Queue, loop, thought/action flow, Superego in/out, and LLM events are streamed as typed events.
- Thought Chain / Timeline includes non-snapshot events, including `llm_raw_response` payloads.
- Queue snapshots are used for queue panels/counts, not shown as timeline items.
- If dashboard bind fails, app continues running without the dashboard server.

## Loop behavior
- Priority order: incoming inputs first.
- Inputs are ingested through `SensoryCortex` (extensible input-source abstraction).
- Input priority levels: `low` (1), `medium` (2, default), `high` (3).
- The built-in stdin source always submits with `high` priority.
- `MemoryStore` keeps bounded rolling memory and compacts older turns into a summary as it nears capacity.
- Memory summary included in Ego/Superego prompts is token-capped to stay within LLM context budgets.
- If no inputs are pending, thoughts and actions are scheduled by urgency (`high`, `medium`, `low`).
- Every proposed action includes a context summary capped at 180 chars.
- Superego validates every action against directives before execution.
- If denied, denial reason (<=180 chars) is pushed back as a new thought.

## Superego directives
- Loaded at startup from `src/main/resources/superego/directives.txt`.
- Use one directive per line.
- Empty lines and lines starting with `#` are ignored.

## Metrics persistence
- SQLite storage records per-run and per-call data (`runs`, `llm_calls`).
- API keys are never stored raw; metrics use a salted key fingerprint.
- Call metrics include actor/call-site/action-type, latency, status, and token usage when returned by the model API.
- Instrumentation health is persisted per run, including dropped instrumentation events and queue-saturation hits.
- Superego token usage is tracked separately for both current run and persistent totals (still included in overall totals).
