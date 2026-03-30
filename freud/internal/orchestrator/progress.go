package orchestrator

import (
	"fmt"
	"io"
	"strings"
)

// ProgressUpdate is a structured live-progress event for long-running built-ins.
type ProgressUpdate struct {
	Step    string
	Phase   string
	Current int
	Total   int
	Status  string
	Message string
}

// ProgressReporter emits structured progress updates.
type ProgressReporter func(ProgressUpdate)

// Emit reports a progress update when a reporter is configured.
func (r ProgressReporter) Emit(update ProgressUpdate) {
	if r != nil {
		r(update)
	}
}

// WithStepProgress injects a default step name into progress events.
func WithStepProgress(step string, reporter ProgressReporter) ProgressReporter {
	if reporter == nil {
		return nil
	}
	return func(update ProgressUpdate) {
		if update.Step == "" {
			update.Step = step
		}
		reporter.Emit(update)
	}
}

// NewConsoleProgressReporter writes compact progress lines to the provided writer.
func NewConsoleProgressReporter(w io.Writer) ProgressReporter {
	if w == nil {
		return nil
	}
	return func(update ProgressUpdate) {
		fmt.Fprintln(w, formatProgressUpdate(update))
	}
}

func formatProgressUpdate(update ProgressUpdate) string {
	parts := []string{"[freud]"}
	if update.Step != "" {
		parts = append(parts, "step="+update.Step)
	}
	if update.Phase != "" {
		parts = append(parts, "phase="+update.Phase)
	}
	if update.Current > 0 && update.Total > 0 {
		parts = append(parts, fmt.Sprintf("progress=%d/%d", update.Current, update.Total))
	} else if update.Current > 0 {
		parts = append(parts, fmt.Sprintf("progress=%d", update.Current))
	}
	if update.Status != "" {
		parts = append(parts, "status="+update.Status)
	}
	if update.Message != "" {
		parts = append(parts, "detail="+fmt.Sprintf("%q", strings.TrimSpace(update.Message)))
	}
	return strings.Join(parts, " ")
}
