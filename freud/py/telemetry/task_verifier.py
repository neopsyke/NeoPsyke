#!/usr/bin/env python3
"""TaskVerifier telemetry aggregator.

Port of freud/scripts/task-verifier-telemetry.sh — parses JSONL events of type
``task_verifier_review`` and prints intent/volatility counters with tuning hints.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

from freud.py.common import pct, repo_root


def _load_events(path: Path, event_type: str) -> list[dict]:
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


def task_verifier_telemetry(input_path: str) -> None:
    """Aggregate and print task verifier telemetry from *input_path*."""
    path = Path(input_path)
    if not path.is_file():
        print(f"Event log not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    events = _load_events(path, "task_verifier_review")
    total = len(events)

    if total == 0:
        print(f"No task_verifier_review events found in: {input_path}")
        return

    allow_count = sum(1 for e in events if e.get("data", {}).get("allow") is True)
    deny_count = sum(1 for e in events if e.get("data", {}).get("allow") is False)
    requires_count = sum(1 for e in events if e.get("data", {}).get("requires_external_evidence") is True)
    graceful_count = sum(
        1 for e in events
        if e.get("data", {}).get("allow") is True
        and e.get("data", {}).get("reason_code") == "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL"
    )
    unknown_intent_count = sum(
        1 for e in events
        if (e.get("data", {}).get("intent_category") or "") == "unknown"
    )
    volatile_count = sum(
        1 for e in events
        if (e.get("data", {}).get("intent_category") or "") == "volatile_fact"
    )
    volatile_deny_count = sum(
        1 for e in events
        if (e.get("data", {}).get("intent_category") or "") == "volatile_fact"
        and e.get("data", {}).get("allow") is False
    )

    print("Task Verifier Telemetry")
    print(f"source: {input_path}")
    print()
    print("Totals")
    print(f"- reviews: {total}")
    print(f"- allow: {allow_count} ({pct(allow_count, total)}%)")
    print(f"- deny: {deny_count} ({pct(deny_count, total)}%)")
    print(f"- requires_external_evidence: {requires_count} ({pct(requires_count, total)}%)")
    print(f"- graceful_allows: {graceful_count} ({pct(graceful_count, total)}%)")
    print(f"- unknown_intent: {unknown_intent_count} ({pct(unknown_intent_count, total)}%)")
    print(f"- volatile_intent: {volatile_count} ({pct(volatile_count, total)}%)")
    print(f"- volatile_denies: {volatile_deny_count}")
    print()

    # Breakdown by reason_code
    print("Breakdown by reason_code")
    reason_counts: Counter[str] = Counter()
    for e in events:
        rc = e.get("data", {}).get("reason_code") or "none"
        reason_counts[rc] += 1
    for rc, count in reason_counts.most_common():
        print(f"- {rc}: {count}")
    print()

    # Breakdown by intent_category
    print("Breakdown by intent_category")
    intent_counts: Counter[str] = Counter()
    for e in events:
        ic = e.get("data", {}).get("intent_category") or "none"
        intent_counts[ic] += 1
    for ic, count in intent_counts.most_common():
        print(f"- {ic}: {count}")
    print()

    # Breakdown by volatility_level
    print("Breakdown by volatility_level")
    vol_counts: Counter[str] = Counter()
    for e in events:
        vl = e.get("data", {}).get("volatility_level") or "none"
        vol_counts[vl] += 1
    for vl, count in vol_counts.most_common():
        print(f"- {vl}: {count}")
    print()

    print("Tuning Hints")
    if unknown_intent_count > 0:
        print("- unknown intent observed: review prompts and add deterministic intent rules before lowering volatility thresholds.")
    if graceful_count > 0:
        print("- graceful allows occurred: inspect action capability health to reduce under-verified volatile answers.")
    if volatile_count > 0:
        volatile_deny_rate = pct(volatile_deny_count, volatile_count)
        print(f"- volatile deny rate: {volatile_deny_rate}% (target depends on tool availability and product posture).")
    if deny_count == 0:
        print("- no denies recorded: verify volatile scenarios are still covered by tests/evals.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Aggregate TaskVerifier telemetry from JSONL events.",
        usage="freud/scripts/task-verifier-telemetry.sh [events_jsonl]",
    )
    parser.add_argument("events_jsonl", nargs="?",
                        default=str(repo_root() / ".neopsyke/logs/latest-events.jsonl"),
                        help="Path to events JSONL file")
    args = parser.parse_args()

    task_verifier_telemetry(args.events_jsonl)


if __name__ == "__main__":
    main()
