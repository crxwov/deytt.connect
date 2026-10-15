package proxy

import (
	"context"
	"errors"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/cupsonline"
	"github.com/p1neappleXpress/OpenFlux/tunnel"
	"sync"
)

type Exit struct {
	session *transport.Session
	carrier *cupsonline.CupsonlineTransport
	stack   *tunnel.TCPTunnel
	once    sync.Once
}

// StartExit requires no TUN, CAP_NET_ADMIN, raw socket, or root. Profile expiry
// is checked by the caller; the supervisor owns live lease renewal/revocation.
func StartExit(ctx context.Context, p Profile) (*Exit, string, error) {
	quietLogs()
	sess, raw, once, err := newSession(p, true)
	if err != nil {
		return nil, "", errors.New("OpenFlux exit configuration failed")
	}
	e := &Exit{session: sess, carrier: raw}
	ok := false
	defer func() {
		if !ok {
			e.Close()
		}
	}()
	deadline, cancel := context.WithTimeout(ctx, startupTimeout)
	defer cancel()
	unhook := context.AfterFunc(deadline, func() { _ = raw.Stop() })
	defer unhook()
	if err := once.Start(); err != nil || deadline.Err() != nil {
		return nil, "", errors.New("OpenFlux exit carrier unavailable")
	}
	rooms := raw.RoomList()
	if !ValidRooms(rooms) {
		return nil, "", errors.New("OpenFlux exit rooms invalid")
	}
	policy, err := currentExitEgressPolicy()
	if err != nil {
		return nil, "", errors.New("OpenFlux exit interface policy unavailable")
	}
	e.stack = tunnel.NewL4ExitWithPolicy(sess, policy)
	if err := sess.Start(); err != nil {
		return nil, "", errors.New("OpenFlux exit session failed")
	}
	ok = true
	return e, rooms, nil
}

func (e *Exit) Close() {
	if e == nil {
		return
	}
	e.once.Do(func() {
		if e.stack != nil {
			e.stack.Close()
		}
		_ = e.carrier.Stop()
		_ = e.session.Stop()
	})
}
