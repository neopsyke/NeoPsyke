# Prompt Budget Run Diagnostics

Operational instructions for using prompt-budget diagnostics during normal user runs via `./run-neopsyke.sh`.

## Quick Commands
Start run:
```bash
./run-neopsyke.sh
```

Live prompt-budget events:
```bash
tail -F .neopsyke/logs/latest-events.jsonl | jq -c 'select(.type=="prompt_budget_allocation") | {ts:.tsIso,call_site:.data.call_site,degradation_path:.data.degradation_path,single_message_fallback:.data.single_message_fallback,floor_violation_count:.data.floor_violation_count,dropped_section_count:.data.dropped_section_count}'
```

Live grounding-gate events:
```bash
tail -F .neopsyke/logs/latest-events.jsonl | jq -c 'select(.type=="grounding_gate_review") | {ts:.tsIso,allow:.data.allow,reason_code:.data.reason_code,grounding_required:.data.grounding_required,evidence_gathered:.data.evidence_gathered,evidence_unavailable:.data.evidence_unavailable}'
```

Prompt budget and grounding-gate telemetry are computed automatically during `freud eval` runs.

Dashboard snapshot:
```bash
curl -s http://127.0.0.1:8787/api/obs/snapshot | jq '.promptBudgetStats, .groundingGateStats'
```

## Purpose
Use this guide to monitor and tune prompt-budget pressure in real runs without digging manually through raw JSONL.

## Prerequisites
- Run the agent through `./run-neopsyke.sh`.
- Ensure `jq` is installed for filtering and aggregation.

## Runtime outputs created by launcher
For each run, the launcher writes:
- `.neopsyke/logs/latest-events.jsonl` (symlink to current run event sidecar)
- `.neopsyke/logs/latest-run.env` (contains `NEOPSYKE_EVENT_LOG_FILE`, `NEOPSYKE_LOG_RUN_ID`, and related metadata)

## 1) Start a run
```bash
./run-neopsyke.sh
```

## 2) Live monitor prompt-budget events
In another terminal:
```bash
tail -F .neopsyke/logs/latest-events.jsonl | jq -c '
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

## 3) Live monitor grounding gate (complementary)
```bash
tail -F .neopsyke/logs/latest-events.jsonl | jq -c '
  select(.type=="grounding_gate_review") |
  {
    ts: .tsIso,
    allow: .data.allow,
    reason_code: .data.reason_code,
    grounding_required: .data.grounding_required,
    evidence_gathered: .data.evidence_gathered,
    evidence_unavailable: .data.evidence_unavailable
  }'
```

## 4) Post-run aggregate summaries

Prompt budget and grounding-gate telemetry are computed automatically during `freud eval` runs and written to the run artifacts directory.

## 5) Dashboard checks
Open dashboard:
- `http://127.0.0.1:8787/dashboard`

Or query snapshot directly:
```bash
curl -s http://127.0.0.1:8787/api/obs/snapshot | jq '.promptBudgetStats, .groundingGateStats'
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
1. Identify affected `call_site` (`planner_prompt`, `superego_prompt`, `meta_reasoner_prompt`, `legacy_web_search_prompt`).
2. Reduce `floorTokens` for lower-criticality sections first.
3. Reclassify sections from `REQUIRED_CONTEXT` to `OPTIONAL` when safe.
4. Increase prompt budget only if structural tuning is insufficient.

## Related docs
- `PROMPT_BUDGET_TUNING_GUIDE.md`
- `TASK_VERIFIER_TUNING_GUIDE.md`
