# Planner Flow Diagram

Current planner architecture as of 2026-04-18.

This file describes the runtime planner flow implemented under
`src/main/kotlin/ai/neopsyke/agent/ego/planner/**`.

## Signal-to-Planner Pipeline

External events enter through `SensoryCortex`, are routed by
`StimulusIngressCoordinator`, queued in `AttentionScheduler`, and dispatched by
`Ego` to the planner. The planner returns an `EgoDecision`, which
`DecisionDispatcher` turns into queued continuations, queued intentions, plan
step continuations, or fallback explanation behavior.

```mermaid
flowchart TB
    subgraph sources ["Signal Sources"]
        user["User input<br/>(dashboard / telegram)"]
        timer["TimerScheduler<br/>(assignment wake)"]
        id_mod["Id module<br/>(drive impulse)"]
        action_fb["Action execution<br/>(feedback cue)"]
        deferred["Queued continuation<br/>(from prior iteration)"]
    end

    subgraph ingress ["StimulusIngressCoordinator"]
        classify{"Classify signal"}
        classify -->|Assignment cue| enq_assignment["enqueueAssignment"]
        classify -->|Id impulse cue| enq_impulse["bindImpulseWake"]
        classify -->|ActionFeedbackCue| enq_feedback["enqueueFeedback"]
        classify -->|User input| enq_input["enqueueInput"]
    end

    subgraph scheduler ["AttentionScheduler"]
        opp_q["Opportunity queue<br/>(priority-ranked)"]
        cont_q["Continuation queue"]
        int_q["Intention queue"]
        act_q["Action queue"]
        opp_q & cont_q & int_q & act_q --> next["nextTask()"]
    end

    user --> classify
    timer --> classify
    id_mod --> classify
    action_fb --> classify

    enq_assignment --> opp_q
    enq_impulse --> opp_q
    enq_feedback --> opp_q
    enq_input --> opp_q
    deferred --> cont_q

    next -->|"LoopTask.AttendOpportunity"| ego_opp
    next -->|"LoopTask.ProcessContinuation"| ego_cont
    next -->|"LoopTask.ProcessIntention"| ego_int
    next -->|"LoopTask.PerformAction"| ego_act

    subgraph ego ["Ego runLoop()"]
        ego_opp["processOpportunity()"]
        ego_cont["processContinuation()"]
        ego_int["processIntention()"]
        ego_act["processAction()"]

        ego_opp -->|"OpportunityTrigger.Input"| proc_input["processInput()"]
        ego_opp -->|"OpportunityTrigger.Impulse"| proc_impulse["processImpulse()"]
        ego_opp -->|"OpportunityTrigger.Feedback"| proc_feedback["processActionFeedback()"]
        ego_opp -->|"OpportunityTrigger.Assignment"| proc_assignment["processAssignment()"]

        ego_cont --> proc_deferred["processContinuation()"]
        ego_int --> ego_act
    end
```

## HierarchicalEgoPlanner (L0 Router)

Each `Ego.process*()` method creates an `EgoTrigger` and calls
`planner.decide(trigger, context)`. `HierarchicalEgoPlanner` deterministically
routes on the sealed trigger type. It does not inspect free text.

```mermaid
flowchart TB
    decide["HierarchicalEgoPlanner.decide()"]

    decide -->|"EgoTrigger.IncomingInput"| L1_input["InputPlanner<br/><b>L1</b>"]
    decide -->|"EgoTrigger.Continuation"| L1_progression["ProgressionPlanner<br/><b>L1</b>"]
    decide -->|"EgoTrigger.ActionFeedback"| L1_progression
    decide -->|"EgoTrigger.Assignment"| L1_assignment["AssignmentLanePlanner<br/><b>L1</b>"]
    decide -->|"EgoTrigger.IncomingImpulse"| L1_impulse["ImpulsePlanner<br/><b>L1</b>"]
```

### Trigger-to-Lane Mapping

