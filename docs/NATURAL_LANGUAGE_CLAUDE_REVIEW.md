# Natural-Language Approvals: Review & Gap Analysis

> Reviewed: 2026-04-04
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

All six sub-criteria are met:

1. Root marked blocked on approval creation (`ActionReviewPipeline:179`).
2. `AttentionScheduler.nextTask()` checks `isBlocked` before returning work.
3. Blocking is per-root; other roots unaffected.
4. `EgoAgentTest:2381` validates unblocking on terminal state.
5. Scheduler checks blocked state at dequeue time, not enqueue time.
6. `transitionRequest()` validates expected status atomically.

### 18.3 Conversation approval queue criteria -- PASS

All five sub-criteria are met:

1. One active (PENDING) prompt per session enforced; extras go to QUEUED.
2. QUEUED requests are persisted durably.
3. Only PENDING triggers prompt delivery.
4. `routeOwnerMessage` resolves only the active request.
5. Prompt-instance binding + explicit ref after refresh prevents stale
   resolution.

### 18.4 Decision-classification criteria -- PASS

All seven sub-criteria are met:

1. Five outcomes: APPROVE, DENY, DENY_AND_REISSUE, UNCLEAR, EXPLAIN.
2. `ApprovalInterpreter` tries deterministic exact-match before LLM.
3. LLM returns only allowed enum values via schema-enforced output.
4. Input capped at 400 chars (reply) and 240 chars (summaries).
5. Falls to UNCLEAR on any ambiguity.
6. Schema-enforced output, retries, required-field validation,
   fallback=UNCLEAR.
7. Unicode/whitespace/case/punctuation canonicalization before parsing.

### 18.5 Approval/denial behavior criteria -- PASS

All seven sub-criteria are met. Tested in `ApprovalRuntimeTest`.

### 18.6 Admin-only explanatory surface criteria -- PASS

All six sub-criteria are met:

1. EXPLAIN classification triggers metadata-only response.
2. `ApprovalExplanationView` renders from staged-action metadata.
3. No Ego, no planning, no tool use, no external lookups.
4. Approval remains pending after explanation.
5. Allowlisted fields in `ApprovalExplanationView`.
6. URL, localhost, UUID, and token redaction tested.

### 18.7 Channel-routing criteria -- PASS

All five sub-criteria are met:

1. Conversation-origin routes to originating channel.
2. Non-conversation uses `DefaultApprovalChannelResolver`.
3. Liveness, deliverability, and YAML-configurable defaults/priority.
4. Explicit deliverable/live distinction; no assumption of human attention.
5. Fail-closed with "unrouted" provider when no eligible channel.

### 18.8 Dashboard-specific criteria -- PASS

All three sub-criteria are met:

1. CSS class `.msg.assistant.approval` with amber styling.
2. `dashboardRequiresLiveSubscriber` checks SSE subscription presence.
3. Dashboard not open/receiving does not win non-conversation routing.

### 18.9 Telegram-specific criteria -- PARTIAL

| # | Status  | Notes |
|---|---------|-------|
| 1 | PASS    | Telegram NL approval delivery and resolution implemented |
| 2 | PASS    | Eligibility based on success/failure tracking |
| 3 | PASS    | Deliverability != human attention |
| 4 | **GAP** | `telegramStartupAckEnabled` config exists but startup ACK send logic not confirmed in Telegram integration startup path |
| 5 | PASS    | By design |
| 6 | PASS    | `lastPromptDeliveryDetail` captures native metadata |

### 18.10 Expiry and clarification criteria -- PASS

All six sub-criteria are met:

1. `ttlMs` in `ApprovalRuntimeConfig`, YAML-configurable.
2. Default = `5 * 60 * 1000L` (5 minutes).
3. Expiry transitions to terminal EXPIRED state, denies staged action.
4. Expiry notice delivered to owner channel.
5. `clarificationTurns` defaults to 2.
6. Exhausted clarifications fail closed.

### 18.11 Security and audit criteria -- MOSTLY PASS

| # | Status  | Notes |
|---|---------|-------|
| 1 | PASS    | Requires live approval request artifact |
| 2 | PASS    | Bound to specific staged action |
| 3 | PASS    | Approver/channel identity validated |
| 4 | PASS    | Does not bypass action control |
| 5 | **GAP** | See missing provenance detail below |
| 6 | PASS    | Records sufficient for reconstruction |
| 7 | PASS    | Approval-flow recording channel + replay tested |

