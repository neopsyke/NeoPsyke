import uuid
from collections import deque
from dataclasses import dataclass

from .config import EgoConfig
from .cortex import MotorCortex
from .event_logger import EventLogger, RuntimeEvent
from .model import (
    ActionProposal,
    ActionType,
    IdImpulse,
    ImpulseFeedback,
    ImpulseResult,
    OriginSource,
    ThoughtStrategy,
    ThoughtTask,
    UserRequest,
)
from .superego import DeterministicSuperego


class EgoPlanner:
    def plan_for_user_request(self, user_request: UserRequest) -> list[ThoughtTask]:
        raise NotImplementedError

    def plan_for_impulse(self, impulse: IdImpulse) -> list[ThoughtTask]:
        raise NotImplementedError

    def propose_action(self, thought_task: ThoughtTask) -> ActionProposal | None:
        raise NotImplementedError


class DeterministicEgoPlanner(EgoPlanner):
    def __init__(self, config: EgoConfig) -> None:
        self._config = config

    def plan_for_user_request(self, user_request: UserRequest) -> list[ThoughtTask]:
        return [ThoughtTask(
            thought_id=str(uuid.uuid4()),
            root_impulse_id=None,
            need_name=None,
            origin=OriginSource.USER,
            content=f"Respond to user request: {user_request.content}",
            strategy=ThoughtStrategy.USER_REQUEST_BRANCH,
        )]

    def plan_for_impulse(self, impulse: IdImpulse) -> list[ThoughtTask]:
        tasks: list[ThoughtTask] = []
        if self._config.include_noop_thought_branch:
            tasks.append(ThoughtTask(
                thought_id=str(uuid.uuid4()),
                root_impulse_id=impulse.root_impulse_id,
                need_name=impulse.need_name,
                origin=OriginSource.ID,
                content=f"Evaluate conservative option for need {impulse.need_name}",
                strategy=ThoughtStrategy.NOOP_BRANCH,
            ))

        execution_branch_count = (
            max(1, self._config.parallel_thoughts_per_impulse - len(tasks))
            if self._config.parallel_thoughts_per_impulse > 0
            else 1
        )

        for _ in range(execution_branch_count):
            tasks.append(ThoughtTask(
                thought_id=str(uuid.uuid4()),
                root_impulse_id=impulse.root_impulse_id,
                need_name=impulse.need_name,
                origin=OriginSource.ID,
                content=f"Evaluate executable option for need {impulse.need_name}",
                strategy=ThoughtStrategy.EXECUTION_BRANCH,
            ))

        return tasks

    def propose_action(self, thought_task: ThoughtTask) -> ActionProposal | None:
        if thought_task.strategy == ThoughtStrategy.NOOP_BRANCH:
            return None

        if thought_task.strategy == ThoughtStrategy.USER_REQUEST_BRANCH:
            return ActionProposal(
                action_id=str(uuid.uuid4()),
                root_impulse_id=None,
                need_name=None,
                origin=thought_task.origin,
                type=ActionType.CONTACT_USER,
                summary="Deliver direct response to user request.",
                payload=thought_task.content.removeprefix("Respond to user request: "),
            )

        action_type_map = {
            "interact_with_user": ActionType.CONTACT_USER,
            "learn_something": ActionType.WEB_SEARCH,
        }
        action_type = action_type_map.get(thought_task.need_name or "", ActionType.REFLECT_INTERNAL)

        payload_map = {
            ActionType.REFLECT_INTERNAL: f"Generate an internal plan for need {thought_task.need_name}.",
            ActionType.WEB_SEARCH: f"Research one useful fact related to need {thought_task.need_name}.",
            ActionType.CONTACT_USER: f"Proactively contact the user about need {thought_task.need_name}.",
        }

        return ActionProposal(
            action_id=str(uuid.uuid4()),
            root_impulse_id=thought_task.root_impulse_id,
            need_name=thought_task.need_name,
            origin=thought_task.origin,
            type=action_type,
            summary="Action candidate generated from impulse branch.",
            payload=payload_map[action_type],
        )


