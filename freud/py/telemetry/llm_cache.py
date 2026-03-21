#!/usr/bin/env python3
"""LLM cache telemetry aggregator.

Parses JSONL events of types ``llm_cache_hit``, ``llm_cache_miss``, and
``llm_cache_divergence`` and outputs cache performance statistics as JSON.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from freud.py.common import repo_root


_CACHE_EVENT_TYPES = {"llm_cache_hit", "llm_cache_miss", "llm_cache_divergence"}


def _load_cache_events(path: Path) -> list[dict]:
    events = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("type") in _CACHE_EVENT_TYPES:
            events.append(obj)
    return events


def llm_cache_telemetry(input_path: str) -> None:
    path = Path(input_path)
    if not path.is_file():
        print(f'{{"error": "Event log not found: {input_path}"}}')
        sys.exit(1)

    events = _load_cache_events(path)

    hits = [e for e in events if e.get("type") == "llm_cache_hit"]
    misses = [e for e in events if e.get("type") == "llm_cache_miss"]
    divergences = [e for e in events if e.get("type") == "llm_cache_divergence"]

    total_cached = len(hits)
    total_real = len(misses)
    total_calls = total_cached + total_real
    hit_rate = (total_cached / total_calls * 100) if total_calls > 0 else 0.0

    divergence_point = None
    divergence_actor = None
    divergence_call_site = None
    if divergences:
        first = divergences[0]
        data = first.get("data", {})
        divergence_point = data.get("sequence_index")
        divergence_actor = data.get("actor", "")
        divergence_call_site = data.get("call_site", "")

    result = {
        "total_calls": total_calls,
        "cached_calls": total_cached,
        "real_calls": total_real,
        "hit_rate_percent": round(hit_rate, 2),
        "divergence_count": len(divergences),
        "divergence_point": divergence_point,
        "divergence_actor": divergence_actor,
        "divergence_call_site": divergence_call_site,
    }

    # Breakdown by actor
    actor_hits: dict[str, int] = {}
    for e in hits:
        actor = e.get("data", {}).get("actor", "unknown")
        actor_hits[actor] = actor_hits.get(actor, 0) + 1
    result["hits_by_actor"] = actor_hits

    # Tuning hints
    hints = []
    if divergence_point is not None:
        hints.append(
            f"Divergence at seq {divergence_point} ({divergence_actor}/{divergence_call_site}): "
            f"code change may have affected this call path."
        )
    if total_calls > 0 and total_cached == 0:
        hints.append("No cache hits: this may be a first run (record mode) or the cache file is empty.")
    if total_calls > 0 and hit_rate == 100.0:
        hints.append("All calls served from cache: fully deterministic replay.")
    result["hints"] = hints

    print(json.dumps(result, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Aggregate LLM cache telemetry from JSONL events.",
    )
    parser.add_argument(
        "events_jsonl",
        nargs="?",
        default=str(repo_root() / ".neopsyke/logs/latest-events.jsonl"),
        help="Path to events JSONL file",
    )
    args = parser.parse_args()

    llm_cache_telemetry(args.events_jsonl)


if __name__ == "__main__":
    main()
