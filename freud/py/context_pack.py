#!/usr/bin/env python3
"""Build a compact context pack for run triage.

Port of freud/scripts/context-pack.sh — bundles artifact paths, failed steps,
trail preview, working-tree diff, and triage snapshot into:
  artifacts/context-pack.md
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

from freud.py.common import (
    extract_json_number,
    extract_json_string,
    head_lines,
    repo_root,
)


def context_pack(run_dir: str) -> str:
    """Generate context-pack.md for *run_dir*. Returns path to context-pack.md."""
    run_path = Path(run_dir)
    if not run_path.is_dir():
        print(f"Run directory does not exist: {run_dir}", file=sys.stderr)
        sys.exit(1)

    root = repo_root()
    artifact_dir = run_path / "artifacts"
    summary_json = artifact_dir / "summary.json"
    steps_file = artifact_dir / "steps.tsv"
    step_index_file = artifact_dir / "step-index.tsv"
    anomalies_md = artifact_dir / "anomalies.md"
    trail_file = artifact_dir / "trail.jsonl"
    trail_index_file = artifact_dir / "trail-index.tsv"
    run_config_file = artifact_dir / "run-config.json"
    step_meta_dir = artifact_dir / "step-meta"
    freud_metrics_json = artifact_dir / "freud-metrics.json"
    compact_summary_md = artifact_dir / "summary-compact.md"
    run_index_md = artifact_dir / "run-index.md"
    context_md = artifact_dir / "context-pack.md"

    feature_id = extract_json_string(summary_json, "feature_id")
    run_id = extract_json_string(summary_json, "run_id")
    status = extract_json_string(summary_json, "status")
    mode = extract_json_string(summary_json, "mode")
    steps_failed = extract_json_number(summary_json, "steps_failed")
    steps_total = extract_json_number(summary_json, "steps_total")

    md: list[str] = [
        "# Freud Context Pack",
        "",
        "Read this file first when triaging a Freud run. It provides a run snapshot,",
        "ordered artifact paths (cheapest first), failure details, and triage highlights.",
        "",
        "## Run Snapshot",
        f"- feature_id: `{feature_id or 'unknown'}`",
        f"- run_id: `{run_id or 'unknown'}`",
        f"- status: `{status or 'unknown'}`",
        f"- mode: `{mode or 'unknown'}`",
        f"- steps_failed: `{steps_failed or '0'}` / `{steps_total or '0'}`",
        f"- run_dir: `{run_dir}`",
        "",
        "## Start Here (Low Token)",
        f"- `{compact_summary_md}`",
        f"- `{run_index_md}`",
        f"- `{summary_json}`",
        f"- `{artifact_dir}/anomalies.json`",
        f"- `{run_config_file}`",
        f"- `{freud_metrics_json}`",
        f"- `{step_index_file}`",
        f"- `{trail_index_file}`",
        f"- `{trail_file}`",
        f"- `{step_meta_dir}/`",
        "",
        "## Failed Steps",
    ]

    if steps_file.is_file():
        failed_count = 0
        for line in steps_file.read_text(encoding="utf-8", errors="replace").splitlines():
            cols = line.split("\t")
            if len(cols) >= 4 and cols[1] == "fail":
                failed_count += 1
                md.append(f"- `{cols[0]}` (`{cols[2]}s`) log: `{cols[3]}`")
        if failed_count == 0:
            md.append("- none")
    else:
        md.append("- steps.tsv missing")
    md.append("")

    md.append("## Trail Preview")
    if trail_file.is_file():
        md.append("```jsonl")
        md.extend(head_lines(trail_file, 12))
        md.append("```")
    else:
        md.append("- trail.jsonl missing")
    md.append("")

    md.append("## Trail Index Preview")
    if trail_index_file.is_file():
        md.append("```text")
        md.extend(head_lines(trail_index_file, 20))
        md.append("```")
    else:
        md.append("- trail-index.tsv missing")
    md.append("")

    # Working tree diff
    md.append("## Working Tree Diff")
    diff_failed = False
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "diff", "--name-only"],
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode != 0:
            diff_files = ""
            diff_failed = True
            print(f"[freud] WARNING: git diff failed (exit {result.returncode}): "
                  f"{result.stderr.strip()}", file=sys.stderr)
        else:
            diff_files = result.stdout.strip()
    except OSError:
        diff_files = ""
        diff_failed = True
        print("[freud] WARNING: git not available for working tree diff", file=sys.stderr)
    except subprocess.TimeoutExpired:
        diff_files = ""
        diff_failed = True
        print("[freud] WARNING: git diff timed out", file=sys.stderr)

    if diff_failed:
        md.append("- git diff unavailable")
    elif diff_files:
        md.append("```text")
        md.extend(diff_files.splitlines()[:120])
        md.append("```")
    else:
        md.append("- clean working tree")
    md.append("")

    md.append("## Triage Snapshot")
    if anomalies_md.is_file():
        md.extend(head_lines(anomalies_md, 120))
    else:
        md.append("- anomalies.md missing")
    md.append("")

    fid = feature_id or "<feature-id>"
    md.extend([
        "## How To Use",
        "1. Check the Run Snapshot above for pass/fail and which steps broke.",
        "2. Read files from **Start Here** in order (they are sorted cheapest-first).",
        "3. For each failed step, read its `step-meta/<step>.json` for command, timing, and first error ref.",
        "4. Only open raw `logs/<step>.log` if the indexed artifacts above are not enough.",
        f"5. After fixing, re-run: `freud/scripts/feature-loop.sh {fid}`",
    ])

    context_md.write_text("\n".join(md) + "\n", encoding="utf-8")
    return str(context_md)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build compact context pack for Freud run triage.",
        usage="freud/scripts/context-pack.sh <run_dir>",
    )
    parser.add_argument("run_dir", help="Path to the Freud run directory")
    args = parser.parse_args()

    result = context_pack(args.run_dir)
    print(result)


if __name__ == "__main__":
    main()
