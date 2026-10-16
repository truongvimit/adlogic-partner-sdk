package com.ads.module.helper.adnative.pool

import androidx.annotation.RestrictTo

/**
 * Switches for every DashDrama 1.0.6 behaviour the pool reproduces, read once per run from
 * `native_flow.pool.parity.*`. Defaults are DashDrama's, except [refreshMarksInFlight], which is
 * the SDK's own refresh model (deviation D-REFRESH), and [screenSlotsBindStarted], where ad
 * policy wins (deviation D-BIND). Each switch names the DashDrama source and the value an
 * optimisation would flip it to.
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
    /** DD requests the raw id list, duplicates included. false: dedup like the classic waterfall. */
    val keepDuplicateIds: Boolean = true,
    /** Any impression from a loader's request marks it Showing. false: only its current ad's. */
    val impressionMarksLoader: Boolean = true,
    /**
     * D-BIND: LFO, Privacy, Goal, the confirm popup and the resume screen bind only while RESUMED;
     * GMA counts an impression under a translucent cover. true: DashDrama's STARTED (U5/h.java:49).
     */
    val screenSlotsBindStarted: Boolean = false,
    /** Pager pages collect while STARTED so an offscreen neighbour can show the pool's held ad. */
    val pagerSlotsBindResumed: Boolean = false,
    /** DD keeps the pool until process death; the resume screen shows leftovers. false: release at completeFlow. */
    val keepAfterFlow: Boolean = true,
    /** DD keeps loaders across runs. true: a new run starts with an empty pool. */
    val resetOnNewRun: Boolean = false,
    /** DD loads without waiting for window focus. true: wait like the classic natives. */
    val respectFocusWindow: Boolean = false,
    /** DD loads native resume only when its screen opens. true: also from the background. */
    val resumeBackgroundPreload: Boolean = false,
    /**
     * D-REFRESH (SDK model, user decision 8): a refresh counts as a request in flight for the gate
     * and for the selector's shimmer, while the held ad keeps showing. false: exact DashDrama, a
     * refresh is invisible to the gate.
     */
    val refreshMarksInFlight: Boolean = true,
)
