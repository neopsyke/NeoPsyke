# neopsyke-pgvector-memory

First-party pgvector-backed long-term memory provider for NeoPsyke.

This project is structured to become its own repository. It already builds as a
standalone Gradle application and includes its own wrapper, license files,
Docker-backed local pgvector setup, and release-bundle task.

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
- Default provider startup transport in this repo: HTTP

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
- `MISTRAL_API_KEY` or `EMBEDDING_API_KEY` for the default embedder
- Docker for the default local pgvector boot path

By default the provider now owns the local pgvector boot path:

- it targets a local PostgreSQL JDBC URL by default
- it auto-starts a Docker-managed `pgvector/pgvector` container when needed
- it persists data in the named Docker volume
  `neopsyke-pgvector-memory-data`

Set `PGVECTOR_BOOTSTRAP_MODE=off` if you want to point the provider at an
already managed PostgreSQL/pgvector instance instead.

## Quick start

1. Copy the env template:

```bash
cp .env.example .env
```

2. Set `MISTRAL_API_KEY` in `.env` or your shell.

3. Run the HTTP provider:

```bash
./gradlew run --args="--transport=http --port=7841"
```

The provider will:

- ensure Docker is available
- start or reuse the local pgvector container if the configured DB is not reachable
- initialize the schema
- expose the HTTP `v1` contract on `http://127.0.0.1:7841`

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

Build the standalone release bundle:

```bash
./gradlew releaseBundleZip
```

Artifacts are written to:

```text
build/libs/neopsyke-pgvector-memory-0.1.0-all.jar
build/distributions/neopsyke-pgvector-memory-0.1.0-bundle.zip
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
- `PGVECTOR_BOOTSTRAP_MODE` (`auto|off`, default: `auto`)
- `PGVECTOR_BOOTSTRAP_DOCKER_IMAGE`
- `PGVECTOR_BOOTSTRAP_CONTAINER_NAME`
- `PGVECTOR_BOOTSTRAP_VOLUME_NAME`
- `PGVECTOR_BOOTSTRAP_STARTUP_TIMEOUT_MS`
- `MEMORY_PROVIDER_TRANSPORT`
- `MEMORY_PROVIDER_HTTP_HOST`
- `MEMORY_PROVIDER_HTTP_PORT`

## Manual Docker management

If you want to run the local pgvector container yourself instead of letting the
provider auto-bootstrap it:

```bash
docker compose up -d pgvector
```

Stop it:

```bash
docker compose stop pgvector
```

Reset local provider data:

```bash
docker compose down -v
```

## Release shape

The standalone publishable artifacts for this project are:

- fat jar: `neopsyke-pgvector-memory-<version>-all.jar`
- release bundle zip: `neopsyke-pgvector-memory-<version>-bundle.zip`

The release bundle includes:

- the fat jar
- this README
- license and notice files
- `.env.example`
- `docker-compose.yml`
- convenience launcher scripts under `scripts/`

Breaking HTTP wire changes must move from `/v1/...` to `/v2/...`.

## Status

This provider is ready to be extracted into its own repository. NeoPsyke still
contains the integration/bootstrap side that launches it as the default memory
runtime, but the provider project itself is no longer shaped like an internal
subproject.
