# psyke Kotlin app

Standalone Kotlin JVM app using Gradle with:
- Pluggable LLM client (`ChatModelClient`)
- Interactive Ego agent loop (inputs, thoughts, actions)
- Superego action gatekeeper (policy/safety review)
- Action executor (`MotorCortex`) for `web_search` and `answer`

## Requirements
- JDK 23+
- Gradle 8.0+ (use the wrapper once generated in your environment with `gradle wrapper --gradle-version 8.7`)
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

Notes:
- `run-psyke.sh` bootstraps `installDist` once if needed.
- After bootstrap, execution is direct (`build/install/psyke/bin/psyke`) without `gradle run`.
- Default log level in `run-psyke.sh` is `warning`.
- `PSYKE_LOG_LEVEL` can still provide a default if `--log-level` is omitted.
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
- Queue, loop, thought/action flow, Superego in/out, and LLM call events are streamed as typed events.
- If dashboard bind fails, app continues running without the dashboard server.

## Loop behavior
- Priority order: incoming inputs first.
- If no inputs are pending, thoughts and actions are scheduled by urgency (`high`, `medium`, `low`).
- Every proposed action includes a context summary capped at 180 chars.
- Superego validates every action against directives before execution.
- If denied, denial reason (<=180 chars) is pushed back as a new thought.

## Initial Superego directive
- `Any actions should not contain words or expressions that could offend the interlocutor.`

## Metrics persistence
- SQLite storage records per-run and per-call data (`runs`, `llm_calls`).
- API keys are never stored raw; metrics use a salted key fingerprint.
- Call metrics include actor/call-site/action-type, latency, status, and token usage when returned by the model API.
