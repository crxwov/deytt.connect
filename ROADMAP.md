# DEYTT Connect finger-tracked tab swipe

## Objective

Make top-level horizontal navigation respond continuously to the finger, then settle a short intentional swipe into the adjacent screen with a restrained, reversible motion.

## Success Criteria

- Screen content begins moving during the drag, not only after release.
- A 24dp intentional drag switches tabs; canceled/reversed gestures settle back smoothly.
- Preserve vertical scrolling, edge back gestures, globe rotation, bottom navigation taps, and reduced-motion behavior.
- Build, install, and exercise the gesture on physical A001, including partial/reversed swipes.
- Review final diff, update this Roadmap, commit and push to main.

## Stages

- [x] 1. Inspect existing gesture: PageSwipeFrame intercepts horizontal input but navigates only on ACTION_UP at 72dp.
- [x] 2. Implement finger-following page progress and responsive commit/return behavior.
- [x] 3. Build, install, capture, and compare gesture behavior on physical A001; fix any regressions.
- [x] 4. Review final state and record verified outcome; commit and push.

## Current State

Completed. Repository: /home/hackov/Documents/deytt-connect, branch main; prior redesign is already pushed (2aa05b7). Finger-tracked swipe with a 24dp commit threshold is built and verified on physical A001. Keep VPN disconnected and Auto selected. Preserve unrelated untracked .agents/.

## Findings and Decisions

- Current top-level screens are separate Activities. The shared PageSwipeFrame observes motion in onInterceptTouchEvent but does not translate the page; on release it calls navigateTopLevel if distance reaches 72dp.
- Keep the existing Activity/screen architecture for this focused motion refinement. Track the content view during direct manipulation, then use a short settle or existing direction-aware Activity transition on release.
- Existing constraints: 28dp edge exclusion for system back, vertical-dominance check, map WebView and bottom controls excluded from page swipes.
- An initial responsive threshold formula made the 1080px-wide device require about 31dp, so a 25dp swipe correctly returned instead of committing. Changed the formula to `max(24dp, 6% of width)`; on A001 the commit threshold is 24dp.
- Previous task's AmneziaWG/Nginx repair and dark visual redesign are complete and pushed. No VPN or subscription logic changes are intended.

## Important Files

- app/src/main/java/space/deytt/connect/DeyttUi.kt
- app/src/main/res/anim/page_*.xml

## Validation and Limitations

- First implementation build succeeded. Physical A001 screenshot during a held swipe confirmed that the content follows the finger.
- `:app:assembleDebug` succeeded; APK installed and launched on physical A001.
- On A001 (1080×2392): 40px swipe returned and remained on MainActivity; 70px swipe (~25dp) switched to RoutesActivity; reverse swipe returned to MainActivity centered. A held 110px swipe showed the screen tracking the finger continuously (`/tmp/deytt-swipe-live-final-2.png`).
- Previously verified on A001: vertical scrolling stayed on RoutesActivity; horizontal swipe over the map rotated the globe without changing tabs. Reduced-motion return and Activity focus reset are implemented; return-to-Main centering was confirmed.
- Final screenshot: `/tmp/deytt-swipe-final-home.png`. VPN stayed disconnected; Auto remained selected.
- Preserve installed data and do not start the tunnel.

## Issues and Failed Attempts

- During one verification attempt, gestures were sent before the app finished its launch splash and were ignored; repeated after the first frame, then verified successfully.

## Next Action and Resume Context

Verified final state; changes are ready to commit and push to main. No server pull or service restart applies to this Android-only refinement; the associated backend fix was deployed in the earlier task.
