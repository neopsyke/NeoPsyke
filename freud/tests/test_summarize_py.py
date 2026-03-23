"""Tests for freud.py.summarize."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from freud.py.summarize import summarize


class TestSummarizeBasics:
    def test_generates_md(self, run_dir: Path):
        result = summarize(str(run_dir))
        assert result.endswith("summary-compact.md")
        assert (run_dir / "artifacts" / "summary-compact.md").is_file()

    def test_generates_json(self, run_dir: Path):
        summarize(str(run_dir))
        assert (run_dir / "artifacts" / "summary-compact.json").is_file()

    def test_json_is_valid(self, run_dir: Path):
        summarize(str(run_dir))
        data = json.loads((run_dir / "artifacts" / "summary-compact.json").read_text())
        assert "run_id" in data
        assert "feature_id" in data

    def test_missing_run_dir_exits(self):
        with pytest.raises(SystemExit) as exc:
            summarize("/nonexistent/path")
        assert exc.value.code == 1


class TestSummarizeExtraction:
    def test_feature_id(self, run_dir: Path):
        summarize(str(run_dir))
        data = json.loads((run_dir / "artifacts" / "summary-compact.json").read_text())
        assert data["feature_id"] == "test-feature"

    def test_status(self, run_dir: Path):
        summarize(str(run_dir))
        data = json.loads((run_dir / "artifacts" / "summary-compact.json").read_text())
        assert data["status"] == "fail"

    def test_steps_failed(self, run_dir: Path):
        summarize(str(run_dir))
        data = json.loads((run_dir / "artifacts" / "summary-compact.json").read_text())
        assert data["steps_failed"] == 1

    def test_first_failed_step(self, run_dir: Path):
        summarize(str(run_dir))
        data = json.loads((run_dir / "artifacts" / "summary-compact.json").read_text())
        assert data["first_failed_step"] == "full_tests"

    def test_trail_events(self, run_dir: Path):
        summarize(str(run_dir))
        data = json.loads((run_dir / "artifacts" / "summary-compact.json").read_text())
        assert data["trail_events"] == 2

    def test_md_status_line(self, run_dir: Path):
        summarize(str(run_dir))
        md = (run_dir / "artifacts" / "summary-compact.md").read_text()
        assert "status: `fail`" in md


class TestSummarizePassRun:
    def test_pass_status(self, run_dir_pass: Path):
        summarize(str(run_dir_pass))
        data = json.loads((run_dir_pass / "artifacts" / "summary-compact.json").read_text())
        assert data["status"] == "pass"


class TestSummarizeEdgeCases:
    def test_missing_step_index(self, run_dir: Path):
        (run_dir / "artifacts" / "step-index.tsv").unlink()
        summarize(str(run_dir))
        assert (run_dir / "artifacts" / "summary-compact.md").is_file()

    def test_freud_metrics_preview(self, run_dir: Path):
        summarize(str(run_dir))
        md = (run_dir / "artifacts" / "summary-compact.md").read_text()
        assert "Freud Metrics Preview" in md