**Missing provenance detail (18.11.5):** The spec requires "provenance fields
on reissued owner messages linking back to the approval request and staged
action." The `ApprovalRequest` tracks `forwardedOwnerReplyRaw` and
`forwardedOwnerSource`, but it is unclear whether the reissued
`OwnerMessageEnvelope` that enters normal ingress carries back-link metadata
(approval request ID, staged action ID) on the sensory input itself.

### 18.12 Backward-path and rollout criteria -- PASS

All four sub-criteria are met.

### 18.13-18.17 Test coverage -- SIGNIFICANT GAPS

This is the largest area of incomplete work.

---

## 2. Missing Items: Detail

### 2.1 Missing approval state: SUPERSEDED

The spec (section 12.1) defines eight approval request states including
`SUPERSEDED`. The code uses `QUEUED`, `PENDING`, `APPROVED`, `DENIED`,
`DENIED_AND_REISSUED`, `EXPIRED` -- six states total. The `SUPERSEDED` terminal
state is absent.

Currently, supersession is handled implicitly through expiry or queue
management, but there is no distinct state for an older approval request that
was explicitly replaced by a newer one for the same staged action.

The spec also lists `PENDING_PROMPT`, `AWAITING_OWNER_REPLY`,
`AWAITING_CLARIFICATION`, and `ANSWERED_METADATA_ONLY` as distinct states. The
code collapses these into `PENDING` with internal tracking (e.g.,
`clarificationCount`). This is a reasonable simplification that preserves
semantic correctness.

### 2.2 Telegram startup ACK logic

`ApprovalRuntimeConfig.telegramStartupAckEnabled` exists as a config flag, but
the actual logic to send a control-plane ACK message to the configured verified
owner chat during Telegram integration startup was not found in the integration
startup path.

### 2.3 Reissued-message provenance on ingress side

When `DENY_AND_REISSUE` forwards the raw owner text into normal ingress, the
`ApprovalRequest` record captures `forwardedOwnerReplyRaw` and
`forwardedOwnerSource`. However, the forwarded `OwnerMessageEnvelope` entering
sensory input may not carry back-link fields (approval request ID, staged action
ID) that would allow downstream audit to connect the ingressed message to its
originating approval flow without cross-referencing timestamps.

### 2.4 Test coverage gaps

The spec requires four test layers (18.13): unit, integration, end-to-end
deterministic runtime, and channel-specific. Only the first layer is partially
present.

#### 2.4.1 Unit tests -- PARTIAL (18.14)

Existing: `ApprovalRuntimeTest` (14 tests) + `ApprovalInterpreterTest` (5
tests).

**Missing required unit test scenarios:**

| 18.14.# | Scenario | Status |
|----------|----------|--------|
| 7  | Blocked-root scheduler suppression | Exists in `EgoAgentTest` but not in approval-specific unit tests |
| 12 | Channel-eligibility resolver policy ordering | Tested indirectly in routing scenarios but no isolated unit test for priority/fallback ordering |
| 13 | Approval-classifier runtime config loading and default-model selection | No test verifying config loads `gpt-5-nano` default or that role routing resolves correctly |
| 18 | Reissued-message provenance field generation | Tested indirectly but no explicit assertion on provenance fields |

#### 2.4.2 Integration tests -- MISSING (18.15)

No dedicated integration tests exist. The spec requires 16 integration test
scenarios covering full pipeline flows:

1. Staged action -> approval request creation -> prompt routing
2. Approve path -> action-control authorize
3. Deny path -> action-control deny
4. `DENY_AND_REISSUE` path -> deny first, then fresh owner ingress
5. Explanatory question path -> metadata answer, approval remains pending
6. Expiry path -> terminal deny + visible expiry notice
7. One-active-approval-per-conversation enforcement with multiple staged actions
8. Unrelated roots continuing while blocked root is suspended
9. Non-conversation-origin routing through eligible channel selection
10. Fail-closed behavior when no eligible owner channel exists
11. Approval-classifier routing follows configured role/model path
12. Provider-native delivery evidence captured and persisted when available
13. Prompt-instance binding enforced
14. Approval resolution remains terminal exactly once across competing paths
15. Reissued owner messages carry approval-origin provenance
16. Freud record/replay captures and replays approval and denial outcomes

#### 2.4.3 End-to-end deterministic runtime tests -- MISSING (18.16)

No scenario-pack or deterministic runtime tests exist. The spec requires 8 e2e
scenarios:

1. Conversation-origin staged action approved via NL in dashboard chat
2. Conversation-origin staged action denied via NL in dashboard chat
3. Modification reply becomes `DENY_AND_REISSUE` + fresh owner message
4. Blocked thread does not continue while awaiting approval
5. Unrelated thread progresses during blocked approval
6. Same semantics hold for at least one non-dashboard channel (Telegram)
7. Fallback classifier runs through configured role/model path
8. Approval/denial recorded and replayed through Freud without divergence

