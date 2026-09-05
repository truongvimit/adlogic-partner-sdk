package com.ads.module.billing

import android.app.Activity
import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.ads.module.funtion.PurchaseCallback
import com.ads.module.helper.Entitlement
import com.ads.module.billingkit.R
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real billing engine and ads entitlement bridge; only the external Play client is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppPurchaseEntitlementTest {
    private lateinit var app: Application
    private lateinit var play: FakePlay
    private lateinit var purchase: AppPurchase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        play = FakePlay(app)
        BillingKit.setDevMode(false)
        purchase = AppPurchase.getInstance()
        purchase.initBilling(
            app,
            listOf(
                PurchaseItem("premium", AppPurchase.TYPE_IAP.PURCHASE),
                PurchaseItem("subscription", AppPurchase.TYPE_IAP.SUBSCRIPTION),
                PurchaseItem("coins", AppPurchase.TYPE_IAP.CONSUMABLE),
            ),
        )
        purchase.setPurchase(false)
    }

    @After
    fun tearDown() {
        BillingKit.setDevMode(false)
        purchase.setPurchaseVerifier(null)
        purchase.setUpdatePurchaseListener(null)
        purchase.setPurchase(false)
        purchase.endConnection()
        play.close()
    }

    @Test
    fun `manual grants and revocations update an existing ads observer`() = runBlocking {
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            purchase.setPurchase(true)
            yield()
            purchase.setPurchase(false)
            yield()

            assertEquals(listOf(false, true, false), observed)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `Play verification publishes grants and refunds after both queries complete`() = runBlocking {
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            purchase.verifyPurchased(false)
            play.replyNext(listOf(premiumPurchase()))
            yield()
            assertEquals(listOf(false), observed)
            play.replyNext(listOf(premiumPurchase()))
            yield()
            assertEquals(listOf(false, true), observed)

            purchase.verifyPurchased(false)
            play.replyNext(emptyList())
            yield()
            assertEquals(listOf(false, true), observed)
            play.replyNext(emptyList())
            yield()
            assertEquals(listOf(false, true, false), observed)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `approved purchase reaches ads and preserves the purchase callback`() = runBlocking {
        lateinit var verification: PurchaseVerifier.Callback
        purchase.setPurchaseVerifier { _, _, _, callback -> verification = callback }
        val callbackAnswers = mutableListOf<Boolean>()
        val callback = object : PurchaseCallback() {
            override fun onProductPurchased(orderId: String?, originalJson: String?) {
                callbackAnswers.add(Entitlement.isPremium(app))
            }
        }
        purchase.addPurchaseCallback(callback)
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            play.deliver(premiumPurchase())
            yield()
            assertEquals(listOf(false), observed)
            assertEquals(emptyList<Boolean>(), callbackAnswers)

            verification.onResult(true, null)
            yield()

            assertEquals(listOf(false, true), observed)
            assertEquals(listOf(true), callbackAnswers)
        } finally {
            collector.cancelAndJoin()
            purchase.removePurchaseCallback(callback)
        }
    }

    @Test
    fun `legacy refresh publishes only its terminal entitlement and preserves the callback`() = runBlocking {
        purchase.verifyPurchased(false)
        play.replyNext(listOf(premiumPurchase()))
        play.replyNext(listOf(premiumPurchase()))
        val terminalAnswers = mutableListOf<Boolean>()
        purchase.setUpdatePurchaseListener { terminalAnswers.add(Entitlement.isPremium(app)) }
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            purchase.updatePurchaseStatus()
            yield()
            assertEquals(listOf(true), observed)
            play.replyNext(emptyList())
            yield()
            assertEquals(listOf(true), observed)
            assertEquals(emptyList<Boolean>(), terminalAnswers)
            play.replyNext(emptyList())
            yield()

            assertEquals(listOf(true, false), observed)
            assertEquals(listOf(false), terminalAnswers)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `failed verification keeps the seeded premium entitlement`() = runBlocking {
        purchase.setPurchase(true)
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            purchase.verifyPurchased(false)
            play.replyNext(emptyList())
            play.replyNext(emptyList(), BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
            yield()

            assertEquals(listOf(true), observed)
            assertEquals(true, Entitlement.isPremium(app))
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `pending and consumable purchases do not grant premium`() = runBlocking {
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            play.deliver(premiumPurchase(pending = true))
            yield()
            play.deliver(premiumPurchase(productId = "coins"))
            yield()

            assertEquals(listOf(false), observed)
            assertEquals(false, Entitlement.isPremium(app))
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `confirming a dev purchase updates the existing ads observer`() = runBlocking {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
        val activity = controller.setup().get()
        BillingKit.setDevMode(true)
        val sheet = PurchaseDevBottomSheet(AppPurchase.TYPE_IAP.PURCHASE, null, activity, null)
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            sheet.show()
            checkNotNull(sheet.findViewById<View>(R.id.bk_txt_continue_purchase)).performClick()
            yield()

            assertEquals(listOf(false, true), observed)
        } finally {
            collector.cancelAndJoin()
            sheet.dismiss()
            controller.pause().stop().destroy()
        }
    }

    private fun premiumPurchase(productId: String = "premium", pending: Boolean = false) = Purchase(
        """{"productIds":["$productId"],"purchaseToken":"$productId-token","purchaseState":${if (pending) 4 else 0},"acknowledged":true}""",
        "signature",
    )

    private class FakePlay(app: Application) : AutoCloseable {
        val queries = mutableListOf<PurchasesResponseListener>()
        lateinit var purchasesUpdated: PurchasesUpdatedListener
            private set
        private val client = mock(BillingClient::class.java)
        private val builder = mock(BillingClient.Builder::class.java, RETURNS_SELF)
        private val factory: MockedStatic<BillingClient> = mockStatic(BillingClient::class.java)

        init {
            `when`(client.isReady).thenReturn(true)
            `when`(builder.build()).thenReturn(client)
            doAnswer {
                purchasesUpdated = it.getArgument(0)
                builder
            }.`when`(builder).setListener(any(PurchasesUpdatedListener::class.java))
            doAnswer {
                queries.add(it.getArgument(1))
                null
            }.`when`(client).queryPurchasesAsync(
                any(QueryPurchasesParams::class.java),
                any(PurchasesResponseListener::class.java),
            )
            factory.`when`<BillingClient.Builder> { BillingClient.newBuilder(app) }.thenReturn(builder)
        }

        override fun close() = factory.close()

        fun replyNext(purchases: List<Purchase>, responseCode: Int = BillingClient.BillingResponseCode.OK) {
            queries.removeAt(0).onQueryPurchasesResponse(
                BillingResult.newBuilder().setResponseCode(responseCode).build(),
                purchases,
            )
        }

        fun deliver(purchase: Purchase) {
            purchasesUpdated.onPurchasesUpdated(
                BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                listOf(purchase),
            )
        }
    }
}
