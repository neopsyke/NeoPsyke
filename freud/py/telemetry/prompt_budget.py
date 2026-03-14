#!/usr/bin/env python3
"""Prompt budget allocator telemetry aggregator.

Port of freud/scripts/prompt-budget-telemetry.sh — parses JSONL events of type
``prompt_budget_allocation`` and prints fallback/degradation counters with tuning hints.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

from freud.py.common import pct, repo_root


def _load_events(path: Path, event_type: str) -> list[dict]:
    """Load JSONL file and return events matching *event_type*."""
    events = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("type") == event_type:
            events.append(obj)
    return events


def prompt_budget_telemetry(input_path: str) -> None:
    """Aggregate and print prompt budget telemetry from *input_path*."""
    path = Path(input_path)
    if not path.is_file():
        print(f"Event log not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    events = _load_events(path, "prompt_budget_allocation")
    total = len(events)

    if total == 0:
        print(f"No prompt_budget_allocation events found in: {input_path}")
        return

    fallback_count = sum(1 for e in events if e.get("data", {}).get("single_message_fallback") is True)
    floor_violation_events = sum(1 for e in events if (e.get("data", {}).get("floor_violation_count") or 0) > 0)
    dropped_sections_total = sum(e.get("data", {}).get("dropped_section_count", 0) for e in events)

    costs = [e.get("data", {}).get("allocated_total_cost", 0) for e in events]
    floors = [e.get("data", {}).get("reserved_floor_cost", 0) for e in events]
    avg_cost = sum(costs) / len(costs) if costs else 0
    avg_floor = sum(floors) / len(floors) if floors else 0

    print("Prompt Budget Telemetry")
    print(f"source: {input_path}")
    print()
    print("Totals")
    print(f"- allocations: {total}")
    print(f"- single_message_fallback: {fallback_count} ({pct(fallback_count, total)}%)")
    print(f"- floor_violation_events: {floor_violation_events} ({pct(floor_violation_events, total)}%)")
    print(f"- dropped_sections_total: {dropped_sections_total}")
    print(f"- avg_allocated_total_cost: {avg_cost:.2f}")
    print(f"- avg_reserved_floor_cost: {avg_floor:.2f}")
    print()

    # Breakdown by call_site
    print("Breakdown by call_site")
    site_counts: Counter[str] = Counter()
    for e in events:
        site = e.get("data", {}).get("call_site") or "unknown"
        site_counts[site] += 1
    for site, count in site_counts.most_common():
        print(f"- {site}: {count}")
    print()

    # Breakdown by degradation_path
    print("Breakdown by degradation_path")
    deg_counts: Counter[str] = Counter()
    for e in events:
        deg = e.get("data", {}).get("degradation_path") or "none"
        deg_counts[deg] += 1
    for deg, count in deg_counts.most_common():
        print(f"- {deg}: {count}")
    print()

    # Fallback rate by call_site
    print("Fallback rate by call_site")
    site_events: dict[str, list[dict]] = {}
    for e in events:
        site = e.get("data", {}).get("call_site") or "unknown"
        site_events.setdefault(site, []).append(e)
    for site in sorted(site_events, key=lambda s: -len(site_events[s])):
        evts = site_events[site]
        t = len(evts)
        fb = sum(1 for e in evts if e.get("data", {}).get("single_message_fallback") is True)
        fv = sum(1 for e in evts if (e.get("data", {}).get("floor_violation_count") or 0) > 0)
        print(f"- {site}: total={t}, fallback={fb}, floor_violations={fv}")

    print()
    print("Tuning Hints")
    if fallback_count > 0:
        print("- single-message fallback occurred: reduce required floors or increase max prompt budget for affected call sites.")
    if floor_violation_events > 0:
        print("- floor violations occurred: required floor reservation exceeds budget in some prompts; inspect degradation_path and band usage.")
    if dropped_sections_total > 0:
        print("- sections were dropped: verify optional/context blocks are ordered and banded by true criticality.")
    if fallback_count == 0 and floor_violation_events == 0:
        print("- no severe prompt pressure observed in this sample.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Aggregate prompt budget telemetry from JSONL events.",
        usage="freud/scripts/prompt-budget-telemetry.sh [events_jsonl]",
    )
    parser.add_argument("events_jsonl", nargs="?",
                        default=str(repo_root() / ".psyke/logs/latest-events.jsonl"),
                        help="Path to events JSONL file")
    args = parser.parse_args()

    prompt_budget_telemetry(args.events_jsonl)


if __name__ == "__main__":
    main()
