# AGENTS.md

Instructions for coding agents working in this repository (Codex, Claude, Gemini/Google, Mistral, etc.).

## Purpose
- Use this file for execution and contribution rules.
- Use `README.md` for product and runtime documentation.

## Priority
- Follow system/developer/user instructions first.
- Then follow this file.
- If instructions conflict, use the highest-priority source.

## Project Snapshot
- Language: Kotlin (JVM), Gradle Kotlin DSL.
- Main source: `src/main/kotlin/psyke`.
- Tests: `src/test/kotlin/psyke`.
- Entrypoints:
  - `./gradlew run`
  - `./run-psyke.sh`

## Environment
- Requires JDK 21+.
- Runtime needs `MISTRAL_API_KEY`.
- Optional runtime flags and env vars are documented in `README.md`.

## Working Rules
- Keep changes focused and minimal.
- Do not make unrelated refactors.
- Do not add license headers unless asked.
- Do not commit secrets, API keys, or local machine paths.
- Prefer ASCII in docs/code unless the file already uses Unicode.
- Preserve existing behavior unless the user asked for behavior changes.

## Build and Test
- Preferred full verification:
  - `./gradlew test`
- For faster iteration, run targeted tests first, then full tests if core/shared code changed.
- If you cannot run tests, clearly state that in your final summary.
- Test execution policy for coding agents:
  - Fast local unit/integration tests with deterministic stubs are allowed in the default `./gradlew test` suite.
  - Tests that require real network calls, real provider APIs, or consume paid external tokens must be manual-only and run only when explicitly requested.

## Code Style
- Follow existing Kotlin style and package structure.
- Prefer small, explicit functions and descriptive names.
- Use existing abstractions (`SensoryCortex`, `MotorCortex`, `SuperegoGatekeeper`, instrumentation hooks) instead of duplicating logic.
- Keep logging and metrics instrumentation consistent with existing patterns.

## Change Summary Format
- When done, report:
  - What changed.
  - Why it changed.
  - How it was validated (tests/commands).
  - Any risks or follow-ups.

## Tool-Specific Files
- If a tool-specific instruction file exists (for example `CLAUDE.md`), keep it thin and aligned with this file.
- Avoid duplicating long instruction sets across multiple files.
