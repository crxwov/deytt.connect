package proxy

import (
	"net"
	"testing"
)

func TestExitEgressBoundary(t *testing.T) {
	policy := exitEgressPolicy([]net.IP{net.ParseIP("203.0.113.10")})
	for _, address := range []string{"0.0.0.0", "0.0.0.1", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "100.127.255.255", "224.0.0.1", "255.255.255.255", "203.0.113.10", "::1", "2001:db8::1"} {
		if policy(net.ParseIP(address)) {
			t.Errorf("private/local/reserved destination accepted: %s", address)
		}
	}
	for _, address := range []string{"1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1"} {
		if !policy(net.ParseIP(address)) {
			t.Errorf("public boundary rejected: %s", address)
		}
	}
	if policy(nil) {
		t.Fatal("invalid destination accepted")
	}
}
