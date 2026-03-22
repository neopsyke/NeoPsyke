# neopsyke-pgvector-memory

First-party pgvector-backed long-term memory provider for NeoPsyke.

This project is being prepared for extraction into its own repository. It is
already structured as a standalone Gradle application so the directory can be
lifted out with minimal follow-up.

## Scope

- Default NeoPsyke memory provider for `memory=default`
- Semantic/vector recall backed by PostgreSQL + pgvector
- Typed imprint support for:
  - narrative memory
  - facts
  - relations
  - episodes
- HTTP `v1` provider contract for NeoPsyke
- Optional MCP adapter for external clients and tool interoperability

## Current transport posture

- NeoPsyke default transport: HTTP
- Optional adapter: MCP

The stable HTTP contract is versioned under `/v1/...`. Breaking wire changes
must move to `/v2/...`.

## Stable HTTP v1 endpoints

- `GET /v1/health`
- `GET /v1/metrics`
- `POST /v1/recall`
- `POST /v1/imprint`
- `POST /v1/admin/forget`
- `POST /v1/admin/reset`

## Requirements

- JDK 21+
- PostgreSQL with pgvector available
- `MISTRAL_API_KEY` or `EMBEDDING_API_KEY` for the default embedder

Provider-side Docker/bootstrap ownership is planned, but not finished yet. For
now the provider expects an already reachable pgvector-backed PostgreSQL
instance via `PGVECTOR_DB_*` environment variables.

## Local development

Run the HTTP provider:

```bash
./gradlew run --args="--transport=http --port=7841"
```

Run the MCP adapter:

```bash
./gradlew run --args="--transport=mcp"
```

Build the standalone fat jar:

```bash
./gradlew fatJar
```

The fat jar is written to:

```text
build/libs/neopsyke-pgvector-memory-0.1.0-all.jar
```

## Environment

- `PGVECTOR_DB_URL`
- `PGVECTOR_DB_USER`
- `PGVECTOR_DB_PASSWORD`
- `MEMORY_DEFAULT_NAMESPACE`
- `EMBEDDING_API_KEY`
- `MISTRAL_API_KEY`
- `EMBEDDING_BASE_URL`
- `EMBEDDING_MODEL`
- `EMBEDDING_DIMENSIONS`
- `MEMORY_SEARCH_DEFAULT_LIMIT`
- `MEMORY_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD`
- `MEMORY_SEMANTIC_DEDUPE_MIN_CONFIDENCE`
- `MEMORY_FACT_DEFAULT_SUBJECT`

## Status

This provider is intended to become its own open-source repository. Before the
actual extraction, NeoPsyke still contains the integration/bootstrap side that
launches this provider as the default memory runtime.
