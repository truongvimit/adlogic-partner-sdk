package io.retentionkit

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.ui.splash.SplashEntry
import io.retentionkit.core.*
import io.retentionkit.integration.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionSuiteTest {
    abstract class Splash : RetentionSplashActivity()
    class Main : ComponentActivity()
    class Feature : ComponentActivity(), RetentionFeatureHost {
        val shown = mutableListOf<Pair<RetentionEntry, String>>()
        var callback: (() -> Unit)? = null
        override fun onRetentionFeature(entry: RetentionEntry, destination: String) { shown.add(entry to destination); callback?.invoke() }
    }
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<out ComponentActivity>>()
    @After fun close() { screens.reversed().forEach { runCatching { it.pause().stop().destroy() } }; RetentionRuntime.uninstallForTests() }
    private fun options(resolve: (String) -> String = { it }, customize: (RetentionKitOptions) -> RetentionKitOptions = { it }) = RetentionSuiteOptions(
        Splash::class.java, Main::class.java,
        RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", android.R.drawable.ic_menu_edit), RetentionFeature("guide", "Guide", android.R.drawable.ic_menu_help)) },
        RetentionRouter { context, _ -> Intent(context, Feature::class.java) }, resolveDestination = resolve,
        customize = { standard -> customize(standard.copy(
            notifications = null, widgets = null, review = null, feedback = null, configSource = null,
            eventSink = RetentionEventSink.NONE, uiHost = RetentionUiHost.NONE,
            adapters = standard.adapters.filter { it.id == "suite.activities" },
            initialUserState = RetentionUserState(setupCompleted = true, entitlement = RetentionEntitlement.NON_SUBSCRIBER),
            store = SharedPreferencesRetentionStore(app, "suite_${UUID.randomUUID()}"),
        )) },
    )
    private fun install(options: RetentionSuiteOptions = options()): RetentionKit =
        (RetentionSuite.install(app, options) as RetentionKitInstallResult.Installed).kit
    private fun entry(destination: String = "notes", mode: RetentionEntryMode = RetentionEntryMode.ONCE) =
        RetentionEntry(RetentionEntrySource.WIDGET, destination, "open", mode = mode)
    private fun intent(entry: RetentionEntry) = RetentionEntryCodec.write(Intent(app, Feature::class.java), entry)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun feature(intent: Intent = Intent(app, Feature::class.java)) = Robolectric.buildActivity(Feature::class.java, intent).also { screens.add(it); it.setup(); idle() }

    @Test fun installOwnsMainAndFeatureLifecycleWithoutAnyActivityForwarding() {
        val kit = install()
        val entry = entry()
        val osIntent = checkNotNull(kit.runtime.createEntryIntent(entry))
        assertEquals(Splash::class.java.name, osIntent.component?.className)
        assertEquals(SplashEntry.WIDGET, SplashEntry.from(osIntent.extras))
        assertFalse(osIntent.getBooleanExtra("ob_without_splash_ads", false))
        // The listener's actual passthrough enters Main; no helper/newIntent/save calls in hosts.
        val main = Robolectric.buildActivity(Main::class.java, Intent(osIntent).setClass(app, Main::class.java)).create()
        screens.add(main)
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        idle(); assertNull(shadowOf(main.get()).nextStartedActivity)
        main.start().resume(); idle()
        val target = checkNotNull(shadowOf(main.get()).nextStartedActivity)
        assertEquals("Final feature must retain the ordinary Main back stack", 0, target.flags)
        assertNotNull(kit.runtime.entries.pending(entry.token))
        main.pause()
        val feature = feature(target)
        assertEquals(listOf(entry to "notes"), feature.get().shown)
        assertNull(kit.runtime.entries.pending(entry.token))
        assertNotNull(kit.runtime.store.snapshot("core.entries").string("consumed:${entry.token}"))
    }

    @Test fun automaticNewIntentLatestWinsAndOrdinaryDeliveryClearsSelectionWithoutDroppingBacklog() {
        val kit = install(); kit.runtime.signal(RetentionSignal.ProcessForeground)
        val screen = feature()
        val old = entry(); val latest = entry("guide")
        screen.newIntent(intent(old)); screen.newIntent(intent(latest)); idle()
        assertEquals(listOf(latest to "guide"), screen.get().shown)
        assertNotNull(kit.runtime.entries.pending(old.token))
        kit.runtime.signal(RetentionSignal.HostUiChanged("dialog", true))
        val blocked = entry(); screen.newIntent(intent(blocked)); idle()
        screen.newIntent(Intent()); kit.runtime.signal(RetentionSignal.HostUiChanged("dialog", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, screen.get().shown.size)
        assertNotNull(kit.runtime.entries.pending(blocked.token))
    }

    @Test fun materializedSelectionSurvivesRecreationAndRetiredAliasDoesNotRewriteEnvelope() {
        val kit = install(options(resolve = { if (it == "old_notes") "notes" else it })); kit.runtime.signal(RetentionSignal.ProcessForeground)
        kit.runtime.signal(RetentionSignal.HostUiChanged("dialog", true))
        val screen = feature(intent(entry("old_notes", RetentionEntryMode.REUSABLE)))
        val materialized = (RetentionEntryCodec.read(screen.get().intent) as RetentionEntryDecodeResult.Valid).entry
        screen.recreate(); idle()
        assertEquals(listOf(materialized), kit.runtime.entries.pending())
        kit.runtime.signal(RetentionSignal.HostUiChanged("dialog", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(listOf(materialized to "notes"), screen.get().shown)
        assertEquals("old_notes", materialized.destination)
    }

    @Test fun resolverReentrantDeliveryCannotConsumeOrRenderStaleSelection() {
        lateinit var screen: ActivityController<Feature>
        val latest = entry("guide")
        var redirected = false
        val kit = install(options(resolve = {
            if (!redirected) { redirected = true; screen.newIntent(intent(latest)) }
            it
        }))
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        screen = feature()
        val first = entry(); screen.newIntent(intent(first)); idle()
        assertEquals(listOf(latest to "guide"), screen.get().shown)
        assertNotNull(kit.runtime.entries.pending(first.token))
    }

    @Test fun duplicateInstallAndRuntimeReplacementDoNotDuplicateFeatureCallbacks() {
        val original = options()
        val kit = install(original)
        assertTrue((RetentionSuite.install(app, original) as RetentionKitInstallResult.Installed).reused)
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        val screen = feature(intent(entry()))
        assertEquals(1, screen.get().shown.size)
        RetentionRuntime.uninstallForTests()
        val replacement = install(); replacement.runtime.signal(RetentionSignal.ProcessForeground)
        val next = feature(intent(entry()))
        assertEquals(1, next.get().shown.size)
        assertNotSame(kit, replacement)
    }

    @Test fun permissionIsExplicitAndBlockedRequestsDoNotLaunchOrClearForeignOwner() {
        val kit = install(); kit.runtime.signal(RetentionSignal.ProcessForeground)
        val screen = feature()
        assertNull(shadowOf(screen.get()).lastRequestedPermission)
        kit.runtime.signal(RetentionSignal.ExternalTransitionStarted("foreign", "test", 10_000))
        val result = checkNotNull(RetentionSuite.get()).requestNotifications(screen.get())
        assertTrue(result is RetentionPermissionResult.Unavailable)
        assertTrue(kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Blocked)
        kit.runtime.signal(RetentionSignal.ExternalTransitionFinished("foreign"))
        assertTrue(kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Allowed)
    }
    @Test fun permissionResultAndTransparentSettingsReturnReleaseOnlyTheirOwnBoundedScope() {
        val kit = install(); kit.runtime.signal(RetentionSignal.ProcessForeground)
        val screen = feature()
        val suite = checkNotNull(RetentionSuite.get())
        assertEquals(RetentionPermissionResult.Scheduled, suite.requestNotifications(screen.get()))
        val request = checkNotNull(shadowOf(screen.get()).lastRequestedPermission)
        assertArrayEquals(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        assertTrue(kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Blocked)
        screen.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        idle()
        assertTrue(kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Allowed)
        assertEquals(RetentionPermissionResult.Scheduled, suite.openNotificationSettings(screen.get()))
        val settings = checkNotNull(shadowOf(screen.get()).nextStartedActivity)
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(app.packageName, settings.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        // A transparent settings/permission return need not generate ProcessForeground.
        screen.pause().resume(); idle()
        assertTrue(kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Allowed)
    }

    @Test fun permissionStartedSubscriberCancellationPreventsPlatformLaunch() {
        val kit = install(); kit.runtime.signal(RetentionSignal.ProcessForeground)
        val screen = feature()
        kit.runtime.subscribe("cancel.permission") { signal ->
            if (signal is RetentionSignal.ExternalTransitionStarted) kit.runtime.signal(RetentionSignal.HostUiChanged("foreign.modal", true))
        }
        checkNotNull(RetentionSuite.get()).requestNotifications(screen.get())
        assertNull(shadowOf(screen.get()).lastRequestedPermission)
        kit.runtime.signal(RetentionSignal.HostUiChanged("foreign.modal", false))
        assertTrue(kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Allowed)
    }

    @Test fun callbackFailureConsumesOnlyOnceAndReentryKeepsTheNewSelection() {
        val kit = install(); kit.runtime.signal(RetentionSignal.ProcessForeground)
        val screen = feature()
        val latest = entry("guide")
        screen.get().callback = {
            screen.get().callback = null
            screen.newIntent(intent(latest))
            error("Host rendering failed")
        }
        val first = entry(); screen.newIntent(intent(first)); idle()
        assertEquals(listOf(first to "notes", latest to "guide"), screen.get().shown)
        screen.pause().resume(); idle()
        assertEquals(2, screen.get().shown.size)
        assertTrue(kit.runtime.entries.pending().isEmpty())
    }

    @Test fun latePermissionResultCannotCloseANewerSettingsTransition() {
        val kit = install(); kit.runtime.signal(RetentionSignal.ProcessForeground)
        val screen = feature()
        val suite = checkNotNull(RetentionSuite.get())
        suite.requestNotifications(screen.get())
        val request = checkNotNull(shadowOf(screen.get()).lastRequestedPermission)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        assertEquals(RetentionPermissionResult.Scheduled, suite.openNotificationSettings(screen.get()))
        screen.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        idle()
        assertTrue("Late permission callback cannot clear the newer settings owner", kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Blocked)
        screen.pause().resume(); idle()
        assertTrue(kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Allowed)
    }

    @Test fun setupAbortOnlyConsumesOwnedSplashSelectionsAndNeverTheBacklog() {
        val kit = install(options(customize = { it.copy(initialUserState = RetentionUserState()) }))
        val backlog = entry("guide")
        kit.runtime.entries.stage(backlog)
        val selected = entry()
        val suite = checkNotNull(RetentionSuite.get())
        suite.captureSplash(intent(selected))
        assertEquals(setOf(backlog.token, selected.token), kit.runtime.entries.pending().map { it.token }.toSet())
        suite.onOutcome(app, OnboardingOutcome.Aborted(null))
        assertNull(kit.runtime.entries.pending(selected.token))
        assertNotNull(kit.runtime.entries.pending(backlog.token))
    }

}