@dataclass
class _ImpulseLifecycleState:
    need_name: str
    pending_thought_count: int
    pending_action_count: int
    had_executed_action: bool


class ImpulseLifecycleTracker:
    def __init__(self) -> None:
        self._lifecycles: dict[str, _ImpulseLifecycleState] = {}

    def start(self, root_impulse_id: str, need_name: str, initial_thought_count: int) -> None:
        self._lifecycles[root_impulse_id] = _ImpulseLifecycleState(
            need_name=need_name,
            pending_thought_count=initial_thought_count,
            pending_action_count=0,
            had_executed_action=False,
        )

    def register_action(self, root_impulse_id: str) -> ImpulseFeedback | None:
        state = self._lifecycles.get(root_impulse_id)
        if state is None:
            return None
        state.pending_action_count += 1
        return self._finalize_if_possible(root_impulse_id, state)

    def complete_thought(self, root_impulse_id: str) -> ImpulseFeedback | None:
        state = self._lifecycles.get(root_impulse_id)
        if state is None:
            return None
        state.pending_thought_count = max(0, state.pending_thought_count - 1)
        return self._finalize_if_possible(root_impulse_id, state)

    def complete_action(self, root_impulse_id: str, executed: bool) -> ImpulseFeedback | None:
        state = self._lifecycles.get(root_impulse_id)
        if state is None:
            return None
        state.pending_action_count = max(0, state.pending_action_count - 1)
        state.had_executed_action = state.had_executed_action or executed
        return self._finalize_if_possible(root_impulse_id, state)

    def force_deny_all(self) -> list[ImpulseFeedback]:
        feedback = [
            ImpulseFeedback(
                root_impulse_id=rid,
                need_name=state.need_name,
                result=ImpulseResult.DENIED,
            )
            for rid, state in self._lifecycles.items()
        ]
        self._lifecycles.clear()
        return feedback

    def _finalize_if_possible(self, root_impulse_id: str, state: _ImpulseLifecycleState) -> ImpulseFeedback | None:
        if state.pending_thought_count > 0 or state.pending_action_count > 0:
            return None

        del self._lifecycles[root_impulse_id]
        result = ImpulseResult.ACCEPTED if state.had_executed_action else ImpulseResult.DENIED
        return ImpulseFeedback(
            root_impulse_id=root_impulse_id,
            need_name=state.need_name,
            result=result,
        )


@dataclass
class EgoProcessingResult:
    impulse_feedback: list[ImpulseFeedback]
    actions_proposed: int
    actions_denied_by_superego: int
    actions_executed: int


