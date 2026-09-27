package proxy

import (
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

const (
	handshakeTimeout    = 10 * time.Second
	dialTimeout         = 15 * time.Second
	udpIdleTimeout      = 60 * time.Second
	maxClients          = 1024
	maxUDPFlows         = 512
	maxAssociationFlows = 32
)

type Dialer interface {
	DialContext(context.Context, string, string) (net.Conn, error)
}

type Server struct {
	listener           net.Listener
	dialer             Dialer
	username, password string
	ctx                context.Context
	cancel             context.CancelFunc
	mu                 sync.Mutex
	closed             bool
	connections        map[io.Closer]struct{}
	wg                 sync.WaitGroup
	clients            chan struct{}
	udpFlows           chan struct{}
}

func NewServer(dialer Dialer, username, password string) (*Server, error) {
	if dialer == nil || len(username) < 16 || len(username) > 255 || len(password) < 16 || len(password) > 255 {
		return nil, errors.New("invalid proxy credentials")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Server{listener: listener, dialer: dialer, username: username, password: password,
		ctx: ctx, cancel: cancel, connections: make(map[io.Closer]struct{}), clients: make(chan struct{}, maxClients), udpFlows: make(chan struct{}, maxUDPFlows)}
	s.wg.Add(1)
	go s.accept()
	return s, nil
}

func (s *Server) Port() int { return s.listener.Addr().(*net.TCPAddr).Port }

func (s *Server) track(c io.Closer) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		c.Close()
		return false
	}
	s.connections[c] = struct{}{}
	return true
}
func (s *Server) release(c io.Closer) {
	c.Close()
	s.mu.Lock()
	delete(s.connections, c)
	s.mu.Unlock()
}
func (s *Server) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	s.cancel()
	s.listener.Close()
	for c := range s.connections {
		c.Close()
	}
	s.mu.Unlock()
	s.wg.Wait()
}

func (s *Server) accept() {
	defer s.wg.Done()
	for {
		client, err := s.listener.Accept()
		if err != nil {
			return
		}
		select {
		case s.clients <- struct{}{}:
		default:
			client.Close()
			continue
		}
		if !s.track(client) {
			<-s.clients
			return
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer func() { <-s.clients }()
			defer s.release(client)
			s.handle(client)
		}()
	}
}

func (s *Server) handle(client net.Conn) {
	client.SetDeadline(time.Now().Add(handshakeTimeout))
	if !s.authenticate(client) {
		return
	}
	header := make([]byte, 4)
	if _, err := io.ReadFull(client, header); err != nil || header[0] != 5 || header[2] != 0 {
		return
	}
	host, port, err := readAddress(client, header[3])
	if err != nil {
		reply(client, 8, 0)
		return
	}
	switch header[1] {
	case 1:
		ctx, cancel := context.WithTimeout(s.ctx, dialTimeout)
		remote, err := s.dialer.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
		cancel()
		if err != nil {
			reply(client, 4, 0)
			return
		}
		if !s.track(remote) {
			return
		}
		defer s.release(remote)
		if !reply(client, 0, 0) {
			return
		}
		client.SetDeadline(time.Time{})
		done := make(chan struct{})
		go func() {
			_, err := io.Copy(remote, client)
			if err != nil {
				remote.Close()
			} else {
				closeWrite(remote)
			}
			close(done)
		}()
		io.Copy(client, remote)
		client.Close()
		remote.Close()
		<-done
	case 3:
		s.associate(client, host, port)
	default:
		reply(client, 7, 0)
	}
}

func closeWrite(connection net.Conn) {
	if half, ok := connection.(interface{ CloseWrite() error }); ok {
		half.CloseWrite()
	} else {
		connection.Close()
	}
}

func (s *Server) authenticate(client net.Conn) bool {
	header := make([]byte, 2)
	if _, err := io.ReadFull(client, header); err != nil || header[0] != 5 || header[1] == 0 {
		return false
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return false
	}
	found := false
	for _, method := range methods {
		if method == 2 {
			found = true
		}
	}
	if !found {
		client.Write([]byte{5, 255})
		return false
	}
	if _, err := client.Write([]byte{5, 2}); err != nil {
		return false
	}
	if _, err := io.ReadFull(client, header); err != nil || header[0] != 1 || header[1] == 0 {
		return false
	}
	username := make([]byte, int(header[1]))
	if _, err := io.ReadFull(client, username); err != nil {
		return false
	}
	if _, err := io.ReadFull(client, header[:1]); err != nil || header[0] == 0 {
		return false
	}
	password := make([]byte, int(header[0]))
	if _, err := io.ReadFull(client, password); err != nil {
		return false
	}
	ok := subtle.ConstantTimeCompare(username, []byte(s.username)) & subtle.ConstantTimeCompare(password, []byte(s.password))
	status := byte(1)
	if ok == 1 {
		status = 0
	}
	_, err := client.Write([]byte{1, status})
	return ok == 1 && err == nil
}

func reply(client net.Conn, status byte, port int) bool {
	_, err := client.Write([]byte{5, status, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	return err == nil
}

func readAddress(reader io.Reader, kind byte) (string, int, error) {
	length := 0
	switch kind {
	case 1:
		length = 4
	case 4:
		length = 16
	case 3:
		one := make([]byte, 1)
		if _, err := io.ReadFull(reader, one); err != nil {
			return "", 0, err
		}
		length = int(one[0])
		if length == 0 {
			return "", 0, errors.New("empty host")
		}
	default:
		return "", 0, errors.New("unsupported address")
	}
	data := make([]byte, length+2)
	if _, err := io.ReadFull(reader, data); err != nil {
		return "", 0, err
	}
	host := string(data[:length])
	if kind != 3 {
		host = net.IP(data[:length]).String()
	}
	return host, int(binary.BigEndian.Uint16(data[length:])), nil
}
