# Why Kotlin?

> **Terminology:** See the [Glossary](glossary.md) for definitions of all agent concepts used in this document.

Most AI agent projects default to Python. This one uses Kotlin, and the choice is deliberate.

## Type safety accelerates agent-assisted development

When coding agents generate or modify code, the compiler catches type errors, nullability violations, and missing branches at compile time — not buried in a runtime stack trace three layers deep. The feedback loop is tight: write, compile, read error, fix, repeat. Agents can self-correct without human intervention. In Python, equivalent errors surface at runtime or not at all if the path isn't exercised, which makes agent-assisted development slower and less reliable.

## The language fits the domain

Kotlin's sealed class hierarchies are a natural match for this architecture. Stimulus families, percept types, action types, gate decisions, goal lifecycle states — the entire cognitive model is built on typed hierarchies. Sealed classes give exhaustive `when` expressions that the compiler enforces: add a new stimulus type and the compiler tells you every place that needs to handle it. Data classes give concise domain models. Extension functions attach behavior close to where it's used without deep inheritance trees.

## Structured concurrency for a concurrent architecture

The agent has natural concurrency: the Id pulse loop runs on its own timer, goal triggers fire independently, LLM calls are I/O-bound, tool execution can block, and the dashboard streams SSE events. Kotlin coroutines handle this with structured concurrency — no thread pool tuning, no callback hell, no event loop surprises. The JVM underneath provides real parallelism, not GIL-constrained threading.

## Pragmatism

I have deeper experience in Kotlin than in production-grade Python. Trying to write well-architected Python was costing more time than the language choice was worth. The JVM ecosystem provides mature, stable libraries for everything the agent needs (HTTP, JSON, SQLite, MCP), and the architecture mattered more than the ecosystem default.

## Coding agents lower the language barrier

The traditional argument against non-mainstream languages — "nobody will contribute" — is weakening as coding agents make it easier to read, understand, and modify unfamiliar code. The types and compiler actually help agents more than they help humans in some cases.
