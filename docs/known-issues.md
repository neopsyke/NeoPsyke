# Known Issues & Limitations

NeoPsyke is at version 0.1.0 — early and experimental. The following are known rough edges
we're actively working on. Contributions and feedback are welcome.

## Goals

The goal subsystem is **experimental and unstable**. Goal lifecycle management (creation,
tracking, completion) works in basic scenarios but needs significantly more testing across
edge cases. Expect breaking changes as the design evolves.

## Dashboard

The web dashboard is **not built or tested for multiple concurrent users** and may behave
unpredictably under concurrent access. It is intended for **local use only** — testing,
monitoring, and debugging a single agent instance.

## Agent Prompts

The core agent prompts (id, ego, superego) are functional but **not yet optimized**. They
produce reasonable results in most scenarios, but there is significant room for improvement
in consistency, token efficiency, and edge-case handling. Prompt tuning is an ongoing effort.

## Evaluations

The evaluation suite is still **small in coverage**. The replay-based evaluation pipeline
works well, but more live evaluations are needed to catch regressions and validate behavior
under real-world conditions. See [evaluation.md](evaluation.md) for the current testing
layers.

## Memory Retrieval

Memory retrieval is functional but **needs polishing**. In particular, the injection
protection layer can be overly aggressive, causing legitimate memories to be filtered out
during retrieval. We're working on improving the balance between safety and recall accuracy.