| Trigger | Origin | L1 Lane | LaneId |
|---------|--------|---------|--------|
| `IncomingInput` | User / external chat ingress | `InputPlanner` | `INPUT_INTENT_ROUTER` |
| `Continuation` | Internal queue | `ProgressionPlanner` | `PROGRESSION` |
| `ActionFeedback` | Motor/action result cue | `ProgressionPlanner` | `PROGRESSION` |
| `Assignment` | Durable-work wake / step activation | `AssignmentLanePlanner` | `ASSIGNMENT` |
| `IncomingImpulse` | Id drive activation | `ImpulsePlanner` | `IMPULSE` |

## InputPlanner (L1) -- Router, Grounding, L2 Dispatch

`InputPlanner` is the only L1 lane with an internal semantic routing stage. It:

1. Calls `InputIntentRouter`
2. Always calls `GroundingClassifier`
3. Dispatches to the selected L2 planner or handles inline clarification/noop

```mermaid
flowchart TB
    input["InputPlanner.plan()"]

    input --> router["InputIntentRouter<br/>(LLM classifier)"]
    router --> route{"InputRoute"}

    route --> grounding["GroundingClassifier"]

    grounding --> grounded_ctx["GroundingMetadata propagated to<br/>PendingInput and PlannerContext"]

    grounded_ctx --> dispatch{"Dispatch"}

    dispatch -->|DirectResponse| L2_direct["DirectResponder"]
    dispatch -->|GeneralAction| L2_general["GeneralActionPlanner"]
    dispatch -->|MultiStepTask| L2_task["TaskDecompositionPlanner"]
    dispatch -->|Assignment| L2_assignment["AssignmentCommandBuilder<br/>(generic / recurrent_task / responsibility)"]

    route -->|ClarificationNeeded| inline_clarify["Inline FormIntention<br/>(CONTACT_USER)"]
    route -->|Noop| inline_noop["Inline Noop"]

    L2_assignment -->|"returns Noop"| L2_general_fb["Fallback to<br/>GeneralActionPlanner"]

    L2_direct --> decision
    L2_general --> decision
    L2_task --> decision
    L2_assignment -->|"returns non-Noop"| decision
    L2_general_fb --> decision
    inline_clarify --> decision
    inline_noop --> decision

    decision["EgoDecision"]
```

### InputIntentRouter Routes

`InputIntentRouter` maps free-text user input to typed routes. The router may
also set an assignment target for the `Assignment` route.

| Route | Target / Meaning | L2 handling | Typical result |
|-------|------------------|-------------|----------------|
| `DirectResponse` | Answer directly from current context | `DirectResponder` | `FormIntention(CONTACT_USER)` or `Noop` |
| `GeneralAction` | One explicit next action | `GeneralActionPlanner` | `FormIntention(...)` or `Noop` |
| `MultiStepTask` | Ordered multi-stage task | `TaskDecompositionPlanner` | `EnqueuePlan` or `Noop` |
| `Assignment` | Persistent work interaction | `AssignmentCommandBuilder` | `FormIntention(ASSIGNMENT_OPERATION)` or `FormIntention(CONTACT_USER)` or `Noop` |
| `ClarificationNeeded` | Route ambiguity or missing user intent | inline | `FormIntention(CONTACT_USER)` |
| `Noop` | No actionable intent | inline | `Noop` |

For `Assignment`, `assignment_target` is one of:

- `generic`
- `recurrent_task`
- `responsibility`

### GroundingClassifier

`GroundingClassifier` runs after route selection and before L2 dispatch.

- Deterministic `NOT_REQUIRED` prefilter:
  - `InputRoute.Assignment`
  - `InputRoute.ClarificationNeeded`
  - `InputRoute.Noop`
- LLM classification:
  - `InputRoute.DirectResponse`
  - `InputRoute.GeneralAction`
  - `InputRoute.MultiStepTask`

The result is propagated onto the grounded `PendingInput` and copied into
`PlannerContext.groundingMetadata`.

## L1 Lane Decision Capabilities

