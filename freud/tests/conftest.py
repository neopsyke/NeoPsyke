"""Shared pytest fixtures for Freud Python tests."""

from __future__ import annotations

import json
import os
import textwrap
from pathlib import Path

import pytest


@pytest.fixture
def run_dir(tmp_path: Path) -> Path:
    """Create a realistic Freud run directory with fixture artifacts."""
    rd = tmp_path / "run"
    artifacts = rd / "artifacts"
    logs = rd / "logs"
    step_meta = artifacts / "step-meta"

    for d in (artifacts, logs, step_meta):
        d.mkdir(parents=True)

    # summary.json
    (artifacts / "summary.json").write_text(json.dumps({
        "feature_id": "test-feature",
        "run_id": "20260314T120000Z",
        "status": "fail",
        "mode": "stub",
        "steps_total": 4,
        "steps_failed": 1,
        "failed_test_count": 2,
    }, indent=2))

    # steps.tsv (with header)
    (artifacts / "steps.tsv").write_text(
        "step\tstatus\tduration_sec\tlog\n"
        "preflight_compile\tpass\t5\t-\n"
        "targeted_tests\tpass\t10\t-\n"
        "full_tests\tfail\t30\tlogs/full_tests.log\n"
    )

    # step-index.tsv (with header)
    (artifacts / "step-index.tsv").write_text(
        "step\tstatus\tduration_sec\tlog\tlog_index\tlog_lines\twarnings\terrors\tfirst_warning\tfirst_error\tfirst_pressure\n"
        "preflight_compile\tpass\t5\t-\t-\t0\t0\t0\t-\t-\t-\n"
        "targeted_tests\tpass\t10\t-\t-\t0\t0\t0\t-\t-\t-\n"
        "full_tests\tfail\t30\tlogs/full_tests.log\t-\t100\t2\t1\tline3\tline10\t-\n"
    )

    # trail.jsonl
    (artifacts / "trail.jsonl").write_text(
        '{"seq":1,"ts":"2026-03-14T12:00:01Z","event":"step_start","step":"preflight_compile"}\n'
        '{"seq":2,"ts":"2026-03-14T12:00:06Z","event":"step_end","step":"preflight_compile","status":"pass"}\n'
    )

    # trail-index.tsv
    (artifacts / "trail-index.tsv").write_text(
        "seq\tts\tevent\tstep\tstatus\tlog\tref\tmessage\n"
        "1\t2026-03-14T12:00:01Z\tstep_start\tpreflight_compile\t-\t-\t-\t-\n"
    )

    # run-config.json
    (artifacts / "run-config.json").write_text(json.dumps({
        "feature_id": "test-feature",
        "mode": "stub",
    }, indent=2))

    # freud-metrics.json
    (artifacts / "freud-metrics.json").write_text(json.dumps({
        "steps_total": 4,
        "steps_passed": 3,
        "steps_failed": 1,
    }, indent=2))

    return rd


@pytest.fixture
def run_dir_pass(run_dir: Path) -> Path:
    """A passing run directory (no failures)."""
    artifacts = run_dir / "artifacts"

    (artifacts / "summary.json").write_text(json.dumps({
        "feature_id": "test-feature",
        "run_id": "20260314T120000Z",
        "status": "pass",
        "mode": "stub",
        "steps_total": 3,
        "steps_failed": 0,
        "failed_test_count": 0,
    }, indent=2))

    (artifacts / "steps.tsv").write_text(
        "step\tstatus\tduration_sec\tlog\n"
        "preflight_compile\tpass\t5\t-\n"
        "targeted_tests\tpass\t10\t-\n"
    )

    (artifacts / "step-index.tsv").write_text(
        "step\tstatus\n"
        "preflight_compile\tpass\n"
        "targeted_tests\tpass\n"
    )

    return run_dir
