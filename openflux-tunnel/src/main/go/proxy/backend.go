package proxy

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/p1neappleXpress/OpenFlux/netbind"
	"github.com/p1neappleXpress/OpenFlux/socks5"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/cupsonline"
	"github.com/p1neappleXpress/OpenFlux/tunnel"
	"github.com/p1neappleXpress/OpenFlux/utils"
)

const startupTimeout = 40 * time.Second

var lifecycle sync.Mutex
var active *Backend
var quiet sync.Once

func quietLogs() { quiet.Do(func() { utils.SetOutput(io.Discard); utils.SetLevel(0) }) }

type Backend struct {
	session   *transport.Session
	carrier   *cupsonline.CupsonlineTransport
	stack     *tunnel.TCPTunnel
	server    *socks5.SOCKS5Server
	protector *netbind.Protector
	closed    atomic.Bool
}

// Start admits one process-wide session. Returning means the AES-GCM Session
// handshake completed, not merely that a carrier websocket was established.
func Start(profileJSON, username, password string, dnsServers []string, protect func(int) bool) (*Backend, error) {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if active != nil {
		return nil, errors.New("OpenFlux session already active")
	}
	p, err := ParseProfile([]byte(profileJSON), time.Now(), false)
	if err != nil {
		return nil, err
	}
	if len(username) < 16 || len(username) > 255 || len(password) < 16 || len(password) > 255 {
		return nil, errors.New("OpenFlux SOCKS credentials are required")
	}
	if protect == nil {
		return nil, errors.New("OpenFlux socket protector is required")
	}
	if err := netbind.SetResolverServers(dnsServers); err != nil {
		return nil, errors.New("OpenFlux carrier DNS unavailable")
	}
	quietLogs()
	gate, err := netbind.InstallProtector(protect)
	if err != nil {
		return nil, errors.New("OpenFlux socket protection unavailable")
	}
	b := &Backend{protector: gate}
	ok := false
	defer func() {
		if !ok {
			b.closeLocked()
		}
	}()
	// UDP connect sends no data. It proves protection before any carrier request.
	probe, err := netbind.Dialer(time.Second).Dial("udp4", "127.0.0.1:9")
	if err != nil {
		return nil, errors.New("OpenFlux socket protection rejected")
	}
	_ = probe.Close()
	sess, raw, once, err := newSession(p, false)
	if err != nil {
		return nil, errors.New("OpenFlux session configuration failed")
	}
	b.session, b.carrier = sess, raw
	deadline, cancel := context.WithTimeout(context.Background(), startupTimeout)
	defer cancel()
	unhook := context.AfterFunc(deadline, func() { _ = raw.Stop() })
	defer unhook()
	if err := once.Start(); err != nil {
		return nil, errors.New("OpenFlux Cups carrier unavailable")
	}
	b.stack = tunnel.NewTCPTunnel(sess, false)
	if err := sess.Start(); err != nil || deadline.Err() != nil {
		return nil, errors.New("OpenFlux encrypted handshake failed")
	}
	b.server = socks5.NewSOCKS5Server("127.0.0.1:0", ipv4Dialer{stack: b.stack})
	b.server.SetAuth(username, password)
	if err := b.server.Bind(); err != nil {
		return nil, errors.New("OpenFlux loopback listener failed")
	}
	active = b
	ok = true
	go func() { _ = b.server.Start() }()
	return b, nil
}

func (b *Backend) Port() int {
	if b == nil || b.closed.Load() || b.server == nil {
		return 0
	}
	return b.server.Port()
}
func (b *Backend) IsConnected() bool { return b != nil && !b.closed.Load() && b.session.IsConnected() }
func (b *Backend) BytesSent() uint64 {
	if b == nil || b.closed.Load() || b.carrier == nil {
		return 0
	}
	return b.carrier.Stats().BytesSent
}
func (b *Backend) BytesReceived() uint64 {
	if b == nil || b.closed.Load() || b.carrier == nil {
		return 0
	}
	return b.carrier.Stats().BytesReceived
}

func (b *Backend) Close() {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	b.closeLocked()
	if active == b {
		active = nil
	}
}

func (b *Backend) closeLocked() {
	if b == nil || b.closed.Swap(true) {
		return
	}
	// Drain JNI callbacks before releasing their C/global-reference owner.
	// Keep the closed gate installed so late goroutines fail closed too.
	if b.protector != nil {
		b.protector.Close()
	}
	if b.server != nil {
		_ = b.server.Close()
	}
	if b.stack != nil {
		b.stack.Close()
	}
	if b.carrier != nil {
		_ = b.carrier.Stop()
	}
	if b.session != nil {
		_ = b.session.Stop()
	}
}

// Application DNS is already routed by sing-box through this SOCKS listener.
// Refuse hostnames here instead of resolving website DNS on the physical path.
type ipv4Dialer struct{ stack *tunnel.TCPTunnel }

func requireIPv4(address string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return errors.New("invalid SOCKS destination")
	}
	ip := net.ParseIP(host)
	if ip == nil || ip.To4() == nil {
		return errors.New("SOCKS destination requires resolved IPv4")
	}
	return nil
}
func (d ipv4Dialer) DialTCP(address string) (net.Conn, error) {
	if err := requireIPv4(address); err != nil {
		return nil, err
	}
	return d.stack.DialTCP(address)
}
func (d ipv4Dialer) DialUDP(address string) (net.Conn, error) {
	if err := requireIPv4(address); err != nil {
		return nil, err
	}
	return d.stack.DialUDP(address)
}
