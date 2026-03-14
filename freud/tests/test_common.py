"""Tests for freud.py.common utilities."""

from __future__ import annotations

import json
from pathlib import Path

from freud.py.common import (
    count_lines,
    extract_json_number,
    extract_json_string,
    head_lines,
    json_escape,
    non_empty_lines,
    pct,
    tsv_escape,
)


class TestJsonEscape:
    def test_plain(self):
        assert json_escape("hello") == "hello"

    def test_quotes(self):
        assert json_escape('he said "hi"') == 'he said \\"hi\\"'

    def test_backslash(self):
        assert json_escape("path\\to") == "path\\\\to"

    def test_newlines(self):
        assert json_escape("line1\nline2") == "line1\\nline2"

    def test_tabs(self):
        assert json_escape("a\tb") == "a\\tb"

    def test_carriage_return(self):
        assert json_escape("a\rb") == "ab"

    def test_empty(self):
        assert json_escape("") == ""

    def test_combined(self):
        result = json_escape('a"b\nc\\d\te')
        assert result == 'a\\"b\\nc\\\\d\\te'


class TestTsvEscape:
    def test_plain(self):
        assert tsv_escape("hello") == "hello"

    def test_tabs(self):
        assert tsv_escape("a\tb") == "a b"

    def test_newlines(self):
        assert tsv_escape("a\nb") == "a b"

    def test_carriage_return(self):
        assert tsv_escape("a\rb") == "a b"

    def test_empty(self):
        assert tsv_escape("") == ""


class TestExtractJsonString:
    def test_extracts_value(self, tmp_path: Path):
        f = tmp_path / "test.json"
        f.write_text('{\n  "feature_id": "my-feat",\n  "status": "pass"\n}')
        assert extract_json_string(f, "feature_id") == "my-feat"

    def test_missing_key(self, tmp_path: Path):
        f = tmp_path / "test.json"
        f.write_text('{\n  "other": "val"\n}')
        assert extract_json_string(f, "feature_id") == ""

    def test_missing_file(self, tmp_path: Path):
        assert extract_json_string(tmp_path / "nope.json", "key") == ""

    def test_no_trailing_comma(self, tmp_path: Path):
        f = tmp_path / "test.json"
        f.write_text('{\n  "status": "pass"\n}')
        assert extract_json_string(f, "status") == "pass"


class TestExtractJsonNumber:
    def test_extracts_value(self, tmp_path: Path):
        f = tmp_path / "test.json"
        f.write_text('{\n  "steps_failed": 3,\n  "count": 42\n}')
        assert extract_json_number(f, "steps_failed") == "3"

    def test_missing_key(self, tmp_path: Path):
        f = tmp_path / "test.json"
        f.write_text('{\n  "other": 1\n}')
        assert extract_json_number(f, "count") == ""

    def test_missing_file(self, tmp_path: Path):
        assert extract_json_number(tmp_path / "nope.json", "count") == ""

    def test_zero(self, tmp_path: Path):
        f = tmp_path / "test.json"
        f.write_text('{\n  "count": 0\n}')
        assert extract_json_number(f, "count") == "0"


class TestPct:
    def test_basic(self):
        assert pct(1, 3) == "33.33"

    def test_zero_denominator(self):
        assert pct(5, 0) == "0.00"

    def test_hundred_percent(self):
        assert pct(10, 10) == "100.00"

    def test_zero_numerator(self):
        assert pct(0, 5) == "0.00"


class TestCountLines:
    def test_counts(self, tmp_path: Path):
        f = tmp_path / "test.txt"
        f.write_text("a\nb\nc\n")
        assert count_lines(f) == 3

    def test_missing_file(self, tmp_path: Path):
        assert count_lines(tmp_path / "nope.txt") == 0

    def test_empty_file(self, tmp_path: Path):
        f = tmp_path / "empty.txt"
        f.write_text("")
        assert count_lines(f) == 0


class TestHeadLines:
    def test_basic(self, tmp_path: Path):
        f = tmp_path / "test.txt"
        f.write_text("a\nb\nc\nd\ne\n")
        assert head_lines(f, 3) == ["a", "b", "c"]

    def test_fewer_lines(self, tmp_path: Path):
        f = tmp_path / "test.txt"
        f.write_text("a\nb\n")
        assert head_lines(f, 5) == ["a", "b"]

    def test_missing_file(self, tmp_path: Path):
        assert head_lines(tmp_path / "nope.txt", 3) == []


class TestNonEmptyLines:
    def test_filters(self):
        assert non_empty_lines("a\n\nb\n\nc") == ["a", "b", "c"]

    def test_empty(self):
        assert non_empty_lines("") == []
