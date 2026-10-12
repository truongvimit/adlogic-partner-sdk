package io.onboardkit.ui.language

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeStatus
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() keeps the first provider, and this one records reloads.
@Config(sdk = [34], application = Application::class,
    instrumentedPackages = ["io.onboardkit.ui.language", "io.onboardkit.ui.base"])
@LooperMode(LooperMode.Mode.PAUSED)
class LanguageReloadTest {
    private companion object {
        val reloads = mutableListOf<AdPlacement>()
        val listeners = mutableMapOf<AdPlacement, AdEventListener>()
        val binds = mutableListOf<AdPlacement>()
        var holdSecondFill = false
        var refuseSecondFill = false
        var secondStatus = NativeStatus.IDLE
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private var controller: ActivityController<ObLanguageActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val list get() = activity.findViewById<RecyclerView>(R.id.ob_language_list)
    private val adapter get() = list.adapter as LanguageAdapter
    private val swapTimeoutMs get() = OnboardingSettings.number("lfo.native2.swap_wait_timeout_ms")

    @Before fun setup() {
        reloads.clear()
        listeners.clear()
        binds.clear()
        holdSecondFill = false
        refuseSecondFill = false
        secondStatus = NativeStatus.IDLE
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSdk.install(app) {
            adProvider = object : FakeAdProvider() {
                override fun bindNative(
                    activity: ComponentActivity,
                    request: NativeAdRequest,
                    container: FrameLayout,
                    listener: AdEventListener,
                ): Boolean {
                    listeners[request.placement] = listener
                    binds += request.placement
                    if (request.placement == AdPlacement.Language2 && refuseSecondFill) {
                        listener.onFailedToLoad()
                        return false
                    }
                    return when (request.placement) {
                        AdPlacement.Language1 -> true
                        AdPlacement.Language2 -> !holdSecondFill
                        else -> false
                    }
                }

                override fun nativeStatus(placement: AdPlacement) =
                    if (placement == AdPlacement.Language2) secondStatus else NativeStatus.IDLE

                override fun reloadNative(placement: AdPlacement): Boolean {
                    reloads += placement
                    return true
                }
            }
            trackkitAutoTracking(false)
        }
        ConsentCenter.setHostConsent(true, false)
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        main.idle()
        ConsentCenter.clearHostConsent()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private fun launch(mode: LanguageScreenMode = LanguageScreenMode.FIRST_OPEN, settings: String? = null) {
        OnboardingSettings.document.acceptSuccessfulFetch(settings)
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            language = LanguageConfig(languages = listOf("en-US", "es", "fr").map { requireNotNull(ObLanguages.find(it)) })
            ads = AdsConfig(languageNative = NativeAdUnit(listOf("lfo1-high", "lfo1-all")),
                languageDupNative = NativeAdUnit(listOf("lfo2-high", "lfo2-all")))
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(ObLanguageActivity::class.java,
            ObLanguageActivity.intentFor(app, mode)).setup()
        main.idle()
    }

    private fun tap(code: String) {
        val index = adapter.currentList.indexOfFirst { it.code == code }
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, index) }.itemView.performClick()
        main.idle()
    }

    private fun confirmShowing() = ShadowDialog.getLatestDialog()?.isShowing == true

    private fun secondSlotShown() = activity.findViewById<View>(R.id.ob_ad_block_2).visibility == View.VISIBLE

    @Test fun `with LFO2 on the first pick swaps LFO2 in instead of a reload`() {
        launch()
        tap("es")
        assertTrue(secondSlotShown())
        assertEquals(emptyList<AdPlacement>(), reloads)
        assertFalse(confirmShowing())
    }

    @Test fun `switching language after the swap reloads LFO2`() {
        launch()
        tap("es")
        tap("fr")
        assertEquals(listOf(AdPlacement.Language2), reloads)
        assertFalse(confirmShowing())
        tap("es")
        assertEquals(listOf(AdPlacement.Language2, AdPlacement.Language2), reloads)
    }

    @Test fun `no reload while the LFO2 swap is still pending`() {
        holdSecondFill = true
        launch()
        tap("es")
        assertFalse(secondSlotShown())
        tap("fr")
        assertEquals(emptyList<AdPlacement>(), reloads)

        requireNotNull(listeners[AdPlacement.Language2]).onLoaded()
        main.idle()
        assertTrue(secondSlotShown())
        tap("es")
        assertEquals(listOf(AdPlacement.Language2), reloads)
    }

