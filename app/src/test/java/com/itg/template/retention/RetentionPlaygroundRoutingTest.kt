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
        val earlier = entry("saved_items")
        val latest = entry("guide")
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
        val burst = listOf("saved_items", "text_tools", "guide", "notes", "guide", "saved_items", "text_tools", "notes").map(::entry)
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

    @Test fun alreadyStagedRetiredDestinationKeepsItsTokenAndOpensTheCanonicalFeature() {
        // Historical token already persisted by the previous installation; do not rewrite it.
        val legacy = RetentionEntry(RetentionEntrySource.WIDGET, "saved_phrases", "open_saved")
        assertTrue(kit.runtime.entries.stage(legacy) is RetentionEntryAcceptance.Accepted)
        deliver(legacy)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RetentionExampleContent.features(controller.get()).single { it.id == "saved_items" }.label, title())
        assertConsumed(legacy)
        assertEquals("saved_phrases", (RetentionEntryCodec.read(controller.get().intent) as RetentionEntryDecodeResult.Valid).entry.destination)
        assertTrue(kit.runtime.entries.pending().isEmpty())
    }

    @Test fun noteSaveEditAndSavedCopyAreRealUiOperationsThatSurviveRecreation() {
        val activity = controller.get()
        val input = activity.findViewById<android.widget.EditText>(R.id.rk_note_input)
        input.setText("Prepare the workshop")
        activity.findViewById<android.widget.Button>(R.id.rk_note_save).performClick()
        assertEquals("Prepare the workshop", ExampleDataStore(activity).notes().single().text)
        activity.findViewById<android.widget.Button>(R.id.rk_item_save).performClick()
        activity.findViewById<android.widget.EditText>(R.id.rk_note_input).setText("Prepare the workshop tomorrow")
        activity.findViewById<android.widget.Button>(R.id.rk_note_save).performClick()
        controller.recreate().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Prepare the workshop tomorrow", controller.get().findViewById<android.widget.EditText>(R.id.rk_note_input).text.toString())
        assertEquals("Prepare the workshop", ExampleDataStore(controller.get()).savedItems().single().text)
    }

    @Test fun newerIntentGetsItsOwnBoundedRetryAfterOlderIntentExhaustedRetries() {
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", true))
        val earlier = entry("saved_items")
        deliver(earlier)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        val latest = entry("guide")
        deliver(latest)
        shadowOf(Looper.getMainLooper()).idle()
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertConsumed(latest)
        assertEquals(RetentionExampleContent.features(controller.get()).single { it.id == latest.destination }.label, title())
        assertNotNull(kit.runtime.entries.pending(earlier.token))
    }

    @Test fun ordinaryNewIntentDoesNotReviveThePreviousBlockedEntry() {
        assertNewDeliveryClearsSelection(Intent())
    }

    @Test fun malformedNewIntentDoesNotReviveThePreviousBlockedEntry() {
        assertNewDeliveryClearsSelection(Intent().putExtra(RetentionEntryCodec.EXTRA_ENTRY, "{malformed"))
    }

    private fun assertNewDeliveryClearsSelection(intent: Intent) {
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", true))
        val previous = entry("guide")
        val originalTitle = title()
        deliver(previous)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(kit.runtime.entries.pending(previous.token))
        controller.newIntent(intent)
        shadowOf(Looper.getMainLooper()).idle()
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals("A new ordinary/rejected delivery cannot select the previous feature", originalTitle, title())
        assertNotNull("The old ledger entry stays unconsumed and inert", kit.runtime.entries.pending(previous.token))
        controller.recreate().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(originalTitle, title())
        assertNotNull(kit.runtime.entries.pending(previous.token))
    }

    @Test fun blockedMaterializedEntryKeepsExactlyItsSavedTokenAcrossRecreation() {
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", true))
        val template = entry("guide").copy(mode = RetentionEntryMode.REUSABLE)
        deliver(template)
        shadowOf(Looper.getMainLooper()).idle()
        val materialized = (RetentionEntryCodec.read(controller.get().intent) as RetentionEntryDecodeResult.Valid).entry
        assertEquals(RetentionEntryMode.ONCE, materialized.mode)
        assertNotEquals(template.token, materialized.token)
        controller.recreate().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(materialized.token), kit.runtime.entries.pending().map { it.token }.toSet())
        kit.runtime.signal(RetentionSignal.HostUiChanged("test.dialog", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertConsumed(materialized)
        assertEquals(RetentionExampleContent.features(controller.get()).single { it.id == "guide" }.label, title())
    }
}
