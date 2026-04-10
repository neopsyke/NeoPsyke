# Planner Flow Diagram

Current planner architecture as of 2026-04-10.

## Signal-to-Planner Pipeline

External events enter through `SensoryCortex`, get classified by
`StimulusIngressCoordinator`, queued in `AttentionScheduler`, and dispatched
by `Ego` to the planner. The planner returns an `EgoDecision` which the
`DecisionDispatcher` converts into scheduled work (thoughts, intentions, or
actions).

```mermaid
flowchart TB
    subgraph sources ["Signal Sources"]
        user["User input<br/>(dashboard / telegram)"]
        timer["TimerScheduler<br/>(cron wake)"]
        id_mod["Id module<br/>(drive impulse)"]
        action_fb["Action execution<br/>(feedback cue)"]
        deferred["Deferred thought<br/>(from prior iteration)"]
    end

    subgraph ingress ["StimulusIngressCoordinator"]
        classify{"Classify signal"}
        classify -->|GoalRuntimeCue| enq_goal["enqueueGoalWork"]
        classify -->|IdImpulseCue| enq_impulse["bindImpulseWake"]
        classify -->|ActionFeedbackCue| enq_feedback["enqueueFeedback"]
        classify -->|User input| enq_input["enqueueInput"]
    end

    subgraph scheduler ["AttentionScheduler"]
        opp_q["Opportunity queue<br/>(priority-ranked)"]
        int_q["Intention queue<br/>(deferred thoughts)"]
        act_q["Action queue"]
        opp_q & int_q & act_q --> next["nextTask()"]
    end

    user --> classify
    timer --> classify
    id_mod --> classify
    action_fb --> classify

    enq_goal --> opp_q
    enq_impulse --> opp_q
    enq_feedback --> opp_q
    enq_input --> opp_q
    deferred --> int_q

    next -->|"LoopTask.AttendOpportunity"| ego_opp
    next -->|"LoopTask.ProcessIntention"| ego_int
    next -->|"LoopTask.PerformAction"| ego_act

    subgraph ego ["Ego runLoop()"]
        ego_opp["processOpportunity()"]
        ego_int["processIntention()"]
        ego_act["processAction()"]

        ego_opp -->|"OpportunityTrigger.Input"| proc_input["processInput()"]
        ego_opp -->|"OpportunityTrigger.Impulse"| proc_impulse["processImpulse()"]
        ego_opp -->|"OpportunityTrigger.Feedback"| proc_feedback["processActionFeedback()"]
        ego_opp -->|"OpportunityTrigger.GoalWork"| proc_goal["processGoalWork()"]

        ego_int -->|"kind = DEFER"| proc_deferred["processDeferredIntention()"]
        ego_int -->|"kind != DEFER"| ego_act
    end
```

## HierarchicalEgoPlanner (L0 Router)

Each `Ego.process*()` method creates an `EgoTrigger` and calls
`planner.decide(trigger, context)`. The `HierarchicalEgoPlanner` dispatches
to one of five L1 lanes based on the trigger type. This is deterministic
routing on typed runtime facts -- no text inspection.

```mermaid
flowchart TB
    decide["HierarchicalEgoPlanner.decide()"]

    decide -->|"EgoTrigger.IncomingInput"| L1_input["InputPlanner<br/><b>L1</b>"]
    decide -->|"EgoTrigger.DeferredIntention"| L1_deferred["DeferredStepPlanner<br/><b>L1</b>"]
    decide -->|"EgoTrigger.ActionFeedback"| L1_feedback["FeedbackPlanner<br/><b>L1</b>"]
    decide -->|"EgoTrigger.GoalWork"| L1_goal["GoalWorkPlanner<br/><b>L1</b>"]
    decide -->|"EgoTrigger.IncomingImpulse"| L1_impulse["ImpulsePlanner<br/><b>L1</b>"]
```

### Trigger-to-Lane Mapping

