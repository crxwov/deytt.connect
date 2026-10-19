package transport

import (
	"bytes"
	"encoding/binary"
	"sync"
	"testing"
	"time"
)

type stripeTestPuller struct {
	negotiationWire
	source func(int) [][]byte
}

func (l *stripeTestPuller) SetFrameSource(source func(int) [][]byte) { l.source = source }

type unorderedStripeTestLane struct{ stripeTestPuller }

func (*unorderedStripeTestLane) ReordersPackets() bool { return true }

// ackSecondOnly represents the second piece arriving before the first, with
// no network loss. A later ACK on an aggregate of rooms is not proof of loss.
func ackSecondOnly(first, second uint32) []byte {
	ack := make([]byte, ackHeader+6)
	ack[0] = stripeAckMagic
	binary.BigEndian.PutUint32(ack[1:5], first)
	binary.BigEndian.PutUint32(ack[5:9], second)
	ack[9] = 1
	binary.BigEndian.PutUint32(ack[ackHeader:], first)
	binary.BigEndian.PutUint16(ack[ackHeader+4:], 1)
	return ack
}

func queuedStripePair(t *testing.T, lane Transport) (*StripedTransport, time.Time, [][]byte) {
	t.Helper()
	s := NewStripedTransport([]Transport{lane})
	s.attach(0, lane)
	stopStripeAfterTest(t, s)
	now := time.Now()
	if !s.snd.enqueue([]byte("earlier")) || !s.snd.enqueue([]byte("later")) {
		t.Fatal("test frames were not queued")
	}
	frames := s.snd.pull(0, 2, now)
	if len(frames) != 2 {
		t.Fatalf("got %d initial frames, want two", len(frames))
	}
	return s, now, frames
}

func stopStripeAfterTest(t *testing.T, s *StripedTransport) {
	t.Helper()
	t.Cleanup(func() {
		_ = s.Stop()
		select {
		case <-s.ro.deliveryDone:
		case <-time.After(time.Second):
			t.Error("stripe delivery goroutine did not stop")
		}
	})
}

func TestUnorderedStripeLaneDoesNotRetransmitReorderedPacket(t *testing.T) {
	s, now, frames := queuedStripePair(t, &unorderedStripeTestLane{})
	first := binary.BigEndian.Uint32(frames[0][1:5])
	second := binary.BigEndian.Uint32(frames[1][1:5])
	if s.snd.onAck(ackSecondOnly(first, second), now.Add(10*time.Millisecond)) || len(s.snd.rtxQ) != 0 {
		t.Fatal("reordered earlier packet was incorrectly queued for retransmission")
	}
	ack := make([]byte, ackHeader)
	ack[0] = stripeAckMagic
	binary.BigEndian.PutUint32(ack[1:5], second+1)
	binary.BigEndian.PutUint32(ack[5:9], second)
	s.snd.onAck(ack, now.Add(20*time.Millisecond))
	if s.snd.low != second+1 || s.snd.rtx != 0 || len(s.snd.rtxQ) != 0 {
		t.Fatal("late delivery did not finish without retransmission")
	}
}

func TestUnorderedStripeLaneStillRecoversRealLossAtRTO(t *testing.T) {
	s, now, frames := queuedStripePair(t, &unorderedStripeTestLane{})
	first := binary.BigEndian.Uint32(frames[0][1:5])
	second := binary.BigEndian.Uint32(frames[1][1:5])
	s.snd.onAck(ackSecondOnly(first, second), now.Add(10*time.Millisecond))
	if len(s.snd.rtxQ) != 0 {
		t.Fatal("loss was inferred before its timeout")
	}
	rto := s.snd.rto()
	if s.snd.detectLoss(now.Add(rto - time.Nanosecond)) {
		t.Fatal("retransmission fired before the adaptive RTO")
	}
	if !s.snd.detectLoss(now.Add(rto)) {
		t.Fatal("genuinely missing packet was not recovered at RTO")
	}
	resent := s.snd.pull(0, 1, now.Add(rto))
	if len(resent) != 1 || !bytes.Equal(resent[0], frames[0]) || s.snd.rtx != 1 {
		t.Fatal("RTO did not resend the original missing packet")
	}
	if s.snd.rtxTime != 1 {
		t.Fatal("real loss was not accounted as timeout recovery")
	}
}

func TestUnmarkedFIFOStripeLaneKeepsFastLossRecovery(t *testing.T) {
	s, now, frames := queuedStripePair(t, &stripeTestPuller{})
	first := binary.BigEndian.Uint32(frames[0][1:5])
	second := binary.BigEndian.Uint32(frames[1][1:5])
	if !s.snd.onAck(ackSecondOnly(first, second), now.Add(10*time.Millisecond)) {
		t.Fatal("FIFO lane lost its existing ACK-based loss recovery")
	}
	if len(s.snd.rtxQ) != 1 || s.snd.rtxQ[0] != first {
		t.Fatal("FIFO fast recovery did not target the missing earlier packet")
	}
}

type stripeTestLaneSource struct{ lane Transport }

func (s *stripeTestLaneSource) NewLane() Transport { return s.lane }
func (*stripeTestLaneSource) SetGrowHook(func())   {}

func TestDynamicUnorderedStripeLaneUsesTimeoutLossRecovery(t *testing.T) {
	lane := &unorderedStripeTestLane{}
	s := NewStripedTransportAuto([]Transport{&stripeTestPuller{}}, &stripeTestLaneSource{lane}, 2, false)
	stopStripeAfterTest(t, s)
	s.addLane("synthetic reorder regression")
	if s.LaneCount() != 2 || lane.source == nil {
		t.Fatal("dynamic lane was not attached")
	}
	now := time.Now()
	s.snd.enqueue([]byte("earlier"))
	s.snd.enqueue([]byte("later"))
	frames := s.snd.pull(1, 2, now)
	if len(frames) != 2 {
		t.Fatal("dynamic lane did not send the test pair")
	}
	first := binary.BigEndian.Uint32(frames[0][1:5])
	second := binary.BigEndian.Uint32(frames[1][1:5])
	if s.snd.onAck(ackSecondOnly(first, second), now.Add(10*time.Millisecond)) {
		t.Fatal("dynamic unordered lane inferred loss from ACK order")
	}
	if !s.snd.detectLoss(now.Add(s.snd.rto())) {
		t.Fatal("dynamic unordered lane lost timeout recovery")
	}
}

func TestStripeLaneClassificationCanChangeAlongsideLossChecks(t *testing.T) {
	var sender sender
	sender.init()
	sender.enqueue([]byte("pending while a new lane attaches"))
	if len(sender.pull(0, 1, time.Now())) != 1 {
		t.Fatal("concurrent loss checks have no outstanding packet")
	}
	var workers sync.WaitGroup
	workers.Add(2)
	go func() {
		defer workers.Done()
		for i := 0; i < 1_000; i++ {
			sender.setLaneReordering(i%maxStripeLanes, i%2 == 0)
		}
	}()
	go func() {
		defer workers.Done()
		for i := 0; i < 1_000; i++ {
			sender.detectLoss(time.Now())
		}
	}()
	workers.Wait()
}
