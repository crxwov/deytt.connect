package socks5

import "net"

// Port reports the OS-reserved listener port after Bind.
func (s *SOCKS5Server) Port() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.listener == nil {
		return 0
	}
	return s.listener.Addr().(*net.TCPAddr).Port
}