| Trigger | Origin | L1 Lane | LaneId |
|---------|--------|---------|--------|
| `IncomingInput` | User (dashboard, telegram) | InputPlanner | `INPUT_INTENT_ROUTER` |
| `DeferredIntention` | Internal queue (prior iteration) | DeferredStepPlanner | `DEFERRED_STEP` |
| `ActionFeedback` | Motor cortex (execution result) | FeedbackPlanner | `FEEDBACK` |
| `GoalWork` | Goal cue (cron, step advance) | GoalWorkPlanner | `GOAL_WORK` |
| `IncomingImpulse` | Id module (drive activation) | ImpulsePlanner | `IMPULSE` |

## InputPlanner (L1) -- Two-Stage Routing

InputPlanner is the only L1 lane with a sub-routing stage. It runs two
sequential LLM classifiers then dispatches to one of five L2 sub-planners.

```mermaid
flowchart TB
    input["InputPlanner.plan()"]

    input --> router["InputIntentRouter<br/>(LLM classifier)"]

    router --> route{"InputRoute?"}

    route -->|DirectResponse| grounding
    route -->|GeneralAction| grounding
    route -->|MultiStepTask| grounding
    route -->|GoalCreation| grounding_skip["Skip grounding<br/>(NOT_REQUIRED)"]
    route -->|GoalManagement| grounding_skip
    route -->|ClarificationNeeded| inline_clarify["Inline:<br/>FormIntention(CONTACT_USER)<br/>ask clarification question"]
    route -->|Noop| inline_noop["Inline:<br/>EgoDecision.Noop"]

    grounding["GroundingClassifier<br/>(LLM if ambiguous)"]
    grounding --> grounded_ctx["Grounded context +<br/>metadata propagated"]

    grounding_skip --> grounded_ctx

    grounded_ctx --> dispatch{"Dispatch to<br/>L2 sub-planner"}

    dispatch -->|DirectResponse| L2_direct["DirectResponsePlanner"]
    dispatch -->|GeneralAction| L2_general["GeneralActionPlanner"]
    dispatch -->|MultiStepTask| L2_task["TaskDecompositionPlanner"]
    dispatch -->|GoalCreation| L2_goalcreate["GoalCreationPlanner"]
    dispatch -->|GoalManagement| L2_goalmgmt["GoalManagementPlanner"]

    L2_goalmgmt -->|"returns Noop?"| L2_general_fb["Fallback to<br/>GeneralActionPlanner"]

    L2_direct --> decision
    L2_general --> decision
    L2_task --> decision
    L2_goalcreate --> decision
    L2_goalmgmt -->|"returns non-Noop"| decision
    L2_general_fb --> decision

    inline_clarify --> decision
    inline_noop --> decision

    decision["EgoDecision"]
```

### InputIntentRouter Routes

The router is an LLM classifier that maps free-text user input to one of
seven typed routes. Dispatch from route to sub-planner is deterministic on
the typed result.

| Route | L2 Sub-Planner | Grounding | Typical Decision |
|-------|---------------|-----------|------------------|
| `DirectResponse` | DirectResponsePlanner | LLM-classified | `EnqueueThought` or `FormIntention(CONTACT_USER)` |
| `GeneralAction` | GeneralActionPlanner | LLM-classified | `FormIntention` (any action type) |
| `MultiStepTask` | TaskDecompositionPlanner | LLM-classified | `EnqueuePlan` |
| `GoalCreation` | GoalCreationPlanner | NOT_REQUIRED | `FormIntention(CONTACT_USER)` with goal params |
| `GoalManagement` | GoalManagementPlanner | NOT_REQUIRED | `FormIntention(CONTACT_USER)` with goal op |
| `ClarificationNeeded` | (inline) | NOT_REQUIRED | `FormIntention(CONTACT_USER)` |
| `Noop` | (inline) | NOT_REQUIRED | `EgoDecision.Noop` |

### GroundingClassifier

Determines whether the input requires fresh external evidence before a
response can be delivered.

- **Deterministic NOT_REQUIRED**: GoalCreation, GoalManagement, ClarificationNeeded, Noop
- **Requires LLM classification**: DirectResponse, GeneralAction, MultiStepTask

Result is propagated as `GroundingMetadata` on the input envelope and the
planner context, consumed downstream by the grounding gate in
`ActionReviewPipeline`.

