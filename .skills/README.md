# Agent skills

Vendored agent skills (open-standard `SKILL.md`) that ground AI assistants in the ad
stack this repo actually ships. Mirrored into `.claude/skills/` for Claude Code;
`.skills/` is the location Gemini in Android Studio reads.

## What is here

| Skill | Source | Scope |
| --- | --- | --- |
| `gma-android-integrate` | https://developers.google.com/admob/android/ai-tools (page dated 2026-09-18) | Legacy `com.google.android.gms:play-services-ads` — Gradle, manifest metadata, init threading, banner ads |

## What is deliberately NOT here

`github.com/google/skills` ships `skills/ads/google-mobile-ads-*` (get-started, banner,
interstitial, rewarded, validate). Every one of them targets the **Next-Gen** SDK
(`com.google.android.libraries.ads.mobile.sdk`) — different package, different callbacks,
callbacks on a background thread. This repo is on legacy `com.google.android.gms.ads`,
so those skills would hand an agent the wrong API surface. Same for
`google-mobile-ads-android-migrate-to-next-gen`, which exists to move a project *off*
legacy.

`github.com/android/skills` (Google's official Android skill repo, 24 skills) contains
no ads skill at all.