Planner lanes do not currently emit `EgoDecision.EnqueueContinuation`
themselves. That shape is introduced later by deliberation-pressure overrides.

| EgoDecision | InputPlanner | ProgressionPlanner | AssignmentLanePlanner | ImpulsePlanner |
|-------------|:-----------:|:------------------:|:----------------------:|:-------------:|
| `FormIntention` | via L2 | yes | yes | yes |
| `EnqueuePlan` | via L2 | yes | yes | no |
| `Noop` | via L2 | yes | yes | yes |
| `EnqueueContinuation` | no | no | no | no |

### L2 Input Sub-Planner Capabilities

| L2 planner | Returns |
|-----------|---------|
| `DirectResponder` | `FormIntention(CONTACT_USER)` or `Noop` |
| `GeneralActionPlanner` | `FormIntention(...)` or `Noop` |
| `TaskDecompositionPlanner` | `EnqueuePlan` or `Noop` |
| `AssignmentCommandBuilder` | `FormIntention(ASSIGNMENT_OPERATION)` or `FormIntention(CONTACT_USER)` or `Noop` |

## Post-Planner Pipeline

After `planner.decide(...)`, `Ego` lets `DeliberationEngine` apply pressure
overrides, then passes the final `EgoDecision` to `DecisionDispatcher`.

```mermaid
flowchart TB
    planner["Planner.decide()"] --> decision["EgoDecision"]

    decision --> meta["DeliberationEngine<br/>pressure override path"]
    meta -->|"FINALIZE_NOW + non-FormIntention"| forced["Forced terminal<br/>FormIntention(CONTACT_USER)"]
    meta -->|"REQUEST_TOOL_THEN_FINALIZE + non-FormIntention"| soft["Synthetic<br/>EnqueueContinuation(HIGH)"]
    meta -->|"No override"| passthrough["Pass through unchanged"]

    forced --> dispatch
    soft --> dispatch
    passthrough --> dispatch

    dispatch["DecisionDispatcher.dispatch()"]

    dispatch -->|EnqueueContinuation| enq_thought["enqueueContinuation()<br/>continuation queue"]
    dispatch -->|FormIntention| form["Validate contract<br/>enqueue QueuedIntention"]
    dispatch -->|EnqueuePlan| plan_steps["Refine plan<br/>dedup / budget gates<br/>enqueue plan-step continuations"]
    dispatch -->|Noop parseFailureShortCircuit=true| short_circuit["enqueueFallbackExplanation()"]
    dispatch -->|Noop normal| noop_record["Record noop<br/>optional fallback explanation"]

    form --> next_loop["Next runLoop() iteration"]
    enq_thought --> next_loop
    plan_steps --> next_loop

    next_loop --> action_review["processAction() via<br/>ActionReviewPipeline"]

    action_review --> grounding_gate["Grounding Gate"]
    grounding_gate --> superego["Superego Review"]
    superego --> action_control["Action Control"]
    action_control --> execute["MotorCortex.execute()"]

    execute --> feedback_cue["ActionFeedbackCue<br/>(back into scheduler)"]

    subgraph safety_net ["Safety Net"]
        force_terminal["maybeForceTerminalAnswer()"]
    end

    next_loop -.-> force_terminal
```

## Full End-to-End Flow (Single Input)

