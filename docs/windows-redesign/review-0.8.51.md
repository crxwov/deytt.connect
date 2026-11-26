# Windows review 0.8.51

## Scope and status

Local implementation and fixture acceptance cover map motion/traffic, typography, localization, routes/profile, connection-action races, tray lifecycle and installation-specific updates. Artifacts are built from the existing dirty feature/windows-redesign worktree; unrelated changes and older packages are preserved.

The user's first connection still exits with code 0x00000001. Its runtime cause is not established. This review fixes loss of the final engine output, not a proven cause of that exit. The task remains active for real cold-start acceptance.

## Changes

- Engine startup/monitor paths drain both output streams before disposal; retain bounded sanitized terminal diagnostics. Runtime logging emits console errors even if subscription logging was disabled or pointed to a file. Preserve SID/ACL restrictions.
- Connection, cancellation, route switching and polling reject stale operation generations. Quit blocks competing actions, waits for pending connect and requires a final confirmed disconnect.
- Closing hides to tray. Tray exposes open, connect/disconnect/cancel, server selection, allowlist bypass and disconnect/quit. Changing an active route disconnects and reconnects using the selected route.
- Updater compares normalized numeric versions, chooses the highest eligible stable version regardless of release array order, and matches MSI versus the existing portable layout. Downloads retain official-host, size and SHA-256 checks. Startup offers a newer matching package; manual checking downloads it, launches a verified MSI after VPN shutdown, or reveals the verified portable ZIP. UAC cancellation keeps the app usable.
- Compact map starts at zoom1.82 (exit1.60), boosts to3.3, fills the content area and shows latency/speed. Default tilt is-3deg and decreases continuously on zoom-out; full-panel hover and focus straighten it.
- Three thin, smooth, gently shimmering arcs use contrasting theme-specific colors. Spherical packets vary in size. Single click explains double click; the inspector isolates one moving flow without repainting the globe, and occupies the right-panel area to avoid city labels. The legend and inspector text are localized. Traffic categories are illustrative, not inspection of actual packet payloads.
- Light land is brighter; light arcs use dark colors and a white contour. Secondary text is at least13px with stronger theme contrast. English/Russian route names and speed units are consistent.
- Route rows use equal minimum height and aligned metrics; unmeasured routes say so and retain measurement actions. Protocol marks and profile disclosure controls use vector assets; allowlist uses the route vector icon and chained flags stay within their frame.
- Profile identity/subscription cards have spacing. The plan reads deytt. ultra; expiry within8days and a reached device limit are highlighted. Traffic is explicitly used traffic, with a separate unlimited-quota label.
- Page/dialog entrance uses short fade/translation/scale; existing hover, press, panel, status and inspector transitions respect reduced motion.

## Validation

- Protocol114, client89, headless95 tests pass:298 total. Includes18 updater cases,13 tray/status lifecycle cases, bounded diagnostics, profile boundaries, flag bounds and speed-unit casing.
- Release publishes pass. A fresh independent Debug restore/build passes with0 errors/warnings; an earlier Debug failure was stale configuration.
- Final rendered fixture evidence: D:\DeyttConnectBuild\review-0.8.51\qa. Includes dark/light/narrow home, compact/reduced/immersive modes, English exit1 copy, routes, profile warnings and settings. Synthetic account/service data; no live session.
- Held-pointer globe remains painted and lost capture releases dragging. Whole-panel hover/focus transform is identity. Single-click hint, selected-flow isolation/continued motion, second-line selection and unchanged static globe on inspector opening pass. Mini metrics remain88ms/48Mbit/s after synthetic refresh; Escape exits immersive mode.
- Local165Hz display: idle sample360 frames,~165fps, p95 render0.5ms, no intervals above20ms. Scripted wheel zoom/rotation sample287 frames,~124fps, p95 render4.3ms and frame interval12.2ms;9 intervals above20ms. These are local fixture measurements, not a hardware-independent frame-rate guarantee.
- Diff whitespace check passes. Staged map asset hashes match source; app/service metadata is0.8.51.0. Engine SHA-256 remains2bb48a559ade9547202c9ddab3648dfbfbe201b5c7d272237367743c86d87ed0.

## Packaging / remaining acceptance

Build root: D:\DeyttConnectBuild\review-0.8.51. Separate fresh MSI and portable sources carry build-info.json. Final installer/portable hashes and Downloads paths are recorded in the root ROADMAP.md after delivery.

Delivered and rehashed:

- C:\Users\hackov\Downloads\deytt-connect-0.8.51.msi,88218105bytes; SHA-2562e4851f835a6b4b609c93fa45e10fb5f3eafc606f7b4145435cd46b88397ebe0.
- C:\Users\hackov\Downloads\deytt-connect-0.8.51-portable.zip,110826287bytes; SHA-2564cd5ac034d170e615036800118cab05a709a70d3f46b026a7b4e6eda0c126e52.
- Both sidecars verified. MSI embedded app/service file versions are0.8.51.0. Portable layout/provenance and all442 payload hashes verified directly from the ZIP.

MSI database validation checks identity/version modes, downgrade prevention, payload/progress layout, branding, launch, SID, service and wizard. Six existing WiX warnings concern directory canonicalization and font language metadata; no build errors. No real installation, update installer launch, tray interaction through the Windows shell or VPN connection was performed by this fixture run. Those require native acceptance, especially the unresolved first engine exit.
