package io.retentionkit.feedback

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionFeedbackModuleTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: RetentionStore
    private lateinit var clock: FakeClock
    private lateinit var module: RetentionFeedbackModule
    private lateinit var runtime: RetentionRuntime
    private var host: ActivityController<Activity>? = null
    private var screen: ActivityController<RetentionFeedbackActivity>? = null
    private var customController: FeedbackController? = null
    private val launches = mutableListOf<Intent>()
    private val events = mutableListOf<RetentionEvent>()
    private var rejectLaunch = false
    private var throwLaunch = false
    private var blockedDuringLaunch = false
    private var selectedLocale = Locale.ENGLISH
    private lateinit var token: String
    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        store = SharedPreferencesRetentionStore(app, "feedback_${UUID.randomUUID()}")
        clock = FakeClock()
    }
    @After fun after() {
        screen?.pause()?.stop()?.destroy(); screen = null
        host?.pause()?.stop()?.destroy(); host = null
        RetentionRuntime.uninstallForTests()
    }
    private fun install(custom: Boolean = true, shortcut: Boolean = false, reasons: Boolean = true, configure: (FeedbackOptions) -> FeedbackOptions = { it }) {
        val factory = if (custom) FeedbackUiFactory { activity, controller, _ ->
            customController = controller
            TextView(activity).apply { text = "Custom content" }
        } else null
        module = RetentionFeedbackModule(configure(FeedbackOptions(shortcutEnabled = shortcut, showReasons = reasons,
            uiFactory = factory, launcher = FeedbackLauncher { activity, intent ->
                assertSame(runtime.activities.current(), activity)
                blockedDuringLaunch = runtime.ui.eligibility() is RetentionEligibility.Blocked
                if (throwLaunch) error("launcher exception")
                if (rejectLaunch) false else { launches.add(Intent(intent)); true }
            })))
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module), store = store, clock = clock,
            initialUserState = RetentionUserState(setupCompleted = true, entitlement = RetentionEntitlement.NON_SUBSCRIBER),
            localeProvider = RetentionLocaleProvider { context -> context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(selectedLocale) }) },
            featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", R.drawable.rk_ic_feedback)) },
            router = RetentionRouter { context, _ -> Intent().setComponent(ComponentName(context.packageName, Activity::class.java.name)) },
            eventSink = RetentionEventSink { events.add(it) }))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
        host = Robolectric.buildActivity(Activity::class.java).setup()
        runtime.signal(RetentionSignal.ProcessForeground)
        idle()
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun show() {
        assertEquals(FeedbackShowResult.Scheduled, module.show()); idle()
        val intent = launches.last()
        token = intent.getStringExtra(RetentionFeedbackModule.EXTRA_SESSION)!!
        assertEquals(RetentionFeedbackActivity::class.java.name, intent.component!!.className)
        host!!.pause()
        screen = Robolectric.buildActivity(RetentionFeedbackActivity::class.java, intent).setup()
        idle()
        assertFalse(screen!!.get().isFinishing)
    }
    private fun view(tag: String): View = screen!!.get().findViewById<ViewGroup>(android.R.id.content).findViewWithTag(tag)

    @Test fun queuedShowDisabledByTransitionObserverKeepsEntryPendingAndReleasesScope() {
        install()
        val entry = RetentionEntry(RetentionEntrySource.SHORTCUT, RetentionFeedbackModule.DESTINATION, "feedback", createdAtMillis = clock.wallTimeMillis())
        runtime.subscribe("test.show") { if (it is RetentionSignal.AdClicked) module.handleEntry(entry) }
        runtime.subscribe("test.disable") { if (it is RetentionSignal.ExternalTransitionStarted) runtime.updateConfig(mapOf("feedback.enabled" to "false")) }
        runtime.signal(RetentionSignal.AdClicked("show")); idle()
        assertTrue(launches.isEmpty())
        assertNotNull(runtime.entries.pending(entry.token))
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
        assertFalse(events.any { it.name == "retention_feedback_requested" })
    }

    @Test fun feedbackShowDoesNotLaunchWhenTransitionSubscriberFinishesHost() {
        install()
        runtime.subscribe("test.finish") { if (it is RetentionSignal.ExternalTransitionStarted) host!!.get().finish() }
        module.show(); idle()
        assertTrue(launches.isEmpty())
        assertFalse(events.any { it.name == "retention_feedback_requested" })
    }

    @Test fun disabledRescueCannotNavigateOrLeaveFalseHandoffSession() {
        install(); show()
        runtime.subscribe("test.disable") { if (it is RetentionSignal.ExternalTransitionStarted && it.token.startsWith("feedback.action.")) runtime.updateConfig(mapOf("feedback.enabled" to "false")) }
        assertTrue(customController!!.tryFeature("notes") is FeedbackActionResult.Blocked)
        assertEquals(1, launches.size)
        assertEquals(FeedbackPhase.CANCELLED, module.session(token)!!.phase)
        assertFalse(events.any { it.name == "retention_feedback_feature_handoff" })
    }

    @Test fun queuedAppInfoHandoffWaitsForOnboardingObserverAndPreservesForeignOwner() {
        install(); show()
        runtime.subscribe("test.action") { if (it is RetentionSignal.AdClicked) customController!!.continueToAppManagement() }
        runtime.subscribe("test.block") { if (it is RetentionSignal.ExternalTransitionStarted && it.token.startsWith("feedback.action.")) {
            runtime.signal(RetentionSignal.OnboardingChanged(true))
            runtime.signal(RetentionSignal.ExternalTransitionStarted("foreign", "host"))
        } }
        runtime.signal(RetentionSignal.AdClicked("settings")); idle()
        assertEquals(1, launches.size)
        assertEquals(FeedbackPhase.OPEN, module.session(token)!!.phase)
        assertFalse(events.any { it.name == "retention_feedback_system_handoff" })
        runtime.signal(RetentionSignal.OnboardingChanged(false))
        assertTrue(runtime.ui.eligibility() is RetentionEligibility.Blocked)
        runtime.signal(RetentionSignal.ExternalTransitionFinished("foreign"))
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
    }

    @Test fun optionalSurveyAllowsDirectSystemAppInfoAndOnlyReportsHandoff() {
        install(); show()
        assertTrue(blockedDuringLaunch)
        assertTrue(customController!!.state()!!.selectedReasons.isEmpty())
        assertEquals(FeedbackActionResult.Applied, customController!!.continueToAppManagement())
        val submitted = launches.last()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, submitted.action)
        assertEquals("package:${app.packageName}", submitted.data.toString())
        assertEquals(FeedbackPhase.SYSTEM_HANDOFF, module.session(token)!!.phase)
        assertEquals(1, events.count { it.name == "retention_feedback_system_handoff" })
        assertTrue(customController!!.continueToAppManagement() is FeedbackActionResult.Blocked)
        assertEquals(2, launches.size)
        screen!!.pause().resume(); idle()
        assertTrue(screen!!.get().isFinishing)
        assertTrue(events.none { it.name.contains("uninstall") || it.name.contains("rated") })
        assertTrue(events.all { it.name.matches(Regex("[a-z0-9_]{1,40}")) })
    }

    @Test fun keepAndUnknownActionsCannotAccidentallyLaunchSettings() {
        install(); show()
        assertTrue(customController!!.selectReason("unknown", true) is FeedbackActionResult.Blocked)
        assertTrue(customController!!.tryFeature("unknown") is FeedbackActionResult.Blocked)
        assertEquals(FeedbackActionResult.Applied, customController!!.keep())
        assertEquals(FeedbackPhase.KEPT, module.session(token)!!.phase)
        assertTrue(screen!!.get().isFinishing)
        assertEquals(1, launches.size)
    }

    @Test fun featureRescueCreatesOneTypedHostEntryWithSessionAttribution() {
        install(); show()
        assertEquals(FeedbackActionResult.Applied, customController!!.tryFeature("notes"))
        val entry = (RetentionEntryCodec.read(launches.last()) as RetentionEntryDecodeResult.Valid).entry
        assertEquals(RetentionEntrySource.FEEDBACK, entry.source)
        assertEquals("notes", entry.destination)
        assertEquals("try_feature", entry.actionId)
        assertEquals(token, entry.instanceId)
        assertEquals(FeedbackPhase.FEATURE_HANDOFF, module.session(token)!!.phase)
        assertTrue(screen!!.get().isFinishing)
        assertTrue(customController!!.tryFeature("notes") is FeedbackActionResult.Blocked)
        assertEquals(2, launches.size)
    }

    @Test fun failedSystemHandoffRestoresOpenAndAllowsRetryWithoutLosingSelectedReasons() {
        install(); show()
        assertEquals(FeedbackActionResult.Applied, customController!!.selectReason("other", true))
        rejectLaunch = true
        assertTrue(customController!!.continueToAppManagement() is FeedbackActionResult.Failed)
        assertEquals(FeedbackPhase.OPEN, customController!!.state()!!.phase)
        assertEquals(setOf("other"), customController!!.state()!!.selectedReasons)
        assertEquals(0, events.count { it.name == "retention_feedback_system_handoff" })
        rejectLaunch = false
        assertEquals(FeedbackActionResult.Applied, customController!!.continueToAppManagement())
        assertEquals(1, events.count { it.name == "retention_feedback_system_handoff" })
    }

    @Test fun customViewRetainsSdkStateAcrossRecreationAndRejectsOldController() {
        install(); show()
        val previous = customController!!
        previous.selectReason("hard_to_use", true)
        screen!!.recreate(); idle()
        assertNotSame(previous, customController)
        assertEquals(token, customController!!.sessionToken)
        assertEquals(setOf("hard_to_use"), customController!!.state()!!.selectedReasons)
        assertTrue(previous.keep() is FeedbackActionResult.Blocked)
        assertEquals(1, events.count { it.name == "retention_feedback_shown" })
        assertEquals(FeedbackActionResult.Applied, customController!!.keep())
    }

    @Test fun defaultUiRestoresCheckboxSelectionAndAppliesSystemAndImeInsets() {
        install(custom = false); show()
        (view("rk_feedback_reason_other") as CheckBox).performClick()
        assertEquals(setOf("other"), module.session(token)!!.selectedReasons)
        screen!!.recreate(); idle()
        assertTrue((view("rk_feedback_reason_other") as CheckBox).isChecked)
        val outer = screen!!.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.dispatchApplyWindowInsets(outer, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(5, 30, 7, 20))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 160)).build())
        assertEquals(5, outer.paddingLeft); assertEquals(30, outer.paddingTop)
        assertEquals(7, outer.paddingRight); assertEquals(160, outer.paddingBottom)
        (view("rk_feedback_continue") as Button).performClick()
        @Suppress("DEPRECATION")
        val expected = Intent.ACTION_UNINSTALL_PACKAGE
        assertEquals(expected, launches.last().action)
        assertEquals("package:${app.packageName}", launches.last().dataString)
    }

    @Test fun defaultContentUsesHostSelectedLocaleAndCanRemoveSurvey() {
        selectedLocale = Locale.forLanguageTag("vi")
        install(custom = false, reasons = false); show()
        assertEquals("Tiếp tục sử dụng", (view("rk_feedback_keep") as Button).text.toString())
        val content = screen!!.get().findViewById<ViewGroup>(android.R.id.content)
        assertNull(content.findViewWithTag<View>("rk_feedback_reason_other"))
        assertNotNull(content.findViewWithTag<View>("rk_feedback_feature_notes"))
        (view("rk_feedback_continue") as Button).performClick()
        assertEquals(FeedbackPhase.SYSTEM_HANDOFF, module.session(token)!!.phase)
    }

    @Test fun materializedEntryRemainsPendingUntilUiCanOpenAndIsConsumedOnceAtLaunch() {
        install()
        val entry = RetentionEntry(RetentionEntrySource.SHORTCUT, RetentionFeedbackModule.DESTINATION, "open_feedback", createdAtMillis = clock.now)
        runtime.signal(RetentionSignal.HostUiChanged("paywall", true))
        module.handleEntry(entry); idle()
        assertNotNull(runtime.entries.pending(entry.token))
        assertTrue(launches.isEmpty())
        runtime.signal(RetentionSignal.HostUiChanged("paywall", false))
        module.handleEntry(entry); idle()
        assertNull(runtime.entries.pending(entry.token))
        assertEquals(1, launches.size)
        module.handleEntry(entry); idle()
        assertEquals(1, launches.size)
        assertTrue(module.handleEntry(entry.copy(token = UUID.randomUUID().toString(), mode = RetentionEntryMode.REUSABLE)) is FeedbackShowResult.Unavailable)
    }

    @Test fun thrownActivityLaunchReleasesItsOwnUiAndAllowsNextUserAttempt() {
        install(); throwLaunch = true
        module.show(); idle()
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
        assertTrue(launches.isEmpty())
        throwLaunch = false
        show()
        assertFalse(screen!!.get().isFinishing)
    }

    @Test fun configDisableClosesCurrentSessionAndDoesNotChangeOtherExternalScopes() {
        install(); show()
        runtime.signal(RetentionSignal.ExternalTransitionStarted("host.payment", "billing", 120_000))
        assertTrue(runtime.updateConfig(mapOf("feedback.enabled" to "false")) is RetentionConfigResult.Applied)
        idle()
        assertEquals(FeedbackPhase.CANCELLED, module.session(token)!!.phase)
        assertTrue(screen!!.get().isFinishing)
        assertTrue(runtime.ui.eligibility() is RetentionEligibility.Blocked)
        assertTrue(customController!!.keep() is FeedbackActionResult.Blocked)
    }

    @Test fun pendingActivityStartTimeoutAndExpiredSessionCannotHoldPromptForever() {
        install()
        module.show(); idle()
        val expired = launches.last().getStringExtra(RetentionFeedbackModule.EXTRA_SESSION)!!
        clock.advance(10_000); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
        clock.advance(1_800_000)
        assertNull(module.session(expired))
        module.show(); idle()
        assertNotEquals(expired, launches.last().getStringExtra(RetentionFeedbackModule.EXTRA_SESSION))
    }

    @Test fun shortcutUpdateAndDisablePreserveForeignDynamicEntries() {
        val manager = app.getSystemService(ShortcutManager::class.java)
        val target = ComponentName(app.packageName, Activity::class.java.name)
        manager.addDynamicShortcuts(listOf(ShortcutInfo.Builder(app, "host.owned").setShortLabel("Host")
            .setActivity(target).setIntent(Intent(Intent.ACTION_VIEW).setComponent(target)).build()))
        install(shortcut = true)
        assertTrue(runtime.diagnostics.snapshot().toString(), manager.dynamicShortcuts.map { it.id }.containsAll(listOf("host.owned", RetentionFeedbackModule.SHORTCUT_ID)))
        val own = manager.dynamicShortcuts.single { it.id == RetentionFeedbackModule.SHORTCUT_ID }
        val template = (RetentionEntryCodec.read(own.intent) as RetentionEntryDecodeResult.Valid).entry
        assertEquals(RetentionEntryMode.REUSABLE, template.mode)
        assertEquals(RetentionFeedbackModule.DESTINATION, template.destination)
        runtime.updateConfig(mapOf("feedback.shortcut_enabled" to "false")); idle()
        assertEquals(listOf("host.owned"), manager.dynamicShortcuts.map { it.id })
    }

    @Test fun shortcutCapacityIncludesManifestAndForeignDynamicWithoutRemovingEither() {
        val manager = app.getSystemService(ShortcutManager::class.java)
        val target = ComponentName(app.packageName, Activity::class.java.name)
        fun shortcut(id: String) = ShortcutInfo.Builder(app, id).setShortLabel(id).setActivity(target)
            .setIntent(Intent(Intent.ACTION_VIEW).setComponent(target)).build()
        shadowOf(manager).setMaxShortcutCountPerActivity(2)
        manager.addDynamicShortcuts(listOf(shortcut("host.dynamic")))
        shadowOf(manager).setManifestShortcuts(listOf(shortcut("host.manifest")))
        install(shortcut = true)
        assertEquals(listOf("host.dynamic"), manager.dynamicShortcuts.map { it.id })
        assertEquals(listOf("host.manifest"), manager.manifestShortcuts.map { it.id })
        assertTrue(events.any { it.name == "retention_feedback_skipped" && it.attributes["reason"] == "shortcut_quota" })
    }

    @Test fun selectedReasonsRestoreAfterRuntimeReplacementWithoutReplayingShownEvent() {
        install(); show()
        customController!!.selectReason("other", true)
        val intent = Intent(screen!!.get().intent)
        val originalToken = token
        screen!!.pause().stop().destroy(); screen = null
        host!!.stop().destroy(); host = null
        RetentionRuntime.uninstallForTests()
        install()
        host!!.pause()
        screen = Robolectric.buildActivity(RetentionFeedbackActivity::class.java, intent).setup(); idle()
        assertFalse(screen!!.get().isFinishing)
        assertEquals(originalToken, customController!!.sessionToken)
        assertEquals(setOf("other"), customController!!.state()!!.selectedReasons)
        assertEquals(1, events.count { it.name == "retention_feedback_shown" })
    }

    @Test fun disableDuringPendingLaunchCancelsDurableSessionBeforeScreenArrives() {
        install()
        module.show(); idle()
        val intent = launches.single()
        val pending = intent.getStringExtra(RetentionFeedbackModule.EXTRA_SESSION)!!
        runtime.updateConfig(mapOf("feedback.enabled" to "false")); idle()
        assertEquals(FeedbackPhase.CANCELLED, module.session(pending)!!.phase)
        host!!.pause()
        screen = Robolectric.buildActivity(RetentionFeedbackActivity::class.java, intent).setup(); idle()
        assertTrue(screen!!.get().isFinishing)
        assertFalse(events.any { it.name == "retention_feedback_shown" })
    }

    @Test fun sessionStorageFailureReleasesLaunchLeaseAndDoesNotStartActivity() {
        val actual = store
        var fail = false
        store = object : RetentionStore {
            override fun snapshot(namespace: String) = actual.snapshot(namespace)
            override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T {
                if (fail && namespace == "feedback.sessions.v1") throw RetentionStorageException("disk unavailable")
                return actual.transaction(namespace, block)
            }
        }
        install(); fail = true
        module.show(); idle()
        assertTrue(launches.isEmpty())
        assertEquals(RetentionEligibility.Allowed, runtime.ui.eligibility())
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "feedback.show" })
    }

    @Test @Config(sdk = [24, 36]) fun defaultScreenRunsAtMinimumAndLatestApiAndBackRemainsAvailable() {
        install(custom = false); show()
        val info = app.packageManager.getActivityInfo(ComponentName(app, RetentionFeedbackActivity::class.java), 0)
        assertFalse(info.exported)
        screen!!.get().onBackPressed()
        assertTrue(screen!!.get().isFinishing)
        assertEquals(FeedbackPhase.KEPT, module.session(token)!!.phase)
    }

    @Test fun openViaEntryUsesHostFrontDoorWithoutCreatingOrShowingSurveyEarly() {
        install()
        module.openViaEntry(); idle()
        val entry = (RetentionEntryCodec.read(launches.single()) as RetentionEntryDecodeResult.Valid).entry
        assertEquals(RetentionEntrySource.FEEDBACK, entry.source)
        assertEquals(RetentionFeedbackModule.DESTINATION, entry.destination)
        assertEquals(Activity::class.java.name, launches.single().component!!.className)
        assertNull(launches.single().getStringExtra(RetentionFeedbackModule.EXTRA_SESSION))
        assertFalse(events.any { it.name == "retention_feedback_shown" })
        assertTrue(events.any { it.name == "retention_feedback_entry_requested" })
    }

    @Test fun unavailableUninstallConfirmationFallsBackToAppInfoWithoutMandatoryReason() {
        val attempts = mutableListOf<String?>()
        install(configure = { base -> base.copy(launcher = FeedbackLauncher { _, intent ->
            if (intent.component != null) { launches.add(intent); true }
            else { attempts.add(intent.action); intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS }
        }) })
        show()
        assertTrue(customController!!.state()!!.selectedReasons.isEmpty())
        assertEquals(FeedbackActionResult.Applied, customController!!.continueToSystem())
        @Suppress("DEPRECATION") val confirmation = Intent.ACTION_UNINSTALL_PACKAGE
        assertEquals(listOf(confirmation, Settings.ACTION_APPLICATION_DETAILS_SETTINGS), attempts)
        assertEquals("app_management", events.last { it.name == "retention_feedback_system_handoff" }.attributes["action"])
        assertEquals(FeedbackPhase.SYSTEM_HANDOFF, customController!!.state()!!.phase)
    }

    @Test fun failedConfirmationCannotFallbackAfterReentrantDisableAndExplicitAppInfoRemainsAvailable() {
        val attempts = mutableListOf<String?>()
        install(configure = { base -> base.copy(launcher = FeedbackLauncher { _, intent ->
            if (intent.component != null) { launches.add(intent); true }
            else { attempts.add(intent.action); runtime.updateConfig(mapOf("feedback.enabled" to "false")); false }
        }) })
        show()
        customController!!.continueToSystem()
        assertEquals(1, attempts.size)
        assertFalse(events.any { it.name == "retention_feedback_system_handoff" })
    }

    @Test fun customNativeSlotReceivesRealLifecycleAndClosesOwnedBindingOncePerRecreation() {
        var bound = 0; var closed = 0
        val owners = mutableListOf<androidx.lifecycle.LifecycleOwner>()
        install(configure = { base -> base.copy(
            uiFactory = FeedbackUiFactory { activity, control, _ ->
                customController = control
                FrameLayout(activity).also { control.bindNative(it) }
            },
            nativeContent = FeedbackNativeContent { _, owner, container ->
                bound++; owners.add(owner)
                container.addView(TextView(container.context).apply { text = "Host native slot" })
                AutoCloseable { closed++ }
            },
        ) })
        show()
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, owners.single().lifecycle.currentState)
        screen!!.recreate(); idle()
        assertEquals(2, bound); assertEquals(1, closed)
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, owners.first().lifecycle.currentState)
        screen!!.pause().stop().destroy(); screen = null
        assertEquals(2, closed)
    }

    private class FakeClock(var now: Long = 1_800_000_000_000L, var elapsed: Long = 1_000L) : RetentionClock {
        override fun wallTimeMillis() = now
        override fun elapsedRealtimeMillis() = elapsed
        override fun timeZone() = java.util.TimeZone.getTimeZone("UTC")
        fun advance(millis: Long) { now += millis; elapsed += millis }
    }
}
