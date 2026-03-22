# Contributing

Thanks for contributing to `neopsyke-pgvector-memory`.

This project is the first-party pgvector-backed long-term memory provider for
NeoPsyke. The codebase is intentionally small, explicit, and provider-oriented.
Changes should preserve that clarity.

## Principles

- Prefer architectural clarity over backward compatibility.
- Keep the HTTP `v1` contract stable. Breaking wire changes must move to
  `/v2/...`.
- Keep provider-core logic transport-agnostic where practical.
- Keep MCP as an adapter, not the primary architecture.
- Do not add live external-token tests to the default test suite.

## Development Setup

Requirements:

- JDK 21+
- Docker for the default local pgvector path
- `MISTRAL_API_KEY` or `EMBEDDING_API_KEY` if you want to run the real embedder

Useful commands:

```bash
./gradlew test
./gradlew fatJar
./gradlew releaseBundleZip
./gradlew run --args="--transport=http --port=7841"
./gradlew run --args="--transport=mcp"
```

If you want to point at an already managed PostgreSQL instance instead of the
provider's local Docker bootstrap:

```bash
export PGVECTOR_BOOTSTRAP_MODE=off
```

## Testing Policy

Default local/CI validation should stay non-live:

- `./gradlew --no-daemon test`

Default tests must not require:

- paid API tokens
- real embedding API calls
- mandatory Docker startup in CI

If adding heavier integration coverage later, keep it separate from the default
PR gate.

## HTTP Contract

The stable HTTP `v1` endpoints are:

- `GET /v1/health`
- `GET /v1/metrics`
- `POST /v1/recall`
- `POST /v1/imprint`
- `POST /v1/admin/forget`
- `POST /v1/admin/reset`

Breaking contract changes require a new versioned namespace.

## Code Style

- Keep Kotlin code small and explicit.
- Prefer descriptive names over clever abstractions.
- Keep provider-core naming centered on `provider`, not `server`, except at the
  transport edge.
- Add focused tests for new request validation, runtime config, or transport
  behavior changes.

## Pull Requests

When opening a PR:

- explain what changed
- explain why it changed
- list how it was validated
- mention any follow-up or known limitations

Good PRs here are narrow, readable, and easy to reason about.

