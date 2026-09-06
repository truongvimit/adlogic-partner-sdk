# RetentionKit task graph

Spec: [spec.md](spec.md)
Base: 632df43
Branch: codex/retentionkit

## Frontier

Tickets01–04 and additive05a Billing entitlement are resolved and merged. Ticket05 has merged compiling/tested core UI host, shared config, facade and Onboard adapter; its final Billing/ad-click adapters and regressions are ongoing. Ticket06 implements the functional example against these real interfaces. Ticket07 is completing consumer composition checks and partner docs; root owns final device/full-example evidence. Ticket08 follows integrated validation.

## Decisions

- Use Translate flows as behavioral reference, shared presets plus replaceable content/UI.
- New opt-in modules; no changes to reference apps and no breaking existing SDK consumers.
- Test on attached Pixel5/API34. API36 emulator core minified preflight routing passed; complete example checks remain pending. This is not physical/OEM coverage. Pixel is currently PIN-locked and the user unlock request is pending.
- Complete individual commits and draft PR; no merge/release tag requested.

## Completion pointers

- 01: final core `6eab586`, integrated `5e620e2`; 29 tests and release build passed. See `retention-core/VERIFICATION.md`.
- 02: `8a23082`, integrated `b972b42`; 35 tests, release build and lint passed. See `retention-notifications/CONTRACT.md` and `README.md`.
- 03: `90cab23`, integrated `235803c`; 26 tests and release build passed. See `retention-widgets/README.md`.
- 04: `0a5e20f`, integrated `bc34199`;32 tests and feedback/review release AARs passed.
- 05a: `67df3c2`, integrated `92e1213`;20 tests and Billing release AAR passed; authoritative entitlement is separate from cached legacy readiness.
- 05 partial bridge: `05ad5d0`, integrated `64ffcfb`; targeted physical Home/return regression passed. Additional core host/config and facade slice `65577b3` integrated `8b66e7a`;core39, Firebase4 and facade9 tests passed. Final adapters/full regressions and example entry integration remain in progress.
- 07 tooling: `9366030`, integrated `56a6c25`; Consumer preparation `04ffac3` adds six project/POM profiles;22 parser/fixture tests passed. These counts are tooling checks, not product behavior tests.
- Draft PR remains blocked by current GitHub CLI account permission; branch pushes through the existing SSH owner identity work. See `verification.md` for exact scope and evidence.
