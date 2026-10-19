package netbind

import (
	"errors"
	"sync"
	"syscall"
)

// Protector keeps callbacks alive while a socket's Control is executing.
// Closing it waits for active callbacks, and all late dial attempts fail closed.
// The owner can safely release its JNI global reference after Close returns.
type Protector struct {
	mu      sync.RWMutex
	protect func(int) bool
	closed  bool
}

var installDefaults sync.Once

func InstallProtector(protect func(int) bool) (*Protector, error) {
	if protect == nil {
		return nil, errors.New("socket protector is required")
	}
	p := &Protector{protect: protect}
	f := controlFunc(p.control)
	current.Store(&f)
	installDefaults.Do(func() { install(f) })
	return p, nil
}

func (p *Protector) control(network, address string, c syscall.RawConn) error {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.closed {
		return errors.New("socket protector is closed")
	}
	allowed := false
	if err := c.Control(func(fd uintptr) { allowed = p.protect(int(fd)) }); err != nil {
		return err
	}
	if !allowed {
		return errors.New("socket protection rejected")
	}
	return nil
}

func (p *Protector) Close() {
	p.mu.Lock()
	p.closed = true
	p.protect = nil
	p.mu.Unlock()
}
