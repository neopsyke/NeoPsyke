#!/usr/bin/env python3
"""Session replay telemetry aggregator.

Parses JSONL events of types ``session_channel_replay_hit`` and
``session_channel_divergence`` and outputs per-channel replay statistics
as JSON.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from freud.py.common import repo_root


_SESSION_EVENT_TYPES = {"session_channel_replay_hit", "session_channel_divergence"}


def _load_session_events(path: Path) -> list[dict]:
    events = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("type") in _SESSION_EVENT_TYPES:
            events.append(obj)
    return events


def session_replay_telemetry(input_path: str) -> dict:
    path = Path(input_path)
    if not path.is_file():
        result = {"error": f"Event log not found: {input_path}"}
        print(json.dumps(result, indent=2))
        sys.exit(1)

    events = _load_session_events(path)
    if not events:
        result = {
            "total_replay_hits": 0,
            "total_divergences": 0,
            "channels": {},
            "hints": ["No session replay events found — this may be a record-only run."],
        }
        print(json.dumps(result, indent=2))
        return result

    hits = [e for e in events if e.get("type") == "session_channel_replay_hit"]
    divergences = [e for e in events if e.get("type") == "session_channel_divergence"]

    # Per-channel stats
    channel_stats: dict[str, dict] = {}
    for e in hits:
        data = e.get("data", {})
        channel = data.get("channel", "unknown")
        if channel not in channel_stats:
            channel_stats[channel] = {"hits": 0, "divergences": 0, "divergence_point": None}
        channel_stats[channel]["hits"] += 1

    for e in divergences:
        data = e.get("data", {})
        channel = data.get("channel", "unknown")
        if channel not in channel_stats:
            channel_stats[channel] = {"hits": 0, "divergences": 0, "divergence_point": None}
        channel_stats[channel]["divergences"] += 1
        if channel_stats[channel]["divergence_point"] is None:
            channel_stats[channel]["divergence_point"] = data.get("sequence_index")
            channel_stats[channel]["expected_hash"] = data.get("expected_hash", "")
            channel_stats[channel]["actual_hash"] = data.get("actual_hash", "")

    # Summary
    total_hits = len(hits)
    total_divergences = len(divergences)
    fully_replayed = [ch for ch, s in channel_stats.items() if s["divergences"] == 0]
    diverged = [ch for ch, s in channel_stats.items() if s["divergences"] > 0]

    # Hints
    hints = []
    for ch, s in channel_stats.items():
        if s["divergences"] > 0:
            hints.append(
                f"Channel '{ch}' diverged at seq {s['divergence_point']}: "
                f"code change may have affected this data path."
            )
    if fully_replayed:
        hints.append(
            f"Channels fully replayed from cache: {', '.join(fully_replayed)}."
        )
    if total_hits > 0 and total_divergences == 0:
        hints.append("All channels served from cache: fully deterministic replay.")

    result = {
        "total_replay_hits": total_hits,
        "total_divergences": total_divergences,
        "fully_replayed_channels": fully_replayed,
        "diverged_channels": diverged,
        "channels": channel_stats,
        "hints": hints,
    }

    print(json.dumps(result, indent=2))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Aggregate session replay telemetry from JSONL events.",
    )
    parser.add_argument(
        "events_jsonl",
        nargs="?",
        default=str(repo_root() / ".neopsyke/logs/latest-events.jsonl"),
        help="Path to events JSONL file",
    )
    args = parser.parse_args()

    session_replay_telemetry(args.events_jsonl)


if __name__ == "__main__":
    main()
