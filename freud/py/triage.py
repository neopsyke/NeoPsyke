#!/usr/bin/env python3
"""Heuristic triage of a Freud run directory.

Port of freud/scripts/triage-run.sh — scans logs for known anomaly patterns,
pressure spikes, and top error/warning signals. Produces:
  artifacts/anomalies.json
  artifacts/anomalies.md
  artifacts/pattern-counts.tsv
  artifacts/top-signals.tsv
  artifacts/pressure-signals.tsv
  artifacts/first-failing-trace.txt
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

from freud.py.common import (
    json_escape,
    non_empty_lines,
    rg_search,
    utcnow_iso,
)

PATTERNS: list[tuple[str, str]] = [
    ("planner_output_repaired", "planner_output_repaired|output_repaired"),
    ("parse_failures", "non-parseable|failed to parse|parse fallback|parse error|parse failure"),
    ("forced_terminal", "forced terminal|forced_terminal_answer"),
    ("queue_saturation", "queue full|queue_saturation|step limit reached"),
    ("policy_denials", "action denied|denied by superego|policy denied"),
    ("provider_failures", "timeout|unavailable|provider check failed"),
    ("cache_divergence", "llm_cache_divergence"),
]



def _record_pattern(pattern_id: str, regex: str, logs_dir: Path) -> tuple[str, int, str]:
    """Search logs for *regex* and return (pattern_id, count, first_sample)."""
    if not logs_dir.is_dir():
        return (pattern_id, 0, "")
    raw = rg_search(regex, logs_dir, case_insensitive=True)
    lines = non_empty_lines(raw)
    count = len(lines)
    sample = lines[0] if lines else ""
    return (pattern_id, count, sample)


def _extract_pressure_value(line: str) -> str | None:
    m = re.search(r"decision_pressure=([0-9]+\.[0-9]+)", line)
    return m.group(1) if m else None


def _compare_gt(a: str, b: str) -> bool:
    try:
        return float(a) > float(b)
    except ValueError:
        return False


def _compare_ge(a: str, threshold: float) -> bool:
    try:
        return float(a) >= threshold
    except ValueError:
        return False


def _context_around(filepath: str, line_num: int, context: int = 2) -> str:
    """Read a few lines of context around *line_num* from *filepath*."""
    try:
        all_lines = Path(filepath).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return ""
    start = max(0, line_num - 1 - context)
    end = min(len(all_lines), line_num + context)
    numbered = []
    for i, l in enumerate(all_lines[start:end], start=start + 1):
        numbered.append(f"  {i}\t{l}")
    return "\n".join(numbered)


def triage(run_dir: str, *, top_n: int = 20) -> str:
    """Run triage on *run_dir*. Returns path to anomalies.json."""
    run_path = Path(run_dir)
    if not run_path.is_dir():
        print(f"Run directory does not exist: {run_dir}", file=sys.stderr)
        sys.exit(1)

    logs_dir = run_path / "logs"
    artifact_dir = run_path / "artifacts"
    artifact_dir.mkdir(parents=True, exist_ok=True)

    patterns_file = artifact_dir / "pattern-counts.tsv"
    top_signals_file = artifact_dir / "top-signals.tsv"
    pressure_file = artifact_dir / "pressure-signals.tsv"
    first_failure_file = artifact_dir / "first-failing-trace.txt"

    # --- record patterns ---
    pattern_results: list[tuple[str, int, str]] = []
    for pid, regex in PATTERNS:
        pattern_results.append(_record_pattern(pid, regex, logs_dir))

    with open(patterns_file, "w", encoding="utf-8") as f:
        for pid, count, sample in pattern_results:
            f.write(f"{pid}\t{count}\t{sample}\n")

    # --- top signals & pressure ---
    top_lines: list[str] = []
    pressure_lines: list[str] = []
    first_fail_line = ""

    if logs_dir.is_dir():
        raw_top = rg_search("error|exception|failed|warning", logs_dir, case_insensitive=True,
                            invert_match=":> Task :")
        top_lines = non_empty_lines(raw_top)[:top_n]

        raw_pressure = rg_search(r"decision_pressure=[0-9]+\.[0-9]+", logs_dir,
                                 case_insensitive=False)
        pressure_lines = non_empty_lines(raw_pressure)

        raw_fail = rg_search("error|exception|failed|traceback|assert", logs_dir,
                             case_insensitive=True, invert_match=":> Task :")
        fail_lines = non_empty_lines(raw_fail)
        first_fail_line = fail_lines[0] if fail_lines else ""

    with open(top_signals_file, "w", encoding="utf-8") as f:
        f.write("\n".join(top_lines) + ("\n" if top_lines else ""))

    with open(pressure_file, "w", encoding="utf-8") as f:
        f.write("\n".join(pressure_lines) + ("\n" if pressure_lines else ""))

    # --- first failing trace snippet ---
    first_failure_text = ""
    if first_fail_line:
        parts = first_fail_line.split(":", 2)
        file_ref = parts[0] if len(parts) > 0 else ""
        line_rest = parts[1] if len(parts) > 1 else ""
        if os.path.isfile(file_ref) and line_rest.isdigit():
            line_num = int(line_rest)
            ctx = _context_around(file_ref, line_num)
            first_failure_text = f"{first_fail_line}\n---\n{ctx}"
        else:
            first_failure_text = first_fail_line

    with open(first_failure_file, "w", encoding="utf-8") as f:
        f.write(first_failure_text)

    # --- pressure analysis ---
    max_pressure_val = ""
    max_pressure_ref = ""
    first_pressure_075 = ""
    first_pressure_090 = ""

    for line in pressure_lines:
        value = _extract_pressure_value(line)
        if value is None:
            continue
        if not max_pressure_val or _compare_gt(value, max_pressure_val):
            max_pressure_val = value
            max_pressure_ref = line
        if not first_pressure_075 and _compare_ge(value, 0.75):
            first_pressure_075 = line
        if not first_pressure_090 and _compare_ge(value, 0.90):
            first_pressure_090 = line

    # --- anomalies.json ---
    anomalies_json_path = artifact_dir / "anomalies.json"
    generated_at = utcnow_iso()

    pattern_entries = []
    for pid, count, sample in pattern_results:
        pattern_entries.append(
            f'    {{"id":"{json_escape(pid)}","count":{count},"sample":"{json_escape(sample)}"}}'
        )

    top_signal_entries = []
    for line in top_lines:
        if line:
            top_signal_entries.append(f'    "{json_escape(line)}"')

    json_lines = [
        "{",
        f'  "generated_at": "{generated_at}",',
        f'  "run_dir": "{json_escape(run_dir)}",',
        '  "pattern_counts": [',
        ",\n".join(pattern_entries),
        "  ],",
        '  "pressure": {',
        f'    "max": {{"value": "{json_escape(max_pressure_val)}", "ref": "{json_escape(max_pressure_ref)}"}},',
        f'    "first_ge_075": "{json_escape(first_pressure_075)}",',
        f'    "first_ge_090": "{json_escape(first_pressure_090)}"',
        "  },",
        f'  "first_failing_trace": "{json_escape(first_failure_text)}",',
        '  "top_signals": [',
        ",\n".join(top_signal_entries),
        "  ]",
        "}",
    ]
    anomalies_json_path.write_text("\n".join(json_lines) + "\n", encoding="utf-8")

    # --- anomalies.md ---
    anomalies_md_path = artifact_dir / "anomalies.md"
    md_lines = [
        "# Anomaly Triage",
        "",
        f"- Run dir: `{run_dir}`",
        f"- Generated at: `{generated_at}`",
        "",
        "## Pattern Counts",
    ]
    for pid, count, sample in pattern_results:
        md_lines.append(f"- `{pid}`: {count}")
        if sample:
            md_lines.append(f"  sample: `{sample}`")
    md_lines.append("")
    md_lines.append("## Pressure Spikes")
    md_lines.append(f"- max decision pressure: `{max_pressure_val or 'none'}`")
    if max_pressure_ref:
        md_lines.append(f"  ref: `{max_pressure_ref}`")
    if first_pressure_075:
        md_lines.append(f"- first >= 0.75: `{first_pressure_075}`")
    else:
        md_lines.append("- first >= 0.75: none")
    if first_pressure_090:
        md_lines.append(f"- first >= 0.90: `{first_pressure_090}`")
    else:
        md_lines.append("- first >= 0.90: none")
    md_lines.append("")
    md_lines.append("## First Failing Trace Snippet")
    if first_failure_text:
        md_lines.append("```text")
        md_lines.append(first_failure_text)
        md_lines.append("```")
    else:
        md_lines.append("- none")
    md_lines.append("")
    md_lines.append("## Top Signals")
    if top_lines:
        for line in top_lines:
            if line:
                md_lines.append(f"- `{line}`")
    else:
        md_lines.append("- none")

    anomalies_md_path.write_text("\n".join(md_lines) + "\n", encoding="utf-8")

    return str(anomalies_json_path)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Heuristic triage of a Freud run directory.",
        usage="freud/scripts/triage-run.sh <run_dir> [--top N]",
    )
    parser.add_argument("run_dir", help="Path to the Freud run directory")
    parser.add_argument("--top", type=int, default=20, help="Max top signals to keep")
    args = parser.parse_args()

    result = triage(args.run_dir, top_n=args.top)
    print(result)


if __name__ == "__main__":
    main()
