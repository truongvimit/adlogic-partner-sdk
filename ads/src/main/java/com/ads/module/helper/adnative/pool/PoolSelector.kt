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

/** What the two pages of a carousel render, decided together so they never show one ad twice. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class CarouselSelection<H : Any>(val first: PoolSelection<H>, val second: PoolSelection<H>)

/**
 * Both pages of a carousel for [snapshot]. Each page shows its own loader's ad ([firstKey],
 * [secondKey]), else the ad it already shows while a loader still holds it, else borrows: the
 * first page the newest unshown ad (or, with [PoolParity.borrowShowing], the newest shown one)
 * that the second page does not show, the second page only the newest unshown ad, never the first
 * page's. A page with nothing shows a shimmer while anything loads. Pure, like [selectFromPool].
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun <H : Any> selectCarouselPages(
    snapshot: PoolSnapshot<H>,
    firstKey: String,
    secondKey: String,
    firstShown: H?,
    secondShown: H?,
    parity: PoolParity,
    nowMs: Long,
    maxAgeMs: Long,
): CarouselSelection<H> {
    val visible = snapshot.loaders
        .filter { it.holdsAd() && !isExpiredAt(it.fillAtMs, nowMs, maxAgeMs) }
        .sortedBy { it.index }
    fun pick(key: String, shown: H?, excluded: H?, borrowShowing: Boolean): PoolSelection<H> {
        val candidates = visible.filter { it.ad !== excluded }
        candidates.firstOrNull { it.key == key }?.let { return it.selected() }
        shown?.let { ad -> candidates.firstOrNull { it.ad === ad }?.let { return it.selected() } }
        candidates.newestBy { (it.state as? PoolState.Loaded)?.ts }?.let { return it.selected() }
        if (borrowShowing) candidates.newestBy { (it.state as? PoolState.Showing)?.ts }?.let { return it.selected() }
        return PoolSelection(null, null, anyInFlight(snapshot))
    }
    val first = pick(firstKey, firstShown, excluded = secondShown, borrowShowing = parity.borrowShowing)
    return CarouselSelection(first, pick(secondKey, secondShown, excluded = first.ad, borrowShowing = false))
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
