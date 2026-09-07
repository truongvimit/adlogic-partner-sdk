package io.retentionkit.review

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionReviewModuleTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: RetentionStore
    private lateinit var clock: FakeClock
    private lateinit var transport: FakeTransport
    private lateinit var module: RetentionReviewModule
    private lateinit var runtime: RetentionRuntime
    private var host: ActivityController<Activity>? = null
    private val events = mutableListOf<RetentionEvent>()
    private var eventHook: (RetentionEvent) -> Unit = {}
    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        store = SharedPreferencesRetentionStore(app, "review_${UUID.randomUUID()}")
        clock = FakeClock()
        transport = FakeTransport()
    }
    @After fun after() { host?.pause()?.stop()?.destroy(); RetentionRuntime.uninstallForTests() }
    private fun install(options: ReviewOptions = ReviewOptions(), restore: Boolean = false, uiHost: RetentionUiHost = RetentionUiHost.NONE) {
        if (restore) { host?.pause()?.stop()?.destroy(); host = null; RetentionRuntime.uninstallForTests() }
        module = RetentionReviewModule(options, ReviewTransportFactory { transport })
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module), store = store, clock = clock, uiHost = uiHost,
            initialUserState = RetentionUserState(setupCompleted = true, entitlement = RetentionEntitlement.NON_SUBSCRIBER),
            eventSink = RetentionEventSink { events.add(it); eventHook(it) }))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
        host = Robolectric.buildActivity(Activity::class.java).setup()
        runtime.signal(RetentionSignal.ProcessForeground)
        idle()
    }
    private fun success(id: String) { runtime.signal(RetentionSignal.BusinessSuccess("notes", id)); idle() }
    private fun five(prefix: String = "success") { (1..5).forEach { success("$prefix-$it") } }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun advance(millis: Long) { clock.advance(millis); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
    private fun ready(index: Int = transport.requests.lastIndex) { transport.requests[index](ReviewInfoResult.Ready(FakeToken)); idle() }
    private fun finish(index: Int = transport.launches.lastIndex) { transport.launches[index](ReviewFlowResult.FinishedOutcomeUnknown); idle() }

    @Test fun automaticReviewCannotUseExplicitEntryHostPermission() {
        install(uiHost = object : RetentionUiHost {
            override fun canPresent(activity: Activity) = false
            override fun canPresentEntry(activity: Activity) = true
        })
        five()
        assertTrue(transport.requests.isEmpty())
        assertEquals(0L, module.snapshot()!!.attempts)
        val entry = runtime.ui.acquire("entry", 5000, RetentionUiPurpose.ENTRY) as RetentionUiLeaseResult.Acquired
        assertSame(host!!.get(), entry.lease.activity())
        entry.lease.close()
    }

    @Test fun defaultsDedupeAndSingleFlightReserveOnlyAtLaunchWithUnknownOutcome() {
        install()
        repeat(4) { success("same") }
        assertEquals(1L, module.snapshot()!!.successesSinceAttempt)
        assertTrue(transport.requests.isEmpty())
        (2..5).forEach { success("success-$it") }
        assertEquals(1, transport.requests.size)
        success("sixth")
        assertEquals(0L, module.snapshot()!!.attempts)
        assertEquals("requesting", module.snapshot()!!.inFlightPhase)
        ready(); ready()
        assertEquals(1, transport.launches.size)
        assertEquals(1L, module.snapshot()!!.attempts)
        assertEquals(1L, module.snapshot()!!.successesSinceAttempt)
        finish(); finish()
        assertNull(module.snapshot()!!.inFlightPhase)
        assertEquals(1, events.count { it.name == "retention_review_flow_unknown" })
        assertTrue(events.all { it.name.matches(Regex("[a-z0-9_]{1,40}")) })
        assertTrue(store.snapshot("review.state.v1").entries().keys.none { it.contains("rated") || it.contains("stars") })
        assertTrue(transport.launchActivityWasCurrent)
        assertTrue(transport.blockedAtLaunch) // handoff signal revoked lease only after Activity was acquired
    }

    @Test fun requestFailureAndTimeoutKeepAttemptBudgetAndLateCallbacksCannotLaunch() {
        install(ReviewOptions(requestTimeoutMillis = 1_000, retryBackoffMillis = 1_000))
        five()
        transport.requests[0](ReviewInfoResult.Failed("offline")); idle()
        assertEquals(0L, module.snapshot()!!.attempts)
        assertEquals(5L, module.snapshot()!!.successesSinceAttempt)
        module.requestIfEligible(); idle(); assertEquals(1, transport.requests.size)
        advance(1_000); module.requestIfEligible(); idle()
        assertEquals(2, transport.requests.size)
        advance(1_000)
        assertNull(module.snapshot()!!.inFlightPhase)
        ready(0); ready(1)
        assertTrue(transport.launches.isEmpty())
        assertEquals(0L, module.snapshot()!!.attempts)
        advance(1_000); module.requestIfEligible(); idle(); ready()
        assertEquals(1L, module.snapshot()!!.attempts)
    }

    @Test fun destroyedActivityConfigAndHostUiCancelBeforeLaunchWithoutSpendingCap() {
        install(ReviewOptions(retryBackoffMillis = 1_000))
        five()
        host!!.pause().stop().destroy(); host = null
        ready()
        assertTrue(transport.launches.isEmpty())
        assertEquals(0L, module.snapshot()!!.attempts)
        host = Robolectric.buildActivity(Activity::class.java).setup()
        runtime.signal(RetentionSignal.ProcessForeground); advance(1_000)
        module.requestIfEligible(); idle()
        runtime.signal(RetentionSignal.HostUiChanged("paywall", true))
        ready()
        assertTrue(transport.launches.isEmpty())
        runtime.signal(RetentionSignal.HostUiChanged("paywall", false)); advance(1_000)
        module.requestIfEligible(); idle()
        assertTrue(runtime.updateConfig(mapOf("review.enabled" to "false")) is RetentionConfigResult.Applied)
        ready()
        assertTrue(transport.launches.isEmpty())
        assertNull(module.snapshot()!!.inFlightPhase)
    }

    @Test fun durableCooldownCapAndEventDedupeSurviveRestartAndClockReversal() {
        install()
        five("a"); ready(); finish()
        install(restore = true)
        five("a")
        assertEquals(0L, module.snapshot()!!.successesSinceAttempt)
        five("b")
        assertEquals(1, transport.requests.size)
        clock.now -= 86_400_000; module.requestIfEligible(); idle()
        assertEquals(1, transport.requests.size)
        advance(11 * 86_400_000L); module.requestIfEligible(); idle(); ready(); finish()
        assertEquals(2L, module.snapshot()!!.attempts)
        five("c"); advance(10 * 86_400_000L); module.requestIfEligible(); idle(); ready(); finish()
        assertEquals(3L, module.snapshot()!!.attempts)
        install(restore = true)
        five("d"); advance(10 * 86_400_000L); module.requestIfEligible(); idle()
        assertEquals(3, transport.requests.size)
        assertEquals(3L, module.snapshot()!!.attempts)
    }

    @Test fun launchFailureAndMissingCallbackStillSpendOnlyOneAttempt() {
        install(ReviewOptions(flowTimeoutMillis = 1_000))
        five(); ready()
        advance(1_000)
        assertNull(module.snapshot()!!.inFlightPhase)
        assertEquals(1L, module.snapshot()!!.attempts)
        finish()
        assertEquals(0, events.count { it.name == "retention_review_flow_unknown" })
        five("next"); advance(10 * 86_400_000L); module.requestIfEligible(); idle(); ready()
        transport.launches.last()(ReviewFlowResult.Failed("play_launch_failed")); idle()
        assertEquals(2L, module.snapshot()!!.attempts)
        assertNull(module.snapshot()!!.inFlightPhase)
    }

    @Test fun manualStoreIndependentOfAutomaticPolicyAndClosesOnlyOwnScopeOnReturn() {
        install(ReviewOptions(enabled = false))
        module.openStore(); idle()
        assertEquals(1, transport.stores)
        assertTrue(transport.blockedAtStore)
        assertEquals(0L, module.snapshot()!!.attempts)
        assertTrue(transport.requests.isEmpty())
        runtime.signal(RetentionSignal.ExternalTransitionStarted("host.settings", "host", 120_000))
        host!!.pause().resume(); idle()
        assertTrue(runtime.ui.eligibility() is RetentionEligibility.Blocked)
        runtime.signal(RetentionSignal.ExternalTransitionFinished("host.settings"))
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
    }

    @Test fun failedStoreHandoffReleasesScopeAndLease() {
        install()
        transport.storeSucceeds = false
        module.openStore(); idle()
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
        assertEquals(0L, module.snapshot()!!.attempts)
        assertFalse(events.any { it.name == "retention_review_store_handoff" })
    }

    @Test fun persistedFlightRecoveryPreservesSpentCapAndBacksOffUnknownRequest() {
        store.transaction("review.state.v1") { it.put("flight_token", "old"); it.put("flight_phase", "requesting"); it.put("successes", 5L) }
        install(ReviewOptions(retryBackoffMillis = 1_000))
        module.requestIfEligible(); idle()
        assertTrue(transport.requests.isEmpty())
        assertNull(module.snapshot()!!.inFlightPhase)
        advance(1_000); module.requestIfEligible(); idle(); ready()
        assertEquals(1L, module.snapshot()!!.attempts)
        // A process death does not execute graceful shutdown; reproduce its durable launching record.
        host!!.pause().stop().destroy(); host = null; RetentionRuntime.uninstallForTests()
        store.transaction("review.state.v1") { it.put("flight_token", "interrupted"); it.put("flight_phase", "launching") }
        install()
        assertEquals(1L, module.snapshot()!!.attempts)
        assertNull(module.snapshot()!!.inFlightPhase)
        assertEquals(2, events.count { it.name == "retention_review_recovered" })
    }

    @Test fun concurrentSuccessAndOutOfOrderCallbacksProduceOneLaunch() {
        install()
        val pool = Executors.newFixedThreadPool(4)
        try {
            (1..20).map { pool.submit { runtime.signal(RetentionSignal.BusinessSuccess("notes", "parallel-${it % 5}")) } }.forEach { it.get() }
            idle()
            assertEquals(1, transport.requests.size)
            assertEquals(5L, module.snapshot()!!.successesSinceAttempt)
            (1..10).map { pool.submit { transport.requests.single()(ReviewInfoResult.Ready(FakeToken)) } }.forEach { it.get() }
            idle()
            assertEquals(1, transport.launches.size)
            assertEquals(1L, module.snapshot()!!.attempts)
        } finally { pool.shutdownNow() }
    }

    @Test fun invalidConfigIsRejectedAndNoOpeningBasedPromptExists() {
        install()
        assertTrue(runtime.updateConfig(mapOf("review.success_threshold" to "0")) is RetentionConfigResult.Rejected)
        assertTrue(runtime.updateConfig(mapOf("review.enabled" to "yes")) is RetentionConfigResult.Rejected)
        runtime.signal(RetentionSignal.ProcessBackground); runtime.signal(RetentionSignal.ProcessForeground); idle()
        assertTrue(transport.requests.isEmpty())
        assertEquals(0L, module.snapshot()!!.successesSinceAttempt)
    }

    @Test fun requestExceptionDoesNotHoldUiOrSpendAttempt() {
        install(); transport.throwRequest = true
        five()
        assertEquals(0L, module.snapshot()!!.attempts)
        assertNull(module.snapshot()!!.inFlightPhase)
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
    }

    @Test fun persistenceFailureWhileClaimingCannotHoldUiLease() {
        val actual = store
        var fail = false
        store = object : RetentionStore {
            override fun snapshot(namespace: String) = actual.snapshot(namespace)
            override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T {
                if (fail && namespace == "review.state.v1") throw RetentionStorageException("disk unavailable")
                return actual.transaction(namespace, block)
            }
        }
        install()
        actual.transaction("review.state.v1") { it.put("successes", 5L) }
        fail = true
        module.requestIfEligible(); idle()
        assertTrue(transport.requests.isEmpty())
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "review.state_machine" })
    }

    @Test fun boundedDedupeLedgerFailsClosedInsteadOfEvictingAndReplayingOldSuccesses() {
        store.transaction("review.state.v1") { state -> repeat(4096) { state.put("seen:$it", true) } }
        install(); five()
        assertEquals(0L, module.snapshot()!!.successesSinceAttempt)
        assertTrue(transport.requests.isEmpty())
        assertTrue(events.any { it.attributes["reason"] == "dedupe_capacity" })
    }

    @Test fun productionStoreTransportUsesMarketAndFallsBackOnlyWhenUnavailable() {
        val activity = Robolectric.buildActivity(StoreActivity::class.java).setup()
        try {
            val play = PlayReviewTransport(app)
            assertTrue(play.openStore(activity.get()))
            assertEquals("market", activity.get().submitted.single().data!!.scheme)
            activity.get().submitted.clear(); activity.get().noMarket = true
            assertTrue(play.openStore(activity.get()))
            assertEquals("https://play.google.com/store/apps/details?id=${app.packageName}", activity.get().submitted.single().data.toString())
            activity.get().noBrowser = true
            assertFalse(play.openStore(activity.get()))
        } finally { activity.pause().stop().destroy() }
    }

    class StoreActivity : Activity() {
        var noMarket = false; var noBrowser = false
        val submitted = mutableListOf<android.content.Intent>()
        override fun startActivity(intent: android.content.Intent) {
            if ((intent.data?.scheme == "market" && noMarket) || (intent.data?.scheme == "https" && noBrowser)) throw android.content.ActivityNotFoundException()
            submitted.add(intent)
        }
    }

    @Test fun launchAttemptSinkCanDisableWithoutSpendingBudgetOrLaunching() {
        install(); five()
        eventHook = { if (it.name == "retention_review_launch_attempt") runtime.updateConfig(mapOf("review.enabled" to "false")) }
        ready()
        assertTrue(transport.launches.isEmpty())
        assertEquals(0L, module.snapshot()!!.attempts)
        assertEquals(5L, module.snapshot()!!.successesSinceAttempt)
        assertNull(module.snapshot()!!.inFlightPhase)
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
    }

    @Test fun transitionSubscriberCanDisableReviewBeforePlatformLaunchAndClosesPublishedScope() {
        install(); five()
        runtime.subscribe("test.disable") { if (it is RetentionSignal.ExternalTransitionStarted) runtime.updateConfig(mapOf("review.enabled" to "false")) }
        ready()
        assertTrue(transport.launches.isEmpty())
        assertEquals(0L, module.snapshot()!!.attempts)
        assertNull(module.snapshot()!!.inFlightPhase)
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
    }

    @Test fun queuedReadyFromExistingSubscriberWaitsForHostFinishObserver() {
        install(); five()
        runtime.subscribe("test.ready") { if (it is RetentionSignal.AdClicked) transport.requests.single()(ReviewInfoResult.Ready(FakeToken)) }
        runtime.subscribe("test.finish") { if (it is RetentionSignal.ExternalTransitionStarted) host!!.get().finish() }
        runtime.signal(RetentionSignal.AdClicked("queued")); idle()
        assertTrue(transport.launches.isEmpty())
        assertEquals(0L, module.snapshot()!!.attempts)
        assertNull(module.snapshot()!!.inFlightPhase)
        assertFalse(runtime.marketingEligibility(0, false) == RetentionEligibility.Blocked(RetentionSuppressionReason.EXTERNAL_TRANSITION))
    }

    @Test fun manualStoreFromSubscriberHonorsNewHostUiButIgnoresAutomaticDisable() {
        install(ReviewOptions(enabled = false))
        runtime.subscribe("test.store") { if (it is RetentionSignal.AdClicked) module.openStore() }
        runtime.subscribe("test.host") { if (it is RetentionSignal.ExternalTransitionStarted && it.kind == "review_or_store") runtime.signal(RetentionSignal.HostUiChanged("host.dialog", true)) }
        runtime.signal(RetentionSignal.AdClicked("manual")); idle()
        assertEquals(0, transport.stores)
        assertEquals(0L, module.snapshot()!!.attempts)
        assertTrue(runtime.ui.eligibility() is RetentionEligibility.Blocked)
        runtime.signal(RetentionSignal.HostUiChanged("host.dialog", false))
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
    }

    @Test fun manualStoreDoesNotOpenFromHostFinishedByTransitionSubscriber() {
        install(ReviewOptions(enabled = false))
        runtime.subscribe("test.finish.store") { if (it is RetentionSignal.ExternalTransitionStarted) host!!.get().finish() }
        module.openStore(); idle()
        assertEquals(0, transport.stores)
        assertEquals(0L, module.snapshot()!!.attempts)
        assertFalse(events.any { it.name == "retention_review_store_handoff" })
    }

    private inner class FakeTransport : ReviewTransport {
        val requests = mutableListOf<(ReviewInfoResult) -> Unit>()
        val launches = mutableListOf<(ReviewFlowResult) -> Unit>()
        var stores = 0; var storeSucceeds = true; var throwRequest = false
        var launchActivityWasCurrent = false; var blockedAtLaunch = false; var blockedAtStore = false
        override fun request(callback: (ReviewInfoResult) -> Unit) { if (throwRequest) error("offline"); requests.add(callback) }
        override fun launch(activity: Activity, token: ReviewToken, callback: (ReviewFlowResult) -> Unit) {
            launchActivityWasCurrent = runtime.activities.current() === activity
            blockedAtLaunch = runtime.ui.eligibility() is RetentionEligibility.Blocked
            launches.add(callback)
        }
        override fun openStore(activity: Activity): Boolean {
            assertSame(runtime.activities.current(), activity)
            blockedAtStore = runtime.ui.eligibility() is RetentionEligibility.Blocked
            stores++; return storeSucceeds
        }
    }
    private object FakeToken : ReviewToken
    private class FakeClock(var now: Long = 1_800_000_000_000L, var elapsed: Long = 1_000L) : RetentionClock {
        override fun wallTimeMillis() = now
        override fun elapsedRealtimeMillis() = elapsed
        override fun timeZone() = java.util.TimeZone.getTimeZone("UTC")
        fun advance(millis: Long) { now += millis; elapsed += millis }
    }
}
