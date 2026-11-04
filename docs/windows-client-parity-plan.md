# Windows parity and connection quality plan

## Goal
Port current Android visual intent into Windows, make route diagnostics truthful, shorten cold start where concurrency is demonstrably safe, and deliver a locally verified MSI.

## Code scope and callers
- `EngineController.ConnectAsync`: resolve AWG peer and pre-TUN HTTPS canary hosts concurrently; retain cancellation and early failure semantics.
- UI progress mapping: reject impossible zero latency before ranking or rendering; keep valid probe values from service unchanged.
- Route page: country expand/measure behavior, auth explanation for speed, protocol icons; preserve focus and keyboard access.
- Home/connect and profile: phase-aware action surface and Android hierarchy adapted to desktop.
- Route globe: use Windows profile to reduce default zoom from 1.1 to 1.0 without changing Android bridge zoom (1.28) or other clients.
- Build: current source app/service at 0.11.84, exact unchanged engine payload from installed 0.11.83 with verified SHA-256, generated provenance, no publication.

## Sequence
1. Add focused regression checks before behavior changes.
2. Implement the connection startup and metrics fixes; verify deterministic tests.
3. Port the Windows UI direction and zoom setting; check desktop sizing, accessibility and route state.
4. Run headless/service tests and release builds at least twice; inspect diff and source status.
5. Create a self-contained package, embed current build provenance, build the MSI, extract/inspect its payload and hashes, then copy it to Downloads under a new filename.

## Constraints
- No GitHub release, commit, push, deployment, or MSI installation.
- Do not read session credentials or bypass server authorization.
- Do not change MTU, routing defaults, or automatic reconnect behavior without live evidence.
- HTTPS diagnostics do not establish game latency or jitter; describe only verified startup improvement.
- Preserve concurrent changes and the parent repository's roadmap.
