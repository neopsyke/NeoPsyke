#!/usr/bin/env python3
"""Generate compact run summaries.

Port of freud/scripts/summarize-run.sh — reads indexed artifacts from a Freud
run directory and produces:
  artifacts/summary-compact.md
  artifacts/summary-compact.json
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from freud.py.common import (
    extract_json_number,
    extract_json_string,
    head_lines,
    json_escape,
    rg_search,
)


def summarize(run_dir: str) -> str:
    """Generate compact summary for *run_dir*. Returns path to summary-compact.md."""
    run_path = Path(run_dir)
    if not run_path.is_dir():
        print(f"Run directory does not exist: {run_dir}", file=sys.stderr)
        sys.exit(1)

    artifact_dir = run_path / "artifacts"
    summary_json = artifact_dir / "summary.json"
    steps_file = artifact_dir / "step-index.tsv"
    trail_file = artifact_dir / "trail.jsonl"
    trail_index_file = artifact_dir / "trail-index.tsv"
    run_config_file = artifact_dir / "run-config.json"
    step_meta_dir = artifact_dir / "step-meta"
    freud_metrics_json = artifact_dir / "freud-metrics.json"
    summary_md = artifact_dir / "summary-compact.md"
    summary_compact_json = artifact_dir / "summary-compact.json"
    anomalies_md = artifact_dir / "anomalies.md"

    feature_id = extract_json_string(summary_json, "feature_id")
    run_id = extract_json_string(summary_json, "run_id")
    status = extract_json_string(summary_json, "status")
    mode = extract_json_string(summary_json, "mode")
    steps_failed = extract_json_number(summary_json, "steps_failed")
    failed_test_count = extract_json_number(summary_json, "failed_test_count")

    # First failed step from step-index.tsv
    first_failed_step = ""
    if steps_file.is_file():
        for line in steps_file.read_text(encoding="utf-8", errors="replace").splitlines()[1:]:
            cols = line.split("\t")
            if len(cols) >= 2 and cols[1] == "fail":
                first_failed_step = cols[0]
                break

    # Trail event count
    trail_events = 0
    if trail_file.is_file():
        trail_events = sum(
            1 for line in trail_file.read_text(encoding="utf-8", errors="replace").splitlines()
            if line.strip()
        )

    # First warning from top-signals.tsv
    top_warning_line = ""
    top_signals_path = artifact_dir / "top-signals.tsv"
    if top_signals_path.is_file():
        raw = rg_search("warning", str(top_signals_path), case_insensitive=True,
                         line_numbers=True)
        lines = [l for l in raw.splitlines() if l]
        if lines:
            top_warning_line = lines[0]

    # --- summary-compact.md ---
    md: list[str] = [
        "# Freud Compact Summary",
        "",
        f"- run_id: `{run_id or 'unknown'}`",
        f"- feature_id: `{feature_id or 'unknown'}`",
        f"- status: `{status or 'unknown'}`",
        f"- mode: `{mode or 'unknown'}`",
        f"- steps_failed: `{steps_failed or '0'}`",
        f"- failed_test_count: `{failed_test_count or '0'}`",
        f"- first_failed_step: `{first_failed_step or 'none'}`",
        f"- trail_events: `{trail_events}`",
        "",
        "## Quick Files",
        f"- `{artifact_dir}/summary.json`",
        f"- `{artifact_dir}/failures.json`",
        f"- `{artifact_dir}/anomalies.json`",
        f"- `{run_config_file}`",
        f"- `{freud_metrics_json}`",
        f"- `{artifact_dir}/step-index.tsv`",
        f"- `{trail_index_file}`",
        f"- `{artifact_dir}/trail.jsonl`",
        f"- `{step_meta_dir}/`",
        "",
    ]

    if steps_file.is_file():
        md.append("## Step Index Preview")
        md.append("```text")
        md.extend(head_lines(steps_file, 14))
        md.append("```")
        md.append("")

    if trail_index_file.is_file():
        md.append("## Trail Index Preview")
        md.append("```text")
        md.extend(head_lines(trail_index_file, 14))
        md.append("```")
        md.append("")

    if freud_metrics_json.is_file():
        md.append("## Freud Metrics Preview")
        md.extend(head_lines(freud_metrics_json, 40))
        md.append("")

    if top_warning_line:
        md.append("## First Warning Ref")
        md.append(f"- `{top_warning_line}`")
        md.append("")

    if anomalies_md.is_file():
        md.append("## Triage Preview")
        md.extend(head_lines(anomalies_md, 40))

    summary_md.write_text("\n".join(md) + "\n", encoding="utf-8")

    # --- summary-compact.json ---
    json_lines = [
        "{",
        f'  "run_id": "{json_escape(run_id)}",',
        f'  "feature_id": "{json_escape(feature_id)}",',
        f'  "status": "{json_escape(status)}",',
        f'  "mode": "{json_escape(mode)}",',
        f'  "steps_failed": {steps_failed or 0},',
        f'  "failed_test_count": {failed_test_count or 0},',
        f'  "first_failed_step": "{json_escape(first_failed_step)}",',
        f'  "trail_events": {trail_events}',
        "}",
    ]
    summary_compact_json.write_text("\n".join(json_lines) + "\n", encoding="utf-8")

    return str(summary_md)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate compact Freud run summary.",
        usage="freud/scripts/summarize-run.sh <run_dir>",
    )
    parser.add_argument("run_dir", help="Path to the Freud run directory")
    args = parser.parse_args()

    result = summarize(args.run_dir)
    print(result)


if __name__ == "__main__":
    main()
