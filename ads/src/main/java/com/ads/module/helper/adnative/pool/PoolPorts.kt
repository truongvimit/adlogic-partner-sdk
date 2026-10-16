package com.ads.module.helper.adnative.pool

import androidx.annotation.RestrictTo

/**
 * Requests one ad for one id. Implementations call exactly one of [PoolLoadSink.onLoaded] or
 * [PoolLoadSink.onFailed], on the main thread, and later forward that ad's impressions and clicks
 * through the same sink. A silent implementation is covered by [PoolRequest.tierDeadlineMs].
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun interface PoolAdLoader<H : Any> {
    fun load(request: PoolRequest, id: String, sink: PoolLoadSink<H>)
}

/** One per id attempt. Events carry the ad so a replaced ad's impression still reaches its loader. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface PoolLoadSink<H : Any> {
    fun onLoaded(ad: H)
    fun onFailed()
    fun onImpression(ad: H)

    /** Once per tap, however many vendor callbacks (clicked, opened) report that tap. */
    fun onClicked(ad: H)
}

/**
 * Why a request may not go out. A config refusal is permanent for this run (premium, placement
 * off, no ad unit, UA gate): no loader is created and the caller does not become the owner. A
 * transient refusal (offline, no consent, requests held) saves the request and reports success,
 * as DashDrama's silent network check does.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface PoolRequestPolicy {
    fun configRefusal(request: PoolRequest): String?
    fun transientRefusal(request: PoolRequest): String?

    companion object {
        val ALLOW_ALL: PoolRequestPolicy = object : PoolRequestPolicy {
            override fun configRefusal(request: PoolRequest): String? = null
            override fun transientRefusal(request: PoolRequest): String? = null
        }
    }
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun interface PoolClock {
    fun nowMs(): Long
}

/** Cancels a scheduled block; calling it after the block ran is a no-op. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun interface PoolCancellable {
    fun cancel()
}

/** Main-thread delays: per-id deadlines, expiry sweeps and the stuck-loading log. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun interface PoolScheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit): PoolCancellable
}

/** `pool | …` lines; the onboarding glue routes them to OB_FLOW. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun interface PoolTraceSink {
    fun trace(line: String)

    companion object {
        val NONE: PoolTraceSink = PoolTraceSink { }
    }
}

/** Live limits the SDK shares with every other native, read at use time. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun interface PoolExpiry {
    /** `native.cache.max_age_ms`. An ad older than this is invisible and its loader returns to None. 0: never. */
    fun maxAgeMs(): Long
}
