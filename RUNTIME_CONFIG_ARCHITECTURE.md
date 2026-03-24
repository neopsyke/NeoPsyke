# Runtime Config Architecture

This file documents the runtime configuration model after the March 24, 2026 cleanup.

It is intentionally separate from `README.md` for now. This is the architecture and operator note for maintainers; `README.md` can later be reduced to the user-facing quick-start layer.

## Goals

- Keep shipped runtime defaults in versioned YAML, not duplicated in Kotlin.
- Make release artifacts runnable without asking users to hand-copy config files.
- Let local operators override only the fields they need.
- Fail fast on malformed runtime config instead of silently drifting into hidden code defaults.
- Prefer architectural clarity over compatibility with older config shapes.

## Source Of Truth

The shipped runtime defaults are the checked-in YAML files:

- `config/agent-runtime.yaml`
- `config/id-runtime.yaml`
- `config/llm-runtime.yaml`
- `config/mcp-runtime.yaml`
- `config/memory-runtime.yaml`

These files are packaged into the application artifact as bundled resources. Kotlin defines schema, merge behavior, env override behavior, and validation. Kotlin is not the authoritative source for the shipped default data.

`config/llm-runtime.yaml` is also the only source of truth for the model catalog.

The shipped defaults are not treated as external override files. The canonical checked-in files live under `config/`, and the classpath-bundled copies are what artifact users get by default.

## Loading Model

Each runtime config now loads in this order:

1. Bundled YAML resource
2. External YAML override file, if present
3. Environment-variable overrides
4. Validation

The external YAML is an overlay on top of the bundled YAML, not a replacement file.

### Overlay Semantics

- YAML objects merge recursively by key.
- Scalars replace bundled values.
- Lists replace bundled lists.
- `null` in an override file is ignored for merge purposes. It does not delete bundled sections.

That means a small override file can safely change only one field without restating the whole runtime config.

The canonical shipped defaults live under `config/`. External overrides are separate files and are not expected to live there.

## File Resolution

Each runtime has:

- a bundled canonical default under `config/`
- a default external override filename in the current working directory
- an optional env var pointing to an external override file anywhere else

Default external override filenames:

- `agent-runtime.yaml`
- `id-runtime.yaml`
- `llm-runtime.yaml`
- `mcp-runtime.yaml`
- `memory-runtime.yaml`

Override env vars:

- `NEOPSYKE_AGENT_CONFIG_FILE`
- `NEOPSYKE_ID_CONFIG_FILE`
- `NEOPSYKE_LLM_CONFIG_FILE`
- `NEOPSYKE_MCP_CONFIG_FILE`
- `NEOPSYKE_MEMORY_CONFIG_FILE`

Resolution rules:

- If the override env var is set, that file must exist and be non-empty.
- If the default external filename exists in the working directory, it is treated as an external overlay file.
- If no external file exists, the bundled YAML is used by itself.
- If a file exists but is empty, loading fails.

This means:

- clone users can drop a local `llm-runtime.yaml` or `agent-runtime.yaml` into the repo root and it will be treated as an override
- artifact users can point `NEOPSYKE_*_CONFIG_FILE` at any file they want
- if neither exists, the app falls back to the bundled `config/*.yaml` resources in the JAR

## Validation Model

Validation is explicit and early. The loaders no longer treat malformed or missing concrete config as a cue to quietly recover through compatibility branches.

Current validation coverage:

- `agent-runtime.yaml`
  - Required top-level and domain sections must exist after merge.
- `id-runtime.yaml`
  - Root `id` section must exist after merge.
- `memory-runtime.yaml`
  - Required provider identity/transport/command fields must be non-blank.
- `mcp-runtime.yaml`
  - Capability `mode` and `provider` must be non-blank.
  - `stdio` capabilities must declare `command`.
- `llm-runtime.yaml`
  - `providers`, `cognitive_roles`, and `web_search` are required.
  - Required roles must exist.
  - Referenced providers must be valid enum values.
  - Referenced providers must have configured provider blocks.
  - Required provider fields such as `base_url` and `api_key_env` must exist.

