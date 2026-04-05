# Natural-Language Approvals: Review & Gap Analysis (Updated)

> Reviewed: 2026-04-04 (updated after implementation pass)
>
> Source of truth: code on branch `feat/natural-language-approvals`
>
> Spec reference: `docs/specs/NATURAL_LANGUAGE_APPROVALS.md`

---

## 1. Acceptance Criteria Status

### 18.1 Architecture and boundary criteria -- PASS

All seven sub-criteria are met:

1. Approval handling lives in `admin/approvals/` as a distinct control-plane
   subsystem, completely outside Ego.
2. Both `ChatRuntimeBridge.submitMessage()` and
   `TelegramUpdateProcessor` route owner replies through
   `approvalRuntime.routeOwnerMessage()` before normal sensory ingress.
3. The interception path is channel-agnostic via `ApprovalRuntime` +
   `ApprovalChannelResolver`.
4. Liveness/deliverability abstracted behind `ApprovalChannelStatusProvider`
   with per-channel implementations.
5. `ActionControlService` remains the durable source of truth for staged
   actions and authorization.
6. `recordThreadBlocked()` in `ActionReviewPipeline:179` calls
   `cognitiveThreads.markBlocked()`. The `AttentionScheduler` skips blocked
   roots via `isBlocked` lambda on every dequeue.
7. Classifier uses `LlmRoleLabels.APPROVAL_INTERPRETER` and the standard
   cognitive-role config pattern in `LlmRuntimeConfig`.

### 18.2 Scheduling and thread-state criteria -- PASS

All six sub-criteria are met.

### 18.3 Conversation approval queue criteria -- PASS

All five sub-criteria are met.

### 18.4 Decision-classification criteria -- PASS

All seven sub-criteria are met.

### 18.5 Approval/denial behavior criteria -- PASS

All seven sub-criteria are met.

### 18.6 Admin-only explanatory surface criteria -- PASS

All six sub-criteria are met.

### 18.7 Channel-routing criteria -- PASS

All five sub-criteria are met.

### 18.8 Dashboard-specific criteria -- PASS

All three sub-criteria are met.

### 18.9 Telegram-specific criteria -- PASS

All six sub-criteria are met:

1. Telegram NL approval delivery and resolution implemented.
2. Eligibility based on success/failure tracking.
3. Deliverability != human attention.
4. `sendTelegramStartupAckIfEnabled()` implemented in `ApprovalRuntime:295`
   and called from `AppModeRunners.kt:1319` during integration startup.
   Tested in `ApprovalTelegramChannelTest`.
5. Startup ACK counts as outbound-readiness evidence only.
6. `lastPromptDeliveryDetail` captures native metadata.

### 18.10 Expiry and clarification criteria -- PASS

All six sub-criteria are met.

### 18.11 Security and audit criteria -- PASS

All seven sub-criteria are met:

1. Requires live approval request artifact.
2. Bound to specific staged action.
3. Approver/channel identity validated.
4. Does not bypass action control.
5. Provenance fields present: `OwnerMessageEnvelope` now has
   `originApprovalRequestId`, `originStagedActionId`,
   `originApprovalSource`. The reissued message also carries provenance
   through `ConversationContext.attributes` (approval_request_id,
   approval_staged_action_id, approval_reissue, approval_prompt_instance_id).
   Tested in `ApprovalUnitGapsTest` and `ApprovalIntegrationTest`.
6. Records sufficient for reconstruction.
7. Approval-flow recording channel + replay tested.

### 18.12 Backward-path and rollout criteria -- PASS

All four sub-criteria are met.

### 18.13 Required test depth -- PASS

All four test layers are now present:

1. Unit tests: `ApprovalRuntimeTest` (14), `ApprovalInterpreterTest` (5),
   `ApprovalUnitGapsTest` (7).
2. Integration tests: `ApprovalIntegrationTest` (16 scenarios).
3. E2E deterministic runtime tests: `ApprovalE2ETest` (8 scenarios).
4. Channel-specific tests: `ApprovalDashboardChannelTest` (10),
   `ApprovalTelegramChannelTest` (10).

### 18.14 Required unit test coverage -- PASS

All 19 required unit test scenarios are covered across
`ApprovalRuntimeTest`, `ApprovalInterpreterTest`, and `ApprovalUnitGapsTest`.

### 18.15 Required integration test coverage -- PASS

All 16 integration scenarios covered in `ApprovalIntegrationTest`.

### 18.16 Required end-to-end runtime test coverage -- PASS

All 8 e2e scenarios covered in `ApprovalE2ETest`.

### 18.17 Required channel-specific test coverage -- PASS

10 per-channel scenarios covered for both dashboard
(`ApprovalDashboardChannelTest`) and Telegram
(`ApprovalTelegramChannelTest`).

### 18.18 Residual quality bar -- PASS

Implementation is architecturally isolated, channel-agnostic, fail-closed,
durably auditable, aligned with LLM role-configuration architecture, and
verified at all required test levels.

---

## 2. Previously Missing Items: Resolution Status

### 2.1 SUPERSEDED status -- RESOLVED

`SUPERSEDED` added to `ApprovalRequestStatus` enum. Marked as terminal in
`isTerminal()`. Available for explicit request replacement tracking.

### 2.2 Telegram startup ACK logic -- RESOLVED (was false positive)

`sendTelegramStartupAckIfEnabled()` was already implemented at
`ApprovalRuntime:295` and called from `AppModeRunners.kt:1319`. The
original review did not find the call site. Now tested in
`ApprovalTelegramChannelTest`.

### 2.3 Reissued-message provenance -- RESOLVED

`OwnerMessageEnvelope` now has `originApprovalRequestId`,
`originStagedActionId`, and `originApprovalSource` fields. The
`forwardReissued` path already carried provenance via
`ConversationContext.attributes`. Both paths are tested.

### 2.4 Test coverage gaps -- RESOLVED

All four required test layers are now present:

- Unit tests: 26 tests across 3 files
- Integration tests: 16 scenarios in `ApprovalIntegrationTest`
- E2E tests: 8 scenarios in `ApprovalE2ETest`
- Channel-specific tests: 20 scenarios (10 dashboard + 10 telegram)
- RecordingActionControlService: 2 approval-specific tests added

---

## 3. Validation Results

| Gate | Status | Detail |
|------|--------|--------|
| `./gradlew compileKotlin compileTestKotlin` | PASS | Zero warnings |
| `./gradlew test` | PASS | All tests pass |
| `./freud/bin/freud run signoff-gate` | PASS | 4/4 steps |
| `./freud/bin/freud test-freud-replay` | PASS | 100% hit rate, 0 divergences |
| `./freud/bin/freud run signoff-gate --live --lane low-llm` | PASS | 6/6 steps, 24/24 BBH cases |
