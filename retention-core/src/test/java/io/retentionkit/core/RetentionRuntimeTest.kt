package io.retentionkit.core

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionRuntimeTest {
    @Before fun before() { RetentionRuntime.uninstallForTests() }
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun concurrentInstallRestoresBeforeAttachAndPublishesOnlyOneRuntime() {
        val store = testStore()
        store.transaction("core.config") { it.put("__revision", 7L); it.put("notifications.enabled", false) }
        val attached = AtomicInteger()
        val module = object : RetentionModule {
            override val id = "notifications"
            override fun attach(runtime: RetentionRuntime) {
                assertNull(RetentionRuntime.get())
                assertEquals(7, runtime.config.revision)
                assertFalse(runtime.config.boolean("notifications.enabled", true))
                attached.incrementAndGet()
            }
        }
        val options = RetentionOptions(store = store, modules = listOf(module))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map { Callable { RetentionRuntime.install(testApplication(), options) } }).map { it.get() }
            assertTrue(results.all { it is RetentionInstallResult.Installed })
            assertEquals(1, results.map { (it as RetentionInstallResult.Installed).runtime }.distinct().size)
            assertEquals(1, attached.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun revisionIsAtomicValidatedAndRestoredWithoutErasingAbsentFields() {
        val module = object : RetentionModule {
            override val id = "notifications"
            override fun attach(runtime: RetentionRuntime) {}
            override fun validateConfig(config: RetentionConfigSnapshot): List<String> =
                if (config.string("notifications.enabled")?.toBooleanStrictOrNull() == null) listOf("invalid enabled") else emptyList()
        }
        val store = testStore()
        val options = RetentionOptions(store = store, modules = listOf(module), initialOverrides = mapOf("notifications.enabled" to "true", "notifications.time" to "08:00"))
        val runtime = installed(options)
        val before = runtime.config
        assertTrue(runtime.updateConfig(mapOf("notifications.enabled" to "false")) is RetentionConfigResult.Applied)
        assertTrue(runtime.updateConfig(mapOf("notifications.enabled" to "invalid", "notifications.time" to "19:00")) is RetentionConfigResult.Rejected)
        assertEquals(1, runtime.config.revision)
        assertEquals("08:00", runtime.config.string("notifications.time"))
        assertTrue(before.boolean("notifications.enabled", false))
        RetentionRuntime.uninstallForTests()
        val restored = installed(options)
        assertEquals(1, restored.config.revision)
        assertFalse(restored.config.boolean("notifications.enabled", true))
        assertEquals("08:00", restored.config.string("notifications.time"))
    }

    @Test fun explicitConfigRemovalSurvivesRestartInsteadOfResurrectingInitialOverride() {
        val options = RetentionOptions(store = testStore(), initialOverrides = mapOf("review.enabled" to "true"))
        val runtime = installed(options)
        assertTrue(runtime.updateConfig(emptyMap(), setOf("review.enabled")) is RetentionConfigResult.Applied)
        assertNull(runtime.config.string("review.enabled"))
        RetentionRuntime.uninstallForTests()
        val restored = installed(options)
        assertNull(restored.config.string("review.enabled"))
        assertEquals(1, restored.config.revision)
    }

    @Test fun failedConfigPersistenceKeepsOldSnapshotAndDoesNotSignalRevision() {
        val actual = testStore()
        var fail = false
        val wrapper = object : RetentionStore {
            override fun snapshot(namespace: String) = actual.snapshot(namespace)
            override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T {
                if (fail && namespace == "core.config") throw RetentionStorageException("disk unavailable")
                return actual.transaction(namespace, block)
            }
        }
        val runtime = installed(RetentionOptions(store = wrapper))
        val signals = mutableListOf<RetentionSignal>()
        runtime.subscribe("test") { signals.add(it) }
        fail = true
        assertTrue(runtime.updateConfig(mapOf("review.enabled" to "true")) is RetentionConfigResult.Rejected)
        assertEquals(0, runtime.config.revision)
        assertTrue(signals.none { it is RetentionSignal.ConfigurationChanged })
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "core.config" })
    }

    @Test fun setupGraceRestoresWhileEntitlementIsResolvedForCurrentProcess() {
        val clock = TestClock()
        val store = testStore()
        val options = RetentionOptions(store = store, clock = clock)
        val runtime = installed(options)
        val setupAt = clock.now + 86_400_000
        clock.advance(86_400_000)
        runtime.signal(RetentionSignal.SetupCompleted)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertEquals(setupAt, runtime.userState.setupCompletedAtMillis)
        assertBlocked(RetentionSuppressionReason.COOLDOWN, runtime.marketingEligibility())
        clock.advance(86_400_000)
        runtime.signal(RetentionSignal.SetupCompleted)
        assertEquals(setupAt, runtime.userState.setupCompletedAtMillis)
        assertEquals(RetentionEligibility.Allowed, runtime.marketingEligibility())
        RetentionRuntime.uninstallForTests()
        val restored = installed(options)
        assertEquals(setupAt, restored.userState.setupCompletedAtMillis)
        assertEquals(RetentionEntitlement.UNKNOWN, restored.userState.entitlement)
        assertTrue(restored.userState.setupCompleted)
        restored.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.UNKNOWN))
        assertBlocked(RetentionSuppressionReason.ENTITLEMENT_UNKNOWN, restored.marketingEligibility())
        restored.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER))
        assertBlocked(RetentionSuppressionReason.SUBSCRIBER, restored.marketingEligibility())
    }

    @Test fun lastActiveTracksSessionEndAndDoesNotMoveBackwardsWithWallClock() {
        val clock = TestClock()
        val runtime = installed(RetentionOptions(store = testStore(), clock = clock))
        runtime.signal(RetentionSignal.ProcessForeground)
        clock.advance(2 * 60 * 60 * 1000)
        runtime.signal(RetentionSignal.ProcessBackground)
        assertEquals(clock.now, runtime.userState.lastActiveAtMillis)
        val last = runtime.userState.lastActiveAtMillis
        clock.now -= 100000
        runtime.signal(RetentionSignal.ProcessForeground)
        assertEquals(last, runtime.userState.lastActiveAtMillis)
    }

    @Test fun failingModulesAndSinkCannotBreakSubscribersAndReentrantDispatchIsOrdered() {
        val events = mutableListOf<String>()
        val bad = object : RetentionModule {
            override val id = "bad"
            override fun attach(runtime: RetentionRuntime) { error("bad attach") }
            override fun shutdown() { events.add("bad.shutdown") }
            override fun onSignal(signal: RetentionSignal) { fail("failed module must not receive signals") }
        }
        val good = object : RetentionModule {
            override val id = "good"
            override fun attach(runtime: RetentionRuntime) { events.add("good.attach") }
            override fun onSignal(signal: RetentionSignal) { events.add("good.signal") }
            override fun reconcile(reason: String) { events.add("good.reconcile") }
            override fun shutdown() { events.add("good.shutdown") }
        }
        val runtime = installed(RetentionOptions(store = testStore(), modules = listOf(bad, good), eventSink = RetentionEventSink { error("sink down") }))
        runtime.emit(RetentionEvent("post_submitted"))
        runtime.subscribe("throws") { error("listener down") }
        val order = mutableListOf<String>()
        lateinit var second: RetentionSubscription
        runtime.subscribe("first") { signal ->
            if (signal is RetentionSignal.AdClicked) {
                order.add("first")
                second.close()
                runtime.subscribe("third") { order.add("third") }
                runtime.signal(RetentionSignal.BusinessSuccess("notes"))
            }
        }
        second = runtime.subscribe("second") { order.add("second") }
        runtime.signal(RetentionSignal.AdClicked())
        assertEquals(listOf("first", "second", "third"), order)
        assertTrue(events.contains("good.signal"))
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "bad.attach" })
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "event_sink" })
        RetentionRuntime.uninstallForTests()
        assertEquals("good.shutdown", events.last())
        assertFalse(runtime.signal(RetentionSignal.ProcessForeground))
    }

    @Test fun invalidInstallReturnsFailureAndCanBeRetriedWithCorrectedOptions() {
        val duplicate = object : RetentionModule { override val id = "same"; override fun attach(runtime: RetentionRuntime) {} }
        assertTrue(RetentionRuntime.install(testApplication(), RetentionOptions(modules = listOf(duplicate, duplicate))) is RetentionInstallResult.Failed)
        assertNull(RetentionRuntime.get())
        assertNotNull(installed())
    }

    @Test fun secondaryProcessIsExplicitlyRejected() {
        val app = testApplication()
        val original = app.applicationInfo.processName
        try {
            app.applicationInfo.processName = Application.getProcessName() + ":other"
            assertTrue(RetentionRuntime.install(app, RetentionOptions(store = testStore())) is RetentionInstallResult.Failed)
        } finally { app.applicationInfo.processName = original }
    }

    @Test fun routeRequiresOwnExplicitComponentAndCatalogueUsesAppLocaleContext() {
        val app = testApplication()
        val localized = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.FRENCH) })
        val options = RetentionOptions(store = testStore(), localeProvider = RetentionLocaleProvider { localized },
            featureProvider = RetentionFeatureProvider { context ->
                assertEquals("fr", context.resources.configuration.locales[0].language)
                listOf(RetentionFeature("notes", "Traduire", 1))
            },
            router = RetentionRouter { _, _ -> Intent().setComponent(ComponentName(app.packageName, "HostEntry")) })
        val runtime = installed(options)
        assertEquals("Traduire", runtime.features().single().label)
        assertSame(localized, runtime.localizedContext())
        val entry = RetentionEntry(RetentionEntrySource.DAILY, "notes", "open")
        val intent = runtime.createEntryIntent(entry)!!
        assertEquals(entry, (RetentionEntryCodec.read(intent) as RetentionEntryDecodeResult.Valid).entry)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        RetentionRuntime.uninstallForTests()
        val invalid = installed(options.copy(router = RetentionRouter { _, _ -> Intent("untrusted.implicit") }))
        assertNull(invalid.createEntryIntent(entry))
        assertTrue(invalid.diagnostics.snapshot().any { it.component == "entry_router" })
    }

    private fun assertBlocked(reason: RetentionSuppressionReason, result: RetentionEligibility) = assertEquals(reason, (result as RetentionEligibility.Blocked).reason)
}
