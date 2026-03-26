import json
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ScheduledUserRequest:
    tick: int
    content: str


@dataclass
class RuntimeConfig:
    deterministic_mode: bool = True
    deterministic_seed: int = 42
    total_ticks: int = 60


@dataclass
class SensoryConfig:
    scheduled_user_requests: list[ScheduledUserRequest] = field(default_factory=list)


@dataclass
class NeedConfig:
    growth_rate: float = 0.03
    reset_value: float = 0.0
    impulse_message: str = ""


@dataclass
class IdConfig:
    enabled: bool = True
    trigger_threshold: float = 0.7
    trigger_probability: float = 1.0
    max_consecutive_denials: int = 3
    backoff_ticks: int = 5
    needs: dict[str, NeedConfig] = field(default_factory=lambda: {
        "be_useful": NeedConfig(growth_rate=0.03, impulse_message="I feel a drive to be useful."),
        "interact_with_user": NeedConfig(growth_rate=0.025, impulse_message="I feel a drive to interact with the user."),
        "learn_something": NeedConfig(growth_rate=0.035, impulse_message="I feel a drive to learn something."),
    })


@dataclass
class EgoConfig:
    parallel_thoughts_per_impulse: int = 2
    include_noop_thought_branch: bool = True


@dataclass
class SuperegoConfig:
    allow_id_contact_user: bool = False
    allowed_id_action_types: set[str] = field(default_factory=lambda: {"REFLECT_INTERNAL", "WEB_SEARCH"})


@dataclass
class MotorConfig:
    executable_action_types: set[str] = field(default_factory=lambda: {"REFLECT_INTERNAL", "WEB_SEARCH", "CONTACT_USER"})


@dataclass
class LoggingConfig:
    events_path: str = "build/poc-events.jsonl"
    summary_path: str = "build/poc-summary.json"


@dataclass
class PocConfig:
    runtime: RuntimeConfig = field(default_factory=RuntimeConfig)
    sensory: SensoryConfig = field(default_factory=SensoryConfig)
    id: IdConfig = field(default_factory=IdConfig)
    ego: EgoConfig = field(default_factory=EgoConfig)
    superego: SuperegoConfig = field(default_factory=SuperegoConfig)
    motor: MotorConfig = field(default_factory=MotorConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)


def load_config(path: str | Path) -> PocConfig:
    with open(path) as f:
        raw = json.load(f)

    runtime = RuntimeConfig(**raw.get("runtime", {}))

    sensory_raw = raw.get("sensory", {})
    sensory = SensoryConfig(
        scheduled_user_requests=[
            ScheduledUserRequest(**r) for r in sensory_raw.get("scheduled_user_requests", [])
        ]
    )

    id_raw = raw.get("id", {})
    needs_raw = id_raw.pop("needs", {})
    needs = {name: NeedConfig(**cfg) for name, cfg in needs_raw.items()}
    id_config = IdConfig(**id_raw, needs=needs)

    ego = EgoConfig(**raw.get("ego", {}))

    superego_raw = raw.get("superego", {})
    superego_raw["allowed_id_action_types"] = set(superego_raw.get("allowed_id_action_types", []))
    superego = SuperegoConfig(**superego_raw)

    motor_raw = raw.get("motor", {})
    motor_raw["executable_action_types"] = set(motor_raw.get("executable_action_types", []))
    motor = MotorConfig(**motor_raw)

    logging_config = LoggingConfig(**raw.get("logging", {}))

    return PocConfig(
        runtime=runtime,
        sensory=sensory,
        id=id_config,
        ego=ego,
        superego=superego,
        motor=motor,
        logging=logging_config,
    )
