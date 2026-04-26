# Full Prompt Asset Refactor

## Summary
Move LLM-facing prompt text, prompt sections, action guidance fragments, retry instructions, eval prompts, and structured-output schemas out of Kotlin into hot-reloadable prompt assets. Kotlin remains responsible for typed control flow, typed parsers, validators, safe fallbacks, action execution, and Superego action directives. No backwards compatibility shims: remove embedded prompt constants and direct prompt builders once migrated.

## Key Changes
- Add a `PromptCatalog` subsystem that loads prompt assets from `config/prompts/**` by default, with `NEOPSYKE_PROMPTS_DIR` override and bundled resource fallback for packaged runs.
- Use simple template variables like `{{action_schema_enum}}`; rendering must use an explicit allowlist per prompt ID, reject unknown/missing variables, and reject unresolved placeholders.
- Implement always-hot-reload by checking prompt/schema file mtimes before each render. If a changed asset is invalid, keep using the current valid in-memory version for that prompt/schema, log an error with prompt ID, path, version/hash, and validation failure, and continue.
- Emit `prompt_id`, `prompt_version`, `prompt_hash`, `schema_id`, and `schema_hash` in LLM metadata/telemetry for every model call.
- Update `AGENT_RUNTIME_LOGIC.md` and relevant `docs/agent-logic/**` files because prompt assembly, schemas, planner behavior, and failure semantics are core runtime behavior.

## Implementation Changes
- Replace Kotlin prompt construction in planner lanes, input planners, meta-reasoner, superego, memory advisor/summarizers, scratchpad finalizer, assignment verifier, approval interpreter, web-search adapters, structured-output prompt-only fallback, and eval harnesses with catalog-rendered prompt assets.
- Preserve `PromptBudgetAllocator.Section` as the runtime assembly primitive, but instantiate sections from YAML assets containing `key`, `role`, `band`, `importance`, `floor_tokens`, and `content`.
- Move JSON schemas to schema asset files with strict/relaxed variants where needed; Kotlin typed payload classes and validation remain the execution contract.
- Externalize planner-facing action plugin prompt fields: `plannerDescription`, `payloadGuidance`, `payloadSchemaExample`, and `followUpPrefix`. Keep `superegoDirectives`, action capabilities, effect class, trust policy, execution logic, and deterministic review logic in Kotlin.
- Refactor `ActionPluginFactoryContext`/`ActionRegistry` to provide prompt-backed action descriptors by `ActionType`, including built-ins, Google/email plugins, and connector actions.
- Keep hot reload simple: a failed reload never mutates the active catalog entry. If no valid prior version exists during startup, fail startup with the same detailed validation error.
- Add a prompt asset validator CLI/test helper that loads all prompt assets, renders required fixtures, parses all schemas, checks variable allowlists, computes hashes, and reports invalid prompt IDs.

## Asset Layout
- `config/prompts/catalog.yaml`: prompt IDs, versions, file references, schema references, and owners/domains.
- `config/prompts/planner/**`: router, grounding classifier, direct response, general action, task decomposition, progression, assignment lane, impulse, assignment command builder, plan refiner.
- `config/prompts/safety/**`: superego review and retry prompts.
- `config/prompts/memory/**`: long-term advisor, logbook summarizer, scratchpad finalizer.
- `config/prompts/actions/**`: planner-facing action descriptor fragments keyed by action type. Superego directives are intentionally excluded from prompt assets.
- `config/prompts/integrations/**`: web-search and provider-specific prompt assets.
- `config/prompts/evals/**`: reasoning, memory live eval, BBH-style, and Freud eval prompt assets.
- `config/prompts/schemas/**`: strict and relaxed JSON schemas keyed by schema ID.

## Tests
- Add catalog unit tests for loading, hot reload, invalid edit retaining last valid version, variable validation, schema parsing, and hash stability.
- Add render snapshot tests for each prompt ID using deterministic fixture variables; snapshots should validate structure, not model quality.
- Add schema-contract tests proving each schema asset matches the existing Kotlin payload parser expectations and required-field validation.
- Add static audit tests that fail if new LLM prompt text is embedded in Kotlin outside the prompt catalog subsystem.
- Update planner/superego/memory/eval tests to assert prompt IDs/hashes appear in metadata and that invalid hot reload logs an error while continuing with the prior valid prompt/schema.
- Run deterministic validation: `./gradlew compileKotlin compileTestKotlin`, targeted prompt/planner tests, then `./freud/bin/freud run signoff-gate`.

## Assumptions
- “All prompting” includes production runtime prompts, eval/test harness prompts, retry prompts, prompt-only JSON fallback text, and planner-facing prompt fragments injected through action descriptors. It does not include Superego action directives.
- Prompt edits are intentionally breaking and may change behavior immediately after a successful reload.
- Invalid hot-reload edits do not replace the active in-memory prompt/schema; startup still fails if there is no valid version.
- Structured schemas move to assets, but Kotlin typed parsing/validation remains authoritative for runtime behavior.
