package com.ads.module.helper.adnative.pool

import androidx.annotation.LayoutRes
import androidx.annotation.RestrictTo

/**
 * The display state of the ad a pool loader holds. Whether a request is running is a separate
 * fact ([PoolLoaderView.inFlight], [PoolLoaderView.refreshing]), the way `NativeAdManager` keeps
 * `ready` apart from `loading`: a refresh runs while the held ad stays [Loaded] or [Showing].
 *
 * [Loaded] and [Showing] always hold an ad; [None] and [LoadError] never do.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
sealed interface PoolState {
    /** Never filled, released, or its ad expired. */
    data object None : PoolState

    /** Holds an ad no slot has reported an impression for since [ts]. */
    data class Loaded(val ts: Long) : PoolState

    /** Holds an ad whose impression was reported at [ts]. */
    data class Showing(val ts: Long) : PoolState

    /** The last normal chain ended with no fill and nothing held. */
    data object LoadError : PoolState
}

/**
 * One placement's request, saved on its loader so a later refresh repeats it.
 *
 * @param key the pool key, `AdPlacement.key` on the onboarding side (`language1`, `step_ob1`…)
 * @param ids the waterfall in order. Blank ids are skipped; duplicates are kept (DashDrama drops
 *   one id per failure and never dedups).
 * @param layoutRes the template the loading call inflates against; slots bind into their own
 * @param adConfigKey the `ad_config` key the ids came from, for the UA gate and logging
 * @param tierDeadlineMs per-id wait; a silent id counts as a failure after it. 0 waits forever.
 * @param trackingKey the placement paid events name, when it is not [key]: a second loader that
 *   serves the same placement reports as that placement
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class PoolRequest(
    val key: String,
    val ids: List<String>,
    @LayoutRes val layoutRes: Int,
    val adConfigKey: String? = null,
    val tierDeadlineMs: Long = 0L,
    val trackingKey: String = key,
)

/** POLITE loads are skipped while any loader has a request in flight; FORCED ones are not. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
enum class LoadMode { POLITE, FORCED }

/**
 * What [NativePoolEngine.load] did. Mirrors DashDrama's return values: only a config refusal
 * reports failure, so only it keeps the caller from becoming the owner.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
sealed interface LoadResult {
    /** A normal chain started. */
    data object Requested : LoadResult

    /** The loader already holds an ad or has a request in flight; nothing was requested. */
    data class NoOpState(val state: PoolState, val inFlight: Boolean) : LoadResult

    /** POLITE while the gate was closed. The request was not saved and is not queued. */
    data object SkippedByGate : LoadResult

    /** Offline, no consent, requests held… The request was saved, so a later refresh can run. */
    data class SkippedTransient(val reason: String) : LoadResult

    /** Premium, placement off, no ad unit… No loader was created and nothing was saved. */
    data class SkippedByConfig(val reason: String) : LoadResult
}

/** DashDrama returns true for every outcome but a config refusal; the caller sets the owner on it. */
@get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
val LoadResult.reportsSuccess: Boolean get() = this !is LoadResult.SkippedByConfig

/**
 * What every slot shows. [lenderKey] is the loader whose [ad] is shown; `show` without an ad is a
 * shimmer, neither is a hidden slot. Equality includes the ad's identity (ads do not override
 * equals), so a new fill of the same loader is a new value.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class PoolSelection<H : Any>(
    val lenderKey: String?,
    val ad: H?,
    val show: Boolean,
) {
    companion object {
        fun <H : Any> hidden(): PoolSelection<H> = PoolSelection(null, null, false)
        fun <H : Any> shimmer(): PoolSelection<H> = PoolSelection(null, null, true)
    }
}

/** An immutable view of one loader. [index] is its insertion order, the selector's tie-break. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class PoolLoaderView<H : Any>(
    val key: String,
    val state: PoolState,
    val ad: H?,
    /** A normal chain is running (DashDrama's LOADING). */
    val inFlight: Boolean,
    /** A refresh chain is running; the held ad and [state] are unchanged meanwhile. */
    val refreshing: Boolean,
    /** When the held ad filled, on the engine clock; 0 when nothing is held. */
    val fillAtMs: Long,
    val index: Int,
    val adopted: Boolean = false,
)

/**
 * The whole pool at one moment, ordered by insertion. [version] grows on every mutation so two
 * snapshots are never equal, which keeps a StateFlow from conflating a real change away.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class PoolSnapshot<H : Any>(
    val loaders: List<PoolLoaderView<H>>,
    val owner: String?,
    val version: Long,
) {
    fun loader(key: String): PoolLoaderView<H>? = loaders.firstOrNull { it.key == key }

    companion object {
        fun <H : Any> empty(): PoolSnapshot<H> = PoolSnapshot(emptyList(), null, 0L)
    }
}

/** Engine events. Impression and click carry the ad that produced them, never filtered by chain. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
sealed interface PoolEvent<H : Any> {
    val key: String

    data class Filled<H : Any>(override val key: String, val ad: H, val refresh: Boolean) : PoolEvent<H>
    data class Exhausted<H : Any>(override val key: String, val refresh: Boolean) : PoolEvent<H>
    data class Impression<H : Any>(override val key: String, val ad: H) : PoolEvent<H>
    data class Clicked<H : Any>(override val key: String, val ad: H) : PoolEvent<H>
    data class Skipped<H : Any>(override val key: String, val result: LoadResult) : PoolEvent<H>
}
