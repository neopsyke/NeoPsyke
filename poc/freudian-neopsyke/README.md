# Freudian NeoPsyke PoC

Minimal, independent proof-of-concept for an Id/Ego/Superego control loop in an LLM-agent architecture.

## Purpose
This PoC isolates the novelty claim:
- internal autonomous impulses (Id),
- strict governance gate (Superego),
- binary feedback (`accepted`/`denied`) that mutates future drive dynamics.

## Architecture
- `SensoryCortex`: user input abstraction (scripted deterministic source).
- `MotorCortex`: action execution abstraction.
- `IdModule`: need vector growth, thresholding, winner selection, trigger probability, pending impulse gate.
- `Ego`: thought/action orchestration + per-root impulse lifecycle tracker.
- `Superego`: deterministic policy, including special rules for Id-origin actions.

## Invariants
- External user path cannot impersonate Id origin.
- At most one pending Id impulse lifecycle exists at a time.
- Impulse lifecycle final result is emitted only when all branches complete.
- One noop/discarded branch does not immediately deny a root impulse if another branch is still pending.

## Run
From repository root:

```bash
./gradlew -p poc/freudian-neopsyke run --args='--mode run --config config/poc.yaml'
```

Outputs:
- `build/poc-events-id-on.jsonl`
- `build/poc-summary.json`

## Ablation
Run `Id=off` vs `Id=on` with same deterministic seed/config:

```bash
./gradlew -p poc/freudian-neopsyke run --args='--mode ablation --config config/poc.yaml --ablation-output build/ablation-summary.json'
```

Outputs:
- `build/ablation-summary.json`
- event logs for both runs (`id-off` and `id-on` suffix)

## Tests
```bash
./gradlew -p poc/freudian-neopsyke test
```
