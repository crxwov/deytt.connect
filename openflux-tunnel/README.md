# Managed OpenFlux Android proxy and L4 exit

This module supplies `space.deytt.openflux.OpenFluxProxyBackend` and a small Linux helper. The upstream subset, pin, license, and local patches are documented in `../third_party/openflux-core/UPSTREAM.md`. No compiled binary belongs in source control.

Data path: authenticated loopback SOCKS5 -> IPv4 gVisor stack -> strict negotiated Session -> AES-256-GCM -> stripe sequence/reorder/ARQ -> Cups rooms. There is one session per Android process, one carrier type, one key/context per managed channel, no classic fallback, and no Direct carrier.

## Android API

`start(profileJson, username, password, SocketProtector)` returns after the encrypted peer handshake succeeds. `port()`, `isConnected()`, `bytesSent()`, `bytesReceived()`, and idempotent `close()` expose state. `SocketProtector.protect(int)` is the only abstract method; override its default `dnsServers()` with IPv4 resolvers from the selected physical network. All carrier HTTP, WebSocket, and pure Go resolver UDP/TCP sockets invoke protection before connect. A refusal aborts the dial. The default resolver list is protected Cloudflare/Google IPv4 DNS when no platform override is provided.

The native dispatcher owns a permanent thread separate from libbox and other Go runtimes. Interrupted callers wait for start/stop completion and then regain their interrupt status. Close drains active protection callbacks and leaves a closed Go gate for late goroutines before releasing the JNI global reference, preventing callback use after free.

SOCKS binds `127.0.0.1:0`, requires 16..255-byte random username/password, accepts resolved IPv4 destinations, and supports TCP/UDP. Application hostname resolution belongs to the existing sing-box tunnel DNS configuration; this proxy refuses hostnames rather than resolving website names on the carrier network. IPv6 is rejected.

Profile JSON fields: optional `version:1`, `id:"openflux:DE"`, `country:"DE"`, optional `label`, future `expires_at` within 24 hours, a 32-byte unpadded base64url `secret`, 16..256-byte `context`, and exactly one `transports` item with `type/name:"cupsonline"`, `priority:100`, `url` containing an unpadded base64url JSON array of 1..4 canonical lowercase room UUIDs. Client URLs must be nonempty. Expiry is checked at admission; the backend manager owns live lease renewal, expiry and revocation.

## Reproducible build

Use Go **1.26.6**, NDK **26.1.10909125**, CMake **3.22.1**, Java 17, Android compile SDK 35/min SDK 24. Supported ABIs: arm64-v8a, armeabi-v7a, x86_64. Dependency versions and checksums are committed; native builds use `-mod=readonly`, `-trimpath`, `-buildvcs=false`, and an empty build ID. ELF segments use 16 KiB alignment.

Include the module in settings and the app dependency, then run `gradlew :openflux-tunnel:assembleRelease -PopenfluxGoExecutable=<absolute Go1.26.6 executable>`. `OPENFLUX_GO_EXECUTABLE` is an alternative environment variable. Set writable `GOPATH`, `GOCACHE`, `GOTMPDIR`, `TEMP` and `TMP` when the Windows profile defaults are unavailable. CMake rejects another Go version. Outputs remain under `build/` and `.cxx/`.

Run `go test ./...` separately in `src/main/go` and `../third_party/openflux-core`. Tests cover malformed/expired profiles, forbidden carriers, DNS-leak prevention, protection rejection and callback draining, egress policy boundaries, authenticated SOCKS, strict encrypted sessions, tamper/replay rejection, and stripe bounds/cleanup.

## Linux helper

From `src/main/go`, build with `GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -ldflags '-s -w -buildid=' -o <artifact> ./cmd/openflux-exit`. Run `openflux-exit --config <private-0600-file>` or `--config -` for stdin. The same profile schema applies; only the helper may receive an empty Cups URL to create rooms. Initial carrier setup is canceled after 40 seconds.

After room provisioning/rejoin and L4 initialization, stdout emits exactly one bounded line: `{"ready":true,"rooms_url":"<packed-list>"}`. This precedes the client's Session handshake and contains no key. The caller persists rooms in its private configuration and supervises process lifetime. Missing saved rooms fail instead of being recreated. No traffic or room details are logged.

The helper needs no root, TUN, or raw-socket capability. A systemd `RestrictAddressFamilies` allowlist must include `AF_NETLINK` alongside `AF_INET`, `AF_INET6` and `AF_UNIX`: Go reads local interface addresses through rtnetlink for the egress policy. An empty `CapabilityBoundingSet` is sufficient. Its egress policy rejects loopback, private, link-local, multicast, unspecified, CGNAT, reserved high IPv4 space, and worker interface IPs before both TCP and UDP dials. The authenticated manager must enforce device/subscription access, per-device keys and unique rooms, admission limits, dynamic lease expiry, process limits and sandboxing. No test sends real application traffic or provisions public rooms automatically.

Native builds and synthetic tests do not establish carrier availability or phone acceptance. Deployment, actual VPN ownership, new website traffic and transport counters must be checked on the connected phone before release.
