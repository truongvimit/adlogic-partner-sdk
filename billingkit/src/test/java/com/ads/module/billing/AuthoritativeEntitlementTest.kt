package com.ads.module.billing

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthoritativeEntitlementTest {
    @Test fun failedSweepsCannotVerifyFreeAndPreserveEitherVerifiedState() {
        val subject = AuthoritativeEntitlement()
        subject.completeSweep(subject.beginSweep(), false, false)
        assertEquals(BillingEntitlement.UNKNOWN, subject.state.value)
        subject.completeSweep(subject.beginSweep(), true, false)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, subject.state.value)
        subject.completeSweep(subject.beginSweep(), false, true)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, subject.state.value)
        subject.grantPremium()
        subject.completeSweep(subject.beginSweep(), false, false)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, subject.state.value)
    }

    @Test fun staleSweepCannotErasePurchaseButFreshSuccessfulSweepCanVerifyRefund() {
        val subject = AuthoritativeEntitlement()
        val beforePurchase = subject.beginSweep()
        subject.grantPremium()
        subject.completeSweep(beforePurchase, true, false)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, subject.state.value)
        subject.completeSweep(subject.beginSweep(), true, false)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, subject.state.value)
    }

    @Test fun overlappingAndDuplicateResultsCannotMoveAuthorityBackwards() {
        val subject = AuthoritativeEntitlement()
        val older = subject.beginSweep()
        val newer = subject.beginSweep()
        subject.completeSweep(older, true, false)
        assertEquals(BillingEntitlement.UNKNOWN, subject.state.value)
        subject.completeSweep(newer, true, true)
        subject.completeSweep(older, true, false)
        subject.completeSweep(newer, true, false)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, subject.state.value)
    }

    @Test fun newCatalogInvalidatesPriorEvidenceAndAllEarlierCallbacks() {
        val subject = AuthoritativeEntitlement()
        subject.grantPremium()
        val old = subject.beginSweep()
        subject.resetForCatalog()
        subject.completeSweep(old, true, true)
        assertEquals(BillingEntitlement.UNKNOWN, subject.state.value)
        subject.completeSweep(subject.beginSweep(), true, false)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, subject.state.value)
    }

    @Test fun lateCollectionSeesCurrentValueAndMetadataChangesDoNotEmitDuplicates() = runBlocking {
        val subject = AuthoritativeEntitlement()
        subject.completeSweep(subject.beginSweep(), true, false)
        val seen = mutableListOf<BillingEntitlement>()
        val job = launch(Dispatchers.Unconfined) { subject.state.collect { seen.add(it) } }
        subject.completeSweep(subject.beginSweep(), true, false)
        subject.grantPremium()
        subject.grantPremium()
        assertEquals(listOf(BillingEntitlement.VERIFIED_NON_PREMIUM, BillingEntitlement.VERIFIED_PREMIUM), seen)
        assertEquals(subject.state.value, subject.state.replayCache.single())
        job.cancelAndJoin()
    }

    @Test fun concurrentPurchaseAndOldQueryCommitAlwaysFinishPremium() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val subject = AuthoritativeEntitlement()
                val sweep = subject.beginSweep()
                val gate = CountDownLatch(1)
                val purchase = pool.submit { gate.await(); subject.grantPremium() }
                val verify = pool.submit { gate.await(); subject.completeSweep(sweep, true, false) }
                gate.countDown(); purchase.get(2, TimeUnit.SECONDS); verify.get(2, TimeUnit.SECONDS)
                assertEquals(BillingEntitlement.VERIFIED_PREMIUM, subject.state.value)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun collectorCanReadAndReenterWithoutEngineLockInversion() = runBlocking {
        val subject = AuthoritativeEntitlement()
        val pool = Executors.newSingleThreadExecutor()
        val seen = mutableListOf<BillingEntitlement>()
        val job = launch(Dispatchers.Unconfined) {
            subject.state.collect { value ->
                seen.add(value)
                if (value == BillingEntitlement.VERIFIED_NON_PREMIUM) {
                    pool.submit { subject.grantPremium() }.get(2, TimeUnit.SECONDS)
                }
            }
        }
        try {
            subject.completeSweep(subject.beginSweep(), true, false)
            assertEquals(BillingEntitlement.VERIFIED_PREMIUM, subject.state.value)
            assertEquals(BillingEntitlement.VERIFIED_PREMIUM, seen.last())
        } finally { job.cancelAndJoin(); pool.shutdownNow() }
    }
}
