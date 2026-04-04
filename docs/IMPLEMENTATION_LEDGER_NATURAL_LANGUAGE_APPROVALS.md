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
- Follow-up hardening after Review #1:
  - added explicit prompt-instance ids, duplicate inbound-event suppression, and provider/channel/principal binding on approval replies
  - added action-hash verification before approval authorization
  - changed expiry and clarification exhaustion to deny through action control before terminal approval resolution
  - persisted unrouted fail-closed approval artifacts when no eligible owner channel is available
  - added `DENIED_AND_REISSUED` terminal state plus approval provenance attributes on reissued owner input
  - introduced shared approval channel resolver logic with default-channel fallback semantics
  - recorded approval prompt delivery outcome/detail on approval requests and in audit
  - tightened approval interpreter fallback so model classification no longer emits `EXPLAIN`
  - made dashboard approval prompts visually distinct in the chat UI
  - fixed Review #2 findings:
    - strengthened deterministic explanation detection and moved explanation rendering onto an explicit allowlisted/redacted metadata view
    - required explicit approval-ref binding for refreshed prompts and rejected stale prompt refs before control-plane resolution
    - resolved blocked roots out of `BLOCKED` on terminal control-plane denials so scheduler suppression ends correctly
    - introduced delivery-evidence-backed channel status tracking so Telegram non-conversation routing no longer assumes liveness from config alone
  - added/updated tests for:
    - refreshed prompt approval-ref binding
    - explanation redaction for ids/tokens/localhost
    - fail-closed non-conversation Telegram routing without delivery evidence
    - Telegram eligibility after startup ACK delivery evidence
    - terminal approval denial unblocking in Ego

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
- `./gradlew --no-daemon test --tests 'ai.neopsyke.admin.approvals.ApprovalRuntimeTest' --tests 'ai.neopsyke.admin.approvals.ApprovalInterpreterTest' --tests 'ai.neopsyke.agent.AttentionSchedulerTest' --tests 'ai.neopsyke.config.AgentRuntimeSettingsLoaderTest' --tests 'ai.neopsyke.config.LlmRuntimeConfigLoaderTest' --tests 'ai.neopsyke.dashboard.DashboardServerTest' --tests 'ai.neopsyke.integrations.telegram.TelegramWebhookBridgeTest' --tests 'ai.neopsyke.integrations.telegram.TelegramPollingBridgeTest' --tests 'ai.neopsyke.session.RecordingActionControlServiceTest'`
- `./gradlew --no-daemon compileKotlin compileTestKotlin`
- `./freud/bin/freud run signoff-gate`
- Latest Freud signoff-gate run:
  - run dir: `.neopsyke/runs/freud/20260404T012711Z-signoff-gate-2541921993`
  - summary: `.neopsyke/runs/freud/20260404T012711Z-signoff-gate-2541921993/artifacts/summary.json`
- `./gradlew --no-daemon test --tests 'ai.neopsyke.admin.approvals.ApprovalRuntimeTest' --tests 'ai.neopsyke.admin.approvals.ApprovalInterpreterTest' --tests 'ai.neopsyke.agent.EgoAgentTest'`
- `./gradlew --no-daemon test --tests 'ai.neopsyke.admin.approvals.ApprovalRuntimeTest' --tests 'ai.neopsyke.admin.approvals.ApprovalInterpreterTest' --tests 'ai.neopsyke.agent.AttentionSchedulerTest' --tests 'ai.neopsyke.agent.EgoAgentTest' --tests 'ai.neopsyke.config.AgentRuntimeSettingsLoaderTest' --tests 'ai.neopsyke.config.LlmRuntimeConfigLoaderTest' --tests 'ai.neopsyke.dashboard.DashboardServerTest' --tests 'ai.neopsyke.integrations.telegram.TelegramWebhookBridgeTest' --tests 'ai.neopsyke.integrations.telegram.TelegramPollingBridgeTest'`
- `./freud/bin/freud run signoff-gate`
- Latest Freud signoff-gate run:
  - run dir: `.neopsyke/runs/freud/20260404T015645Z-signoff-gate-3848765970`
  - summary: `.neopsyke/runs/freud/20260404T015645Z-signoff-gate-3848765970/artifacts/summary.json`

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

## Review #1
- Acceptance criteria have not yet been reached.
- Confirmed strengths:
  - approval handling is implemented outside Ego
  - dashboard and Telegram owner ingress intercept before normal sensory ingress
  - blocked-root scheduling exists
  - approval-interpreter routing is wired through the normal LLM role configuration path
- Findings against code as source of truth:
  - approval replies are not explicitly bound to the request target provider/channel/principal before resolution
  - approval resolution does not explicitly verify the stored action hash before authorization
  - expiry marks approval requests terminal but does not deny the staged action through action control or notify Ego through the normal denial path
  - if no eligible approval channel exists, staging returns early without creating a durable fail-closed approval artifact
  - duplicate inbound approval replies after terminal resolution can fall through to normal sensory ingress as fresh owner instructions
  - `DENY_AND_REISSUE` has no dedicated terminal state or durable provenance on the reissued owner input
  - prompt-instance binding is only a timestamp heuristic, not a durable prompt-instance identity check
  - channel routing/liveness is still hardcoded in `ApprovalRuntime` rather than abstracted behind a shared resolver contract
  - `defaultChannel` is loaded from config but not used in routing semantics
  - provider-native delivery metadata is not durably captured for approval prompts
  - the fallback classifier allows `explain`, which is broader than the spec’s fallback decision surface
  - canonicalization and the explanatory metadata view are both weaker than the spec requires
  - test coverage is missing for expiry, stale prompt binding, duplicate delivery, no-channel fail-closed behavior, explanation flow, non-conversation-origin routing, and reissue provenance
- Immediate fix direction:
  - harden approval request binding and terminal-once semantics in the store/runtime
  - make expiry and no-channel cases fail closed through action control
  - add durable reissue provenance and duplicate suppression
  - extract channel resolution/delivery evidence behind a clearer contract
  - tighten interpreter/explainer behavior and expand tests to the missing acceptance cases

## Review #2
- Findings raised in review:
  - explanatory question detection and redaction were weaker than the spec
  - terminal approval denials left roots permanently blocked
  - Telegram non-conversation routing assumed liveness without delivery evidence
  - prompt-instance binding only used a timestamp heuristic
- Fixes implemented:
  - deterministic explanation detection now preserves question-form signals and the explanation path renders from an explicit allowlisted/redacted approval explanation view
  - terminal control-plane denials now resolve the blocked root out of `BLOCKED` and emit a dedicated thread update
  - channel routing now uses a shared status provider; Telegram eligibility for non-conversation-origin approvals depends on recorded outbound delivery evidence rather than configuration alone
  - refreshed approval prompts now require the current approval ref and stale prompt refs are rejected before classification
- Current status relative to Review #2:
  - all four findings were addressed in code and covered by new or expanded tests
