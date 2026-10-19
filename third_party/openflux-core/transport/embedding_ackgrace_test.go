package transport

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"testing"
	"time"
)

func compatibilityReceiver(t *testing.T, unordered bool) *reorderBuffer {
	t.Helper()
	var lane Transport = &stripeTestPuller{}
	if unordered {
		lane = &unorderedStripeTestLane{}
	}
	s := NewStripedTransport([]Transport{lane})
	s.attach(0, lane)
	stopStripeAfterTest(t, s)
	return s.ro
}

func assertCumulativeOnly(t *testing.T, ack []byte, next uint32) {
	t.Helper()
	if len(ack) != ackHeader || ack[0] != stripeAckMagic || ack[9] != 0 ||
		binary.BigEndian.Uint32(ack[1:5]) != next || binary.BigEndian.Uint32(ack[5:9]) != next-1 {
		t.Fatalf("invalid cumulative-only ACK for sequence %d: %x", next, ack)
	}
}

func TestAckGraceProtectsLegacySenderFromHundredMillisecondReorder(t *testing.T) {
	// The sender deliberately has the old, unmarked FIFO loss behavior.
	s, now, frames := queuedStripePair(t, &stripeTestPuller{})
	r := compatibilityReceiver(t, true)
	first := binary.BigEndian.Uint32(frames[0][1:5])
	second := binary.BigEndian.Uint32(frames[1][1:5])
	r.pushAt(second, first, frames[1][stripeHeader:], now)
	ack := r.ackFrameAt(now.Add(10 * time.Millisecond))
	assertCumulativeOnly(t, ack, first)
	if s.snd.onAck(ack, now.Add(10*time.Millisecond)) || len(s.snd.rtxQ) != 0 {
		t.Fatal("legacy sender retransmitted before the delayed original arrived")
	}
	r.pushAt(first, first, frames[0][stripeHeader:], now.Add(100*time.Millisecond))
	s.snd.onAck(r.ackFrameAt(now.Add(100*time.Millisecond)), now.Add(100*time.Millisecond))
	if s.snd.low != second+1 || len(s.snd.rtxQ) != 0 || s.snd.rtx != 0 {
		t.Fatal("reordered pair was not acknowledged without retransmission")
	}
}

func TestAckGraceExposesPersistentLossAtDeadline(t *testing.T) {
	s, now, frames := queuedStripePair(t, &stripeTestPuller{})
	r := compatibilityReceiver(t, true)
	first := binary.BigEndian.Uint32(frames[0][1:5])
	second := binary.BigEndian.Uint32(frames[1][1:5])
	r.pushAt(second, first, frames[1][stripeHeader:], now)
	assertCumulativeOnly(t, r.ackFrameAt(now.Add(unorderedAckGrace-time.Nanosecond)), first)
	if !r.ackGraceExpired(now.Add(130*time.Millisecond), now.Add(unorderedAckGrace)) {
		t.Fatal("ACK clock did not notice the selective ACK deadline")
	}
	ack := r.ackFrameAt(now.Add(unorderedAckGrace))
	if len(ack) != ackHeader+6 || ack[9] != 1 {
		t.Fatal("persistent loss remained hidden after the grace deadline")
	}
	if r.ackGraceExpired(now.Add(unorderedAckGrace), now.Add(160*time.Millisecond)) {
		t.Fatal("expired grace forced repeated extra ACKs")
	}
	if !s.snd.onAck(ack, now.Add(unorderedAckGrace)) || s.snd.rtxTime != 0 {
		t.Fatal("legacy sender did not fast recover the persistent loss")
	}
	resent := s.snd.pull(0, 1, now.Add(unorderedAckGrace))
	if len(resent) != 1 || !bytes.Equal(resent[0], frames[0]) || s.snd.rtx != 1 {
		t.Fatal("persistent loss did not resend the original missing frame")
	}
	r.pushAt(first, first, resent[0][stripeHeader:], now.Add(160*time.Millisecond))
	s.snd.onAck(r.ackFrameAt(now.Add(160*time.Millisecond)), now.Add(160*time.Millisecond))
	// An original delayed for 300 ms is now merely a duplicate of the repair.
	r.pushAt(first, first, frames[0][stripeHeader:], now.Add(300*time.Millisecond))
	if s.snd.low != second+1 || r.count != 0 {
		t.Fatal("repair or a late original disrupted cumulative progress")
	}
}

