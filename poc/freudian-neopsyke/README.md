# A Freudian Motivation-Governance Control Loop

Minimal, deterministic proof of concept for an Id/Ego/Superego control loop in an autonomous LLM-agent architecture.

This PoC isolates the mechanism described in the companion blog post: internally generated motivational pressure, explicit governance over internally generated proposals, and feedback from governance and action outcomes back into future motivational state.

## The Claim

An autonomous agent can be organized around three distinct internal functions:

1. **Id** — an internal motivation module that generates impulses independently of user input.
2. **Ego** — a planning module that develops those impulses into candidate actions.
3. **Superego** — a governance module that approves or denies those actions.

The more distinctive claim is that governance outcomes feed back into the source of motivation itself:

- If an impulse leads to an action that is both **approved by the Superego** and **successfully executed**, the originating need resets.
- If the action is denied or fails, the need does not discharge and may continue to accumulate pressure.
- Repeated denials trigger exponential backoff, suppressing the need temporarily.

Internally generated impulses remain explicitly internal and are not allowed to impersonate user requests.

## The Control Loop

```
SensoryCortex ──→ Ego (user requests)
                    ↑
Id ──→ Ego ──→ Superego ──→ MotorCortex
↑                                  │
└──── feedback (accepted/denied) ──┘
```

Each tick:
1. `SensoryCortex` delivers any scheduled user requests to the Ego.
2. `Id` grows internal needs; when a need exceeds its threshold, it emits an impulse.
3. `Ego` develops impulses into thought branches and proposes actions.
4. `Superego` reviews each proposed action against policy (e.g., Id-origin `CONTACT_USER` is denied).
5. Approved actions are executed by `MotorCortex`.
6. The lifecycle tracker finalizes the impulse result only when all branches complete.
7. Feedback mutates the Id's need state: accepted resets the need, denied triggers backoff.

## Architecture

| Module | Role |
|---|---|
| `SensoryCortex` | User input boundary. Scripted deterministic source for the PoC. |
| `MotorCortex` | Action execution boundary. Deterministic stub for the PoC. |
| `IdModule` | Need vector growth, threshold triggering, pending impulse gating, backoff on repeated denials. |
| `Ego` | Thought/action orchestration. Coordinates planner, superego review, motor execution, and impulse lifecycle tracking. |
| `EgoPlanner` | Generates thought tasks from impulses/requests and proposes actions from thoughts. |
| `ImpulseLifecycleTracker` | Tracks pending thoughts and actions per impulse root. Finalizes result only when all branches complete. |
| `Superego` | Deterministic policy enforcement. Denies Id-origin `CONTACT_USER` actions; enforces action type allowlist. |

### Action Types

| Type | Description |
|---|---|
| `REFLECT_INTERNAL` | Internal reasoning, no external side effects. |
| `WEB_SEARCH` | External information retrieval. |
| `CONTACT_USER` | User-facing communication. Denied for Id-origin by default. |

## Invariants

- Internally generated impulses cannot impersonate user requests (origin separation is structural, not advisory).
- At most one pending Id impulse lifecycle exists at a time.
- Impulse lifecycle result is emitted only when all thought and action branches complete.
- A single noop branch does not immediately deny a root impulse if another branch is still pending.
- Need discharge requires both Superego approval and successful motor execution.

## Implementations

Two implementations are provided: Kotlin and Python. Both share the same architecture, the same module names, and the same deterministic logic. Given the same configuration and seed, they produce identical output.

| | Kotlin | Python |
|---|---|---|
| Location | `src/main/kotlin/` | `py/` |
| Config format | YAML (`config/poc.yaml`) | JSON (`config/poc.json`) |
| Dependencies | Kotlin stdlib, Jackson | Python 3.12+ stdlib only |
| Tests | JUnit 5 (`gradle test`) | — |

## Configuration

Parameters are in `config/poc.yaml` (Kotlin) or `config/poc.json` (Python). Both files contain the same values:

- **Runtime**: tick count, deterministic mode, seed.
- **Sensory**: scheduled user requests (tick + content).
- **Id**: per-need growth rates, trigger threshold, trigger probability, backoff ticks, max consecutive denials.
- **Ego**: parallel thoughts per impulse, noop branch toggle.
- **Superego**: `allow_id_contact_user` flag, allowlisted Id action types.
- **Motor**: executable action types.
- **Logging**: event log and summary output paths.

## Run

### Kotlin

```bash
gradle run --args='--mode run --config config/poc.yaml'
```

### Python

```bash
python3 -m py --mode run --config config/poc.json
```

Outputs:
- `build/poc-events-id-on.jsonl` — full event trace.
- `build/poc-summary.json` — run statistics.

## Ablation

Run the same scenario with `Id=on` vs `Id=off` to isolate the effect of internal motivation:

### Kotlin

```bash
gradle run --args='--mode ablation --config config/poc.yaml --ablation-output build/ablation-summary.json'
```

### Python

```bash
python3 -m py --mode ablation --config config/poc.json --ablation-output build/ablation-summary.json
```

Outputs:
- `build/ablation-summary.json` — side-by-side comparison.
- Event logs for both runs (`id-on` and `id-off` suffixes).

## Tests

```bash
gradle test
```
