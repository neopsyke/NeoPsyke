"""Shared utilities for Freud Python modules.

Mirrors the helper functions found in the Bash scripts (json_escape, tsv_escape,
extract_json_string, extract_json_number, pct) and adds common file-reading helpers.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path


def _warn(msg: str) -> None:
    """Print a warning to stderr with [freud] prefix."""
    print(f"[freud] WARNING: {msg}", file=sys.stderr)


def json_escape(value: str) -> str:
    """Escape a string for safe embedding inside a JSON string literal.

    Mirrors the Bash json_escape() used across Freud scripts.
    """
    value = value.replace("\\", "\\\\")
    value = value.replace('"', '\\"')
    value = value.replace("\n", "\\n")
    value = value.replace("\r", "")
    value = value.replace("\t", "\\t")
    return value


def tsv_escape(value: str) -> str:
    """Collapse newlines, carriage returns and tabs to spaces for TSV fields."""
    value = value.replace("\n", " ")
    value = value.replace("\r", " ")
    value = value.replace("\t", " ")
    return value


def extract_json_string(filepath: str | Path, key: str) -> str:
    """Extract a string value from a simple JSON file using regex (no json module).

    Mirrors the sed-based extract_json_string() in Bash. Returns empty string on
    any failure (missing file, missing key).
    """
    filepath = Path(filepath)
    if not filepath.is_file():
        return ""
    pattern = re.compile(
        rf'^\s*"{re.escape(key)}"\s*:\s*"(.*)"\s*,?\s*$'
    )
    try:
        for line in filepath.read_text(encoding="utf-8", errors="replace").splitlines():
            m = pattern.match(line)
            if m:
                return m.group(1)
    except OSError:
        pass
    return ""


def extract_json_number(filepath: str | Path, key: str) -> str:
    """Extract an integer value from a simple JSON file using regex.

    Returns empty string on any failure (missing file, missing key).
    """
    filepath = Path(filepath)
    if not filepath.is_file():
        return ""
    pattern = re.compile(
        rf'^\s*"{re.escape(key)}"\s*:\s*([0-9]+)\s*,?\s*$'
    )
    try:
        for line in filepath.read_text(encoding="utf-8", errors="replace").splitlines():
            m = pattern.match(line)
            if m:
                return m.group(1)
    except OSError:
        pass
    return ""


def pct(numerator: int, denominator: int) -> str:
    """Return a percentage string with 2 decimal places. Returns '0.00' when denominator is 0."""
    if denominator == 0:
        return "0.00"
    return f"{(numerator * 100.0) / denominator:.2f}"


def count_lines(filepath: str | Path) -> int:
    """Count non-empty lines in a file. Returns 0 if file doesn't exist."""
    filepath = Path(filepath)
    if not filepath.is_file():
        return 0
    try:
        text = filepath.read_text(encoding="utf-8", errors="replace")
        return sum(1 for line in text.splitlines() if line)
    except OSError:
        return 0


def head_lines(filepath: str | Path, n: int) -> list[str]:
    """Return the first *n* lines of a file. Returns [] if file doesn't exist."""
    filepath = Path(filepath)
    if not filepath.is_file():
        return []
    try:
        lines = filepath.read_text(encoding="utf-8", errors="replace").splitlines()
        return lines[:n]
    except OSError:
        return []


def read_file_text(filepath: str | Path) -> str:
    """Read entire file as text. Returns empty string on failure."""
    filepath = Path(filepath)
    try:
        return filepath.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def rg_search(pattern: str, directory: str | Path, *, case_insensitive: bool = True,
              line_numbers: bool = True, invert_match: str | None = None) -> str:
    """Run ripgrep and return raw output. Returns empty string if no matches or rg unavailable."""
    directory = str(directory)
    cmd = ["rg"]
    if case_insensitive:
        cmd.append("-i")
    if line_numbers:
        cmd.extend(["-n", "-H"])
    cmd.extend(["-e", pattern, directory])
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        # rg exit code 1 = no matches (normal), 2+ = actual error
        if result.returncode >= 2:
            _warn(f"rg failed (exit {result.returncode}): {result.stderr.strip()}")
            return ""
        output = result.stdout
    except OSError:
        _warn("ripgrep (rg) not found; install it for log analysis")
        return ""
    except subprocess.TimeoutExpired:
        _warn(f"rg timed out searching '{pattern}' in {directory}")
        return ""
    if invert_match and output:
        inv_cmd = ["rg", "-v", invert_match]
        try:
            result = subprocess.run(inv_cmd, input=output, capture_output=True, text=True, timeout=30)
            if result.returncode >= 2:
                _warn(f"rg invert-match filter failed (exit {result.returncode})")
            else:
                output = result.stdout
        except OSError:
            _warn("rg not available for invert-match filter")
        except subprocess.TimeoutExpired:
            _warn(f"rg invert-match filter timed out for pattern '{invert_match}'")
    return output


def non_empty_lines(text: str) -> list[str]:
    """Split text into lines and filter out empty ones."""
    return [line for line in text.splitlines() if line]


def repo_root() -> Path:
    """Return the repository root (two levels up from freud/py/)."""
    return Path(__file__).resolve().parent.parent.parent


def utcnow_iso() -> str:
    """Return current UTC time as ISO 8601 string (no microseconds)."""
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_json_file(filepath: str | Path, obj: dict) -> None:
    """Write a dict as formatted JSON to a file."""
    filepath = Path(filepath)
    try:
        filepath.parent.mkdir(parents=True, exist_ok=True)
        filepath.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")
    except OSError as e:
        _warn(f"failed to write {filepath}: {e}")
        raise
