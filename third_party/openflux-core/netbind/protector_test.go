package netbind

import (
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

type rawSocket struct{}

func (rawSocket) Control(f func(uintptr)) error  { f(17); return nil }
func (rawSocket) Read(func(uintptr) bool) error  { return errors.New("unused") }
func (rawSocket) Write(func(uintptr) bool) error { return errors.New("unused") }

func TestProtectorRejectsAndDrainsCallbacks(t *testing.T) {
	if _, err := InstallProtector(nil); err == nil {
		t.Fatal("missing protector accepted")
	}
	var calls atomic.Int32
	p, _ := InstallProtector(func(int) bool { calls.Add(1); return false })
	if p.control("tcp4", "127.0.0.1:1", rawSocket{}) == nil {
		t.Fatal("failed protection accepted")
	}
	p.Close()
	if p.control("udp4", "127.0.0.1:53", rawSocket{}) == nil || calls.Load() != 1 {
		t.Fatal("closed protector called owner")
	}
	entered := make(chan struct{})
	release := make(chan struct{})
	drained := make(chan struct{})
	p, _ = InstallProtector(func(int) bool { close(entered); <-release; return true })
	go func() { _ = p.control("tcp4", "127.0.0.1:1", rawSocket{}) }()
	<-entered
	go func() { p.Close(); close(drained) }()
	select {
	case <-drained:
		t.Fatal("Close did not drain active callback")
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	select {
	case <-drained:
	case <-time.After(time.Second):
		t.Fatal("Close blocked")
	}
	if p.control("tcp4", "127.0.0.1:1", rawSocket{}) == nil {
		t.Fatal("late callback accepted")
	}
}
