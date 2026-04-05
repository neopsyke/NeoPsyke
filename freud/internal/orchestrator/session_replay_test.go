package orchestrator

import "testing"

func TestIsZeroCallCacheDivergence(t *testing.T) {
	if !isZeroCallCacheDivergence(0, 0, 0, 1) {
		t.Fatal("expected zero-call cache divergence to be ignored")
	}
	if isZeroCallCacheDivergence(1, 1, 0, 1) {
		t.Fatal("expected real cached-call divergences to remain failures")
	}
	if isZeroCallCacheDivergence(0, 0, 0, 0) {
		t.Fatal("expected no divergence to return false")
	}
}
