from dataclasses import dataclass
from enum import Enum


class ActionType(Enum):
    REFLECT_INTERNAL = "REFLECT_INTERNAL"
    WEB_SEARCH = "WEB_SEARCH"
    CONTACT_USER = "CONTACT_USER"

    @staticmethod
    def from_raw(raw: str) -> "ActionType":
        return ActionType(raw.strip().upper())


class OriginSource(Enum):
    USER = "USER"
    ID = "ID"


class ThoughtStrategy(Enum):
    NOOP_BRANCH = "NOOP_BRANCH"
    EXECUTION_BRANCH = "EXECUTION_BRANCH"
    USER_REQUEST_BRANCH = "USER_REQUEST_BRANCH"


class ImpulseResult(Enum):
    ACCEPTED = "ACCEPTED"
    DENIED = "DENIED"


@dataclass(frozen=True)
class UserRequest:
    content: str


@dataclass(frozen=True)
class IdImpulse:
    root_impulse_id: str
    need_name: str
    message: str
    urgency: float
    raw_need_value: float


@dataclass(frozen=True)
class ThoughtTask:
    thought_id: str
    root_impulse_id: str | None
    need_name: str | None
    origin: OriginSource
    content: str
    strategy: ThoughtStrategy


@dataclass(frozen=True)
class ActionProposal:
    action_id: str
    root_impulse_id: str | None
    need_name: str | None
    origin: OriginSource
    type: ActionType
    summary: str
    payload: str


@dataclass(frozen=True)
class SuperegoDecision:
    allow: bool
    reason_code: str
    reason: str


@dataclass(frozen=True)
class MotorOutcome:
    success: bool
    status: str


@dataclass(frozen=True)
class ImpulseFeedback:
    root_impulse_id: str
    need_name: str
    result: ImpulseResult