#### 2.4.4 Channel-specific tests -- MISSING (18.17)

No per-channel test suites exist. The spec requires 10 test scenarios per
supported channel (dashboard and Telegram):

1. Approval prompt delivery
2. Pending approval reply interception before normal Ego ingress
3. Natural-language approval resolution
4. Natural-language denial resolution
5. Explanatory question behavior
6. Expiry notice delivery
7. Channel-specific liveness/eligibility semantics used by approval router
8. Optional Telegram startup ACK behavior and outbound-readiness state effect
9. Provider-native delivery/acceptance metadata capture when available
10. Duplicate inbound-event handling and idempotent terminal resolution

#### 2.4.5 RecordingActionControlService test gap

`RecordingActionControlServiceTest` contains no approval-related tests. The
`RecordingActionControlService` records `user_approval` events, but no tests
verify that NL approval/denial flows produce correct Freud-compatible recording
entries.

---

## 3. Suggestions

### 3.1 Add SUPERSEDED status

Add `SUPERSEDED` to `ApprovalRequestStatus` in `ApprovalModels.kt`. Use it when
a newer approval request for the same staged action explicitly replaces an older
one. This provides a clear audit trail distinguishing "replaced by newer
request" from "timed out."

### 3.2 Implement Telegram startup ACK

In the Telegram integration startup path (likely `TelegramUpdateProcessor` init
or the integration bootstrap), add logic that sends a short control-plane
message to the configured verified owner chat when `telegramStartupAckEnabled`
is true. Record the delivery result as outbound-readiness evidence in the
channel status provider.

### 3.3 Add provenance back-links on reissued messages

When `DENY_AND_REISSUE` forwards the raw owner reply into normal ingress,
attach metadata fields to the `OwnerMessageEnvelope` or sensory input entry:
- `originApprovalRequestId`
- `originStagedActionId`
- `originApprovalSource = "deny_and_reissue"`

This enables downstream audit without timestamp-based cross-referencing.

### 3.4 Write integration tests (highest priority)

Create `src/test/kotlin/ai/neopsyke/admin/approvals/ApprovalIntegrationTest.kt`
covering the 16 scenarios listed in section 2.4.2. These should wire up
`ApprovalRuntime` with a real `SqliteApprovalStore`, a mock
`ActionControlService`, and mock channel delivery sinks to validate full
pipeline flows.

### 3.5 Write e2e deterministic runtime tests

Create scenario-pack style tests (or use the existing Freud infrastructure) to
exercise the 8 scenarios in section 2.4.3. These should use the actual
`AttentionScheduler`, `CognitiveThreadStore`, and `ActionReviewPipeline` to
validate runtime-level behavior.

### 3.6 Write channel-specific test suites

Create `ApprovalDashboardChannelTest.kt` and
`ApprovalTelegramChannelTest.kt` covering the 10 per-channel scenarios in
section 2.4.4. These should test actual channel adapter behavior including
message interception, delivery, and channel-specific liveness semantics.

### 3.7 Add missing unit tests

Fill the gaps identified in section 2.4.1:
- Isolated channel-eligibility resolver ordering test
- Approval-classifier config loading test (verify default model, verify
  role-routing resolution)
- Explicit reissued-message provenance assertion
- Blocked-root scheduler suppression in approval-specific test context

### 3.8 Add RecordingActionControlService approval tests

Add tests to `RecordingActionControlServiceTest` verifying that NL
approval/denial through `ApprovalRuntime` produces correct `user_approval`
recording entries compatible with Freud record/replay.

---

## 4. Priority Order

| Priority | Item | Effort | Impact |
|----------|------|--------|--------|
| 1 | Integration tests (3.4) | High | Validates full pipeline; blocks production trust |
| 2 | Channel-specific tests (3.6) | Medium | Required by spec; validates per-channel correctness |
| 3 | E2E runtime tests (3.5) | High | Validates scheduling + approval interaction at runtime level |
| 4 | Missing unit tests (3.7) | Low | Fills coverage gaps |
| 5 | RecordingActionControlService tests (3.8) | Low | Validates Freud compatibility |
| 6 | Reissued-message provenance (3.3) | Low | Audit completeness |
| 7 | SUPERSEDED status (3.1) | Low | Spec alignment; currently handled implicitly |
| 8 | Telegram startup ACK (3.2) | Low | Config exists but logic may be missing |
