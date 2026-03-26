import argparse
import random as random_module
from pathlib import Path

from .config import PocConfig, load_config
from .cortex import DeterministicMotorCortex, ScriptedSensoryCortex
from .ego import DeterministicEgoPlanner, Ego
from .event_logger import CompositeEventLogger, JsonlEventLogger, RuntimeEvent
from .id_module import IdModule
from .model import ActionType
from .runtime import AblationSummary, PocRuntime, RunSummary, run_ablation
from .superego import DeterministicSuperego


def _events_path_for_run(events_path: str, id_enabled: bool) -> str:
    suffix = "id-on" if id_enabled else "id-off"
    dot_index = events_path.rfind(".")
    if dot_index <= 0:
        return f"{events_path}-{suffix}"
    prefix = events_path[:dot_index]
    extension = events_path[dot_index:]
    return f"{prefix}-{suffix}{extension}"


def _run_single_scenario(config: PocConfig) -> RunSummary:
    rng = random_module.Random(config.runtime.deterministic_seed if config.runtime.deterministic_mode else None)

    events_path = Path(_events_path_for_run(config.logging.events_path, config.id.enabled))
    event_logger = JsonlEventLogger(events_path)
    composite_logger = CompositeEventLogger([event_logger])

    sensory_cortex = ScriptedSensoryCortex(
        scheduled_requests=[(r.tick, r.content) for r in config.sensory.scheduled_user_requests]
    )

    id_module = IdModule(config=config.id, rng=rng, event_logger=composite_logger)

    ego = Ego(
        planner=DeterministicEgoPlanner(config.ego),
        superego=DeterministicSuperego(config.superego),
        motor_cortex=DeterministicMotorCortex(
            executable_action_types={ActionType.from_raw(t) for t in config.motor.executable_action_types}
        ),
        event_logger=composite_logger,
    )

    composite_logger.log(RuntimeEvent(
        type="run_start",
        attributes={
            "deterministic_mode": config.runtime.deterministic_mode,
            "deterministic_seed": config.runtime.deterministic_seed,
            "id_enabled": config.id.enabled,
            "total_ticks": config.runtime.total_ticks,
        },
    ))

    summary = PocRuntime(
        config=config,
        sensory_cortex=sensory_cortex,
        id_module=id_module,
        ego=ego,
        event_logger=composite_logger,
    ).run()

    event_logger.close()
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Freudian control loop PoC (Python)")
    parser.add_argument("--mode", choices=["run", "ablation"], default="run")
    parser.add_argument("--config", default="config/poc.json")
    parser.add_argument("--ablation-output", default="build/ablation-summary.json")
    args = parser.parse_args()

    config = load_config(args.config)

    if args.mode == "run":
        summary = _run_single_scenario(config)
        summary.write_json(Path(config.logging.summary_path))
        print(f"Run completed. Summary written to {config.logging.summary_path}")
        print(summary.to_dict())

    elif args.mode == "ablation":
        ablation_summary = run_ablation(config, _run_single_scenario)
        output_path = Path(args.ablation_output)
        ablation_summary.write_json(output_path)
        print(f"Ablation completed. Report written to {output_path}")
        print(ablation_summary.to_dict())


if __name__ == "__main__":
    main()
