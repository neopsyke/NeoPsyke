import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol


@dataclass
class RuntimeEvent:
    type: str
    attributes: dict[str, Any]
    tick: int | None = None

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {"type": self.type}
        if self.tick is not None:
            d["tick"] = self.tick
        d["attributes"] = self.attributes
        return d


class EventLogger(Protocol):
    def log(self, event: RuntimeEvent) -> None: ...
    def close(self) -> None: ...


class JsonlEventLogger:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._file = open(path, "w")

    def log(self, event: RuntimeEvent) -> None:
        self._file.write(json.dumps(event.to_dict()) + "\n")

    def close(self) -> None:
        self._file.close()


class CompositeEventLogger:
    def __init__(self, loggers: list[JsonlEventLogger]) -> None:
        self._loggers = loggers

    def log(self, event: RuntimeEvent) -> None:
        for logger in self._loggers:
            logger.log(event)

    def close(self) -> None:
        for logger in self._loggers:
            logger.close()


class InMemoryEventLogger:
    def __init__(self) -> None:
        self.events: list[RuntimeEvent] = []

    def log(self, event: RuntimeEvent) -> None:
        self.events.append(event)

    def close(self) -> None:
        pass
