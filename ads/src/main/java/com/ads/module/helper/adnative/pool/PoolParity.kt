package com.ads.module.helper.adnative.pool

import androidx.annotation.RestrictTo

/**
 * Switches for the pool's reference behaviours, read once per run from
 * `native_flow.pool.parity.*`. Defaults are the reference behaviour. Each switch says what it
 * does by default and the value an optimisation would flip it to.
 *
 * Expiry and the per-id wait are not here: they are the SDK's own `native.cache.max_age_ms` and
 * `native.load.tier_timeout_ms`, shared with every other native.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class PoolParity(
    /** A refresh replaces a Loaded or Showing loader's ad. false: Showing only. */
    val refreshOverwritesUnshown: Boolean = true,
    /** A POLITE load the gate skipped is never retried. true: replay it when the gate opens. */
    val retrySkippedPolite: Boolean = false,
    /** The gate is computed on Dispatchers.Default, so it can lag. false: synchronous. */
    val asyncGate: Boolean = true,
    /** The selection is shared on Dispatchers.Default with replay 1. false: synchronous. */
    val asyncSelector: Boolean = true,
    /** Selector rule 3 re-binds an ad already shown elsewhere. false: hide instead. */
    val borrowShowing: Boolean = true,
    /** A loader's ad is replaced without destroying the old one. true: destroy it. */
    val destroyReplacedAds: Boolean = false,
    /** Pager pages collect while STARTED so an offscreen neighbour can show the pool's held ad. */
    val pagerSlotsBindResumed: Boolean = false,
)
