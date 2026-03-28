package orchestrator

import (
	"os"
	"regexp"
	"strings"
)

var egoPrefix = regexp.MustCompile(`(?i)^ego>\s*`)

// ExtractAnswerLine reads stdout.log and returns the last line matching "^ego> " (stripped).
// Falls back to the full trimmed file content if no ego> lines found.
func ExtractAnswerLine(stdoutLogPath string) string {
	data, err := os.ReadFile(stdoutLogPath)
	if err != nil {
		return ""
	}

	lines := strings.Split(string(data), "\n")
	lastEgo := ""
	for _, line := range lines {
		if egoPrefix.MatchString(line) {
			lastEgo = egoPrefix.ReplaceAllString(line, "")
		}
	}

	if lastEgo != "" {
		return strings.TrimSpace(lastEgo)
	}

	// Fallback: return full trimmed content
	return strings.TrimSpace(string(data))
}

var multiSpace = regexp.MustCompile(`\s+`)

// NormalizeAnswer lowercases, strips "ego> " prefix, collapses whitespace, trims.
// If stripQuotes is true, also removes surrounding double quotes.
func NormalizeAnswer(raw string, stripQuotes bool) string {
	s := egoPrefix.ReplaceAllString(raw, "")
	s = strings.ToLower(s)
	s = strings.ReplaceAll(s, "\n", " ")
	s = multiSpace.ReplaceAllString(s, " ")
	s = strings.TrimSpace(s)

	if stripQuotes && len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		s = s[1 : len(s)-1]
		s = strings.TrimSpace(s)
	}

	return s
}
