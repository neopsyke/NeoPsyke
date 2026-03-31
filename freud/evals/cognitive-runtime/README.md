# Cognitive Runtime Live Eval Pack

This directory holds the curated low-llm live eval inputs for the cognitive runtime migration.

These inputs are phase-oriented rather than product-marketing examples. Each file is meant to be recorded once with:

```bash
./freud/bin/freud eval --live --record --lane low-llm --input <file>
```

Then replayed during debugging with either:

```bash
./freud/bin/freud eval --live --cache-replay <run-dir>/artifacts/llm-cache.jsonl --input <file>
```

or:

```bash
./freud/bin/freud eval --live --session-replay <run-dir>
```

The inputs intentionally target the architectural seams under migration:

- `phase-1-thread-foundation.txt`
- `phase-2-opportunity-shaping.txt`
- `phase-3-intentions.txt`
- `phase-4-feedback-reentry.txt`
- `phase-5-goal-runtime.txt`
- `phase-6-policy-control.txt`
- `phase-7-convergence.txt`

The acceptance source of truth remains `docs/COGNITIVE_RUNTIME_ARCHITECTURE_STATUS.md`.
