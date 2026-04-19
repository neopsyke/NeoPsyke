# Plan Refinement Live Evals

Live eval inputs that exercise the Ego-level plan generation and refinement
pipeline for durable assignments. These require `--assignments` to enable the
assignment subsystem.

## Running

```bash
# Simple recurring assignment (plan generation + refinement + approval staging)
./freud/bin/freud eval --live --assignments --lane low-llm \
    --input freud/evals/plan-refinement/simple-recurring-assignment.txt

# Multi-step assignment with richer plan (more steps, data flow, grounding)
./freud/bin/freud eval --live --assignments --lane low-llm \
    --input freud/evals/plan-refinement/multi-step-assignment.txt

# Record for later replay
./freud/bin/freud eval --live --assignments --record --lane low-llm \
    --input freud/evals/plan-refinement/simple-recurring-assignment.txt
```

## What to check in the artifacts

After a run, inspect the run directory:

1. **Telemetry events** (`logs/events.jsonl`):
   - `plan_refinement_completed` with `plan_kind=assignment_create`
   - `refinement_mode` should be `unchanged` or `llm_rewritten`
   - `original_step_count` and `refined_step_count` should be > 0

2. **Agent log** (`logs/neopsyke.log`):
   - `handleCreate:` with title and plan step count
   - `Plan refinement completed:` with mode and step counts
   - `Plan payload validated:` with normalized step IDs
   - `Approval context built:` with entry and step counts

3. **Stdout** (`logs/stdout.log`):
   - The approval prompt should show plan steps to the user

These evals are lane-agnostic. Use `--lane low-llm` for fast iteration or
`--lane high-llm` for production-quality model routing.

## Pipeline integration

All live evals (cognitive-runtime + plan-refinement) run as the `live_eval_pack`
pipeline step:

```bash
./freud/bin/freud run signoff-gate --live --lane low-llm --only live_eval_pack
```
