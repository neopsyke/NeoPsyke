import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from .config import PocConfig
from .cortex import SensoryCortex
from .ego import Ego
from .event_logger import EventLogger, RuntimeEvent
from .id_module import IdModule
from .model import ImpulseResult


@dataclass
class RunSummary:
    total_ticks: int
    user_requests_processed: int
    impulses_fired: int
    impulses_accepted: int
    impulses_denied: int
    actions_proposed: int
    actions_denied_by_superego: int
    actions_executed: int
    final_needs: dict[str, float]

    def to_dict(self) -> dict[str, Any]:
        return {
            "total_ticks": self.total_ticks,
            "user_requests_processed": self.user_requests_processed,
            "impulses_fired": self.impulses_fired,
            "impulses_accepted": self.impulses_accepted,
            "impulses_denied": self.impulses_denied,
            "actions_proposed": self.actions_proposed,
            "actions_denied_by_superego": self.actions_denied_by_superego,
            "actions_executed": self.actions_executed,
            "final_needs": self.final_needs,
        }

    def write_json(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), indent=2))


class PocRuntime:
    def __init__(
        self,
        config: PocConfig,
        sensory_cortex: SensoryCortex,
        id_module: IdModule,
        ego: Ego,
        event_logger: EventLogger,
    ) -> None:
        self._config = config
        self._sensory_cortex = sensory_cortex
        self._id_module = id_module
        self._ego = ego
        self._event_logger = event_logger

    def run(self) -> RunSummary:
        user_requests_processed = 0
        impulses_fired = 0
        impulses_accepted = 0
        impulses_denied = 0
        actions_proposed = 0
        actions_denied_by_superego = 0
        actions_executed = 0

        for tick in range(self._config.runtime.total_ticks):
            user_requests = self._sensory_cortex.poll_user_requests(tick)
            for req in user_requests:
                self._ego.submit_user_request(tick, req)
                user_requests_processed += 1

            impulse = self._id_module.tick(tick)
            if impulse is not None:
                impulses_fired += 1
                self._ego.submit_impulse(tick, impulse)

            result = self._ego.process_all_pending(tick)
            actions_proposed += result.actions_proposed
            actions_denied_by_superego += result.actions_denied_by_superego
            actions_executed += result.actions_executed

            for feedback in result.impulse_feedback:
                self._id_module.on_impulse_feedback(tick, feedback)
                if feedback.result == ImpulseResult.ACCEPTED:
                    impulses_accepted += 1
                else:
                    impulses_denied += 1

        forced_feedback = self._ego.force_deny_all_impulses(self._config.runtime.total_ticks)
        for feedback in forced_feedback:
            self._id_module.on_impulse_feedback(self._config.runtime.total_ticks, feedback)
            impulses_denied += 1

        summary = RunSummary(
            total_ticks=self._config.runtime.total_ticks,
            user_requests_processed=user_requests_processed,
            impulses_fired=impulses_fired,
            impulses_accepted=impulses_accepted,
            impulses_denied=impulses_denied,
            actions_proposed=actions_proposed,
            actions_denied_by_superego=actions_denied_by_superego,
            actions_executed=actions_executed,
            final_needs=self._id_module.needs_snapshot(),
        )

        self._event_logger.log(RuntimeEvent(
            tick=self._config.runtime.total_ticks,
            type="run_summary",
            attributes=summary.to_dict(),
        ))

        return summary


@dataclass
class AblationSummary:
    id_off: RunSummary
    id_on: RunSummary

    def to_dict(self) -> dict[str, Any]:
        return {
            "id_off": self.id_off.to_dict(),
            "id_on": self.id_on.to_dict(),
            "delta": {
                "impulses_fired": self.id_on.impulses_fired - self.id_off.impulses_fired,
                "impulses_accepted": self.id_on.impulses_accepted - self.id_off.impulses_accepted,
                "impulses_denied": self.id_on.impulses_denied - self.id_off.impulses_denied,
                "actions_executed": self.id_on.actions_executed - self.id_off.actions_executed,
                "actions_denied_by_superego": self.id_on.actions_denied_by_superego - self.id_off.actions_denied_by_superego,
            },
        }

    def write_json(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), indent=2))


def run_ablation(base_config: PocConfig, run_scenario: Callable[[PocConfig], RunSummary]) -> AblationSummary:
    id_off_config = PocConfig(
        runtime=base_config.runtime,
        sensory=base_config.sensory,
        id=IdConfig_disabled(base_config),
        ego=base_config.ego,
        superego=base_config.superego,
        motor=base_config.motor,
        logging=base_config.logging,
    )
    id_on_config = PocConfig(
        runtime=base_config.runtime,
        sensory=base_config.sensory,
        id=IdConfig_enabled(base_config),
        ego=base_config.ego,
        superego=base_config.superego,
        motor=base_config.motor,
        logging=base_config.logging,
    )

    return AblationSummary(
        id_off=run_scenario(id_off_config),
        id_on=run_scenario(id_on_config),
    )


def IdConfig_disabled(base_config: PocConfig):
    from .config import IdConfig
    c = base_config.id
    return IdConfig(
        enabled=False,
        trigger_threshold=c.trigger_threshold,
        trigger_probability=c.trigger_probability,
        max_consecutive_denials=c.max_consecutive_denials,
        backoff_ticks=c.backoff_ticks,
        needs=c.needs,
    )


def IdConfig_enabled(base_config: PocConfig):
    from .config import IdConfig
    c = base_config.id
    return IdConfig(
        enabled=True,
        trigger_threshold=c.trigger_threshold,
        trigger_probability=c.trigger_probability,
        max_consecutive_denials=c.max_consecutive_denials,
        backoff_ticks=c.backoff_ticks,
        needs=c.needs,
    )
