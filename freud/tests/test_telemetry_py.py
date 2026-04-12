"""Tests for freud.py.telemetry modules."""

from __future__ import annotations

import json
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import pytest

from freud.py.telemetry.prompt_budget import prompt_budget_telemetry
from freud.py.telemetry.task_verifier import task_verifier_telemetry as grounding_gate_telemetry


# --- Fixtures ---

@pytest.fixture
def budget_events_file(tmp_path: Path) -> Path:
    events = [
        {"type": "prompt_budget_allocation", "data": {
            "call_site": "planner", "single_message_fallback": True,
            "floor_violation_count": 1, "dropped_section_count": 2,
            "allocated_total_cost": 100.0, "reserved_floor_cost": 30.0,
            "degradation_path": "fallback",
        }},
        {"type": "prompt_budget_allocation", "data": {
            "call_site": "planner", "single_message_fallback": False,
            "floor_violation_count": 0, "dropped_section_count": 0,
            "allocated_total_cost": 80.0, "reserved_floor_cost": 20.0,
        }},
        {"type": "prompt_budget_allocation", "data": {
            "call_site": "action_executor", "single_message_fallback": False,
            "floor_violation_count": 0, "dropped_section_count": 0,
            "allocated_total_cost": 60.0, "reserved_floor_cost": 15.0,
        }},
        {"type": "other_event", "data": {"irrelevant": True}},
    ]
    f = tmp_path / "budget-events.jsonl"
    f.write_text("\n".join(json.dumps(e) for e in events) + "\n")
    return f


@pytest.fixture
def grounding_events_file(tmp_path: Path) -> Path:
    events = [
        {"type": "grounding_gate_review", "data": {
            "allow": True,
            "grounding_required": False,
            "evidence_gathered": False,
            "evidence_failed_technically": False,
            "evidence_unavailable": False,
            "forced_terminal": False,
        }},
        {"type": "grounding_gate_review", "data": {
            "allow": False,
            "reason_code": "GROUNDING_EVIDENCE_REQUIRED",
            "grounding_required": True,
            "evidence_gathered": False,
            "evidence_failed_technically": False,
            "evidence_unavailable": False,
            "forced_terminal": False,
        }},
        {"type": "grounding_gate_review", "data": {
            "allow": True,
            "reason_code": "GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL",
            "grounding_required": True,
            "evidence_gathered": False,
            "evidence_failed_technically": False,
            "evidence_unavailable": True,
            "forced_terminal": False,
        }},
    ]
    f = tmp_path / "grounding-events.jsonl"
    f.write_text("\n".join(json.dumps(e) for e in events) + "\n")
    return f


@pytest.fixture
def empty_events_file(tmp_path: Path) -> Path:
    f = tmp_path / "empty.jsonl"
    f.write_text('{"type": "other_event", "data": {}}\n')
    return f


# --- Prompt Budget Tests ---

class TestPromptBudget:
    def test_totals(self, budget_events_file: Path, capsys):
        prompt_budget_telemetry(str(budget_events_file))
        out = capsys.readouterr().out
        assert "allocations: 3" in out
        assert "single_message_fallback: 1" in out
        assert "floor_violation_events: 1" in out
        assert "dropped_sections_total: 2" in out

    def test_call_site_breakdown(self, budget_events_file: Path, capsys):
        prompt_budget_telemetry(str(budget_events_file))
        out = capsys.readouterr().out
        assert "planner: 2" in out
        assert "action_executor: 1" in out

    def test_tuning_hints_fallback(self, budget_events_file: Path, capsys):
        prompt_budget_telemetry(str(budget_events_file))
        out = capsys.readouterr().out
        assert "single-message fallback occurred" in out

    def test_tuning_hints_floor(self, budget_events_file: Path, capsys):
        prompt_budget_telemetry(str(budget_events_file))
        out = capsys.readouterr().out
        assert "floor violations occurred" in out

    def test_tuning_hints_dropped(self, budget_events_file: Path, capsys):
        prompt_budget_telemetry(str(budget_events_file))
        out = capsys.readouterr().out
        assert "sections were dropped" in out

    def test_no_events(self, empty_events_file: Path, capsys):
        prompt_budget_telemetry(str(empty_events_file))
        out = capsys.readouterr().out
        assert "No prompt_budget_allocation events" in out

    def test_missing_file(self):
        with pytest.raises(SystemExit) as exc:
            prompt_budget_telemetry("/nonexistent/file.jsonl")
        assert exc.value.code == 1


# --- Grounding Gate Tests ---

class TestGroundingGate:
    def test_totals(self, grounding_events_file: Path, capsys):
        grounding_gate_telemetry(str(grounding_events_file))
        out = capsys.readouterr().out
        assert "reviews: 3" in out
        assert "allow: 2" in out
        assert "deny: 1" in out

    def test_grounding_required(self, grounding_events_file: Path, capsys):
        grounding_gate_telemetry(str(grounding_events_file))
        out = capsys.readouterr().out
        assert "grounding_required: 2" in out

    def test_evidence_unavailable(self, grounding_events_file: Path, capsys):
        grounding_gate_telemetry(str(grounding_events_file))
        out = capsys.readouterr().out
        assert "evidence_unavailable: 1" in out

    def test_reason_code_breakdown(self, grounding_events_file: Path, capsys):
        grounding_gate_telemetry(str(grounding_events_file))
        out = capsys.readouterr().out
        assert "GROUNDING_EVIDENCE_REQUIRED" in out
        assert "GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL" in out

    def test_tuning_hint_unavailable(self, grounding_events_file: Path, capsys):
        grounding_gate_telemetry(str(grounding_events_file))
        out = capsys.readouterr().out
        assert "evidence unavailable observed" in out

    def test_no_events(self, empty_events_file: Path, capsys):
        grounding_gate_telemetry(str(empty_events_file))
        out = capsys.readouterr().out
        assert "No grounding_gate_review events" in out

    def test_missing_file(self):
        with pytest.raises(SystemExit) as exc:
            grounding_gate_telemetry("/nonexistent/file.jsonl")
        assert exc.value.code == 1
