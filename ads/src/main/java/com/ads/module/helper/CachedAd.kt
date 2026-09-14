package com.ads.module.helper

/**
 * A buffered ad and when it arrived.
 *
 * GMA discards an unshown ad after about an hour while the wrapper keeps reporting itself
 * ready right up until `show()` silently does nothing — expiry has to be tracked here.
 */
internal class CachedAd<T : Any>(
    val ad: T,
    private val loadedAtMs: Long = System.currentTimeMillis(),
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    val isFresh: Boolean get() = System.currentTimeMillis() - loadedAtMs < maxAgeMs

    companion object {
        /** GMA's ~1 hour staleness cutoff for unshown full-screen and native ads. */
        const val MAX_AGE_MS: Long = 60 * 60 * 1_000L
    }
}
