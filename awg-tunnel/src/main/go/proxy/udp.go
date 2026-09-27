package proxy

import (
	"bytes"
	"context"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

type udpFlow struct {
	conn   net.Conn
	header []byte
}

func (s *Server) associate(client net.Conn, requestedHost string, requestedPort int) {
	requestedIP := net.ParseIP(requestedHost)
	if requestedIP == nil || (!requestedIP.IsUnspecified() && !requestedIP.IsLoopback()) {
		reply(client, 2, 0)
		return
	}
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		reply(client, 1, 0)
		return
	}
	if !s.track(udp) {
		return
	}
	defer s.release(udp)
	if !reply(client, 0, udp.LocalAddr().(*net.UDPAddr).Port) {
		return
	}
	client.SetDeadline(time.Time{})
	ctx, cancel := context.WithCancel(s.ctx)
	defer cancel()
	controlDone := make(chan struct{})
	go func() { io.Copy(io.Discard, client); cancel(); udp.Close(); close(controlDone) }()
	defer func() { client.Close(); <-controlDone }()
	var flowMu sync.Mutex
	flows := make(map[string]*udpFlow)
	var readers sync.WaitGroup
	defer func() {
		flowMu.Lock()
		for _, flow := range flows {
			flow.conn.Close()
		}
		flowMu.Unlock()
		readers.Wait()
	}()
	var clientEndpoint *net.UDPAddr
	buffer := make([]byte, 65535)
	for {
		n, source, err := udp.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		if !source.IP.IsLoopback() || (requestedPort != 0 && source.Port != requestedPort) {
			continue
		}
		if clientEndpoint != nil && (source.Port != clientEndpoint.Port || !source.IP.Equal(clientEndpoint.IP)) {
			continue
		}
		if n < 7 || buffer[0] != 0 || buffer[1] != 0 || buffer[2] != 0 {
			continue
		} // no fragmented SOCKS datagrams
		reader := bytes.NewReader(buffer[4:n])
		host, port, err := readAddress(reader, buffer[3])
		if err != nil || port == 0 {
			continue
		}
		headerLength := n - reader.Len()
		if clientEndpoint == nil {
			clientEndpoint = source
		}
		destination := net.JoinHostPort(host, strconv.Itoa(port))
		flowMu.Lock()
		flow := flows[destination]
		count := len(flows)
		flowMu.Unlock()
		if flow == nil {
			if count >= maxAssociationFlows {
				continue
			}
			select {
			case s.udpFlows <- struct{}{}:
			default:
				continue
			}
			dialCtx, dialCancel := context.WithTimeout(ctx, dialTimeout)
			remote, dialErr := s.dialer.DialContext(dialCtx, "udp", destination)
			dialCancel()
			if dialErr != nil {
				<-s.udpFlows
				continue
			}
			if !s.track(remote) {
				<-s.udpFlows
				return
			}
			flow = &udpFlow{conn: remote, header: append([]byte(nil), buffer[:headerLength]...)}
			flowMu.Lock()
			flows[destination] = flow
			flowMu.Unlock()
			readers.Add(1)
			go func(key string, current *udpFlow, endpoint *net.UDPAddr) {
				defer readers.Done()
				defer func() { <-s.udpFlows }()
				defer s.release(current.conn)
				defer func() {
					flowMu.Lock()
					if flows[key] == current {
						delete(flows, key)
					}
					flowMu.Unlock()
				}()
				response := make([]byte, 65535)
				copy(response, current.header)
				for {
					current.conn.SetReadDeadline(time.Now().Add(udpIdleTimeout))
					n, err := current.conn.Read(response[len(current.header):])
					if err != nil {
						return
					}
					if _, err = udp.WriteToUDP(response[:len(current.header)+n], endpoint); err != nil {
						return
					}
				}
			}(destination, flow, clientEndpoint)
		}
		flow.conn.SetWriteDeadline(time.Now().Add(dialTimeout))
		flow.conn.SetReadDeadline(time.Now().Add(udpIdleTimeout))
		if _, err := flow.conn.Write(buffer[headerLength:n]); err != nil {
			flow.conn.Close()
		}
	}
}
