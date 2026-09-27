package proxy

import (
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"testing"
	"time"

	awgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"
)

const testUser = "test-user-long-random"
const testPassword = "test-password-long-random"

func testServer(t *testing.T) *Server {
	t.Helper()
	s, err := NewServer(&net.Dialer{}, testUser, testPassword)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	return s
}

func authenticateTest(t *testing.T, s *Server, password string) (net.Conn, byte) {
	t.Helper()
	c, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(s.Port())), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	c.SetDeadline(time.Now().Add(3 * time.Second))
	c.Write([]byte{5, 2, 0, 2})
	header := make([]byte, 2)
	if _, err := io.ReadFull(c, header); err != nil || header[0] != 5 || header[1] != 2 {
		t.Fatalf("auth selection: %v %v", header, err)
	}
	request := append([]byte{1, byte(len(testUser))}, []byte(testUser)...)
	request = append(request, byte(len(password)))
	request = append(request, []byte(password)...)
	c.Write(request)
	if _, err := io.ReadFull(c, header); err != nil {
		t.Fatal(err)
	}
	return c, header[1]
}

func request(t *testing.T, c net.Conn, command byte, port int) []byte {
	t.Helper()
	c.Write([]byte{5, command, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	response := make([]byte, 10)
	if _, err := io.ReadFull(c, response); err != nil {
		t.Fatal(err)
	}
	return response
}

func TestAuthenticationRequiredAndRejected(t *testing.T) {
	s := testServer(t)
	c, status := authenticateTest(t, s, "wrong-password-long")
	if status != 1 {
		t.Fatal("wrong password accepted")
	}
	c.Close()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(s.Port())))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(time.Second))
	c.Write([]byte{5, 1, 0})
	response := make([]byte, 2)
	io.ReadFull(c, response)
	if response[1] != 255 {
		t.Fatal("unauthenticated SOCKS accepted")
	}
}

func TestTCPConnectAndShutdown(t *testing.T) {
	s := testServer(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	echoDone := make(chan struct{})
	go func() {
		defer close(echoDone)
		c, err := listener.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		io.Copy(c, c)
	}()
	c, status := authenticateTest(t, s, testPassword)
	if status != 0 {
		t.Fatal("valid credentials rejected")
	}
	if response := request(t, c, 1, listener.Addr().(*net.TCPAddr).Port); response[1] != 0 {
		t.Fatal("CONNECT failed")
	}
	c.Write([]byte("through-proxy"))
	result := make([]byte, 13)
	if _, err := io.ReadFull(c, result); err != nil || string(result) != "through-proxy" {
		t.Fatalf("echo: %q %v", result, err)
	}
	done := make(chan struct{})
	go func() { s.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("close blocked with active TCP")
	}
	select {
	case <-echoDone:
	case <-time.After(time.Second):
		t.Fatal("remote connection leaked")
	}
}

func TestUDPAssociateRoundTripAndSourceIsolation(t *testing.T) {
	s := testServer(t)
	echo, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, addr, err := echo.ReadFromUDP(buffer)
			if err != nil {
				return
			}
			echo.WriteToUDP(buffer[:n], addr)
		}
	}()
	c, status := authenticateTest(t, s, testPassword)
	if status != 0 {
		t.Fatal("auth")
	}
	response := request(t, c, 3, 0)
	if response[1] != 0 {
		t.Fatal("UDP ASSOCIATE")
	}
	port := int(binary.BigEndian.Uint16(response[8:]))
	udp, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	targetPort := echo.LocalAddr().(*net.UDPAddr).Port
	packet := append([]byte{0, 0, 0, 1, 127, 0, 0, 1, byte(targetPort >> 8), byte(targetPort)}, []byte("udp-proxy")...)
	udp.SetDeadline(time.Now().Add(3 * time.Second))
	udp.Write(packet)
	buffer := make([]byte, 2048)
	n, err := udp.Read(buffer)
	if err != nil || string(buffer[10:n]) != "udp-proxy" {
		t.Fatalf("UDP echo: %v", err)
	}
	other, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	other.SetDeadline(time.Now().Add(100 * time.Millisecond))
	other.Write(packet)
	if _, err = other.Read(buffer); err == nil {
		t.Fatal("second UDP source accepted")
	}
	c.Close()
	done := make(chan struct{})
	go func() { s.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("UDP shutdown blocked")
	}
}

func TestProtectedBindRejectsBeforeUse(t *testing.T) {
	calls := 0
	b := &protectedBind{Bind: awgconn.NewStdNetBind(), protect: func(fd int) bool { calls++; return false }}
	defer b.Close()
	if _, _, err := b.Open(0); err == nil {
		t.Fatal("unprotected bind accepted")
	}
	if calls == 0 {
		t.Fatal("protection callback never called")
	}
	// Reopening succeeds only if every available family was protected.
	b.protect = func(fd int) bool { calls++; return true }
	if _, _, err := b.Open(0); err != nil {
		t.Fatal(err)
	}
}

func TestTCPHalfCloseKeepsResponseReadable(t *testing.T) {
	s := testServer(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		c, err := listener.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		io.ReadAll(c)
		c.Write([]byte("after-eof"))
	}()
	c, status := authenticateTest(t, s, testPassword)
	if status != 0 {
		t.Fatal("auth")
	}
	if response := request(t, c, 1, listener.Addr().(*net.TCPAddr).Port); response[1] != 0 {
		t.Fatal("CONNECT")
	}
	c.Write([]byte("request"))
	c.(*net.TCPConn).CloseWrite()
	response, err := io.ReadAll(c)
	if err != nil || string(response) != "after-eof" {
		t.Fatalf("half-close response %q %v", response, err)
	}
}
