package cupsonline

import (
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport"
)

func TestCupsAggregateDeclaresPacketReordering(t *testing.T) {
	carrier := NewCupsonlineTransport("", transport.DefaultConfig(), true)
	defer carrier.Stop()
	var lane transport.PacketReorderer = carrier
	if !lane.ReordersPackets() {
		t.Fatal("independently paced Cups rooms were presented as one FIFO lane")
	}
}