Environment variables are then applied on top of validated YAML and can still override specific values. This is mainly for secrets, machine-local endpoints, and operator-local runtime toggles.

## LLM Runtime Clean Cut

`llm-runtime.yaml` no longer supports the old top-level routing shape.

Removed legacy pattern:

- top-level `provider`
- top-level `base_url`
- top-level `api_key_env`
- top-level `models.*` as the routing source

Canonical shape now:

```yaml
providers:
  groq:
    api_key_env: GROQ_API_KEY
    base_url: https://api.groq.com/openai/v1
    default_model: openai/gpt-oss-20b
    default_web_search_model: groq/compound-mini

cognitive_roles:
  planner:
    provider: groq
    model: openai/gpt-oss-120b
  action_verifier:
    provider: openai
    model: gpt-4o-mini
  superego_primary:
    provider: openai
    model: gpt-4o-mini
  meta_reasoner:
    provider: groq
    model: openai/gpt-oss-120b
  memory_advisor:
    provider: openai
    model: gpt-4.1-mini

web_search:
  provider: groq
  model: groq/compound-mini
```

Important consequences:

- Provider base URLs live in YAML, not Kotlin.
- Provider default model names live in YAML, not Kotlin.
- Partial external LLM configs inherit bundled provider definitions automatically.
- If a role references an invalid or missing provider, loading fails with a direct error.
- `model_catalog` is YAML-only and supports source-review timestamps through `metadata_updated_at`.

### Provider Auth Variables

Providers declare which auth env var they use via `providers.<name>.api_key_env`.

Typical values in the shipped config:

- `ANTHROPIC_API_KEY`
- `GROQ_API_KEY`
- `GOOGLE_API_KEY`
- `MISTRAL_API_KEY`
- `OPENAI_API_KEY`
- `OLLAMA_API_KEY`

`OLLAMA_API_KEY` is optional for local Ollama and only matters for authenticated Ollama hosts.

## Practical Examples

Ready-to-use external overlay examples live under:

- `examples/runtime-config/`
- `examples/runtime-config/README.md`

Those are designed as fast-start operator overlays with the important decisions already made, not as exhaustive copies of the canonical runtime files.

### Example: Small LLM Overlay

```yaml
cognitive_roles:
  planner:
    provider: anthropic
    model: claude-sonnet-4-20250514
```

This changes only the planner. The rest of the shipped roles and provider blocks stay inherited from the bundled `config/llm-runtime.yaml`.

### Example: Small Agent Overlay

```yaml
app:
  dashboard_port: 9101
agent:
  planner:
    max_loop_steps_per_input: 21
```

This changes only those two values. The remaining agent defaults still come from the bundled `config/agent-runtime.yaml`.

## Professional Packaging Model

For this project, the intended operator experience is:

- source checkouts can run with the checked-in YAML files
- release artifacts also run because those same YAML files are bundled into the artifact
- machine-specific or secret values are set via environment variables or local override YAML files

This avoids two failure modes:

- duplicated defaults in Kotlin and YAML drifting apart
- downloadable artifacts failing because a config file was not copied into the working directory

## What Is Not In Scope

Not every runtime-related file should become a shipped canonical YAML.

Generated local state is still allowed to be created by code. Example:

- `.neopsyke/runtime-defaults.yaml`

That file is operational state, not shipped product configuration.

## Regression Coverage Added

Regression tests now cover:

- partial overlay inheritance for `agent`, `id`, `llm`, `mcp`, and `memory`
- explicit override-path failures
- strict `llm-runtime` provider validation
- malformed `memory` and `mcp` config failures

## Current Precedence Contract

For shipped runtime configs, the contract is:

`env overrides > external YAML overlay > bundled YAML defaults`

This precedence applies to runtime config loading itself.

CLI flags remain a separate layer for app behavior outside the runtime YAML files, but they are not part of the YAML merge chain.

There should be no intentional dependence on legacy config shapes or duplicate Kotlin-owned default catalogs.