class Ego:
    def __init__(
        self,
        planner: EgoPlanner,
        superego: DeterministicSuperego,
        motor_cortex: MotorCortex,
        event_logger: EventLogger,
    ) -> None:
        self._planner = planner
        self._superego = superego
        self._motor_cortex = motor_cortex
        self._event_logger = event_logger
        self._thought_queue: deque[ThoughtTask] = deque()
        self._action_queue: deque[ActionProposal] = deque()
        self._lifecycle_tracker = ImpulseLifecycleTracker()

    def submit_user_request(self, tick: int, user_request: UserRequest) -> None:
        tasks = self._planner.plan_for_user_request(user_request)
        self._thought_queue.extend(tasks)
        self._event_logger.log(RuntimeEvent(
            tick=tick,
            type="ego_user_request_queued",
            attributes={"content": user_request.content, "thought_count": len(tasks)},
        ))

    def submit_impulse(self, tick: int, impulse: IdImpulse) -> None:
        tasks = self._planner.plan_for_impulse(impulse)
        self._lifecycle_tracker.start(
            root_impulse_id=impulse.root_impulse_id,
            need_name=impulse.need_name,
            initial_thought_count=len(tasks),
        )
        self._thought_queue.extend(tasks)
        self._event_logger.log(RuntimeEvent(
            tick=tick,
            type="ego_impulse_queued",
            attributes={
                "root_impulse_id": impulse.root_impulse_id,
                "need_name": impulse.need_name,
                "thought_count": len(tasks),
            },
        ))

    def process_all_pending(self, tick: int) -> EgoProcessingResult:
        feedback: list[ImpulseFeedback] = []
        actions_proposed = 0
        actions_denied_by_superego = 0
        actions_executed = 0

        while self._thought_queue or self._action_queue:
            while self._thought_queue:
                thought = self._thought_queue.popleft()
                self._event_logger.log(RuntimeEvent(
                    tick=tick,
                    type="ego_thought_processing",
                    attributes={
                        "thought_id": thought.thought_id,
                        "origin": thought.origin.value.lower(),
                        "root_impulse_id": thought.root_impulse_id,
                        "strategy": thought.strategy.value.lower(),
                        "content": thought.content,
                    },
                ))

                action = self._planner.propose_action(thought)
                if action is not None:
                    self._action_queue.append(action)
                    actions_proposed += 1
                    if action.root_impulse_id is not None:
                        fb = self._lifecycle_tracker.register_action(action.root_impulse_id)
                        if fb is not None:
                            feedback.append(fb)

                    self._event_logger.log(RuntimeEvent(
                        tick=tick,
                        type="ego_action_proposed",
                        attributes={
                            "action_id": action.action_id,
                            "type": action.type.value,
                            "origin": action.origin.value.lower(),
                            "root_impulse_id": action.root_impulse_id,
                            "need_name": action.need_name,
                        },
                    ))

                if thought.root_impulse_id is not None:
                    fb = self._lifecycle_tracker.complete_thought(thought.root_impulse_id)
                    if fb is not None:
                        feedback.append(fb)

            while self._action_queue:
                action = self._action_queue.popleft()
                decision = self._superego.review(action)
                self._event_logger.log(RuntimeEvent(
                    tick=tick,
                    type="superego_review",
                    attributes={
                        "action_id": action.action_id,
                        "type": action.type.value,
                        "origin": action.origin.value.lower(),
                        "root_impulse_id": action.root_impulse_id,
                        "allow": decision.allow,
                        "reason_code": decision.reason_code,
                        "reason": decision.reason,
                    },
                ))

                if decision.allow:
                    outcome = self._motor_cortex.execute(action)
                    was_executed = outcome.success
                    if outcome.success:
                        actions_executed += 1
                    self._event_logger.log(RuntimeEvent(
                        tick=tick,
                        type="motor_execution",
                        attributes={
                            "action_id": action.action_id,
                            "success": outcome.success,
                            "status": outcome.status,
                            "origin": action.origin.value.lower(),
                            "root_impulse_id": action.root_impulse_id,
                        },
                    ))
                else:
                    actions_denied_by_superego += 1
                    was_executed = False

                if action.root_impulse_id is not None:
                    fb = self._lifecycle_tracker.complete_action(
                        root_impulse_id=action.root_impulse_id,
                        executed=was_executed,
                    )
                    if fb is not None:
                        feedback.append(fb)

        for fb in feedback:
            self._event_logger.log(RuntimeEvent(
                tick=tick,
                type="impulse_lifecycle_finalized",
                attributes={
                    "root_impulse_id": fb.root_impulse_id,
                    "need_name": fb.need_name,
                    "result": fb.result.value.lower(),
                },
            ))

        return EgoProcessingResult(
            impulse_feedback=feedback,
            actions_proposed=actions_proposed,
            actions_denied_by_superego=actions_denied_by_superego,
            actions_executed=actions_executed,
        )

    def force_deny_all_impulses(self, tick: int) -> list[ImpulseFeedback]:
        denied = self._lifecycle_tracker.force_deny_all()
        for fb in denied:
            self._event_logger.log(RuntimeEvent(
                tick=tick,
                type="impulse_lifecycle_forced_denied",
                attributes={
                    "root_impulse_id": fb.root_impulse_id,
                    "need_name": fb.need_name,
                },
            ))
        return denied
