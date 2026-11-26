# Windows connection, route scoring, and map polish 0.8.52

## Scope
This update follows the user's actual first-start error screenshot and the latest map/ranking corrections. It builds on the 0.8.51 UI, tray, and updater work already in this dirty `feature/windows-redesign` checkout.

## Changes
- A TUN startup exit is retried once, after a one-second wait, only when bounded engine diagnostics contain both `tun` and `already exists`. Startup verification retains ownership of this retry; other engine errors are surfaced without retry. No network adapter was removed or reset.
- Route ranking now chooses the highest throughput among complete measurements within `max(5ms, 10% of fastest latency)`, with latency and route tag as stable tie-breakers. For the screenshot values (71ms/74.7Mbps Trojan; 72ms/82.9Mbps Hysteria), Hysteria wins.
- Home protocol labels are `./vless+ws`, `./trojan`, `./hysteria`, and `./amnezia3.1`.
- Light map palette restored to a saturated blue ocean and pale blue land. Panel tilt starts near zero, caps near23 degrees, and remains above the globe. Wheel, keyboard, and zoom buttons share an 18% multiplicative detent; compact mode begins at1.62x and fullscreen map at2.12x, without forced zoom jumps.

## Validation
- Protocol tests:117 passed. Headless UI tests:101 passed. Windows UI tests:89 passed (307 total).
- Rendered QA: light/dark default, zoomed compact and immersive screenshots under `D:\DeyttConnectBuild\review-0.8.52\qa`.
- Wheel QA measured identical1.18 ratios at1x and2x. At2.3x fullscreen activates. Default panel angle is about-1 degree; compact stage about-11 degrees.
- MSI build and installer database recovery checks passed. App/service assemblies are0.8.52.0. Portable ZIP manifest hashes matched9 payload files. Source portable archive SHA256: `1db6bf9a9e98bb668ed5b71ae24a186f87b8ee785abc9cd7e8f63c275ca33737`.
- Six WiX warnings remain (one directory-name canonicalization and five ICE60 language metadata warnings); no build errors.

## Delivery
- `C:\Users\hackov\Downloads\deytt-connect-0.8.52.msi` SHA256 `2f9477edf58e4d39942ae14a1443d4b05d757002ffc2eea1b34733c92a383f14`.
- `C:\Users\hackov\Downloads\deytt-connect-0.8.52-portable.zip` SHA256 `1db6bf9a9e98bb668ed5b71ae24a186f87b8ee785abc9cd7e8f63c275ca33737`.
- Both SHA256 sidecars were copied and verified. MSI and portable are unsigned.

## Acceptance limits
No live cold-start connection, installer launch, service replacement, or tray-shell test was performed. The actual screenshot diagnostic now has a targeted one-retry mitigation, but that timing fix needs confirmation on the next real cold start. The currently present TUN may be active, so it was not interrupted. Portable packaging left a generated 288,792,265-byte folder and 117,524,055-byte archive under `windows/artifacts` on C:. Automatic approval review rejected my attempt to remove those exact temporary outputs, leaving about45MiB free. The delivered files in Downloads are intact.

