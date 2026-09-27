package main

// #include "bridge.h"
import "C"

import (
	"space.deytt/awgproxy/proxy"
	"sync"
)

var handles = struct {
	sync.Mutex
	next     int64
	backends map[int64]*proxy.Backend
}{backends: make(map[int64]*proxy.Backend)}

//export deyttStart
func deyttStart(settings, addresses, dns *C.char, mtu C.int, username, password *C.char, protector C.uintptr_t) C.int64_t {
	b, err := proxy.Start(C.GoString(settings), C.GoString(addresses), C.GoString(dns), int(mtu), C.GoString(username), C.GoString(password),
		func(fd int) bool { return C.deytt_protect_socket(protector, C.int(fd)) != 0 })
	if err != nil {
		return 0
	}
	handles.Lock()
	defer handles.Unlock()
	handles.next++
	handles.backends[handles.next] = b
	return C.int64_t(handles.next)
}

//export deyttPort
func deyttPort(handle C.int64_t) C.int {
	handles.Lock()
	defer handles.Unlock()
	if b := handles.backends[int64(handle)]; b != nil {
		return C.int(b.Port())
	}
	return 0
}

//export deyttStop
func deyttStop(handle C.int64_t) {
	handles.Lock()
	b := handles.backends[int64(handle)]
	delete(handles.backends, int64(handle))
	handles.Unlock()
	if b != nil {
		b.Close()
	}
}

func main() {}
