package main

// #include <stdlib.h>
// #include "bridge.h"
import "C"

import (
	"space.deytt/openflux/proxy"
	"strings"
	"sync"
)

var handles = struct {
	sync.Mutex
	next      int64
	backends  map[int64]*proxy.Backend
	lastError string
}{backends: make(map[int64]*proxy.Backend)}

//export openfluxStart
func openfluxStart(profile, username, password, dns *C.char, protector C.uintptr_t) C.int64_t {
	b, err := proxy.Start(C.GoString(profile), C.GoString(username), C.GoString(password), strings.Split(C.GoString(dns), ","),
		func(fd int) bool { return C.openflux_protect_socket(protector, C.int(fd)) != 0 })
	handles.Lock()
	defer handles.Unlock()
	if err != nil {
		handles.lastError = err.Error()
		return 0
	}
	handles.next++
	handles.backends[handles.next] = b
	handles.lastError = ""
	return C.int64_t(handles.next)
}

//export openfluxError
func openfluxError() *C.char {
	handles.Lock()
	defer handles.Unlock()
	return C.CString(handles.lastError)
}

//export openfluxPort
func openfluxPort(h C.int64_t) C.int {
	handles.Lock()
	defer handles.Unlock()
	if b := handles.backends[int64(h)]; b != nil {
		return C.int(b.Port())
	}
	return 0
}

//export openfluxConnected
func openfluxConnected(h C.int64_t) C.int {
	handles.Lock()
	defer handles.Unlock()
	if b := handles.backends[int64(h)]; b != nil && b.IsConnected() {
		return 1
	}
	return 0
}

//export openfluxBytesSent
func openfluxBytesSent(h C.int64_t) C.int64_t {
	handles.Lock()
	defer handles.Unlock()
	if b := handles.backends[int64(h)]; b != nil {
		return C.int64_t(b.BytesSent())
	}
	return 0
}

//export openfluxBytesReceived
func openfluxBytesReceived(h C.int64_t) C.int64_t {
	handles.Lock()
	defer handles.Unlock()
	if b := handles.backends[int64(h)]; b != nil {
		return C.int64_t(b.BytesReceived())
	}
	return 0
}

//export openfluxStop
func openfluxStop(h C.int64_t) {
	handles.Lock()
	b := handles.backends[int64(h)]
	delete(handles.backends, int64(h))
	handles.Unlock()
	if b != nil {
		b.Close()
	}
}

func main() {}
