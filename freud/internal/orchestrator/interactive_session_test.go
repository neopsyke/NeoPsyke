package orchestrator

import "testing"

func TestResolveInteractiveResponseWaitDefaultsToTimeout(t *testing.T) {
	if got := resolveInteractiveResponseWait(120, 0); got != 120 {
		t.Fatalf("expected response wait to default to timeout, got %d", got)
	}
}

func TestResolveInteractiveResponseWaitHonorsExplicitOverride(t *testing.T) {
	if got := resolveInteractiveResponseWait(120, 45); got != 45 {
		t.Fatalf("expected explicit response wait override, got %d", got)
	}
}

func TestResolveInteractiveResponseWaitFallsBackToDefault(t *testing.T) {
	if got := resolveInteractiveResponseWait(0, 0); got != 60 {
		t.Fatalf("expected default response wait of 60 seconds, got %d", got)
	}
}
