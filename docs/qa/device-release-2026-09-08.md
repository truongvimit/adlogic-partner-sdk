# Device release verification — 2026-09-08

Production revision: `c6d1048`. Device: Pixel 5, Android 14, USB ADB.
Google official test inventory only; failure cases use a deliberately invalid, nonmonetized ID.
Each fixture runs in a fresh instrumentation process with actual Android lifecycle and GMA.
No production callback is fabricated. Real fullscreen ads are closed through their visible Close UI.

Final result: **16/16 device cases passed**, **441/441 unit tests passed**, release AAR builds passed.

## Completed device cases

| Case | Verified behavior | Result |
|---|---|---|
| `retry` | Timeout then bounded recovery (45s observation) | PASS |
| `delay0` | Remote delay 0 ms | PASS |
| `delay500` | Remote delay 500 ms | PASS |
| `delay5000` | Remote delay 5000 ms | PASS |
| `disabled` | Resume disabled | PASS |
| `consent` | Consent denied | PASS |
| `release` | Release during request; late vendor fill rejected | PASS |
| `disable_inflight` | Disable during request; late vendor fill rejected | PASS |
| `home_return` | Click-suppression snapshot shared once then cleared | PASS |
| `retry125` | Exactly three requests over 125s | PASS |
| `quick_return` | Early foreground cancels dispatch; next background loads | PASS |
| `cache_roundtrip` | Same cache across two physical Home/return cycles | PASS |
| `offline_recovery` | Offline to online in one background stay | PASS |
| `buffer_group` | Real AutoBuffer cache, refill, shared gate and OB exclusion | PASS |
| `resume_presentation` | Real automatic resume ad held >95s, Home/return preserves one presentation, actual Close releases ownership | PASS |
| `inter_restore` | Home during show preparation retains the same real fill; explicit retry shows it and actual Close completes once | PASS |

## Concrete observations

- Remote delay is measured from process ON_STOP (AndroidX lifecycle debounce precedes it):
  configured 0/500/5000 ms dispatched after **36/528/5053 ms**, respectively.
- The long invalid-ID run sent exactly **3 requests** over 125 seconds. First retry was **5003 ms**
  after its failure, second retry **30010 ms** after its failure; no fourth request.
- A real app-open presentation stayed owned past 95 seconds, then survived physical Home/return
  as the same AdActivity. Actual vendor callbacks recorded **one show, one close**, released ownership,
  and the next policy-only return remained eligible.
- The interstitial preparation test paused the host after **92 ms**, before the 800 ms preparation
  finished. The same wrapper and vendor ad remained cached; return alone did not show, explicit retry
  produced one vendor show, and real Close produced one completion with an empty cache.
- A separate run experienced a genuine 30-second load timeout and recovered through the retry path.
- Release and disable cases both received a real vendor success after invalidation; the buffer
  remained empty at the end. These are observed late callbacks, not merely no-fill assertions.
- Quick return recorded zero requests before the second Home, then one successful request.
- Cache case recorded one request total across two additional physical foreground/background cycles.
- Offline recovery explicitly observed zero dispatches with both radios disabled, then one load
  after connectivity was restored without another process ON_STOP.
- Interstitial fixture used real fills for inter_all, inter_back and inter_after_ob3. It checked
  explicit content activation, initial remote gate, topUpNow obeying gate, foreground scheduling,
  cache reuse, a 30-second post-dismissal shared gate, ready back preservation, unscoped OB show,
  OB close not resetting the group gate, and replenishment only after that gate.

## Build and review

`./gradlew testDebugUnitTest :ads:assembleRelease :onboardkitorigin:assembleRelease --continue --console=plain`
passed. 441 unit tests across participating modules: ads 223, onboarding 209, trackkit 8, app 1;
zero failures/errors/skips. Both release AARs built. Test APK assembly also passed.

Standards and Spec reviews hardened the device fixtures before this round: actual request counts,
confirmed foreground transitions, observation before a hypothetical OB-based deadline, and refill
comparison against each placement's baseline. No production code change was needed for these checks.

Release AAR SHA-256:

- `ads-release.aar`: `1e36f614e9b339b15ca827efe88e633e44682d5a1372b1c49ab11cb74e28fa6d`
- `onboardkitorigin-release.aar`: `c94761ce869b860e21b61f25e98b5207800889359194ff9fc168e4a10339d6cf`

## Evidence and limits

Local raw evidence: `/tmp/sdk-device-final-round/` (per-case instrumentation output, filtered logcat,
screenshots of real test-ad Close UI, and build output). Temporary logs are QA evidence, not an SDK
dependency. This document retains the substantive conclusions if those temporary files expire.

One initial `inter_restore` attempt timed out while waiting for the operator to tap Close. Its
output is retained as `inter_restore-manual-timeout.txt`; the complete rerun passed in 56.977 seconds.
This was a manual-step timeout, not counted as a passing run or hidden as an SDK success.

Temporary device changes were restored: Wi-Fi and mobile data both enabled (`1`), plugged-in
keep-awake restored to its original value (`2`).

Four-hour expiry and exact stale-callback races remain covered by the deterministic public-seam
suite; the device round does not pretend to force every possible GMA callback ordering or wait four
hours. These tests do not establish a production show-rate, impression/session or revenue uplift.

No release tag has been created by the verification round.
