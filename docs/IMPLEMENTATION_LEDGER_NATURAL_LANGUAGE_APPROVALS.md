# Implementation Ledger: Natural-Language Approvals

## Status
- Current phase: Phase 3
- Overall status: in_progress
- Spec: frozen
- Implementation plan: frozen

## Completed Work
- Created frozen implementation plan document.
- Created mutable execution ledger.
- Added approval runtime config domain and loader support.
- Added approval-interpreter LLM role routing/config support with `approval_interpreter`.
- Added admin approval subsystem under `src/main/kotlin/ai/neopsyke/admin/approvals/`:
  - request/audit models
  - SQLite approval store
  - deterministic + LLM-backed approval interpreter
  - approval runtime / staging hook
- Enforced blocked-root scheduling in `AttentionScheduler` via thread-state gating.
- Refactored approval-backed staging so Ego no longer emits the old approval fallback response path.
- Added Ego-facing approval hook integration:
  - external approval executed
  - external approval denied
  - staged-approval callback into admin runtime
- Added owner-ingress interception hooks for:
  - dashboard chat
  - Telegram update processing
  - Telegram webhook bridge
- Added legacy dashboard action-control mutation hook so dashboard fallback actions still resolve the approval runtime state cleanly.
- Added session-recording support for approval flow through a dedicated `approval_flow` record/replay channel.
- Wired approval runtime into interactive app assembly:
  - approval broker creation
  - dashboard / Telegram router hookup
  - startup Telegram ACK dispatch
  - expiry monitor loop
  - approval-interpreter client wiring in the interactive runtime
- Updated runtime logic docs:
  - `AGENT_LOGIC_SUMMARY.md`
  - `AGENT_LOGIC_DIAGRAM.md`
- Added/updated tests for:
  - blocked-root scheduling
  - approval runtime approve / deny-and-reissue
  - approval-flow session replay
  - config loading for approvals and approval-interpreter routing
  - scenario pack expectations for approval-backed goal staging

## Remaining Work
- Complete the remaining Phase 3 validation from the frozen plan:
  - Freud record/replay e2e coverage for this flow
  - interactive record/replay e2e coverage with approval replies
  - recorded low-llm live evals
- Confirm whether freud-live mode also needs explicit approval-interpreter client wiring or whether interactive/runtime coverage is sufficient for the current scope.
- Review whether any additional Ego/integration tests should assert the new approval-hook behavior directly instead of the legacy fallback wording.

## Validation
- `./gradlew compileKotlin compileTestKotlin`
- `./gradlew test --tests 'ai.neopsyke.agent.AttentionSchedulerTest' --tests 'ai.neopsyke.admin.approvals.ApprovalRuntimeTest' --tests 'ai.neopsyke.config.LlmRuntimeConfigLoaderTest' --tests 'ai.neopsyke.config.AgentRuntimeSettingsLoaderTest' --tests 'ai.neopsyke.dashboard.DashboardServerTest' --tests 'ai.neopsyke.integrations.telegram.TelegramWebhookBridgeTest' --tests 'ai.neopsyke.integrations.telegram.TelegramPollingBridgeTest' --tests 'ai.neopsyke.session.RecordingActionControlServiceTest'`
- `./gradlew test --tests 'ai.neopsyke.admin.approvals.ApprovalRuntimeTest'`
- `./gradlew test`
- `./freud/bin/freud run signoff-gate`
- Latest Freud signoff-gate run:
  - run dir: `.neopsyke/runs/freud/20260404T004328Z-signoff-gate-2894239959`
  - summary: `.neopsyke/runs/freud/20260404T004328Z-signoff-gate-2894239959/artifacts/summary.json`

## Blockers
- None currently.

## Deviations From Frozen Plan
- None so far.

## Handoff Notes
- Keep this ledger as the only mutable project-level record for this feature.
- Do not edit the spec or implementation plan after implementation has started.
- The main unresolved work is now validation breadth, not core architecture.
- Approval-flow session replay is implemented through `SessionRecordingManager.approvalFlow`; replay hashes must stay bound to stable staged-action identity, not runtime-generated approval-request ids.
- A scenario-pack regression surfaced after approval-backed staging stopped emitting the old fallback explanation output; the scenario was updated to assert approval-hook capture instead.
