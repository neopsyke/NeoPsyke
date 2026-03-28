package orchestrator

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/atomitl/neopsyke/freud/internal/analysis"
)

// TrailEmitter writes trail events to JSONL and TSV index files.
type TrailEmitter struct {
	trailFile      *os.File
	trailIndexFile *os.File
	seq            int
	mu             sync.Mutex
}

// NewTrailEmitter creates a trail emitter writing to the given artifact directory.
func NewTrailEmitter(artifactsDir string) (*TrailEmitter, error) {
	trailPath := artifactsDir + "/trail.jsonl"
	indexPath := artifactsDir + "/trail-index.tsv"

	trailFile, err := os.Create(trailPath)
	if err != nil {
		return nil, fmt.Errorf("creating trail file: %w", err)
	}

	indexFile, err := os.Create(indexPath)
	if err != nil {
		trailFile.Close()
		return nil, fmt.Errorf("creating trail index: %w", err)
	}

	// Write TSV header
	fmt.Fprintln(indexFile, "seq\tts\tevent\tstep\tstatus\tlog\tref\tmessage")

	return &TrailEmitter{
		trailFile:      trailFile,
		trailIndexFile: indexFile,
	}, nil
}

// Emit writes a trail event. All parameters except event are optional.
func (t *TrailEmitter) Emit(event, step, status, message, cmd, log, ref string) {
	t.mu.Lock()
	defer t.mu.Unlock()

	t.seq++
	ts := time.Now().UTC().Format("2006-01-02T15:04:05Z")

	// JSONL line
	jsonLine := fmt.Sprintf(
		`{"seq":%d,"ts":"%s","event":"%s","step":"%s","status":"%s","message":"%s","cmd":"%s","log":"%s","ref":"%s"}`,
		t.seq, ts,
		analysis.JSONEscape(event),
		analysis.JSONEscape(step),
		analysis.JSONEscape(status),
		analysis.JSONEscape(message),
		analysis.JSONEscape(cmd),
		analysis.JSONEscape(log),
		analysis.JSONEscape(ref),
	)
	fmt.Fprintln(t.trailFile, jsonLine)

	// TSV line
	tsvLine := fmt.Sprintf("%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s",
		t.seq, ts,
		analysis.TSVEscape(event),
		analysis.TSVEscape(step),
		analysis.TSVEscape(status),
		analysis.TSVEscape(log),
		analysis.TSVEscape(ref),
		analysis.TSVEscape(message),
	)
	fmt.Fprintln(t.trailIndexFile, tsvLine)
}

// Seq returns the current sequence number.
func (t *TrailEmitter) Seq() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.seq
}

// Close flushes and closes both files.
func (t *TrailEmitter) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()

	var errs []string
	if err := t.trailFile.Close(); err != nil {
		errs = append(errs, err.Error())
	}
	if err := t.trailIndexFile.Close(); err != nil {
		errs = append(errs, err.Error())
	}
	if len(errs) > 0 {
		return fmt.Errorf("closing trail: %s", strings.Join(errs, "; "))
	}
	return nil
}
