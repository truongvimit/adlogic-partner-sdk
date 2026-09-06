# RetentionKit task graph

Spec: [spec.md](spec.md)
Base: 632df43
Branch: codex/retentionkit

## Frontier

Tickets 01–03 are resolved and merged. Ticket 04 implements feedback/review; ticket 05 completes the facade/adapters after its existing-SDK bridge increment. Ticket 06 waits for those final interfaces. Ticket 07 has merged evidence tooling and prepares the independent selective-consumer fixture; its full validation still depends on 06. Ticket 08 follows integrated validation.

## Decisions

- Use Translate flows as behavioral reference, shared presets plus replaceable content/UI.
- New opt-in modules; no changes to reference apps and no breaking existing SDK consumers.
- Test on attached Pixel5/API34. An installed API36 AVD is also available for emulator smoke once the complete example is ready; it is not yet tested and is not physical/OEM coverage.
- Complete individual commits and draft PR; no merge/release tag requested.

## Completion pointers

- 01: final core `6eab586`, integrated `5e620e2`; 29 tests and release build passed. See `retention-core/VERIFICATION.md`.
- 02: `8a23082`, integrated `b972b42`; 35 tests, release build and lint passed. See `retention-notifications/CONTRACT.md` and `README.md`.
- 03: `90cab23`, integrated `235803c`; 26 tests and release build passed. See `retention-widgets/README.md`.
- 05 partial bridge: `05ad5d0`, integrated `64ffcfb`; targeted physical Home/return regression passed. Final facade, host-UI coordination and entry integration remain in progress.
- 07 tooling: `9366030`, integrated `56a6c25`; 17 parser/fixture tests passed. These counts are tooling checks, not product behavior tests.
- Draft PR remains blocked by current GitHub CLI account permission; branch pushes through the existing SSH owner identity work. See `verification.md` for exact scope and evidence.
