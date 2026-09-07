package io.retentionkit.sample

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ProofApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ProofSplashRoutingTest {
    private lateinit var runtime: RetentionRuntime
    private var splash: ActivityController<ProofSplashActivity>? = null
    private var feature: ActivityController<ProofActivity>? = null
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun prepare() {
        runtime = checkNotNull(RetentionRuntime.get())
        runtime.signal(RetentionSignal.SetupCompleted)
        runtime.signal(RetentionSignal.ProcessForeground)
    }

    @After fun cleanup() {
        feature?.pause()?.stop()?.destroy()
        splash?.pause()?.stop()?.destroy()
        RetentionRuntime.uninstallForTests()
    }

    private fun entry(destination: String = "word_count", reusable: Boolean = false) = RetentionEntry(
        RetentionEntrySource.WIDGET, destination, "open", instanceId = "sample-widget",
        mode = if (reusable) RetentionEntryMode.REUSABLE else RetentionEntryMode.ONCE)

    private fun launch(value: RetentionEntry) {
        val intent = checkNotNull(runtime.createEntryIntent(value))
        assertEquals(ProofSplashActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        splash = Robolectric.buildActivity(ProofSplashActivity::class.java, intent).setup().visible()
    }

    @Test fun realSplashForwardsRewrittenWidgetEntryAndOnlyDestinationConsumesIt() {
        val original = entry(reusable = true)
        launch(original)
        val selected = (RetentionEntryCodec.read(splash!!.get().intent) as RetentionEntryDecodeResult.Valid).entry
        assertNotEquals(original.token, selected.token)
        assertEquals(RetentionEntryMode.ONCE, selected.mode)
        assertNotNull(runtime.entries.pending(selected.token))
        assertNull(shadowOf(splash!!.get()).nextStartedActivity)
        main.idleFor(Duration.ofMillis(350))
        val next = checkNotNull(shadowOf(splash!!.get()).nextStartedActivity)
        assertEquals(ProofActivity::class.java.name, next.component?.className)
        assertEquals(selected, (RetentionEntryCodec.read(next) as RetentionEntryDecodeResult.Valid).entry)
        assertNotNull("Splash must not consume a destination it has not opened", runtime.entries.pending(selected.token))
        splash!!.pause().stop().destroy()
        splash = null
        feature = Robolectric.buildActivity(ProofActivity::class.java, next).setup().visible()
        main.idle()
        assertNull(runtime.entries.pending(selected.token))
        assertTrue(feature!!.get().findViewById<TextView>(R.id.rk_proof_status).text.contains("Tool: word_count; pending entry: false"))
    }

    @Test fun rotationPreservesSelectedTokenAndDoesNotMaterializeWidgetAgain() {
        launch(entry(reusable = true))
        val selected = (RetentionEntryCodec.read(splash!!.get().intent) as RetentionEntryDecodeResult.Valid).entry
        val saved = Bundle()
        val originalIntent = Intent(splash!!.get().intent)
        splash!!.saveInstanceState(saved).pause().stop().destroy()
        splash = Robolectric.buildActivity(ProofSplashActivity::class.java, originalIntent)
            .create(saved).start().resume().visible()
        main.idleFor(Duration.ofMillis(350))
        val next = checkNotNull(shadowOf(splash!!.get()).nextStartedActivity)
        assertEquals(selected.token, (RetentionEntryCodec.read(next) as RetentionEntryDecodeResult.Valid).entry.token)
        assertEquals(listOf(selected.token), runtime.entries.pending().map { it.token })
    }

    @Test fun newIntentSelectsLatestEntryAndPausedSplashCannotNavigate() {
        val earlier = entry("uppercase")
        launch(earlier)
        val latest = entry("word_count")
        splash!!.newIntent(runtime.createEntryIntent(latest))
        splash!!.pause()
        main.idleFor(Duration.ofSeconds(1))
        assertNull(shadowOf(splash!!.get()).nextStartedActivity)
        splash!!.resume()
        main.idleFor(Duration.ofMillis(350))
        val next = checkNotNull(shadowOf(splash!!.get()).nextStartedActivity)
        assertEquals(latest.token, (RetentionEntryCodec.read(next) as RetentionEntryDecodeResult.Valid).entry.token)
        assertNotNull(runtime.entries.pending(earlier.token))
        main.idleFor(Duration.ofSeconds(1))
        assertNull(shadowOf(splash!!.get()).nextStartedActivity)
    }
}
