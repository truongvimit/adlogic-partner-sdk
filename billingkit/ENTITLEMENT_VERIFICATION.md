# Entitlement verification — 2026-09-07

Ticket05a adds current-process evidence alongside existing cached/readiness APIs. Contract: [AUTHORITATIVE_ENTITLEMENT.md](AUTHORITATIVE_ENTITLEMENT.md).

```sh
./gradlew :billingkit:testDebugUnitTest :billingkit:assembleRelease --max-workers=2
```

PASS: **20 tests, zero failures/errors/skips**, plus release AAR. `AuthoritativeEntitlementTest` has7 pure JVM tests; `BillingEntitlementIntegrationTest` has13 Robolectric API34 tests running actual AppPurchase and a mocked BillingClient transport.

| Check | Evidence |
|---|---|
| Unknown startup | Cached true/false, empty catalogue, disconnected service and missing first/second result never verify free. |
| Full verification | Complete configured OK/non-null responses verify non-premium; owned non-consumable/subscription establishes premium. Null OK payload fails closed. |
| Failure retention | Failed/partial queries retain prior verified premium or free; failed initial sweep keeps UNKNOWN even while legacy awaitReady returns Ready. |
| Purchase and refund | Configured PURCHASED receipts pass through existing optional verifier before premium. Pending/rejected/consumable/unknown/simulated receipts do not verify. Fresh successful empty sweep after a grant verifies a refund/revocation. |
| Concurrency | Concurrent query callbacks produce coherent state.200 races between a purchase and an older empty sweep always finish premium. Newer/duplicate sweeps cannot publish stale state. |
| Late install/collection | Billing and AppPurchase return the same engine-owned StateFlow; late install/collection immediately observes completed verification with no future callback required. |
| Flow behavior | Same-state metadata updates do not duplicate enum emissions; synchronous snapshot/replay agree. A collector can synchronously await a worker reentering the state without lock inversion. |
| Compatibility | Cached isPremium, readiness and manual setPurchase behavior remain intact. setPurchase(true) is explicit trusted backend grant; false cannot verify free. Java static Billing.getEntitlement and engine snapshot/flow getters are present. Release bytecode remains Java8 (major52). |

`releaseRuntimeClasspath` resolves without RetentionKit or the optional ads project. No production dependency was added; test-only libraries supply Robolectric/Mockito. Existing Play Billing transitive dependencies are unchanged. Artifact: `build/outputs/aar/billingkit-release.aar`.

These tests verify engine state and transport callback boundaries. They do not claim a purchase/refund occurred against a live Play account. No ADB, push, merge, root checkout change or unrelated Billing rewrite was performed.
