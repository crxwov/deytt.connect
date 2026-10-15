package socks5

import (
	"io"
	"net"
	"strconv"
	"testing"
	"time"
)

type forbiddenDialer struct{}

func (forbiddenDialer) DialTCP(string) (net.Conn, error) { return nil, net.ErrClosed }

func TestManagedLoopbackRequiresAuthentication(t *testing.T) {
	s := NewSOCKS5Server("127.0.0.1:0", forbiddenDialer{})
	s.SetAuth("0123456789abcdef", "fedcba9876543210")
	if err := s.Bind(); err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	go func() { _ = s.Start() }()
	dial := func() net.Conn {
		c, err := net.Dial("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(s.Port())))
		if err != nil {
			t.Fatal(err)
		}
		_ = c.SetDeadline(time.Now().Add(2 * time.Second))
		return c
	}
	c := dial()
	_, _ = c.Write([]byte{5, 1, 0})
	var reply [2]byte
	if _, err := io.ReadFull(c, reply[:]); err != nil || reply != [2]byte{5, 255} {
		t.Fatalf("anonymous greeting accepted: %v %v", reply, err)
	}
	_ = c.Close()
	c = dial()
	defer c.Close()
	_, _ = c.Write([]byte{5, 1, 2})
	if _, err := io.ReadFull(c, reply[:]); err != nil || reply != [2]byte{5, 2} {
		t.Fatal("password auth unavailable")
	}
	auth := append([]byte{1, 16}, []byte("0123456789abcdef")...)
	auth = append(auth, 16)
	auth = append(auth, []byte("wrong-wrong-wron")...)
	_, _ = c.Write(auth)
	if _, err := io.ReadFull(c, reply[:]); err != nil || reply[1] == 0 {
		t.Fatal("wrong password accepted")
	}
	_ = c.Close()
	c = dial()
	defer c.Close()
	_, _ = c.Write([]byte{5, 1, 2})
	_, _ = io.ReadFull(c, reply[:])
	auth = append([]byte{1, 16}, []byte("0123456789abcdef")...)
	auth = append(auth, 16)
	auth = append(auth, []byte("fedcba9876543210")...)
	_, _ = c.Write(auth)
	if _, err := io.ReadFull(c, reply[:]); err != nil || reply != [2]byte{1, 0} {
		t.Fatal("valid password rejected")
	}
}
