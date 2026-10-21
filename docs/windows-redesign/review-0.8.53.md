# Windows user flow repair 0.8.53 candidate

## Scope and decisions
Repair the reported 0.8.52 first-run, exit, uninstall, device-slot and map workflow. Preserve the existing dirty `feature/windows-redesign` work. Latest user feedback overrides the traffic experiment: retain the original arcs and moving dots. Reduce excessive land depth and heavy callouts. This is a local review candidate, not a public release or verified live acceptance.

## Resulting behavior
- First-run setup uses the actual application icon and `Подключите ./Connect`. Mandatory setup cannot escape into Home through Back, Escape or navigation. Existing-account reauthentication can still cancel; host shutdown can abort the view.
- Telegram usernames normalize one leading `@`, enforce 5–32 ASCII characters, and validate before sending an API request. Subscription-link import remains available. The occupied-computer-slot message points to owner-controlled removal in the bot.
- Exit waits for a pending connection operation before disconnecting, checks whether the service/tunnel is inactive, then shuts down the desktop lifetime including owned windows. A failed or unknown stop is surfaced; the original reported hang cause remains unconfirmed.
- MSI registers removal in Windows and includes removal shortcuts. Explicit removal cleans known application-owned data and private runtime remnants; upgrades retain user data. Portable service registration has a bounded file manifest and an uninstall command. Unknown files and reparse points are preserved. SID access controls remain enforced.
- Home retains the selected automatic mode while authenticated local engine telemetry reports its actual canonical primary exit. Unknown telemetry produces no fabricated country. Confirmed automatic selection shows the chosen city, country flag and protocol; only origin/selected-exit callouts remain.
- Wheel zoom changes globe scale with the same 1.18 ratio per detent and never requests fullscreen. Panel docking follows the readable-text boundary; explicit Space/Escape fullscreen remains available. Zoom settles quickly; reduced motion remains supported.
- Redundant visible connection heading, repeated automatic protocol text, persistent traffic legend and corner zoom controls are removed. Keyboard zoom and accessible stream descriptions remain. Connected route copy uses the blue state treatment; connection actions remain visible.
- Country tags are smaller with restrained padding, no card shadow, readable dark backgrounds in light mode and visible leaders. Cluster placement preserves available country labels in the tested wide/narrow views. Land extrusion and blurred coastline shadows are disabled; sphere lighting is softer.
- Original `trafficStreams`, `drawArc` and `drawTraffic` are restored exactly from the prior local payload. Experimental QA injection is removed. Double-click inspection still selects one stream and keeps its animation running.

## Backend delivery
Owner-scoped Connect computer/mobile device management is deployed on `nl-vpn`. Bot menu: `Устройства` → previous Connect computer → disable → confirm → retry login. Listing and release preserve independent device categories and session ownership; stale callbacks fail closed. No account/device blocks or slot limits were bypassed. Legacy unbound sessions cannot be identified safely, and an offline saved tunnel may persist until independently revoked.

## Validation
- Windows: 118 headless, 105 client and 127 protocol tests passed (350 total). Backend: 78 bot, 2 routing and 41 API tests passed.
- App/service/QA builds pass. Authenticated shipped-engine selector and URLTest smoke passes without creating a TUN; anonymous control requests are rejected.
- Synthetic native desktop lifetime exits successfully with an owned child window that vetoes ordinary close. Removal/upgrade path fixtures and MSI database registration/recovery checks pass.
- Actual WebView2 light/dark, narrow/wide, docked, high-zoom, explicit fullscreen/Escape and restored traffic-inspection fixtures pass. All five countries plus origin remain visible in the tested default views with zero callout overlaps, clipping or leader crossings. Wheel ratios and lost-pointer-capture recovery pass.
- Combined QA click fixture initially retained synthetic drag distance and suppressed clicks. The injected click sequence now resets that fixture state; hint, hover, first/second stream selection and ongoing animation all pass.
- JS syntax and `git diff --check` pass. WebView2 emits an unregister-class Error 1412 during fixture shutdown; captures succeed and processes exit 0.
- Evidence root: `D:\DeyttConnectBuild\user-flow-0.8.52`. Lifecycle and telemetry reports, rendered PNGs and final packaging verification are retained there.

## Delivery and acceptance
Fresh local 0.8.53 artifacts and SHA256 sidecars are delivered in Downloads:
- `C:\Users\hackov\Downloads\deytt-connect-0.8.53.msi`: `4570e436cbb2c2991355255add2ae1dacd26ce172afa697c70831dccb6437596`.
- `C:\Users\hackov\Downloads\deytt-connect-0.8.53-portable.zip`: `d543b4f0fb5ac258c1ad097c4ec7eea5495755689fc29494d31d5949bec61e75`.

Versions, map assets, uninstall scripts and manifests match the fresh stage. Installer registration/removal verification passes; all 450 stage, 452 portable manifest and 453 ZIP entries match. Independent-root Protocol metadata initially differed; canonical PathMap rebuilding produced identical shared assemblies. Dirty source is explicitly recorded as a local candidate. Six existing WiX warnings remain; no build errors. MSI is unsigned. Downloads receipt hashes pass, and both original 0.8.52 artifact hashes are unchanged. Final evidence: `final-packaging-report.md` and `final-validation-results.json` under the evidence root.

Real authenticated cold start, exit, install/update/uninstall and tunnel acceptance remain outstanding. Primary engine selection does not prove that every bypass or UDP flow uses the same exit. The revised callout appearance remains open for user review; no completion claim is made for that acceptance.
