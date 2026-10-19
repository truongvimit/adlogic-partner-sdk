package com.ads.module.helper.adnative.pool

import androidx.annotation.RestrictTo

/**
 * What every slot shows for [snapshot]: the owner's own ad, else the newest unshown ad, else
 * (with [PoolParity.borrowShowing]) the newest shown one, else a shimmer while anything loads.
 * Pure, so the async derivation can run it off the main thread.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun <H : Any> selectFromPool(
    snapshot: PoolSnapshot<H>,
    parity: PoolParity,
    nowMs: Long,
    maxAgeMs: Long,
): PoolSelection<H> {
    val visible = snapshot.loaders
        .filter { it.holdsAd() && !isExpiredAt(it.fillAtMs, nowMs, maxAgeMs) }
        .sortedBy { it.index }
    snapshot.owner?.let { owner ->
        visible.firstOrNull { it.key == owner }?.let { return it.selected() }
    }
    visible.newestBy { (it.state as? PoolState.Loaded)?.ts }?.let { return it.selected() }
    if (parity.borrowShowing) {
        visible.newestBy { (it.state as? PoolState.Showing)?.ts }?.let { return it.selected() }
    }
    return PoolSelection(null, null, anyInFlight(snapshot))
}

/** The gate's input; a refresh counts while the held ad keeps showing. */
internal fun anyInFlight(snapshot: PoolSnapshot<*>): Boolean =
    snapshot.loaders.any { it.inFlight || it.refreshing }

internal fun isExpiredAt(fillAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
    maxAgeMs > 0 && nowMs - fillAtMs >= maxAgeMs

private fun PoolLoaderView<*>.holdsAd(): Boolean =
    ad != null && (state is PoolState.Loaded || state is PoolState.Showing)

private fun <H : Any> PoolLoaderView<H>.selected(): PoolSelection<H> = PoolSelection(key, ad, true)

/** Strictly greater wins, so on a tie the lower insertion index (earlier in the list) stays. */
private fun <H : Any> List<PoolLoaderView<H>>.newestBy(ts: (PoolLoaderView<H>) -> Long?): PoolLoaderView<H>? {
    var best: PoolLoaderView<H>? = null
    var bestTs = Long.MIN_VALUE
    for (view in this) {
        val t = ts(view) ?: continue
        if (best == null || t > bestTs) {
            best = view
            bestTs = t
        }
    }
    return best
}
