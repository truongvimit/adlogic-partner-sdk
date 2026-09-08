package io.onboardkit.ui.language

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.RemoteFlags
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
    fun `preselected language never schedules a hint`() {
        launch(language = LanguageConfig(defaultCode = "en-US"))
        main.idleFor(Duration.ofSeconds(10))
        assertNull(adapter.hintCode)
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
    ) {
        OnboardingSdk.configure(onboardKitConfig {
            this.language = language.copy(
                languages = listOf(
                    ObLanguages.find("en-US")!!, ObLanguages.find("es")!!
                )
            )
        }.getOrThrow()).getOrThrow()
        OnboardingSdk.remoteOrNull()!!.applySnapshot(flags.copy(enableAllAds = false))
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
