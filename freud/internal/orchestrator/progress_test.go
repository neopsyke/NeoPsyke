package orchestrator

import "testing"

func TestFormatProgressUpdate(t *testing.T) {
	line := formatProgressUpdate(ProgressUpdate{
		Step:    "reasoning_eval_model",
		Phase:   "case_result",
		Current: 3,
		Total:   24,
		Status:  "pass",
		Message: "foo-case pass=3 timeout=0 schema=0",
	})

	expected := `[freud] step=reasoning_eval_model phase=case_result progress=3/24 status=pass detail="foo-case pass=3 timeout=0 schema=0"`
	if line != expected {
		t.Fatalf("unexpected progress line:\nwant: %s\ngot:  %s", expected, line)
	}
}