```mermaid
sequenceDiagram
    participant U as User
    participant SC as SensoryCortex
    participant SIC as StimulusIngress
    participant AS as AttentionScheduler
    participant Ego as Ego.runLoop()
    participant HP as HierarchicalEgoPlanner
    participant IP as InputPlanner
    participant GC as GroundingClassifier
    participant L2 as L2 Input Planner
    participant DE as DeliberationEngine
    participant DD as DecisionDispatcher
    participant ARP as ActionReviewPipeline
    participant MC as MotorCortex

    U->>SC: Send message
    SC->>SIC: StimulusReceived
    SIC->>AS: enqueueInput(opportunity)
    AS->>Ego: nextTask() -> AttendOpportunity

    Ego->>HP: decide(IncomingInput, context)
    HP->>IP: InputPlanner.plan()
    IP->>IP: InputIntentRouter.route() [LLM]
    IP->>GC: classify(route, trigger, context)
    GC-->>IP: GroundingMetadata
    IP->>L2: selected L2 planner [LLM]
    L2-->>IP: EgoDecision
    IP-->>HP: EgoDecision
    HP-->>Ego: EgoDecision

    Ego->>DE: maybeApplyPressureOverride()
    DE-->>Ego: final EgoDecision

    Ego->>DD: dispatch(decision)
    DD->>AS: enqueue intention / enqueue continuation / enqueue plan steps / fallback

    Note over Ego,AS: runLoop continues draining scheduler

    AS->>Ego: nextTask() -> ProcessIntention or PerformAction

    alt Intention -> Action
        Ego->>ARP: reviewAndExecute(action)
        ARP->>ARP: grounding gate
        ARP->>ARP: superego review
        ARP->>ARP: action control
        ARP->>MC: execute(action)
        MC-->>ARP: ActionOutcome
    else Continuation
        Ego->>HP: decide(Continuation, context)
        HP->>HP: ProgressionPlanner.plan() [LLM]
        HP-->>Ego: EgoDecision
        Ego->>DD: dispatch(decision)
    end

    Note over Ego: Loop until scheduler empty or step limit reached
```

## Circuit Breaker Coverage

Only some planner lanes use the per-root parse-failure circuit breaker in
`PlannerRuntime`.

| LaneId | Circuit breaker |
|--------|:---------------:|
| `INPUT_INTENT_ROUTER` | no |
| `DIRECT_RESPONSE` | no |
| `GENERAL_ACTION` | yes |
| `TASK_DECOMPOSITION` | no |
| `ASSIGNMENT_GENERIC` | no |
| `ASSIGNMENT_RECURRENT_TASK` | no |
| `ASSIGNMENT_RESPONSIBILITY` | no |
| `PROGRESSION` | yes |
| `ASSIGNMENT` | yes |
| `IMPULSE` | yes |
| `GROUNDING_CLASSIFIER` | no |
| `PLAN_REFINER` | no |

When a circuit-backed lane trips, it returns
`Noop(parseFailureShortCircuit = true)`, which sends the flow directly to the
fallback-explanation path instead of normal noop handling.

## File Index

| Component | File |
|-----------|------|
| `HierarchicalEgoPlanner` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/HierarchicalEgoPlanner.kt` |
| `PlannerLane` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/PlannerLane.kt` |
| `LaneId` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/LaneId.kt` |
| `InputPlanner` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/InputPlanner.kt` |
| `ProgressionPlanner` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/ProgressionPlanner.kt` |
| `AssignmentLanePlanner` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/AssignmentLanePlanner.kt` |
| `ImpulsePlanner` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/lane/ImpulsePlanner.kt` |
| `InputIntentRouter` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/InputIntentRouter.kt` |
| `GroundingClassifier` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GroundingClassifier.kt` |
| `DirectResponder` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/DirectResponder.kt` |
| `GeneralActionPlanner` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/GeneralActionPlanner.kt` |
| `TaskDecompositionPlanner` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/TaskDecompositionPlanner.kt` |
| `AssignmentCommandBuilder` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/input/AssignmentCommandBuilder.kt` |
| `InputRoute` | `src/main/kotlin/ai/neopsyke/agent/ego/planner/model/InputRoute.kt` |
| `EgoTrigger` / `EgoDecision` | `src/main/kotlin/ai/neopsyke/agent/model/CognitionModels.kt` |
| `DecisionDispatcher` | `src/main/kotlin/ai/neopsyke/agent/ego/DecisionDispatcher.kt` |
| `DeliberationEngine` | `src/main/kotlin/ai/neopsyke/agent/ego/DeliberationEngine.kt` |
| `ActionReviewPipeline` | `src/main/kotlin/ai/neopsyke/agent/ego/ActionReviewPipeline.kt` |
