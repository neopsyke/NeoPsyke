# Feature Brief

## Goal
- Describe the feature outcome in one sentence.

## Acceptance Checks
- [ ] Deterministic tests to pass
- [ ] Reasoning logic eval to pass
- [ ] No new critical anomalies in triage output

## Constraints
- Keep changes scoped.
- Keep runtime behavior unchanged unless explicitly requested.

## File Scope
- Files expected to change:

## Workflow
1. Implement smallest useful slice.
2. Run: `freud/scripts/feature-loop.sh <feature-id>`
3. Read:
   - `.neopsyke/runs/freud/<run>/artifacts/summary.json`
   - `.neopsyke/runs/freud/<run>/artifacts/anomalies.md`
4. Fix only failing segments.
5. Re-run loop.