## L1 Lane Decision Capabilities

Each L1 lane parses an LLM response into one of the `EgoDecision` variants.
Not all lanes support all decision types.

| EgoDecision | InputPlanner | DeferredStep | Feedback | GoalWork | Impulse |
|-------------|:---:|:---:|:---:|:---:|:---:|
| `EnqueueThought` (defer) | via L2 | yes | yes | yes | yes |
| `FormIntention` (intend) | via L2 | yes | yes | yes | yes |
| `EnqueuePlan` (plan) | via L2 | yes | yes | yes | **no** |
| `Noop` (noop) | via L2 | yes | yes | yes | yes |

## Post-Planner Pipeline

After the planner returns an `EgoDecision`, the Ego applies meta-reasoning
pressure and dispatches the decision.

```mermaid
flowchart TB
    planner["Planner.decide()"] --> decision["EgoDecision"]

    decision --> meta["DeliberationEngine<br/>maybeAssessAndUpdateGuidance()"]
    meta -->|"Meta-reasoner fires"| assessment["MetaReasonerAssessment"]
    meta -->|"Not due"| override

    assessment --> override["maybeApplyPressureOverride()"]

    override -->|"FINALIZE_NOW +<br/>non-FormIntention"| forced["Enqueue forced<br/>terminal CONTACT_USER<br/>(isForcedTerminal=true)"]
    override -->|"REQUEST_TOOL_THEN_FINALIZE +<br/>non-FormIntention"| soft["Replace with<br/>EnqueueThought(HIGH)<br/>(soft hint)"]
    override -->|"FormIntention or<br/>no override needed"| passthrough["Pass through unchanged"]

    forced --> dispatch
    soft --> dispatch
    passthrough --> dispatch

    dispatch["DecisionDispatcher.dispatch()"]

    dispatch -->|EnqueueThought| enq_thought["enqueueDeferredIntention()<br/>-> scheduler thought queue"]
    dispatch -->|FormIntention| form["Validate contract<br/>-> enqueue QueuedIntention"]
    dispatch -->|EnqueuePlan| plan_steps["Decompose into<br/>deferred step thoughts"]
    dispatch -->|Noop| noop_enq["Re-enqueue as<br/>deferred thought<br/>(unless parseFailure)"]

    form --> next_loop["Next runLoop() iteration"]
    enq_thought --> next_loop
    plan_steps --> next_loop
    noop_enq --> next_loop

    next_loop --> action_review["processAction() via<br/>ActionReviewPipeline"]

    action_review --> grounding_gate["Grounding Gate<br/>(evidence check)"]
    grounding_gate --> superego["Superego Review<br/>(authorization)"]
    superego --> action_control["Action Control<br/>(staging / auto-commit)"]
    action_control --> execute["MotorCortex.execute()"]

    execute --> feedback_cue["ActionFeedbackCue<br/>(back to scheduler)"]

    subgraph safety_net ["Safety Net (post-task)"]
        force_terminal["maybeForceTerminalAnswer()<br/>(backstop for extreme thresholds)"]
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
    participant HP as HierarchicalPlanner
    participant L1 as L1 Lane
    participant L2 as L2 Sub-Planner
    participant DE as DeliberationEngine
    participant DD as DecisionDispatcher
    participant ARP as ActionReviewPipeline
    participant MC as MotorCortex

    U->>SC: Send message
    SC->>SIC: StimulusReceived
    SIC->>AS: enqueueInput(opportunity)
    AS->>Ego: nextTask() -> AttendOpportunity

    Ego->>HP: decide(IncomingInput, context)
    HP->>L1: InputPlanner.plan()
    L1->>L1: InputIntentRouter.route() [LLM]
    L1->>L1: GroundingClassifier.classify()
    L1->>L2: e.g. DirectResponsePlanner.plan() [LLM]
    L2-->>L1: EgoDecision
    L1-->>HP: EgoDecision
    HP-->>Ego: EgoDecision

    Ego->>DE: maybeApplyPressureOverride()
    DE-->>Ego: (possibly overridden) EgoDecision

    Ego->>DD: dispatch(decision)
    DD->>AS: enqueueIntention / enqueueThought / enqueueAction

    Note over Ego,AS: runLoop continues draining scheduler

    AS->>Ego: nextTask() -> ProcessIntention or PerformAction

    alt Intention -> Action
        Ego->>ARP: reviewAndExecute(action)
        ARP->>ARP: Grounding Gate
        ARP->>ARP: Superego Review [LLM]
        ARP->>ARP: Action Control (stage/commit)
        ARP->>MC: execute(action)
        MC-->>ARP: ActionOutcome
        ARP-->>Ego: cleanup if CONTACT_USER
    else Deferred Intention
        Ego->>HP: decide(DeferredIntention, context)
        HP->>L1: DeferredStepPlanner.plan() [LLM]
        L1-->>HP: EgoDecision
        HP-->>Ego: EgoDecision
        Ego->>DD: dispatch(decision)
    end

    Note over Ego: Loop until scheduler empty or step limit reached
    Ego->>DE: maybeForceTerminalAnswer() [safety net, each iteration]
```

