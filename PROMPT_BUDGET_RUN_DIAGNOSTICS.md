# Prompt Budget Run Diagnostics

Operational instructions for using prompt-budget diagnostics during normal user runs via `./run-psyke.sh`.

## Quick Commands
Start run:
```bash
./run-psyke.sh
```

Live prompt-budget events:
```bash
tail -F .psyke/logs/latest-events.jsonl | jq -c 'select(.type=="prompt_budget_allocation") | {ts:.tsIso,call_site:.data.call_site,degradation_path:.data.degradation_path,single_message_fallback:.data.single_message_fallback,floor_violation_count:.data.floor_violation_count,dropped_section_count:.data.dropped_section_count}'
```

Live task-verifier events:
```bash
tail -F .psyke/logs/latest-events.jsonl | jq -c 'select(.type=="task_verifier_review") | {ts:.tsIso,allow:.data.allow,reason_code:.data.reason_code,intent:.data.intent_category,volatility:.data.volatility_level}'
```

Aggregate latest run:
```bash
freud/scripts/prompt-budget-telemetry.sh
freud/scripts/task-verifier-telemetry.sh
```

Aggregate exact run file:
```bash
source .psyke/logs/latest-run.env
freud/scripts/prompt-budget-telemetry.sh "$PSYKE_EVENT_LOG_FILE"
freud/scripts/task-verifier-telemetry.sh "$PSYKE_EVENT_LOG_FILE"
```

Dashboard snapshot:
```bash
curl -s http://127.0.0.1:8787/api/obs/snapshot | jq '.promptBudgetStats, .taskVerifierStats'
```

## Purpose
Use this guide to monitor and tune prompt-budget pressure in real runs without digging manually through raw JSONL.

## Prerequisites
- Run the agent through `./run-psyke.sh`.
- Ensure `jq` is installed for filtering and aggregation.

## Runtime outputs created by launcher
For each run, the launcher writes:
- `.psyke/logs/latest-events.jsonl` (symlink to current run event sidecar)
- `.psyke/logs/latest-run.env` (contains `PSYKE_EVENT_LOG_FILE`, `PSYKE_LOG_RUN_ID`, and related metadata)

## 1) Start a run
```bash
./run-psyke.sh
```

## 2) Live monitor prompt-budget events
In another terminal:
```bash
tail -F .psyke/logs/latest-events.jsonl | jq -c '
  select(.type=="prompt_budget_allocation") |
  {
    ts: .tsIso,
    call_site: .data.call_site,
    max_tokens: .data.max_tokens,
    allocated_total_cost: .data.allocated_total_cost,
    reserved_floor_cost: .data.reserved_floor_cost,
    degradation_path: .data.degradation_path,
    single_message_fallback: .data.single_message_fallback,
    floor_violation_count: .data.floor_violation_count,
    dropped_section_count: .data.dropped_section_count
  }'
```

## 3) Live monitor task verifier (complementary)
```bash
tail -F .psyke/logs/latest-events.jsonl | jq -c '
  select(.type=="task_verifier_review") |
  {
    ts: .tsIso,
    allow: .data.allow,
    reason_code: .data.reason_code,
    intent: .data.intent_category,
    volatility: .data.volatility_level,
    requires_external_evidence: .data.requires_external_evidence
  }'
```

## 4) Post-run aggregate summaries
Prompt budget summary:
```bash
freud/scripts/prompt-budget-telemetry.sh
```

Task verifier summary:
```bash
freud/scripts/task-verifier-telemetry.sh
```

Both scripts default to `.psyke/logs/latest-events.jsonl`.

## 5) Analyze an exact run file
Use this when `latest` may have moved to another run:
```bash
source .psyke/logs/latest-run.env
freud/scripts/prompt-budget-telemetry.sh "$PSYKE_EVENT_LOG_FILE"
freud/scripts/task-verifier-telemetry.sh "$PSYKE_EVENT_LOG_FILE"
```

## 6) Dashboard checks
Open dashboard:
- `http://127.0.0.1:8787/dashboard`

Or query snapshot directly:
```bash
curl -s http://127.0.0.1:8787/api/obs/snapshot | jq '.promptBudgetStats, .taskVerifierStats'
```

## Prompt-budget tuning signals
Primary alerts:
- `single_message_fallback == true`
- `floor_violation_count > 0`

Secondary pressure indicators:
- high `dropped_section_count`
- frequent `degradation_path` containing `trim_required_context` or `trim_required_core`

## Fast triage actions
If severe pressure appears (`single_message_fallback` or floor violations):
1. Identify affected `call_site` (`planner_prompt`, `action_verifier_prompt`, `superego_prompt`, `legacy_web_search_prompt`).
2. Reduce `floorTokens` for lower-criticality sections first.
3. Reclassify sections from `REQUIRED_CONTEXT` to `OPTIONAL` when safe.
4. Increase prompt budget only if structural tuning is insufficient.

## Related docs
- `PROMPT_BUDGET_TUNING_GUIDE.md`
- `TASK_VERIFIER_TUNING_GUIDE.md`
