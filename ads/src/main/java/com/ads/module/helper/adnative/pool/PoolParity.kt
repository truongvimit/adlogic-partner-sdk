package com.ads.module.helper.adnative.pool

import androidx.annotation.RestrictTo

/**
 * Switches for the DashDrama 1.0.6 behaviours the pool reproduces, read once per run from
 * `native_flow.pool.parity.*`. Defaults are DashDrama's. Each switch names the DashDrama source
 * and the value an optimisation would flip it to.
 *
 * Expiry and the per-id wait are not here: they are the SDK's own `native.cache.max_age_ms` and
 * `native.load.tier_timeout_ms`, shared with every other native.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class PoolParity(
    /** DD P2/j.java:494 refreshes a Loaded or Showing loader. false: Showing only. */
    val refreshOverwritesUnshown: Boolean = true,
    /** DD never retries a POLITE load the gate skipped. true: replay it when the gate opens. */
    val retrySkippedPolite: Boolean = false,
    /** DD P2/j.java:82-89 computes the gate on Dispatchers.Default, so it can lag. false: synchronous. */
    val asyncGate: Boolean = true,
    /** DD P2/j.java:90 shares the selection on Dispatchers.Default with replay 1. false: synchronous. */
    val asyncSelector: Boolean = true,
    /** Selector rule 3 (DD P2/g) re-binds an ad already shown elsewhere. false: hide instead. */
    val borrowShowing: Boolean = true,
    /** DD replaces a loader's ad without destroying the old one. true: destroy it. */
    val destroyReplacedAds: Boolean = false,
    /** Pager pages collect while STARTED so an offscreen neighbour can show the pool's held ad. */
    val pagerSlotsBindResumed: Boolean = false,
)
