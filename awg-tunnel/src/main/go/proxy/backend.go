package proxy

import (
	"errors"
	"net/netip"
	"strings"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

// Backend has no Android TUN. The app's domain router sends only tunneled
// connections here; the internal netstack exchanges packets with AmneziaWG.
type Backend struct {
	server *Server
	device *device.Device
}

func Start(settings, addresses, dns string, mtu int, username, password string, protect func(int) bool) (*Backend, error) {
	local, err := parseAddresses(addresses)
	if err != nil || len(local) == 0 || mtu < 576 || mtu > 65535 || protect == nil {
		return nil, errors.New("invalid tunnel parameters")
	}
	resolvers, err := parseAddresses(dns)
	if err != nil {
		return nil, errors.New("invalid resolver parameters")
	}
	tun, network, err := netstack.CreateNetTUN(local, resolvers, mtu)
	if err != nil {
		return nil, err
	}
	bind := &protectedBind{Bind: conn.NewStdNetBind(), protect: protect}
	dev := device.NewDevice(tun, bind, &device.Logger{Verbosef: func(string, ...any) {}, Errorf: func(string, ...any) {}})
	if err = dev.IpcSet(settings); err != nil {
		dev.Close()
		return nil, errors.New("tunnel configuration rejected")
	}
	dev.DisableSomeRoamingForBrokenMobileSemantics()
	if err = dev.Up(); err != nil {
		dev.Close()
		return nil, errors.New("tunnel activation failed")
	}
	server, err := NewServer(network, username, password)
	if err != nil {
		dev.Close()
		return nil, err
	}
	return &Backend{server: server, device: dev}, nil
}

func (b *Backend) Port() int { return b.server.Port() }
func (b *Backend) Close()    { b.server.Close(); b.device.Close() }

func parseAddresses(value string) ([]netip.Addr, error) {
	var result []netip.Addr
	for _, raw := range strings.Split(value, ",") {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		if prefix, err := netip.ParsePrefix(raw); err == nil {
			result = append(result, prefix.Addr())
			continue
		}
		addr, err := netip.ParseAddr(raw)
		if err != nil {
			return nil, err
		}
		result = append(result, addr)
	}
	return result, nil
}

type protectedBind struct {
	conn.Bind
	protect func(int) bool
}

// Protection runs before Open returns to device.Up, before even the first
// keepalive can be sent. Reopening a bind repeats protection for the new FDs.
func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, actual, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	peek, ok := b.Bind.(conn.PeekLookAtSocketFd)
	if !ok {
		b.Bind.Close()
		return nil, 0, errors.New("socket protection unavailable")
	}
	protected := 0
	for _, getter := range []func() (int, error){peek.PeekLookAtSocketFd4, peek.PeekLookAtSocketFd6} {
		fd, socketErr := getter()
		if socketErr != nil || fd < 0 {
			continue
		}
		if !b.protect(fd) {
			b.Bind.Close()
			return nil, 0, errors.New("socket protection failed")
		}
		protected++
	}
	if protected == 0 {
		b.Bind.Close()
		return nil, 0, errors.New("no protected sockets")
	}
	return fns, actual, nil
}