    /** The first pick started an LFO2 swap that never bound; its timeout reloaded LFO1 once. */
    private fun timedOutSwap() {
        holdSecondFill = true
        launch()
        tap("es")
        main.idleFor(swapTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
        assertFalse(secondSlotShown())
        assertEquals(listOf(AdPlacement.Language1), reloads)
    }

    @Test fun `an LFO2 swap that times out on the first pick reloads LFO1 once, at the timeout`() {
        holdSecondFill = true
        launch()
        val tappedAt = SystemClock.uptimeMillis()
        tap("es")
        assertEquals(emptyList<AdPlacement>(), reloads)
        main.idleFor(tappedAt + swapTimeoutMs - 100 - SystemClock.uptimeMillis(), TimeUnit.MILLISECONDS)
        assertEquals("Nothing before the timeout", emptyList<AdPlacement>(), reloads)
        main.idleFor(200, TimeUnit.MILLISECONDS)
        assertFalse(secondSlotShown())
        assertEquals(listOf(AdPlacement.Language1), reloads)
        main.idleFor(swapTimeoutMs * 2, TimeUnit.MILLISECONDS)
        assertEquals("Exactly one", listOf(AdPlacement.Language1), reloads)
    }

    @Test fun `an LFO2 refused on the first pick reloads LFO1 once, right then`() {
        refuseSecondFill = true
        launch()
        tap("es")
        assertFalse(secondSlotShown())
        assertEquals(listOf(AdPlacement.Language1), reloads)
        main.idleFor(swapTimeoutMs * 2, TimeUnit.MILLISECONDS)
        assertEquals("The swap timeout went with the swap", listOf(AdPlacement.Language1), reloads)
    }

    @Test fun `an LFO2 that fails while the swap waits reloads LFO1 once, when it fails`() {
        holdSecondFill = true
        launch()
        tap("es")
        assertEquals(emptyList<AdPlacement>(), reloads)
        requireNotNull(listeners[AdPlacement.Language2]).onFailedToLoad()
        main.idle()
        assertFalse(secondSlotShown())
        assertEquals(listOf(AdPlacement.Language1), reloads)
        main.idleFor(swapTimeoutMs * 2, TimeUnit.MILLISECONDS)
        assertEquals(listOf(AdPlacement.Language1), reloads)
    }

    @Test fun `a swap that times out under the confirm modal reloads nothing`() {
        holdSecondFill = true
        launch()
        tap("es")
        tap("es")
        assertTrue(confirmShowing())
        main.idleFor(swapTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
        assertTrue(confirmShowing())
        assertFalse(secondSlotShown())
        assertEquals(emptyList<AdPlacement>(), reloads)
    }

    @Test fun `an LFO2 refused under the confirm modal reloads nothing`() {
        holdSecondFill = true
        launch()
        tap("es")
        tap("es")
        assertTrue(confirmShowing())
        requireNotNull(listeners[AdPlacement.Language2]).onFailedToLoad()
        main.idleFor(swapTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
        assertFalse(secondSlotShown())
        assertEquals(emptyList<AdPlacement>(), reloads)
    }

    @Test fun `a swap that ends after the LFO started its exit reloads nothing`() {
        holdSecondFill = true
        launch()
        tap("es")
        activity.findViewById<View>(R.id.ob_language_confirm).performClick()
        main.idle()
        requireNotNull(listeners[AdPlacement.Language2]).onFailedToLoad()
        main.idleFor(swapTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
        assertEquals(emptyList<AdPlacement>(), reloads)
    }

    @Test fun `after a timed-out swap a late LFO2 fill swaps in on the next pick instead of a reload`() {
        timedOutSwap()
        secondStatus = NativeStatus.READY
        holdSecondFill = false
        tap("fr")
        assertTrue(secondSlotShown())
        assertFalse(confirmShowing())
        assertEquals("The pick swaps, it asks for nothing", listOf(AdPlacement.Language1), reloads)
        secondStatus = NativeStatus.IDLE
        tap("es")
        assertEquals("The next pick reloads LFO2", listOf(AdPlacement.Language1, AdPlacement.Language2), reloads)
    }

    @Test fun `after a timed-out swap a pick with LFO2 not ready reloads LFO1`() {
        timedOutSwap()
        val swapBinds = binds.count { it == AdPlacement.Language2 }
        secondStatus = NativeStatus.LOADING
        tap("fr")
        assertEquals(List(2) { AdPlacement.Language1 }, reloads)
        secondStatus = NativeStatus.FAILED
        tap("es")
        assertEquals(List(3) { AdPlacement.Language1 }, reloads)
        assertFalse(secondSlotShown())
        assertFalse(confirmShowing())
        assertEquals("No second swap attempt", swapBinds, binds.count { it == AdPlacement.Language2 })
    }

    @Test fun `a parked LFO2 that refuses to bind on the retry falls back to one LFO1 reload`() {
        timedOutSwap()
        secondStatus = NativeStatus.READY
        refuseSecondFill = true
        tap("fr")
        assertFalse(secondSlotShown())
        assertEquals(List(2) { AdPlacement.Language1 }, reloads)
        main.idleFor(swapTimeoutMs * 2, TimeUnit.MILLISECONDS)
        assertEquals(List(2) { AdPlacement.Language1 }, reloads)
    }

    @Test fun `tapping the selected language raises the confirm modal and never reloads`() {
        launch()
        tap("es")
        tap("es")
        assertTrue(confirmShowing())
        assertEquals(emptyList<AdPlacement>(), reloads)
    }

    @Test fun `the switch that reaches show_from_tap raises the modal instead of a reload`() {
        launch()
        tap("es")
        tap("fr")
        tap("es")
        assertFalse(confirmShowing())
        assertEquals(listOf(AdPlacement.Language2, AdPlacement.Language2), reloads)
        tap("fr")
        assertTrue("Tap 4 of the bundled show_from_tap=4", confirmShowing())
        assertEquals(listOf(AdPlacement.Language2, AdPlacement.Language2), reloads)
    }

    @Test fun `a remote show_from_tap moves the last reloading switch`() {
        launch(settings = """{"lfo":{"confirm_dialog":{"show_from_tap":3}}}""")
        tap("es")
        tap("fr")
        tap("es")
        assertTrue(confirmShowing())
        assertEquals(listOf(AdPlacement.Language2), reloads)
    }

    @Test fun `a remote show_from_tap above the default keeps reloading until it is reached`() {
        launch(settings = """{"lfo":{"confirm_dialog":{"show_from_tap":6}}}""")
        tap("es")
        listOf("fr", "es", "fr", "es").forEachIndexed { i, code ->
            tap(code)
            assertFalse("Tap ${i + 2} is below show_from_tap=6", confirmShowing())
            assertEquals(List(i + 1) { AdPlacement.Language2 }, reloads)
        }
        tap("fr")
        assertTrue("Tap 6 raises the modal", confirmShowing())
        assertEquals("Tap 6 does not reload", List(4) { AdPlacement.Language2 }, reloads)
    }

    @Test fun `a show_from_tap raised while the LFO is open applies on the next tap`() {
        launch()
        tap("es")
        tap("fr")
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"confirm_dialog":{"show_from_tap":6}}}""")
        tap("es")
        tap("fr")
        assertFalse("Tap 4 no longer reaches the boundary", confirmShowing())
        tap("es")
        assertFalse(confirmShowing())
        assertEquals(List(4) { AdPlacement.Language2 }, reloads)
        tap("fr")
        assertTrue(confirmShowing())
        assertEquals(List(4) { AdPlacement.Language2 }, reloads)
    }

    @Test fun `a show_from_tap lowered while the LFO is open applies on the next tap`() {
        launch()
        tap("es")
        tap("fr")
        assertEquals(listOf(AdPlacement.Language2), reloads)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"confirm_dialog":{"show_from_tap":3}}}""")
        tap("es")
        assertTrue(confirmShowing())
        assertEquals(listOf(AdPlacement.Language2), reloads)
    }

    @Test fun `with LFO2 off every pick before show_from_tap reloads LFO1, the first included`() {
        launch(settings = """{"lfo":{"native2":{"enabled":false}}}""")
        tap("es")
        assertFalse(secondSlotShown())
        assertEquals(listOf(AdPlacement.Language1), reloads)
        tap("fr")
        tap("es")
        assertFalse(confirmShowing())
        assertEquals(List(3) { AdPlacement.Language1 }, reloads)
        tap("fr")
        assertTrue(confirmShowing())
        assertEquals(List(3) { AdPlacement.Language1 }, reloads)
    }

    @Test fun `a switch that starts the LFO2 swap does not also reload LFO1`() {
        launch(settings = """{"lfo":{"native2":{"enabled":false}}}""")
        tap("es")
        assertEquals(listOf(AdPlacement.Language1), reloads)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        tap("fr")
        assertTrue("LFO2 turned on mid-screen swaps in on this switch", secondSlotShown())
        assertEquals(listOf(AdPlacement.Language1), reloads)
        tap("es")
        assertEquals(listOf(AdPlacement.Language1, AdPlacement.Language2), reloads)
    }

    @Test fun `with the confirm modal off every switch after the LFO2 swap reloads`() {
        launch(settings = """{"lfo":{"confirm_dialog":{"enabled":false}}}""")
        listOf("es", "fr", "es", "fr", "es", "fr").forEach(::tap)
        assertFalse(confirmShowing())
        assertEquals(List(5) { AdPlacement.Language2 }, reloads)
        tap("fr")
        assertEquals("Tapping the selected language is not a switch", 5, reloads.size)
    }

    @Test fun `reselecting the current language never reloads, even with the confirm modal off`() {
        launch(settings = """{"lfo":{"native2":{"enabled":false},"confirm_dialog":{"enabled":false}}}""")
        tap("es")
        assertEquals(listOf(AdPlacement.Language1), reloads)
        repeat(5) { tap("es") }
        assertFalse(confirmShowing())
        assertEquals(listOf(AdPlacement.Language1), reloads)
        tap("fr")
        assertEquals(listOf(AdPlacement.Language1, AdPlacement.Language1), reloads)
    }

    @Test fun `settings mode never reloads`() {
        launch(mode = LanguageScreenMode.SETTINGS)
        listOf("es", "fr", "es").forEach(::tap)
        assertEquals(emptyList<AdPlacement>(), reloads)
    }
}
