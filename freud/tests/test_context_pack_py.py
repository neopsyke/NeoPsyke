"""Tests for freud.py.context_pack."""

from __future__ import annotations

from pathlib import Path

import pytest

from freud.py.context_pack import context_pack


class TestContextPackBasics:
    def test_generates_md(self, run_dir: Path):
        result = context_pack(str(run_dir))
        assert result.endswith("context-pack.md")
        assert (run_dir / "artifacts" / "context-pack.md").is_file()

    def test_missing_run_dir_exits(self):
        with pytest.raises(SystemExit) as exc:
            context_pack("/nonexistent/path")
        assert exc.value.code == 1

    def test_outputs_path(self, run_dir: Path):
        result = context_pack(str(run_dir))
        assert "context-pack.md" in result


class TestContextPackContent:
    def test_contains_feature_id(self, run_dir: Path):
        context_pack(str(run_dir))
        md = (run_dir / "artifacts" / "context-pack.md").read_text()
        assert "feature_id: `test-feature`" in md

    def test_contains_run_id(self, run_dir: Path):
        context_pack(str(run_dir))
        md = (run_dir / "artifacts" / "context-pack.md").read_text()
        assert "run_id: `20260314T120000Z`" in md

    def test_contains_status(self, run_dir: Path):
        context_pack(str(run_dir))
        md = (run_dir / "artifacts" / "context-pack.md").read_text()
        assert "status: `fail`" in md

    def test_lists_failed_steps(self, run_dir: Path):
        context_pack(str(run_dir))
        md = (run_dir / "artifacts" / "context-pack.md").read_text()
        assert "full_tests" in md

    def test_no_failures_shows_none(self, run_dir_pass: Path):
        context_pack(str(run_dir_pass))
        md = (run_dir_pass / "artifacts" / "context-pack.md").read_text()
        assert "none" in md

    def test_contains_start_here(self, run_dir: Path):
        context_pack(str(run_dir))
        md = (run_dir / "artifacts" / "context-pack.md").read_text()
        assert "Start Here" in md
        assert "summary.json" in md
        assert "freud-metrics.json" in md

    def test_contains_trail_preview(self, run_dir: Path):
        context_pack(str(run_dir))
        md = (run_dir / "artifacts" / "context-pack.md").read_text()
        assert "Trail Preview" in md

    def test_contains_how_to_use(self, run_dir: Path):
        context_pack(str(run_dir))
        md = (run_dir / "artifacts" / "context-pack.md").read_text()
        assert "How To Use" in md
