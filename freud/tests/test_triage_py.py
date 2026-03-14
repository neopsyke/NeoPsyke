"""Tests for freud.py.triage."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from freud.py.triage import triage


@pytest.fixture
def triage_run_dir(tmp_path: Path) -> Path:
    """Create a run dir with logs for triage testing."""
    rd = tmp_path / "run"
    logs = rd / "logs"
    artifacts = rd / "artifacts"
    logs.mkdir(parents=True)
    artifacts.mkdir(parents=True)
    return rd


def _write_log(logs_dir: Path, name: str, content: str) -> None:
    (logs_dir / name).write_text(content)


class TestTriageBasics:
    def test_generates_anomalies_json(self, triage_run_dir: Path):
        _write_log(triage_run_dir / "logs", "test.log", "all good\n")
        result = triage(str(triage_run_dir))
        assert result.endswith("anomalies.json")
        assert (triage_run_dir / "artifacts" / "anomalies.json").is_file()

    def test_generates_anomalies_md(self, triage_run_dir: Path):
        _write_log(triage_run_dir / "logs", "test.log", "all good\n")
        triage(str(triage_run_dir))
        assert (triage_run_dir / "artifacts" / "anomalies.md").is_file()

    def test_anomalies_json_is_valid(self, triage_run_dir: Path):
        _write_log(triage_run_dir / "logs", "test.log", "error happened\nwarning here\n")
        triage(str(triage_run_dir))
        data = json.loads((triage_run_dir / "artifacts" / "anomalies.json").read_text())
        assert "pattern_counts" in data
        assert "pressure" in data
        assert "top_signals" in data

    def test_missing_run_dir_exits(self):
        with pytest.raises(SystemExit) as exc:
            triage("/nonexistent/path")
        assert exc.value.code == 1


class TestTriageCleanLogs:
    def test_zero_pattern_counts(self, triage_run_dir: Path):
        _write_log(triage_run_dir / "logs", "clean.log", "all good\nnothing to see\n")
        triage(str(triage_run_dir))
        data = json.loads((triage_run_dir / "artifacts" / "anomalies.json").read_text())
        for entry in data["pattern_counts"]:
            assert entry["count"] == 0


class TestTriagePatternDetection:
    def test_detects_errors(self, triage_run_dir: Path):
        _write_log(triage_run_dir / "logs", "test.log",
                    "parse error on line 5\nfailed to parse response\n")
        triage(str(triage_run_dir))
        data = json.loads((triage_run_dir / "artifacts" / "anomalies.json").read_text())
        parse = next(e for e in data["pattern_counts"] if e["id"] == "parse_failures")
        assert parse["count"] >= 2

    def test_top_signals_populated(self, triage_run_dir: Path):
        _write_log(triage_run_dir / "logs", "test.log",
                    "error: something broke\nwarning: watch out\n")
        triage(str(triage_run_dir))
        signals = (triage_run_dir / "artifacts" / "top-signals.tsv").read_text()
        assert "error" in signals.lower() or "warning" in signals.lower()


class TestTriagePressure:
    def test_extracts_pressure(self, triage_run_dir: Path):
        lines = "\n".join([
            "step1 decision_pressure=0.45 ok",
            "step2 decision_pressure=0.72 ok",
            "step3 decision_pressure=0.88 caution",
            "step4 decision_pressure=0.91 critical",
        ])
        _write_log(triage_run_dir / "logs", "pressure.log", lines)
        triage(str(triage_run_dir))
        data = json.loads((triage_run_dir / "artifacts" / "anomalies.json").read_text())
        assert data["pressure"]["max"]["value"] == "0.91"

    def test_pressure_thresholds(self, triage_run_dir: Path):
        lines = "\n".join([
            "step1 decision_pressure=0.45 ok",
            "step2 decision_pressure=0.76 threshold",
            "step3 decision_pressure=0.91 critical",
        ])
        _write_log(triage_run_dir / "logs", "pressure.log", lines)
        triage(str(triage_run_dir))
        data = json.loads((triage_run_dir / "artifacts" / "anomalies.json").read_text())
        assert "0.76" in data["pressure"]["first_ge_075"]
        assert "0.91" in data["pressure"]["first_ge_090"]


class TestTriageTopN:
    def test_top_flag_limits(self, triage_run_dir: Path):
        lines = "\n".join([f"error line {i}" for i in range(50)])
        _write_log(triage_run_dir / "logs", "test.log", lines)
        triage(str(triage_run_dir), top_n=5)
        signals = (triage_run_dir / "artifacts" / "top-signals.tsv").read_text().strip().splitlines()
        assert len(signals) <= 5


class TestTriageEmptyDirs:
    def test_empty_logs_dir(self, triage_run_dir: Path):
        triage(str(triage_run_dir))
        data = json.loads((triage_run_dir / "artifacts" / "anomalies.json").read_text())
        for entry in data["pattern_counts"]:
            assert entry["count"] == 0

    def test_no_logs_dir(self, tmp_path: Path):
        rd = tmp_path / "run"
        (rd / "artifacts").mkdir(parents=True)
        triage(str(rd))
        assert (rd / "artifacts" / "anomalies.json").is_file()
