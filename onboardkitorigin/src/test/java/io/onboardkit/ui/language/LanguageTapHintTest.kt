package io.onboardkit.ui.language

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.RemoteFlags
import io.onboardkit.remote.OnboardingSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
// Keep this no-provider install in its own sandbox; SDK install retains the first process provider.
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.ui.language"])
@LooperMode(LooperMode.Mode.PAUSED)
class LanguageTapHintTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private var controller: ActivityController<ObLanguageActivity>? = null
    private lateinit var list: RecyclerView
    private val adapter get() = list.adapter as LanguageAdapter

    @Before
    fun install() {
        OnboardingSdk.install(app) { trackkitAutoTracking(false) }
    }

    @After
    fun destroy() {
        controller?.pause()?.stop()?.destroy()
        main.idle()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    @Test
    fun `remote Done action uses primary color and stays dim until language selection`() {
        launch(settings = """{"lfo":{"confirm_button":{"style":"TEXT"}},"onboarding":{"primary_color":"#1E88E5"}}""")
        val activity = controller!!.get()
        val action = activity.findViewById<View>(R.id.ob_language_confirm)
        val text = activity.findViewById<TextView>(R.id.ob_language_confirm_text)
        assertEquals(View.VISIBLE, text.visibility)
        assertEquals("Done", text.text.toString())
        assertEquals(android.graphics.Color.parseColor("#1E88E5"), text.currentTextColor)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_language_confirm_icon).visibility)
        assertEquals(View.VISIBLE, action.visibility)
        assertEquals(0.5f, action.alpha)
        action.performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        row(0).itemView.performClick()
        assertEquals(1f, action.alpha)
        action.performClick()
        main.idle()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `Done respects hidden before selection and becomes visible after selection`() {
        launch(settings = """{"lfo":{"confirm_button":{"style":"TEXT","visible_before_selection":false}}}""")
        val action = controller!!.get().findViewById<View>(R.id.ob_language_confirm)
        assertEquals(View.GONE, action.visibility)
        row(0).itemView.performClick()
        assertEquals(View.VISIBLE, action.visibility)
        assertEquals(1f, action.alpha)
    }

    @Test
    fun `default action remains a dim check icon with primary color`() {
        launch(settings = """{"onboarding":{"primary_color":"#1E88E5"}}""")
        val activity = controller!!.get()
        val icon = activity.findViewById<ImageView>(R.id.ob_language_confirm_icon)
        assertEquals(View.VISIBLE, icon.visibility)
        assertEquals(android.graphics.Color.parseColor("#1E88E5"), icon.imageTintList!!.defaultColor)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_language_confirm_text).visibility)
        assertEquals(0.5f, activity.findViewById<View>(R.id.ob_language_confirm).alpha)
    }

    @Test
    fun `first open does not treat configured default as a reselect`() {
        launch(language = LanguageConfig(defaultCode = "en-US"))
        row(0).itemView.performClick()
        assertEquals("en-US", adapter.selectedCode)
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing != true)

        row(1).itemView.performClick()
        assertEquals("es", adapter.selectedCode)
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing != true)
        row(0).itemView.performClick()
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing != true)
        row(1).itemView.performClick()
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `default hand stays hidden until three seconds after entry`() {
        launch()
        main.idleFor(Duration.ofSeconds(2))
        assertNull(adapter.hintCode)
        main.idleFor(Duration.ofSeconds(1))
        assertNotNull(adapter.hintCode)
        val holder = row(adapter.currentList.indexOfFirst { it.code == adapter.hintCode })
        assertEquals(
            View.VISIBLE,
            holder.itemView.findViewById<View>(R.id.ob_language_hint).visibility
        )
    }

    @Test
    fun `custom delay takes effect`() {
        launch(flags = RemoteFlags(languageTapHintDelaySec = 7))
        main.idleFor(Duration.ofSeconds(3))
        assertNull(adapter.hintCode)
        main.idleFor(Duration.ofSeconds(4))
        assertNotNull(adapter.hintCode)
    }

    @Test
    fun `zero delay shows immediately`() {
        launch(flags = RemoteFlags(languageTapHintDelaySec = 0))
        main.idle()
        assertNotNull(adapter.hintCode)
    }

    @Test
    fun `build time disable ignores zero delay`() {
        launch(
            language = LanguageConfig(tapHintEnabled = false),
            flags = RemoteFlags(languageTapHintDelaySec = 0)
        )
        main.idleFor(Duration.ofSeconds(10))
        assertNull(adapter.hintCode)
    }

    @Test
    fun `remote disable ignores zero delay`() {
        launch(flags = RemoteFlags(showLanguageTapHint = false, languageTapHintDelaySec = 0))
        main.idleFor(Duration.ofSeconds(10))
        assertNull(adapter.hintCode)
    }

    @Test
    fun `selection before deadline prevents late hint`() {
        launch()
        main.idleFor(Duration.ofSeconds(1))
        row(0).itemView.performClick()
        main.idleFor(Duration.ofSeconds(10))
        assertNull(adapter.hintCode)
        assertNotNull(adapter.selectedCode)
    }

    @Test
    fun `selection hides a visible hand and updates the row selection`() {
        launch()
        main.idleFor(Duration.ofSeconds(3))
        val index = adapter.currentList.indexOfFirst { it.code == adapter.hintCode }
        val holder = row(index)
        holder.itemView.performClick()
        adapter.onBindViewHolder(holder, index)
        assertTrue(holder.itemView.isSelected)
        assertEquals(
            View.GONE,
            holder.itemView.findViewById<View>(R.id.ob_language_hint).visibility
        )
    }

    @Test
    fun `destroying screen cancels the pending hand`() {
        launch()
        controller!!.pause().stop().destroy()
        controller = null
        main.idleFor(Duration.ofSeconds(10))
        assertNull(adapter.hintCode)
    }

    @Test
    fun `configured default does not suppress the device language hint`() {
        launch(language = LanguageConfig(defaultCode = "en-US"))
        main.idleFor(Duration.ofSeconds(10))
        assertNotNull(adapter.hintCode)
    }

    @Test
    fun `first-open LFO starts with no selected row even when code default exists`() {
        launch(language = LanguageConfig(defaultCode = "en-US"))
        assertNull("A configured default is not a user selection on LFO1", adapter.selectedCode)
        adapter.currentList.forEachIndexed { index, language ->
            val holder = row(index)
            assertEquals("row ${language.code}", false, holder.itemView.isSelected)
        }
    }

    @Test
    fun `settings never schedules a hint`() {
        launch(mode = LanguageScreenMode.SETTINGS)
        main.idleFor(Duration.ofSeconds(10))
        assertNull(adapter.hintCode)
    }

    private fun launch(
        language: LanguageConfig = LanguageConfig(),
        flags: RemoteFlags = RemoteFlags(),
        mode: LanguageScreenMode = LanguageScreenMode.FIRST_OPEN,
        settings: String? = null,
    ) {
        OnboardingSettings.document.acceptSuccessfulFetch(settings)
        OnboardingSdk.configure(onboardKitConfig {
            this.language = language.copy(
                languages = listOf(
                    ObLanguages.find("en-US")!!, ObLanguages.find("es")!!
                )
            )
        }.getOrThrow()).getOrThrow()
        OnboardingSdk.remoteOrNull()!!.applySnapshot(flags)
        controller = Robolectric.buildActivity(
            ObLanguageActivity::class.java,
            ObLanguageActivity.intentFor(app, mode)
        ).setup()
        list = controller!!.get().findViewById(R.id.ob_language_list)
        main.idle()
    }

    private fun row(index: Int): LanguageAdapter.RowHolder =
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, index) }
}
