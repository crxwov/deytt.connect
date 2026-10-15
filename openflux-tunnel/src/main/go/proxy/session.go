package proxy

import (
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/cupsonline"
	"sync"
)

// Session.Start normally supervises failed carriers indefinitely. Managed
// profiles instead require the initial join to succeed before admitting traffic.
type onceTransport struct {
	transport.Transport
	once sync.Once
	err  error
}

func (t *onceTransport) Start() error {
	t.once.Do(func() { t.err = t.Transport.Start() })
	return t.err
}

func newSession(p Profile, exit bool) (*transport.Session, *cupsonline.CupsonlineTransport, *onceTransport, error) {
	s, err := transport.NewSession(transport.PeerParameters{
		Capabilities:  transport.CapabilityIPv4 | transport.CapabilityTCP | transport.CapabilityUDP,
		MaxPacketSize: 1500,
	}, exit)
	if err != nil {
		return nil, nil, nil, err
	}
	cfg := transport.DefaultConfig()
	cfg.MaxQueueSize = 128
	raw := cupsonline.NewCupsonlineTransport(p.Transports[0].URL, cfg, !exit)
	// Match the upstream registry's Cups stack: rooms reorder frames, so the
	// sequence/reassembly/ARQ layer belongs below authenticated encryption.
	once := &onceTransport{Transport: transport.NewStripedTransport([]transport.Transport{raw})}
	// NewSession defaults to ClassicOff. Never call SetClassic, provide
	// alternate contexts, register another carrier, or install Direct.
	if err := s.AddTransport("cupsonline", once, p.Secret, p.Context, 100); err != nil {
		_ = once.Stop()
		return nil, nil, nil, err
	}
	return s, raw, once, nil
}
