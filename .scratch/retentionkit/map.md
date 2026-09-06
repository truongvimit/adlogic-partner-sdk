# RetentionKit task graph

Spec: [spec.md](spec.md)
Base: 632df43
Branch: codex/retentionkit

## Frontier

Tickets01–05 and additive05a Billing entitlement are resolved and merged. Ticket06 is integrated with four functional example tools and is completing Android UI acceptance. Ticket07 has passed all twelve project/Maven minified consumer combinations at723a57c; final device checks found a widget Activity-return gap and a proof-consumer route-readiness race, now assigned to scoped implementers. Root owns final device/full-example evidence. Ticket08 follows integrated validation.

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
- 05: final source `ac8c7d5`, integrated `d8a4af4`;385 tests (ads162, Onboard168, core39, Firebase4, facade12) passed. Independent umbrella project/POM R8 evidence at723a57c completed the final acceptance condition, recorded by4be56c9.
- 06: implementation0178bc7 plus scoped outbox/fixture/channel/route corrections through89b9386, integratedce85e7f;14 app unit tests passed. Device cases are still being verified; compiled tests are not a device pass.
- 07 tooling: `9366030`, integrated `56a6c25`; six project/POM profiles and25 parser/fixture tests. Twelve release R8/graph/composition checks and six publication inspections passed at723a57c. Later source corrections require targeted replacement evidence. Tooling fixture counts are separate from product tests.
- Draft PR remains blocked by current GitHub CLI account permission; branch pushes through the existing SSH owner identity work. See `verification.md` for exact scope and evidence.
