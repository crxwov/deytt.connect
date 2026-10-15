# OpenFlux source subset

- Source: https://github.com/p1neappleXpress/OpenFlux
- Commit: `edcf77a1848a72370cfba9f346d3b897b61e7e7d`
- License: GPL-3.0-or-later; original `LICENSE`, `COPYRIGHT`, and `NOTICE` retained.
- `UPSTREAM-SOURCES.sha256` records the original upstream bytes of every selected file before local patches. No upstream checkout metadata or generated binaries is vendored.

Only the shared Session/framing/encryption/stripe layers, Cups carrier, protected dialing, IPv4 netstack, SOCKS, and their utility packages are included. Other carriers, registry, mobile framework, CLI, privileged L3, and application assets are omitted. `go.mod`/`go.sum` contain the required pinned dependency subset. Classic compatibility code remains because Session references it internally; this integration never enables it.

Local patches:

- `netbind/protector.go`: install a fail-closed socket control callback, drain callbacks on close before JNI reference release, reject late callbacks. Defaults use a fresh HTTP pool without environment proxies. Pure Go DNS exposes UDP/TCP sockets to protection and accepts physical-network IPv4 resolver addresses.
- `transport/cupsonline/cupsonline.go`: bounded HTTP response bodies, 128 send records per room, 16 KiB socket buffers and 128 KiB WebSocket frames; remove CLI room-list banners; fail when saved rooms disappear instead of silently changing a managed profile.
- `transport/striped.go`: retain the upstream Cups sequence/reorder/ARQ wire layer; use a 4096-piece window and 256-piece delivery queue, reject oversized pre-authentication pieces, bound frame reassembly to approximately 1 MiB, and stop the delivery goroutine with the session. Wire headers and cryptography are unchanged.
- `tunnel/tunnel.go`: remove the unused privileged L3 setter/import, use 128 KiB default and 1 MiB maximum TCP buffers, cap TCP/UDP flows at 64. Add an optional immutable L4 egress predicate installed before receiving packets; the managed exit enables it, the Android client does not.
- `socks5`: expose reserved listener port; cap clients and per-association UDP flows at 64.
- `netbind/protector_test.go`, `transport/embedding*_test.go`, `socks5/embedding_test.go`: integration-specific regression coverage. Selected upstream AES-GCM and Session tests are included unchanged.

To refresh, compare against the pinned source manifest, preserve these deliberate patches, and rerun both Go test suites plus every Android ABI build and connected-device acceptance. Never update the pin or broaden carriers implicitly.
