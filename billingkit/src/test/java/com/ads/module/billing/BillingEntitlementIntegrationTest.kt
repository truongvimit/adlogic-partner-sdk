package com.ads.module.billing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fake only the BillingClient transport; exercise actual AppPurchase collection and grant paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingEntitlementIntegrationTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var engine: AppPurchase
    private lateinit var client: BillingClient
    private val queries = CopyOnWriteArrayList<PurchasesResponseListener>()
    @Before fun before() {
        app.getSharedPreferences("erain_billing", 0).edit().clear().commit()
        engine = AppPurchase::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        AppPurchase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, engine)
        (Billing::class.java.getDeclaredField("installed").apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
        Billing::class.java.getDeclaredField("application").apply { isAccessible = true }.set(null, null)
        client = mock(BillingClient::class.java)
        `when`(client.isReady).thenReturn(true)
        doAnswer { call -> queries.add(call.getArgument(1)); null }.`when`(client)
            .queryPurchasesAsync(any(QueryPurchasesParams::class.java), any(PurchasesResponseListener::class.java))
        field("billingClient", client); field("appContext", app); field("isAvailable", true)
        configure(listOf(PurchaseItem("lifetime", AppPurchase.TYPE_IAP.PURCHASE), PurchaseItem("monthly", AppPurchase.TYPE_IAP.SUBSCRIPTION)))
    }
    @After fun after() { engine.endConnection() }
    private fun field(name: String, value: Any?) = AppPurchase::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
    @Suppress("UNCHECKED_CAST") private fun configure(items: List<PurchaseItem>) {
        val inApp = AppPurchase::class.java.getDeclaredField("inAppIds").apply { isAccessible = true }.get(engine) as MutableList<String>
        val subs = AppPurchase::class.java.getDeclaredField("subsIds").apply { isAccessible = true }.get(engine) as MutableList<String>
        val registered = AppPurchase::class.java.getDeclaredField("purchaseItems").apply { isAccessible = true }.get(engine) as MutableList<PurchaseItem>
        inApp.clear(); subs.clear(); registered.clear(); registered.addAll(items)
        items.forEach { if (it.type == AppPurchase.TYPE_IAP.SUBSCRIPTION) subs.add(it.itemId) else inApp.add(it.itemId) }
    }
    private fun result(code: Int = BillingClient.BillingResponseCode.OK) = BillingResult.newBuilder().setResponseCode(code).build()
    private fun reply(index: Int, code: Int = BillingClient.BillingResponseCode.OK, purchases: List<Purchase>? = emptyList()) {
        if (purchases == null) {
            // Fault-inject a malformed transport callback through Java's erased nullability seam.
            PurchasesResponseListener::class.java.getMethod("onQueryPurchasesResponse", BillingResult::class.java, List::class.java)
                .invoke(queries[index], result(code), null)
        } else queries[index].onQueryPurchasesResponse(result(code), purchases)
    }
    private fun receipt(productId: String = "lifetime", state: Int = 0) = Purchase(JSONObject()
        .put("productId", productId).put("purchaseState", state).put("purchaseToken", "token-$productId")
        .put("orderId", "order-$productId").put("acknowledged", true).toString(), "signature")
    private fun buy(purchase: Purchase = receipt()) { engine.purchasesUpdatedListener.onPurchasesUpdated(result(), listOf(purchase)) }
    private fun state() = engine.entitlementSnapshot

    @Test fun cachedFalseAndSuccessfulFirstHalfCannotVerifyFreeUntilBothQueriesComplete() {
        PurchasePrefs.write(app, false, "cache")
        Billing.install(app)
        assertFalse(Billing.isPremium.value)
        assertEquals(BillingEntitlement.UNKNOWN, Billing.entitlement.value)
        engine.verifyPurchased(false)
        reply(0)
        assertEquals(BillingEntitlement.UNKNOWN, state())
        reply(1)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, state())
        assertEquals(state(), Billing.entitlement.value)
    }

    @Test fun cachedPremiumDoesNotBecomeVerifiedBeforePlayAndFullEmptySweepCanRevokeIt() {
        PurchasePrefs.write(app, true, "cache")
        Billing.install(app)
        assertTrue(Billing.isPremium.value)
        assertEquals(BillingEntitlement.UNKNOWN, Billing.entitlement.value)
        engine.verifyPurchased(false); reply(0); reply(1)
        assertFalse(Billing.isPremium.value)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, Billing.entitlement.value)
    }

    @Test fun failedSweepLegacyReadyCannotBeMistakenForVerifiedFree() = runBlocking {
        Billing.install(app)
        engine.verifyPurchased(false)
        reply(0); reply(1, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        assertTrue(engine.isVerifyFinish)
        assertEquals(ReadyResult.Ready, Billing.awaitReady()) // existing compatibility behavior
        assertEquals(BillingEntitlement.UNKNOWN, Billing.entitlement.value)
        assertFalse(Billing.isPremium.value)
    }

    @Test fun disconnectedAndEmptyCatalogueCannotVerifyFree() {
        `when`(client.isReady).thenReturn(false)
        engine.verifyPurchased(false)
        assertTrue(queries.isEmpty())
        assertEquals(BillingEntitlement.UNKNOWN, state())
        `when`(client.isReady).thenReturn(true)
        configure(emptyList())
        engine.verifyPurchased(false)
        assertTrue(queries.isEmpty())
        assertEquals(BillingEntitlement.UNKNOWN, state())
    }

    @Test fun nullOkPayloadIsUnknownEvenWhenOtherQuerySucceeded() {
        engine.verifyPurchased(false)
        reply(0, purchases = null); reply(1)
        assertEquals(BillingEntitlement.UNKNOWN, state())
    }

    @Test fun lateBillingInstallObservesCompletedVerificationWithoutWaitingForCallback() {
        engine.verifyPurchased(false); reply(0); reply(1)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, state())
        val engineFlow = engine.entitlement
        Billing.install(app)
        assertSame(engineFlow, Billing.entitlement)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, Billing.entitlement.value)
    }

    @Test fun configuredPurchasedReceiptVerifiesPremiumAndFreshEmptySweepVerifiesRefund() {
        engine.verifyPurchased(false)
        reply(0, purchases = listOf(receipt())); reply(1)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
        engine.verifyPurchased(false); reply(2); reply(3)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, state())
        buy()
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
    }

    @Test fun pendingRejectedConsumableUnknownAndDevPurchaseDoNotVerifyEntitlement() {
        configure(listOf(PurchaseItem("lifetime", AppPurchase.TYPE_IAP.PURCHASE), PurchaseItem("coin", AppPurchase.TYPE_IAP.CONSUMABLE)))
        buy(receipt(state = 4)) // Play receipt JSON maps 4 to PurchaseState.PENDING
        assertEquals(BillingEntitlement.UNKNOWN, state())
        engine.setPurchaseVerifier { _, _, _, callback -> callback.onResult(false, "rejected") }
        buy()
        assertEquals(BillingEntitlement.UNKNOWN, state())
        engine.setPurchaseVerifier(null)
        buy(receipt("coin")); buy(receipt("unregistered"))
        engine.grantDevPurchase("lifetime", "{}", null)
        assertEquals(BillingEntitlement.UNKNOWN, state())
    }

    @Test fun successfulOptionalVerifierUpdatesSameFlowAndInvalidatesEarlierSweep() {
        var verification: PurchaseVerifier.Callback? = null
        engine.setPurchaseVerifier { _, _, _, callback -> verification = callback }
        engine.verifyPurchased(false)
        buy()
        assertEquals(BillingEntitlement.UNKNOWN, state())
        verification!!.onResult(true, "verified")
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
        reply(0); reply(1)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
    }

    @Test fun partialAndFailedSweepPreservePreviouslyVerifiedPremiumAndFree() {
        buy(); assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
        engine.verifyPurchased(false); reply(0)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
        reply(1, BillingClient.BillingResponseCode.ERROR)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
        engine.verifyPurchased(false); reply(2); reply(3)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, state())
        engine.verifyPurchased(false); reply(4, BillingClient.BillingResponseCode.ERROR); reply(5)
        assertEquals(BillingEntitlement.VERIFIED_NON_PREMIUM, state())
    }

    @Test fun olderOverlappingSweepCannotEraseNewerVerifiedResult() {
        engine.verifyPurchased(false)
        engine.verifyPurchased(false)
        reply(2, purchases = listOf(receipt())); reply(3)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
        reply(0); reply(1)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
    }

    @Test fun manualTrueIsExplicitGrantButLegacyFalseDoesNotVerifyFree() {
        engine.setPurchase(false)
        assertEquals(BillingEntitlement.UNKNOWN, state())
        engine.setPurchase(true)
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
        engine.setPurchase(false)
        assertFalse(engine.isPurchased(app))
        assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
    }

    @Test fun concurrentQueryCallbacksPublishOneCoherentResult() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            engine.verifyPurchased(false)
            val start = CountDownLatch(1)
            val inapp = pool.submit { start.await(); reply(0, purchases = listOf(receipt())) }
            val subs = pool.submit { start.await(); reply(1) }
            start.countDown(); inapp.get(3, TimeUnit.SECONDS); subs.get(3, TimeUnit.SECONDS)
            assertEquals(BillingEntitlement.VERIFIED_PREMIUM, state())
            assertEquals(state(), Billing.entitlement.value)
        } finally { pool.shutdownNow() }
    }
}
