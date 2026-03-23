"""Tests for freud.py.telemetry modules."""

from __future__ import annotations

import json
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import pytest

from freud.py.telemetry.prompt_budget import prompt_budget_telemetry
from freud.py.telemetry.task_verifier import task_verifier_telemetry


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
def verifier_events_file(tmp_path: Path) -> Path:
    events = [
        {"type": "task_verifier_review", "data": {
            "allow": True, "reason_code": "TASK_VERIFIED",
            "intent_category": "factual", "volatility_level": "low",
            "requires_external_evidence": True,
        }},
        {"type": "task_verifier_review", "data": {
            "allow": True, "reason_code": "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL",
            "intent_category": "unknown", "volatility_level": "medium",
            "requires_external_evidence": True,
        }},
        {"type": "task_verifier_review", "data": {
            "allow": False, "reason_code": "VOLATILE_UNVERIFIED",
            "intent_category": "volatile_fact", "volatility_level": "high",
            "requires_external_evidence": False,
        }},
    ]
    f = tmp_path / "verifier-events.jsonl"
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


# --- Task Verifier Tests ---

class TestTaskVerifier:
    def test_totals(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "reviews: 3" in out
        assert "allow: 2" in out
        assert "deny: 1" in out

    def test_requires_external(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "requires_external_evidence: 2" in out

    def test_graceful_allows(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "graceful_allows: 1" in out

    def test_unknown_intent(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "unknown_intent: 1" in out

    def test_volatile_intent(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "volatile_intent: 1" in out

    def test_reason_code_breakdown(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "TASK_VERIFIED" in out
        assert "VOLATILE_UNVERIFIED" in out
        assert "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL" in out

    def test_tuning_hint_unknown(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "unknown intent observed" in out

    def test_tuning_hint_graceful(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "graceful allows occurred" in out

    def test_volatile_deny_rate(self, verifier_events_file: Path, capsys):
        task_verifier_telemetry(str(verifier_events_file))
        out = capsys.readouterr().out
        assert "volatile deny rate" in out

    def test_no_events(self, empty_events_file: Path, capsys):
        task_verifier_telemetry(str(empty_events_file))
        out = capsys.readouterr().out
        assert "No task_verifier_review events" in out

    def test_missing_file(self):
        with pytest.raises(SystemExit) as exc:
            task_verifier_telemetry("/nonexistent/file.jsonl")
        assert exc.value.code == 1
