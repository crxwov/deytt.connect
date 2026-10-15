package transport

import (
	"bytes"
	"testing"
	"time"
)

func TestManagedStripeBoundsAndStop(t *testing.T) {
	s := NewStripedTransport(nil)
	s.deliverPiece([]byte{pieceMore, 1}, false)
	for i := 0; i < maxStripeFrameBytes/4096+2; i++ {
		s.deliverPiece(append([]byte{pieceCont | pieceMore}, make([]byte, 4096)...), false)
	}
	if len(s.asm) > maxStripeFrameBytes {
		t.Fatal("unbounded stripe assembly")
	}
	s.onLane(0, append([]byte{stripeMagic}, make([]byte, maxStripePieceBytes+stripeHeader+1)...))
	if s.ro.count != 0 {
		t.Fatal("oversized unauthenticated piece queued")
	}
	_ = s.Stop()
	select {
	case <-s.ro.deliveryDone:
	case <-time.After(time.Second):
		t.Fatal("stripe delivery leaked after close")
	}
}

func TestManagedStripedEncryptedSession(t *testing.T) {
	a, b := &negotiationWire{}, &negotiationWire{}
	a.peer = b
	b.peer = a
	params := PeerParameters{Capabilities: CapabilityIPv4 | CapabilityTCP | CapabilityUDP, MaxPacketSize: 1500}
	client, _ := NewSession(params, false)
	exit, _ := NewSession(params, true)
	if err := client.AddTransport("cupsonline", NewStripedTransport([]Transport{a}), testSessionSecret, testSessionCtx, 100); err != nil {
		t.Fatal(err)
	}
	if err := exit.AddTransport("cupsonline", NewStripedTransport([]Transport{b}), testSessionSecret, testSessionCtx, 100); err != nil {
		t.Fatal(err)
	}
	defer client.Stop()
	defer exit.Stop()
	received := make(chan []byte, 1)
	exit.Receive(func(p []byte) { received <- append([]byte(nil), p...) })
	errors := make(chan error, 2)
	go func() { errors <- exit.Start() }()
	go func() { errors <- client.Start() }()
	for i := 0; i < 2; i++ {
		select {
		case err := <-errors:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("striped session handshake timed out")
		}
	}
	want := testIPv4(64, 6)
	if err := client.Send(want); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-received:
		if !bytes.Equal(got, want) {
			t.Fatal("striped session changed packet")
		}
	case <-time.After(time.Second):
		t.Fatal("striped session lost packet")
	}
}
