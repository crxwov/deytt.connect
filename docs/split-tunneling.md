# Domain split tunneling

Normal connections use one Android VPN interface, owned by `ConnectVpnService`.
The subscription remains the source of the explicit bypass domain list. The
runtime adds the provider's Happ suffix rules for `.ru` and `.xn--p1ai`, and the
exact `cp.cloudflare.com` exception. Updates arrive with subscription refresh.
No GeoIP/Geosite dataset is downloaded or silently invented.

`SplitTunnelProfile` sends matching DNS queries to the physical network resolver
and enables DNS reverse mapping for traffic classification. The route's remaining
traffic uses its selected VLESS, Trojan, Hysteria, chained, or automatic transport.
Private-network behavior from the subscription is preserved. Domain matching
uses label boundaries; specific entries such as `st.ozone.ru` do not expand to
all of `ozone.ru`.

AmneziaWG uses the same libbox routing layer. A process-local SOCKS5 listener
forwards TCP and UDP through an AmneziaWG userspace network stack. It has fresh
random control-channel credentials for each connection and binds only to
loopback. The native transport protects both outer UDP sockets before the
AmneziaWG device can send a handshake, including after socket recreation. Failed
protection aborts activation. It creates no second Android VPN interface.

All AWG JNI calls, including library loading, run on a dedicated process-lifetime
Java thread. They must never run on libbox threads: the independent Go shared
libraries otherwise reuse Android's thread-local Go state and can crash on the
next libbox call. Keep the interleaved native/libbox device regression when
changing either engine or the dispatcher. The corresponding upstream problem
is tracked in [Go issue 73841](https://github.com/golang/go/issues/73841).

The native bridge uses the pinned AmneziaWG v3 module, preserving extended
masking parameters through `Config.toAwgUserspaceString()`. Stopping the core
closes the local transport and active flows. Normal restoration after diagnostic
measurements goes through `ConnectVpnService` too; temporary app-only measurement
tunnels retain their separate existing backend.

## Limits

Like other domain routers, matching relies on visible DNS or protocol metadata.
Applications using their own encrypted DNS and hiding hostnames may prevent
domain identification; unmatched traffic uses the VPN. Shared-address DNS reverse
mapping is not a guarantee of per-domain separation for protocols without visible
hostnames. SOCKS5 UDP authenticates the TCP association, not every datagram; the
UDP relay pins its first local source and rejects subsequent other sources.

## Validation commands

- JVM: `:app:testDebugUnitTest`; Android static checks: `:app:lintDebug`.
- Native call dispatch: `:awg-tunnel:testDebugUnitTest`.
- Native: `go test -race ./proxy` and `go vet ./proxy` in `awg-tunnel/src/main/go`.
- Synthetic device runner: build with `-PisolatedQa=true
  -PqaRunner=space.deytt.connect.SplitTunnelInstrumentation`, then run its
  instrumentation against the separate QA package. It checks libbox schemas,
  socket-protection failure, JNI loading and repeated native start/close.
- Explicit live device runner: `SplitTunnelLiveInstrumentation` uses the installed
  subscription and VPN permission, tests one route per protocol, observes core
  outbound decisions for bypass/tunnel HTTPS probes, and restores route and
  connection state. It does not export subscription credentials.

## Verified in 0.8.12

128 JVM tests (125 app + 3 dispatcher), lint and multi-ABI debug builds passed.
Native race tests cover authenticated TCP/UDP, half-close, resource cleanup,
protected sockets and encrypted exchanges between two AWG peers with legacy and
extended masking parameters.

On the physical Android device, synthetic tests passed libbox configuration
validation, rejected socket protection and repeated interleaving of live AWG
backends with libbox calls. Live subscription tests passed VLESS, Trojan,
Hysteria 2, RU-to-DE and AmneziaWG 3.1: successful HTTPS requests plus observed
core connection events confirmed `yandex.ru` used `direct` and
`www.cloudflare.com` used the VPN outbound. The device was left disconnected
and temporary QA packages were removed.
