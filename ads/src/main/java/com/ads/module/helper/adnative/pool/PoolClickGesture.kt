package com.ads.module.helper.adnative.pool

/**
 * One click per tap: GMA reports a tap as clicked and opened, in either order, some adapters only
 * one of them. A callback pairs with an open tap of the other kind within [pairWindowMs]. Main thread.
 */
internal class PoolClickGesture(
    private val clock: PoolClock,
    private val pairWindowMs: Long = PAIR_WINDOW_MS,
) {
    private var openHalf: Half? = null
    private var openedAtMs = 0L

    /** True when this onAdClicked is a new tap. */
    fun clicked(): Boolean = accept(Half.CLICKED)

    /** True when this onAdOpened is a new tap. */
    fun opened(): Boolean = accept(Half.OPENED)

    private fun accept(half: Half): Boolean {
        val now = clock.nowMs()
        val open = openHalf
        if (open != null && open != half && now - openedAtMs < pairWindowMs) {
            openHalf = null
            return false
        }
        openHalf = half
        openedAtMs = now
        return true
    }

    private enum class Half { CLICKED, OPENED }

    companion object {
        /** Generous: an adapter may report opened only once the click URL has resolved. */
        const val PAIR_WINDOW_MS = 10_000L
    }
}
