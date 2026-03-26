from typing import Protocol

from .model import ActionProposal, ActionType, MotorOutcome, UserRequest


class SensoryCortex(Protocol):
    def poll_user_requests(self, tick: int) -> list[UserRequest]: ...


class ScriptedSensoryCortex:
    def __init__(self, scheduled_requests: list[tuple[int, str]]) -> None:
        self._schedule: dict[int, list[str]] = {}
        for tick, content in scheduled_requests:
            self._schedule.setdefault(tick, []).append(content)

    def poll_user_requests(self, tick: int) -> list[UserRequest]:
        contents = self._schedule.get(tick, [])
        return [UserRequest(content=c) for c in contents]


class MotorCortex(Protocol):
    def execute(self, action: ActionProposal) -> MotorOutcome: ...


class DeterministicMotorCortex:
    def __init__(self, executable_action_types: set[ActionType]) -> None:
        self._executable = executable_action_types

    def execute(self, action: ActionProposal) -> MotorOutcome:
        if action.type not in self._executable:
            return MotorOutcome(
                success=False,
                status=f"Action type {action.type.value} is disabled in deterministic motor cortex.",
            )
        status_map = {
            ActionType.REFLECT_INTERNAL: "Internal reflection completed.",
            ActionType.WEB_SEARCH: "Web search completed.",
            ActionType.CONTACT_USER: "User contact delivered.",
        }
        return MotorOutcome(success=True, status=status_map[action.type])
