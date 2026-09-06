package com.itg.template.retention

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.itg.template.R
import io.retentionkit.*
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
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RetentionPlaygroundRoutingTest {
    private lateinit var kit: RetentionKit
    private lateinit var controller: ActivityController<RetentionPlaygroundActivity>
    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("retention_example_data_v1", Context.MODE_PRIVATE).edit().clear().commit()
        kit = (RetentionKit.install(app, RetentionKitOptions(
            featureProvider = RetentionFeatureProvider(RetentionExampleContent::features),
            router = RetentionRouter { context, _ -> Intent(context, RetentionPlaygroundActivity::class.java) },
            notifications = null, widgets = null, feedback = null, review = null,
            initialUserState = RetentionUserState(setupCompleted = true),
            store = SharedPreferencesRetentionStore(app, "route_${UUID.randomUUID()}"),
        )) as RetentionKitInstallResult.Installed).kit
        controller = Robolectric.buildActivity(RetentionPlaygroundActivity::class.java)
        controller.get().setTheme(R.style.Theme_Main)
        controller.setup().visible()
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun after() {
        controller.pause().stop().destroy()
        RetentionRuntime.uninstallForTests()
    }
    private fun entry(destination: String) = RetentionEntry(RetentionEntrySource.PINNED, destination, "open_$destination")
    private fun deliver(entry: RetentionEntry) { controller.newIntent(checkNotNull(kit.runtime.createEntryIntent(entry))) }
    private fun title() = controller.get().findViewById<TextView>(R.id.rk_feature_title).text.toString()
    private fun assertConsumed(entry: RetentionEntry) {
        assertNotNull("The actual token must have a durable consumed receipt", kit.runtime.store.snapshot("core.entries").string("consumed:${entry.token}"))
    }

    @Test fun twoNewIntentsBeforeDispatchKeepLatestExplicitDestination() {
        val earlier = entry("saved_phrases")
        val latest = entry("document")
        deliver(earlier)
        deliver(latest)
        shadowOf(Looper.getMainLooper()).idle()
        val expected = RetentionExampleContent.features(controller.get()).single { it.id == latest.destination }.label
        assertEquals("An older pending token must not navigate after the newer explicit Intent", expected, title())
        assertNull(kit.runtime.entries.pending(latest.token))
        assertConsumed(latest)
        assertNotNull("Older unconsumed token must remain inert", kit.runtime.entries.pending(earlier.token))
    }

    @Test fun burstCannotAcknowledgeDefaultTitleOrReplayBacklogOnResumeAndRecreate() {
        val burst = listOf("saved_phrases", "text_tools", "document", "translate", "document", "saved_phrases", "text_tools", "translate").map(::entry)
        burst.forEach(::deliver)
        shadowOf(Looper.getMainLooper()).idle()
        val latest = burst.last()
        assertConsumed(latest)
        assertEquals(RetentionExampleContent.features(controller.get()).single { it.id == latest.destination }.label, title())
        assertEquals(burst.dropLast(1).map { it.token }.toSet(), kit.runtime.entries.pending().map { it.token }.toSet())
        controller.pause().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        controller.recreate().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RetentionExampleContent.features(controller.get()).single { it.id == latest.destination }.label, title())
        assertEquals(7, kit.runtime.entries.pending().size)
    }

    @Test fun newerIntentGetsItsOwnBoundedRetryAfterOlderIntentExhaustedRetries() {
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", true))
        val earlier = entry("saved_phrases")
        deliver(earlier)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        val latest = entry("document")
        deliver(latest)
        shadowOf(Looper.getMainLooper()).idle()
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertConsumed(latest)
        assertEquals(RetentionExampleContent.features(controller.get()).single { it.id == latest.destination }.label, title())
        assertNotNull(kit.runtime.entries.pending(earlier.token))
    }
}
