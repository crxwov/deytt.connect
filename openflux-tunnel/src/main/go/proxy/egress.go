package proxy

import "net"

func exitEgressPolicy(local []net.IP) func(net.IP) bool {
	locals := make(map[string]bool)
	for _, ip := range local {
		if ip4 := ip.To4(); ip4 != nil {
			locals[ip4.String()] = true
		}
	}
	return func(ip net.IP) bool {
		v := ip.To4()
		if v == nil || !v.IsGlobalUnicast() || v.IsPrivate() || v.IsLoopback() || v.IsLinkLocalUnicast() || locals[v.String()] {
			return false
		}
		// Also exclude this-network, CGNAT and reserved high IPv4 space.
		return v[0] != 0 && !(v[0] == 100 && v[1] >= 64 && v[1] <= 127) && v[0] < 224
	}
}

func currentExitEgressPolicy() (func(net.IP) bool, error) {
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		return nil, err
	}
	local := make([]net.IP, 0, len(addresses))
	for _, address := range addresses {
		ip, _, err := net.ParseCIDR(address.String())
		if err == nil {
			local = append(local, ip)
		}
	}
	return exitEgressPolicy(local), nil
}
