#!/usr/bin/env bats
# Tests for shared helper functions: json_escape, tsv_escape, extract_json_string, extract_json_number.

setup() {
  load helpers/setup.bash
  load helpers/source_functions.bash
  source_feature_loop_helpers
  source_extract_helpers
  TEST_TMPDIR="$(mktemp -d)"
}

teardown() {
  [[ -d "${TEST_TMPDIR:-}" ]] && rm -rf "$TEST_TMPDIR"
}

# --- json_escape ---

@test "json_escape: plain string unchanged" {
  result="$(json_escape "hello world")"
  [[ "$result" == "hello world" ]]
}

@test "json_escape: escapes double quotes" {
  result="$(json_escape 'he said "hi"')"
  [[ "$result" == 'he said \"hi\"' ]]
}

@test "json_escape: escapes backslashes" {
  result="$(json_escape 'path\to\file')"
  [[ "$result" == 'path\\to\\file' ]]
}

@test "json_escape: escapes newlines" {
  input=$'line1\nline2'
  result="$(json_escape "$input")"
  [[ "$result" == 'line1\nline2' ]]
}

@test "json_escape: escapes tabs" {
  input=$'col1\tcol2'
  result="$(json_escape "$input")"
  [[ "$result" == 'col1\tcol2' ]]
}

@test "json_escape: strips carriage returns" {
  input=$'text\r\nmore'
  result="$(json_escape "$input")"
  [[ "$result" == 'text\nmore' ]]
}

@test "json_escape: empty string" {
  result="$(json_escape "")"
  [[ "$result" == "" ]]
}

@test "json_escape: combined special characters" {
  input=$'say "hi"\ttab\nnewline'
  result="$(json_escape "$input")"
  [[ "$result" == 'say \"hi\"\ttab\nnewline' ]]
}

# --- tsv_escape ---

@test "tsv_escape: plain string unchanged" {
  result="$(tsv_escape "hello world")"
  [[ "$result" == "hello world" ]]
}

@test "tsv_escape: converts tabs to spaces" {
  input=$'col1\tcol2'
  result="$(tsv_escape "$input")"
  [[ "$result" == "col1 col2" ]]
}

@test "tsv_escape: converts newlines to spaces" {
  input=$'line1\nline2'
  result="$(tsv_escape "$input")"
  [[ "$result" == "line1 line2" ]]
}

@test "tsv_escape: converts carriage returns to spaces" {
  input=$'text\rmore'
  result="$(tsv_escape "$input")"
  [[ "$result" == "text more" ]]
}

@test "tsv_escape: empty string" {
  result="$(tsv_escape "")"
  [[ "$result" == "" ]]
}

# --- extract_json_string ---

@test "extract_json_string: extracts quoted value" {
  echo '  "feature_id": "my-feature",' >"$TEST_TMPDIR/test.json"
  result="$(extract_json_string "$TEST_TMPDIR/test.json" "feature_id")"
  [[ "$result" == "my-feature" ]]
}

@test "extract_json_string: missing key returns empty" {
  echo '  "other_key": "value"' >"$TEST_TMPDIR/test.json"
  result="$(extract_json_string "$TEST_TMPDIR/test.json" "feature_id")"
  [[ "$result" == "" ]]
}

@test "extract_json_string: missing file returns empty" {
  result="$(extract_json_string "$TEST_TMPDIR/nonexistent.json" "feature_id")"
  [[ "$result" == "" ]]
}

@test "extract_json_string: value without trailing comma" {
  echo '  "status": "pass"' >"$TEST_TMPDIR/test.json"
  result="$(extract_json_string "$TEST_TMPDIR/test.json" "status")"
  [[ "$result" == "pass" ]]
}

# --- extract_json_number ---

@test "extract_json_number: extracts numeric value" {
  echo '  "steps_failed": 3,' >"$TEST_TMPDIR/test.json"
  result="$(extract_json_number "$TEST_TMPDIR/test.json" "steps_failed")"
  [[ "$result" == "3" ]]
}

@test "extract_json_number: missing key returns empty" {
  echo '  "other": 1' >"$TEST_TMPDIR/test.json"
  result="$(extract_json_number "$TEST_TMPDIR/test.json" "steps_failed")"
  [[ "$result" == "" ]]
}

@test "extract_json_number: missing file returns empty" {
  result="$(extract_json_number "$TEST_TMPDIR/nonexistent.json" "count")"
  [[ "$result" == "" ]]
}

@test "extract_json_number: value without trailing comma" {
  echo '  "count": 42' >"$TEST_TMPDIR/test.json"
  result="$(extract_json_number "$TEST_TMPDIR/test.json" "count")"
  [[ "$result" == "42" ]]
}

@test "extract_json_number: zero value" {
  echo '  "count": 0,' >"$TEST_TMPDIR/test.json"
  result="$(extract_json_number "$TEST_TMPDIR/test.json" "count")"
  [[ "$result" == "0" ]]
}
