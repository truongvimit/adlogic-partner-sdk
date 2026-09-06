package com.ads.module.billing

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Current-process evidence, independent of cached [Billing.isPremium] and legacy readiness. */
enum class BillingEntitlement {
    UNKNOWN,
    VERIFIED_NON_PREMIUM,
    VERIFIED_PREMIUM,
}

/** One atomic source for ordering, snapshots and subscribers; never calls host code under a lock. */
internal class AuthoritativeEntitlement {
    private data class Snapshot(
        val entitlement: BillingEntitlement = BillingEntitlement.UNKNOWN,
        val latestSweep: Long = 0,
        val completedSweep: Long = 0,
        val purchaseRevision: Long = 0,
    )
    data class Sweep(val sequence: Long, val purchaseRevision: Long)
    private val snapshot = MutableStateFlow(Snapshot())

    // A projection of the same atomic source, not another asynchronously seeded flow. A late
    // observer's value is current even if verification preceded Billing.install/collection.
    val state: StateFlow<BillingEntitlement> = object : StateFlow<BillingEntitlement> {
        override val value: BillingEntitlement get() = snapshot.value.entitlement
        override val replayCache: List<BillingEntitlement> get() = listOf(value)
        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<BillingEntitlement>): Nothing {
            snapshot.map { it.entitlement }.distinctUntilChanged().collect(collector)
            error("Entitlement StateFlow cannot complete")
        }
    }

    fun beginSweep(): Sweep {
        while (true) {
            val old = snapshot.value
            val next = old.copy(latestSweep = old.latestSweep + 1)
            if (snapshot.compareAndSet(old, next)) return Sweep(next.latestSweep, next.purchaseRevision)
        }
    }

    fun completeSweep(sweep: Sweep, trustworthy: Boolean, premium: Boolean) {
        if (!trustworthy) return
        while (true) {
            val old = snapshot.value
            // Newer sweeps and a purchase committed since this query began supersede this result.
            if (sweep.sequence != old.latestSweep || sweep.sequence <= old.completedSweep || sweep.purchaseRevision != old.purchaseRevision) return
            val next = old.copy(
                entitlement = if (premium) BillingEntitlement.VERIFIED_PREMIUM else BillingEntitlement.VERIFIED_NON_PREMIUM,
                completedSweep = sweep.sequence,
            )
            if (snapshot.compareAndSet(old, next)) return
        }
    }

    fun grantPremium() {
        while (true) {
            val old = snapshot.value
            if (snapshot.compareAndSet(old, old.copy(entitlement = BillingEntitlement.VERIFIED_PREMIUM, purchaseRevision = old.purchaseRevision + 1))) return
        }
    }

    fun resetForCatalog() {
        while (true) {
            val old = snapshot.value
            if (snapshot.compareAndSet(old, old.copy(entitlement = BillingEntitlement.UNKNOWN, latestSweep = old.latestSweep + 1, purchaseRevision = old.purchaseRevision + 1))) return
        }
    }
}