func TestAckGraceDeadlineIsNotExtendedByHigherPackets(t *testing.T) {
	r := compatibilityReceiver(t, true)
	now := time.Time{}
	r.pushAt(11, 10, []byte{0, 1}, now)
	r.pushAt(12, 10, []byte{0, 2}, now.Add(100*time.Millisecond))
	r.pushAt(13, 10, []byte{0, 3}, now.Add(149*time.Millisecond))
	assertCumulativeOnly(t, r.ackFrameAt(now.Add(149*time.Millisecond)), 10)
	if len(r.ackFrameAt(now.Add(150*time.Millisecond))) == ackHeader {
		t.Fatal("higher arrivals restarted grace for the same missing sequence")
	}
	r.pushAt(10, 10, []byte{0, 0}, now.Add(200*time.Millisecond))
	r.pushAt(15, 14, []byte{0, 5}, now.Add(210*time.Millisecond))
	assertCumulativeOnly(t, r.ackFrameAt(now.Add(250*time.Millisecond)), 14)
	if len(r.ackFrameAt(now.Add(360*time.Millisecond))) == ackHeader {
		t.Fatal("a newly advancing gap did not expire its own bounded grace")
	}
}

func TestAckGraceCumulativeHeaderWrapsWithoutAcknowledgingTheGap(t *testing.T) {
	for _, first := range []uint32{0, ^uint32(0)} {
		t.Run(fmt.Sprint(first), func(t *testing.T) {
			r := compatibilityReceiver(t, true)
			now := time.Time{}
			r.pushAt(first+1, first, []byte{0, 1}, now)
			assertCumulativeOnly(t, r.ackFrameAt(now.Add(10*time.Millisecond)), first)
			ack := r.ackFrameAt(now.Add(unorderedAckGrace))
			if ack[9] != 1 || binary.BigEndian.Uint32(ack[1:5]) != first ||
				binary.BigEndian.Uint32(ack[5:9]) != first+1 {
				t.Fatal("selective ACK after wrap lost the missing sequence")
			}
		})
	}
}

func TestFIFOLaneDoesNotDelaySelectiveAcknowledgement(t *testing.T) {
	r := compatibilityReceiver(t, false)
	now := time.Time{}
	r.pushAt(11, 10, []byte{0, 1}, now)
	ack := r.ackFrameAt(now)
	if len(ack) != ackHeader+6 || ack[9] != 1 {
		t.Fatal("an unmarked FIFO lane gained an ACK grace period")
	}
	if r.ackGraceExpired(now, now.Add(unorderedAckGrace)) {
		t.Fatal("FIFO ACK clock acquired an unordered deadline")
	}
}

// This event replay measures legacy-sender retransmissions and repair delay,
// not real carrier bandwidth. One in every 100 runs loses the earlier frame.
func replayLegacyAckGrace(grace bool, reorder time.Duration, loseFirst bool) (uint64, time.Duration) {
	var q sender
	q.init()
	now := time.Unix(1, 0)
	q.enqueue([]byte("earlier"))
	q.enqueue([]byte("later"))
	frames := q.pull(0, 2, now)
	first := binary.BigEndian.Uint32(frames[0][1:5])
	second := binary.BigEndian.Uint32(frames[1][1:5])
	r := &reorderBuffer{ring: make([]reorderSlot, reorderWindow), out: make(chan reorderOut, 256),
		done: make(chan struct{}), ackGraceEnabled: grace}
	originalArrived := !loseFirst && reorder == 0
	if originalArrived {
		r.pushAt(first, first, frames[0][stripeHeader:], now)
	}
	r.pushAt(second, first, frames[1][stripeHeader:], now)
	var repairAt time.Duration
	for elapsed := 10 * time.Millisecond; elapsed <= time.Second; elapsed += 10 * time.Millisecond {
		at := now.Add(elapsed)
		if !originalArrived && !loseFirst && elapsed >= reorder {
			r.pushAt(first, first, frames[0][stripeHeader:], at)
			originalArrived = true
		}
		if repairAt > 0 && elapsed >= repairAt {
			r.pushAt(first, first, frames[0][stripeHeader:], at)
			repairAt = 0
		}
		q.onAck(r.ackFrameAt(at), at)
		if len(q.rtxQ) > 0 && len(q.pull(0, 1, at)) > 0 && repairAt == 0 {
			repairAt = elapsed + 10*time.Millisecond
		}
		if q.low == second+1 {
			return q.rtx, elapsed
		}
	}
	panic("synthetic legacy sender failed to recover")
}

func BenchmarkLegacySenderAckGrace(b *testing.B) {
	for _, grace := range []bool{false, true} {
		for _, reorder := range []time.Duration{0, 30 * time.Millisecond, 100 * time.Millisecond, 300 * time.Millisecond} {
			for _, lossPercent := range []int{0, 1} {
				b.Run(fmt.Sprintf("grace_%t/reorder_%dms/loss_%dpct", grace, reorder.Milliseconds(), lossPercent), func(b *testing.B) {
					var retransmissions uint64
					var repairTime time.Duration
					for i := 0; i < b.N; i++ {
						rtx, elapsed := replayLegacyAckGrace(grace, reorder, lossPercent == 1 && i%100 == 0)
						retransmissions += rtx
						repairTime += elapsed
					}
					b.ReportMetric(float64(retransmissions)/float64(b.N), "rtx/op")
					b.ReportMetric(float64(repairTime.Microseconds())/float64(b.N)/1000, "repair_ms/op")
				})
			}
		}
	}
}
