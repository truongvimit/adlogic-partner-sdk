package io.onboardkit.core.analytics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.onboardkit.core.StepId
import io.trackkit.AdFormat
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the translation from the SDK's internal catalog to the canonical Trackkit taxonomy.
 * `strictValidation` is on so a name or param key that breaks the GA4 grammar fails here rather
 * than being silently dropped by Firebase in production.
 */
class TrackkitPluginTest {

    private lateinit var sink: RecordingSink

    @Before
    fun setUp() {
        Tracker.resetForTesting()
        sink = RecordingSink()
        Tracker.addSink(sink)
        Tracker.install(FakeContext(), TrackerConfig(strictValidation = true, logLevel = 0))
    }

    @After
    fun tearDown() {
        Tracker.resetForTesting()
    }

    @Test
    fun `splash view and complete map to the canonical pair`() {
        TrackkitPlugin.execute(AnalyticsEvent.SplashViewed())
        TrackkitPlugin.execute(AnalyticsEvent.SplashCompleted(1_200))

        assertEquals(listOf("fo_splash_view", "fo_splash_complete"), sink.names())
        assertEquals(1_200L, sink.paramsOf("fo_splash_complete")["dwell_ms"])
    }

    @Test
    fun `language view carries screen index and variant instead of encoding them in the name`() {
        TrackkitPlugin.execute(AnalyticsEvent.LanguageViewed(2, variant = "cta_bottom"))

        assertEquals(listOf("fo_language_view"), sink.names())
        val params = sink.paramsOf("fo_language_view")
        assertEquals(2, params["screen_index"])
        assertEquals("cta_bottom", params["variant"])
    }

    @Test
    fun `an absent variant is omitted rather than sent as the string null`() {
        TrackkitPlugin.execute(AnalyticsEvent.LanguageViewed(1))

        assertFalse(sink.paramsOf("fo_language_view").containsKey("variant"))
    }

    @Test
    fun `language complete carries the selected language`() {
        TrackkitPlugin.execute(AnalyticsEvent.LanguageCompleted(1, "vi"))

        val params = sink.paramsOf("fo_language_complete")
        assertEquals(1, params["screen_index"])
        assertEquals("vi", params["language"])
    }

    @Test
    fun `the language flow milestone gets its own name so the LFO exit is counted once`() {
        TrackkitPlugin.execute(AnalyticsEvent.LanguageCompleted(2, "vi"))
        TrackkitPlugin.execute(AnalyticsEvent.LanguageFlowCompleted("vi"))

        assertEquals(
            listOf("fo_language_complete", "fo_language_flow_complete"),
            sink.names(),
        )
        assertEquals("vi", sink.paramsOf("fo_language_flow_complete")["language"])
    }

    @Test
    fun `a language tap gets its own event, which the audited SDK never had`() {
        TrackkitPlugin.execute(AnalyticsEvent.LanguageSelected(1, "vi"))

        assertEquals(listOf("fo_language_select"), sink.names())
        val select = sink.paramsOf("fo_language_select")
        assertEquals(1, select["screen_index"])
        assertEquals("vi", select["language"])
    }

    /**
     * Two language screens produce two completions carrying two different `screen_index` values —
     * the audited SDK's `lfo1_complete` + `lfo2_complete`. Collapsing them would erase the
     * slot-1 → slot-2 drop-off, which is the whole point of the second native slot.
     */
    @Test
    fun `each language screen reports its own completion, keyed by screen index`() {
        TrackkitPlugin.execute(AnalyticsEvent.LanguageCompleted(1, "vi"))
        TrackkitPlugin.execute(AnalyticsEvent.LanguageCompleted(2, "vi"))
        TrackkitPlugin.execute(AnalyticsEvent.LanguageFlowCompleted("vi"))

        assertEquals(
            listOf("fo_language_complete", "fo_language_complete", "fo_language_flow_complete"),
            sink.names(),
        )
        assertEquals(
            listOf(1, 2),
            sink.allParamsOf("fo_language_complete").map { it["screen_index"] })
    }

    @Test
    fun `the flow start milestone is the funnel denominator`() {
        TrackkitPlugin.execute(AnalyticsEvent.FlowStarted())

        assertEquals(listOf("fo_flow_start"), sink.names())
    }

    @Test
    fun `step complete carries the exit reason that ended the step`() {
        TrackkitPlugin.execute(AnalyticsEvent.StepCompleted(StepId.OB3, 2, 900, StepExit.SKIP))

        assertEquals("skip", sink.paramsOf("fo_step_complete")["exit_reason"])
    }

