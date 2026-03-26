import random as random_module
import uuid
from typing import Any

from .config import IdConfig, NeedConfig
from .event_logger import EventLogger, RuntimeEvent
from .model import IdImpulse, ImpulseFeedback, ImpulseResult

_MAX_BACKOFF_ESCALATION = 4


class NeedState:
    def __init__(self, name: str, config: NeedConfig) -> None:
        self.name = name
        self.config = config
        self.value: float = 0.0
        self.consecutive_denials: int = 0
        self.backoff_remaining_ticks: int = 0

    def grow(self) -> None:
        self.value = max(0.0, min(1.0, self.value + self.config.growth_rate))
        if self.backoff_remaining_ticks > 0:
            self.backoff_remaining_ticks -= 1

    def is_eligible(self) -> bool:
        return self.backoff_remaining_ticks <= 0

    def apply_accepted_feedback(self) -> None:
        self.value = max(0.0, min(1.0, self.config.reset_value))
        self.consecutive_denials = 0
        self.backoff_remaining_ticks = 0

    def apply_denied_feedback(self, max_consecutive_denials: int, base_backoff_ticks: int) -> None:
        self.consecutive_denials += 1
        if max_consecutive_denials > 0 and base_backoff_ticks > 0 and self.consecutive_denials % max_consecutive_denials == 0:
            escalation = min(self.consecutive_denials // max_consecutive_denials, _MAX_BACKOFF_ESCALATION)
            self.backoff_remaining_ticks = base_backoff_ticks * (1 << escalation)

    def snapshot(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "value": self.value,
            "consecutive_denials": self.consecutive_denials,
            "backoff_remaining_ticks": self.backoff_remaining_ticks,
        }


class IdModule:
    def __init__(self, config: IdConfig, rng: random_module.Random, event_logger: EventLogger) -> None:
        self._config = config
        self._rng = rng
        self._event_logger = event_logger
        self._needs: dict[str, NeedState] = {
            name: NeedState(name=name, config=cfg)
            for name, cfg in config.needs.items()
        }
        self._pending_impulse: IdImpulse | None = None

    def tick(self, tick: int) -> IdImpulse | None:
        for need in self._needs.values():
            need.grow()

        self._event_logger.log(RuntimeEvent(
            tick=tick,
            type="id_pulse",
            attributes={
                "needs": [n.snapshot() for n in self._needs.values()],
                "pending_impulse": self._pending_impulse is not None,
            },
        ))

        if not self._config.enabled:
            return None

        if self._pending_impulse is not None:
            self._event_logger.log(RuntimeEvent(
                tick=tick,
                type="id_impulse_not_emitted",
                attributes={"reason": "pending_impulse"},
            ))
            return None

        candidates = [
            n for n in self._needs.values()
            if n.is_eligible() and n.value >= self._config.trigger_threshold
        ]

        if not candidates:
            return None

        probability_roll = self._rng.random()
        if probability_roll > self._config.trigger_probability:
            self._event_logger.log(RuntimeEvent(
                tick=tick,
                type="id_impulse_not_emitted",
                attributes={
                    "reason": "probability_gate",
                    "roll": probability_roll,
                    "trigger_probability": self._config.trigger_probability,
                },
            ))
            return None

        selected_need = max(candidates, key=lambda n: n.value)
        impulse = IdImpulse(
            root_impulse_id=str(uuid.uuid4()),
            need_name=selected_need.name,
            message=selected_need.config.impulse_message,
            urgency=selected_need.value,
            raw_need_value=selected_need.value,
        )
        self._pending_impulse = impulse

        self._event_logger.log(RuntimeEvent(
            tick=tick,
            type="id_impulse_fired",
            attributes={
                "root_impulse_id": impulse.root_impulse_id,
                "need_name": impulse.need_name,
                "urgency": impulse.urgency,
                "raw_need_value": impulse.raw_need_value,
            },
        ))
        return impulse

    def on_impulse_feedback(self, tick: int, feedback: ImpulseFeedback) -> None:
        if self._pending_impulse is None:
            return
        if self._pending_impulse.root_impulse_id != feedback.root_impulse_id:
            return

        need = self._needs.get(feedback.need_name)
        if need is None:
            return

        if feedback.result == ImpulseResult.ACCEPTED:
            need.apply_accepted_feedback()
        else:
            need.apply_denied_feedback(
                max_consecutive_denials=self._config.max_consecutive_denials,
                base_backoff_ticks=self._config.backoff_ticks,
            )
        self._pending_impulse = None

        self._event_logger.log(RuntimeEvent(
            tick=tick,
            type="id_impulse_feedback",
            attributes={
                "root_impulse_id": feedback.root_impulse_id,
                "need_name": feedback.need_name,
                "result": feedback.result.value.lower(),
                "need_state": need.snapshot(),
            },
        ))

    def needs_snapshot(self) -> dict[str, float]:
        return {name: state.value for name, state in self._needs.items()}
