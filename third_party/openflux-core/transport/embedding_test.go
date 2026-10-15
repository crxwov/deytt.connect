package transport

import (
	"testing"
	"time"
)

// Selected upstream Session tests share this helper with an omitted lifecycle
// test file; keep the imported tests unchanged while retaining their assertion.
func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal(what)
}
