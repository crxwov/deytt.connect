# Roadmap — GitHub first-client readiness

## Objective and success criteria
Make the public repository and Releases page understandable and safe for the first Android client users. Keep every release's notes limited to verified changes, provide one clear current download path, and publish matching source and release metadata.

## Stages
- [x] Inspect repository metadata, current README, release history/assets, and GitHub branch ancestry.
- [ ] Set an accurate repository identity and write first-client install, update, support, compatibility, and checksum guidance.
- [ ] Reduce every published release body to a concise changelog; remove only verified duplicate or obsolete release assets.
- [ ] Align the 0.8.9 release tag and published APK with source history, then fast-forward GitHub `main` if the update stays non-destructive.
- [ ] Validate GitHub metadata, all release notes/assets, the direct APK download/checksum, and repository changes; commit and push `main`.

## Current state
- Repository `/home/hackov/Documents/deytt-connect`, branch `main`; existing unrelated worktree changes are present and must remain unstaged.
- Gitea `origin/main` is at `dc88f60`; GitHub `main` is an ancestor of local `HEAD`, so a normal fast-forward is possible.
- GitHub repository metadata has no description, homepage, topics, detected license, or issue templates. README still points to 0.8.8 and contains duplicated stale artifact instructions.
- GitHub has 21 releases from 0.2.0 through the renamed 0.8.9 publication. Release notes mix changelogs with QA logs, backend deployment details, known issue history, and checksums. 0.4.0 and 0.3.3 have duplicate APKs by digest; 0.3.2 contains ARM64 plus a separate universal debug build.
- Latest release currently uses old tag `v0.8.8-debug`; uploaded APK is 0.8.9. Its release is non-prerelease and the asset digest is verified.
- Current app contract: Android 7.0+ (API 24), published 0.8.9 asset is arm64-v8a, Telegram one-time-code pairing imports the active DEYTTT subscription, and VPN setup is confirmed by Android's system dialog.
- Existing code license is explicitly GPL-3.0-or-later, but `LICENSE` contains only a summary, so GitHub reports `NOASSERTION`. Third-party notices already document sing-box and AmneziaWG components.
- Local README, full GPL text, Issue forms, and `SECURITY.md` are drafted. GitHub private vulnerability reporting is enabled; repository profile, source branch, releases, and assets are not yet updated.
- The legal-info URL used by the app and profile, `https://deytt.space/info`, timed out from this environment. Keep the existing canonical link, but do not claim it was reachable during this pass.

## Findings and decisions
- Keep published release history and each unique downloadable build. Do not delete historical releases or assets merely to shorten the page.
- Keep release bodies focused on user-visible changes. Put install details and the current SHA-256 in README or asset labels, not repeated release prose.
- Current source commit is available for a GitHub fast-forward. Correct the latest tag only after GitHub has the matching source commit.
- Retain unique historic APKs and release records. Remove only exact duplicate assets that have no users, and preserve any existing user-facing download alias.

## Issues and failed attempts
- None in this task.

## Validation and blockers
- Git ancestry check confirms GitHub `main` can fast-forward to local `HEAD` without force.
- No repository metadata, release descriptions, release tags, source refs, or assets have been changed in this task yet.

## Next action and resume context
Review the drafted public docs and Issue forms, validate the direct release/download path, then push the GitHub branch, normalize all 21 release changelogs, remove the two exact duplicate assets, and verify the resulting GitHub state.