## Circuit Breaker Coverage

Each lane tracks consecutive parse failures per `rootInputId`. When the
circuit opens, the lane returns `Noop(parseFailureShortCircuit = true)` which
bypasses the normal noop re-enqueue and goes directly to fallback
explanation.

| LaneId | Circuit Breaker |
|--------|:-:|
| `INPUT_INTENT_ROUTER` | no (delegates to L2) |
| `DIRECT_RESPONSE` | no |
| `GENERAL_ACTION` | yes |
| `TASK_DECOMPOSITION` | no |
| `GOAL_CREATION` | no |
| `GOAL_MANAGEMENT` | no |
| `DEFERRED_STEP` | yes |
| `FEEDBACK` | yes |
| `GOAL_WORK` | yes |
| `IMPULSE` | yes |
| `GROUNDING_CLASSIFIER` | no |

## File Index

| Component | File |
|-----------|------|
| `Ego.Planner` interface | `agent/ego/Ego.kt:103-110` |
| `HierarchicalEgoPlanner` | `agent/ego/planner/HierarchicalEgoPlanner.kt` |
| `PlannerLane` interface | `agent/ego/planner/PlannerLane.kt` |
| `LaneId` enum | `agent/ego/planner/LaneId.kt` |
| `InputPlanner` | `agent/ego/planner/lane/InputPlanner.kt` |
| `DeferredStepPlanner` | `agent/ego/planner/lane/DeferredStepPlanner.kt` |
| `FeedbackPlanner` | `agent/ego/planner/lane/FeedbackPlanner.kt` |
| `GoalWorkPlanner` | `agent/ego/planner/lane/GoalWorkPlanner.kt` |
| `ImpulsePlanner` | `agent/ego/planner/lane/ImpulsePlanner.kt` |
| `InputIntentRouter` | `agent/ego/planner/input/InputIntentRouter.kt` |
| `GroundingClassifier` | `agent/ego/planner/input/GroundingClassifier.kt` |
| `DirectResponsePlanner` | `agent/ego/planner/input/DirectResponsePlanner.kt` |
| `GeneralActionPlanner` | `agent/ego/planner/input/GeneralActionPlanner.kt` |
| `TaskDecompositionPlanner` | `agent/ego/planner/input/TaskDecompositionPlanner.kt` |
| `GoalCreationPlanner` | `agent/ego/planner/input/GoalCreationPlanner.kt` |
| `GoalManagementPlanner` | `agent/ego/planner/input/GoalManagementPlanner.kt` |
| `InputRoute` sealed interface | `agent/ego/planner/model/InputRoute.kt` |
| `EgoTrigger` sealed interface | `agent/model/CognitionModels.kt:127-133` |
| `EgoDecision` sealed interface | `agent/model/CognitionModels.kt:135-164` |
| `DeliberationEngine` | `agent/ego/DeliberationEngine.kt` |
| `DecisionDispatcher` | `agent/ego/DecisionDispatcher.kt` |
| `ActionReviewPipeline` | `agent/ego/ActionReviewPipeline.kt` |
