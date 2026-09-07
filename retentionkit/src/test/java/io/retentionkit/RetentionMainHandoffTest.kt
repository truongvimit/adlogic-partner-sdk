package io.retentionkit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import io.retentionkit.feedback.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionMainHandoffTest {
    class Main : Activity()
    class Feature : Activity()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val handles = mutableListOf<RetentionMainHandoff>()
    @After fun after() { handles.forEach { it.close() }; RetentionRuntime.uninstallForTests() }
    private fun install(host: RetentionUiHost = RetentionUiHost.NONE, feedback: FeedbackOptions? = null): RetentionKit =
        (RetentionKit.install(app, RetentionKitOptions(
            featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", android.R.drawable.ic_menu_edit)) },
            router = RetentionSplashRouter(Main::class.java), uiHost = host,
            notifications = null, widgets = null, review = null, feedback = feedback,
            initialUserState = RetentionUserState(setupCompleted = true, entitlement = RetentionEntitlement.NON_SUBSCRIBER),
            store = SharedPreferencesRetentionStore(app, "main_${UUID.randomUUID()}"),
        )) as RetentionKitInstallResult.Installed).kit
    private fun envelope(destination: String = "notes", mode: RetentionEntryMode = RetentionEntryMode.ONCE): Intent =
        RetentionEntryCodec.write(Intent(app, Main::class.java), RetentionEntry(RetentionEntrySource.WIDGET, destination, "open", mode = mode))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun bind(kit: RetentionKit, activity: Activity, state: Bundle? = null, router: RetentionRouter = RetentionRouter { context, _ -> Intent(context, Feature::class.java) }) =
        kit.mainHandoff(activity, state, router).also(handles::add)

    @Test fun mainActuallyResumesBeforeExactFeatureForwardAndFinalFeatureConsumesOnce() {
        val kit = install()
        val activity = Robolectric.buildActivity(Main::class.java, envelope()).create()
        val helper = bind(kit, activity.get())
        val token = (RetentionEntryCodec.read(activity.get().intent) as RetentionEntryDecodeResult.Valid).entry.token
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        idle()
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        activity.start().resume(); idle()
        val target = requireNotNull(shadowOf(activity.get()).nextStartedActivity)
        assertEquals(Feature::class.java.name, target.component!!.className)
        assertNotNull(kit.runtime.entries.pending(token)) // Main does not consume.
        activity.pause()
        val final = Robolectric.buildActivity(Feature::class.java, target).setup()
        assertEquals(token, (kit.capture(target) as RetentionEntryAcceptance.Accepted).entry.token)
        assertTrue(kit.dispatchPending(token) is RetentionDispatchResult.Navigate)
        assertTrue(kit.dispatchPending(token) is RetentionDispatchResult.Unavailable)
        assertFalse(helper.hasPendingEntry)
        final.pause().stop().destroy(); activity.stop().destroy()
    }

    @Test fun restoredReusableSelectionDoesNotMaterializeTwiceOrOpenOldBacklog() {
        val kit = install()
        kit.runtime.entries.stage(RetentionEntry(RetentionEntrySource.DAILY, "old", "old"))
        val original = envelope(mode = RetentionEntryMode.REUSABLE)
        val first = Robolectric.buildActivity(Main::class.java, Intent(original)).create()
        val saved = Bundle()
        bind(kit, first.get()).onSaveInstanceState(saved)
        first.destroy()
        val restored = Robolectric.buildActivity(Main::class.java, Intent(original)).create(saved)
        bind(kit, restored.get(), saved)
        assertEquals(2, kit.runtime.entries.pending().size) // one unrelated + one selected tap
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        restored.start().resume(); idle()
        val target = requireNotNull(shadowOf(restored.get()).nextStartedActivity)
        assertEquals("notes", (RetentionEntryCodec.read(target) as RetentionEntryDecodeResult.Valid).entry.destination)
        restored.pause().stop().destroy()
        val ordinary = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java)).create()
        bind(kit, ordinary.get()); ordinary.start().resume(); idle()
        assertNull(shadowOf(ordinary.get()).nextStartedActivity)
        ordinary.pause().stop().destroy()
    }

    @Test fun rapidTapSupersedesEarlierSelectionAndFailedRouterRemainsRetryableIncludingAlias() {
        val kit = install()
        val activity = Robolectric.buildActivity(Main::class.java, envelope()).create()
        var fail = true
        val helper = bind(kit, activity.get(), router = RetentionRouter { context, entry ->
            if (fail) null else Intent(context, Feature::class.java).putExtra("display_id", if (entry.destination == "retired_notes") "notes" else entry.destination)
        })
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        activity.start().resume(); idle()
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        helper.onNewIntent(envelope("saved_items"))
        helper.onNewIntent(envelope("retired_notes"))
        fail = false
        idle()
        val target = requireNotNull(shadowOf(activity.get()).nextStartedActivity)
        assertEquals("retired_notes", (RetentionEntryCodec.read(target) as RetentionEntryDecodeResult.Valid).entry.destination)
        assertEquals("notes", target.getStringExtra("display_id"))
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        assertEquals(3, kit.runtime.entries.pending().size)
        activity.pause().stop().destroy()
    }

    @Test fun delayedHostReleaseClosesOnlyOwnedResourceAndDestroyClosesDeferredHandles() {
        val open = mutableSetOf<Int>(); var next = 0
        val host = object : RetentionUiHost {
            override fun canPresent(activity: Activity) = false
            override fun canPresentEntry(activity: Activity) = true
            override fun onLeaseAcquired(owner: String, token: String, durationMillis: Long): AutoCloseable {
                val id = next++; open.add(id); return AutoCloseable { assertTrue(open.remove(id)) }
            }
        }
        val kit = install(host)
        val activity = Robolectric.buildActivity(Main::class.java, envelope()).create()
        val helper = bind(kit, activity.get(), router = RetentionRouter { _, _ -> null })
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        activity.start().resume(); idle()
        activity.pause(); activity.resume() // Old close is deferred through the lifecycle dispatch.
        helper.onNewIntent(envelope("saved_items"))
        idle()
        assertEquals(1, open.size)
        activity.pause().stop().destroy() // Must flush detached handles before removing callbacks.
        assertTrue(open.isEmpty())
    }

    @Test fun feedbackEntryCanOpenFromMainWhileNormalPromptCannotAndWaitsForExternalReturn() {
        val host = object : RetentionUiHost {
            override fun canPresent(activity: Activity) = activity !is Main
            override fun canPresentEntry(activity: Activity) = true
        }
        val launches = mutableListOf<Intent>()
        val kit = install(host, FeedbackOptions(shortcutEnabled = false, launcher = FeedbackLauncher { _, intent -> launches.add(intent); true }))
        val activity = Robolectric.buildActivity(Main::class.java, envelope(RetentionFeedbackModule.DESTINATION)).create()
        bind(kit, activity.get())
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        kit.runtime.signal(RetentionSignal.ExternalTransitionStarted("source.return", "feedback"))
        activity.start().resume(); idle()
        assertTrue(launches.isEmpty())
        kit.feedback!!.show(); idle()
        assertTrue(launches.isEmpty())
        kit.runtime.signal(RetentionSignal.ExternalTransitionFinished("source.return"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(1, launches.size)
        assertEquals(RetentionFeedbackActivity::class.java.name, launches.single().component!!.className)
        assertTrue(kit.runtime.entries.pending().isEmpty())
        activity.pause().stop().destroy()
    }
}