    @Test
    fun `flow complete carries the end to end duration`() {
        TrackkitPlugin.execute(AnalyticsEvent.FlowCompleted(3, dwellMs = 42_000))

        val params = sink.paramsOf("fo_flow_complete")
        assertEquals(3, params["steps_shown"])
        assertEquals(42_000L, params["dwell_ms"])
    }

    @Test
    fun `an ad slot reports request, failure and skip against its placement`() {
        TrackkitPlugin.execute(AnalyticsEvent.AdRequested("language1", AdFormat.NATIVE))
        TrackkitPlugin.execute(AnalyticsEvent.AdFailed("language1", AdFormat.NATIVE))
        TrackkitPlugin.execute(
            AnalyticsEvent.AdSkipped("question_native", AdFormat.NATIVE, AdSkipReason.POLICY),
        )

        assertEquals(listOf("ad_request", "ad_load_failed", "ad_skipped"), sink.names())
        assertEquals("language1", sink.paramsOf("ad_request")["placement"])
        assertEquals("native", sink.paramsOf("ad_load_failed")["ad_format"])
        assertEquals("policy", sink.paramsOf("ad_skipped")["reason"])
    }

    @Test
    fun `every paywall view gets a result, including the purchase`() {
        TrackkitPlugin.execute(AnalyticsEvent.PaywallViewed("after_onboarding"))
        TrackkitPlugin.execute(
            AnalyticsEvent.PaywallResolved("after_onboarding", AnalyticsEvent.PAYWALL_PURCHASED),
        )

        assertEquals(listOf("iap_paywall_view", "iap_paywall_result"), sink.names())
        assertEquals("purchased", sink.paramsOf("iap_paywall_result")["status"])
    }

    @Test
    fun `leaving the paywall without buying carries the outcome as status`() {
        TrackkitPlugin.execute(
            AnalyticsEvent.PaywallResolved(
                "splash_inter",
                AnalyticsEvent.PAYWALL_CONTINUE_WITH_ADS
            ),
        )

        assertEquals(listOf("iap_paywall_result"), sink.names())
        val params = sink.paramsOf("iap_paywall_result")
        assertEquals("splash_inter", params["source"])
        assertEquals("continue_with_ads", params["status"])
    }

    @Test
    fun `step view carries the step id, index and winning variant`() {
        TrackkitPlugin.execute(AnalyticsEvent.StepViewed(StepId.OB1, 0, "cta_top"))

        assertEquals(listOf("fo_step_view"), sink.names())
        val params = sink.paramsOf("fo_step_view")
        assertEquals("ob1", params["step"])
        assertEquals(0, params["index"])
        assertEquals("cta_top", params["variant"])
    }

    @Test
    fun `step complete carries dwell time`() {
        TrackkitPlugin.execute(AnalyticsEvent.StepCompleted(StepId.OB2, 1, 4_500))

        val params = sink.paramsOf("fo_step_complete")
        assertEquals("ob2", params["step"])
        assertEquals(1, params["index"])
        assertEquals(4_500L, params["dwell_ms"])
    }

    @Test
    fun `question events map onto the canonical question funnel`() {
        TrackkitPlugin.execute(AnalyticsEvent.QuestionViewed("new_user"))
        TrackkitPlugin.execute(AnalyticsEvent.QuestionOptionSelected("opt_a", true))
        TrackkitPlugin.execute(AnalyticsEvent.QuestionCompleted(3))

        assertEquals(
            listOf("fo_question_view", "fo_question_answer", "fo_question_complete"),
            sink.names(),
        )
        assertEquals("new_user", sink.paramsOf("fo_question_view")["source"])
        assertEquals("opt_a", sink.paramsOf("fo_question_answer")["option_id"])
        assertEquals(true, sink.paramsOf("fo_question_answer")["selected"])
        assertEquals(3, sink.paramsOf("fo_question_complete")["count"])
    }

    @Test
    fun `flow complete carries the number of steps shown`() {
        TrackkitPlugin.execute(AnalyticsEvent.FlowCompleted(4))

        assertEquals(4, sink.paramsOf("fo_flow_complete")["steps_shown"])
    }

    @Test
    fun `a full screen native impression becomes ad_show with its placement`() {
        TrackkitPlugin.execute(AnalyticsEvent.AdImpression("fullscreen_ob3"))

        assertEquals(listOf("ad_show"), sink.names())
        val params = sink.paramsOf("ad_show")
        assertEquals("fullscreen_ob3", params["placement"])
        assertEquals("native_full_screen", params["ad_format"])
    }

    @Test
    fun `default params ride along on every translated event`() {
        TrackkitPlugin.execute(AnalyticsEvent.FlowCompleted(1))

        val params = sink.paramsOf("fo_flow_complete")
        assertTrue(params.containsKey("sdk_ver"))
        assertTrue(params.containsKey("session_no"))
        assertTrue(params.containsKey("install_day"))
    }

    @Test
    fun `no legacy ob_ name survives the translation`() {
        listOf(
            AnalyticsEvent.FlowStarted(),
            AnalyticsEvent.SplashViewed(),
            AnalyticsEvent.SplashCompleted(1),
            AnalyticsEvent.LanguageViewed(1, "cta_top"),
            AnalyticsEvent.LanguageSelected(1, "en"),
            AnalyticsEvent.LanguageCompleted(1, "en"),
            AnalyticsEvent.LanguageFlowCompleted("en"),
            AnalyticsEvent.StepViewed(StepId.OB1, 0, "cta_top"),
            AnalyticsEvent.StepCompleted(StepId.OB1, 0, 10),
            AnalyticsEvent.QuestionViewed("old_user"),
            AnalyticsEvent.QuestionOptionSelected("opt_a", false),
            AnalyticsEvent.QuestionCompleted(1),
            AnalyticsEvent.FlowCompleted(2),
            AnalyticsEvent.AdRequested("ob5", AdFormat.NATIVE_FULL_SCREEN),
            AnalyticsEvent.AdImpression("ob5"),
            AnalyticsEvent.AdFailed("ob5", AdFormat.NATIVE_FULL_SCREEN),
            AnalyticsEvent.AdSkipped("ob5", AdFormat.NATIVE_FULL_SCREEN, AdSkipReason.NO_UNIT),
            AnalyticsEvent.PaywallViewed("after_onboarding"),
            AnalyticsEvent.PaywallResolved("after_onboarding", AnalyticsEvent.PAYWALL_DISMISSED),
        ).forEach(TrackkitPlugin::execute)

        assertEquals(19, sink.names().size)
        assertTrue(sink.names().none { it.startsWith("ob_") })
        assertTrue(
            sink.names().all {
                it.startsWith("fo_") || it.startsWith("ad_") || it.startsWith("iap_")
            },
        )
    }

    @Test
    fun `the hub forwards through the plugin exactly once per registration`() {
        AnalyticsHub.clear()
        AnalyticsHub.addPlugin(TrackkitPlugin)
        AnalyticsHub.addPlugin(TrackkitPlugin)

        AnalyticsHub.track(AnalyticsEvent.FlowCompleted(1))
        AnalyticsHub.clear()

        assertEquals(listOf("fo_flow_complete"), sink.names())
    }
}

/** Captures what reached the fan-out, in order. */
private class RecordingSink : TrackSink {

    override val id: String = "recording"

    private val received = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun onEvent(name: String, params: Map<String, Any?>) {
        received += name to params
    }

    fun names(): List<String> = received.map { it.first }

    fun paramsOf(name: String): Map<String, Any?> =
        received.first { it.first == name }.second

    /** For events a single run legitimately emits more than once, e.g. one per language screen. */
    fun allParamsOf(name: String): List<Map<String, Any?>> =
        received.filter { it.first == name }.map { it.second }
}

/**
 * Trackkit only needs `applicationContext` and a `SharedPreferences`; overriding those two keeps
 * the test free of Robolectric and of any mocking framework.
 */
private class FakeContext : ContextWrapper(null) {

    private val prefs = FakePrefs()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

private class FakePrefs : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {

    private val staged = mutableMapOf<String, Any?>()
    private val removed = mutableSetOf<String>()
    private var cleared = false

    override fun putString(key: String, value: String?) = stage(key, value)

    override fun putStringSet(key: String, value: MutableSet<String>?) = stage(key, value)

    override fun putInt(key: String, value: Int) = stage(key, value)

    override fun putLong(key: String, value: Long) = stage(key, value)

    override fun putFloat(key: String, value: Float) = stage(key, value)

    override fun putBoolean(key: String, value: Boolean) = stage(key, value)

    override fun remove(key: String): SharedPreferences.Editor {
        removed += key
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        cleared = true
        return this
    }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (cleared) values.clear()
        removed.forEach(values::remove)
        values.putAll(staged)
        staged.clear()
        removed.clear()
        cleared = false
    }

    private fun stage(key: String, value: Any?): SharedPreferences.Editor {
        staged[key] = value
        return this
    }
}
