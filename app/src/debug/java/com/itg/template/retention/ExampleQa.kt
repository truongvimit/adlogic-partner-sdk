package com.itg.template.retention

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.itg.template.R
import io.retentionkit.*
import io.retentionkit.core.*
import io.retentionkit.notifications.*
import io.retentionkit.integration.BillingRetentionBridge
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit debug harness. Android notification transport and module receiver remain real. */
object ExampleQa {
    const val FIXTURE_FILE = "retention_example_engine_qa_v1"
    private val handler = Handler(Looper.getMainLooper())
    private var fixtureClock: QaClock? = null
    private data class Profile(val store: RetentionStore, val state: RetentionUserState,
        val overrides: Map<String, String>, val notifications: RetentionNotificationOptions)
    private var profile: Profile? = null
    /** Synthetic engine state only. The production Splash router, host gate, Onboard observer,
     * terminal listener and actual ad transport are kept. Billing cannot overwrite the declared
     * fixture entitlement; production Billing still owns real ad eligibility independently. */
    fun options(standard: RetentionKitOptions): RetentionKitOptions {
        observeAds()
        val active = profile ?: return standard
        return standard.copy(
            initialUserState = active.state, initialOverrides = active.overrides,
            notifications = active.notifications, clock = checkNotNull(fixtureClock), store = active.store,
            adapters = standard.adapters.filterNot { it is BillingRetentionBridge },
            configSource = null, // The fixture's declared map is deterministic; production uses shared Firebase.
            eventSink = RetentionEventSink { event -> standard.eventSink.onEvent(event); events.add(event) },
        )
    }
    data class EntryAdSelection(val token: String?, val key: String)
    val entryAdSelections = CopyOnWriteArrayList<EntryAdSelection>()
    fun entryAdSelected(intent: Intent, key: String) {
        val token = (RetentionEntryCodec.read(intent) as? RetentionEntryDecodeResult.Valid)?.entry?.token
        entryAdSelections.add(EntryAdSelection(token, key))
    }
    data class AdObservation(val name: String, val placement: String?, val reason: String?, val elapsed: Long)
    val adEvents = CopyOnWriteArrayList<AdObservation>()
    private val adSink = object : io.trackkit.TrackSink {
        override val id = "example.retention.debug"
        override fun onEvent(name: String, params: Map<String, Any?>) {
            if (name.startsWith("ad_")) {
                adEvents.add(AdObservation(name, params["placement"]?.toString(), params["reason"]?.toString(), SystemClock.elapsedRealtime()))
                while (adEvents.size > 512) adEvents.removeAt(0)
            }
        }
    }
    private fun observeAds() {
        if (adSink.id !in io.trackkit.Tracker.sinkIds()) io.trackkit.Tracker.addSink(adSink)
    }
    val events = CopyOnWriteArrayList<RetentionEvent>()
    data class NativeObservation(val placement: String, val phase: String)
    val nativeEvents = CopyOnWriteArrayList<NativeObservation>()
    fun nativeEvent(placement: String, phase: String) { nativeEvents.add(NativeObservation(placement, phase)) }
    class QaClock(var now: Long = System.currentTimeMillis()) : RetentionClock {
        override fun wallTimeMillis() = now
        override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
    }
    fun clock(context: Context): RetentionClock = RetentionClock.System
    fun store(context: Context): RetentionStore? = null

    /** Explicit QA action only. Erases this harness's file, never normal SDK or business state.
     * Tests may pin initialTimeMillis before installation records setup and schedules alarms. */
    fun prepare(application: Application, setup: Boolean = true, extra: Map<String, String> = emptyMap(), clear: Boolean = true,
        notifications: RetentionNotificationOptions = RetentionNotificationOptions(),
        initialTimeMillis: Long = System.currentTimeMillis()): RetentionKit {
        handler.removeCallbacksAndMessages(null)
        RetentionRuntime.uninstallForTests()
        if (clear) check(application.getSharedPreferences(FIXTURE_FILE, Context.MODE_PRIVATE).edit().clear().commit())
        fixtureClock = QaClock(initialTimeMillis)
        events.clear()
        nativeEvents.clear()
        entryAdSelections.clear()
        adEvents.clear()
        val overrides = mapOf(
            "notifications.setup_grace_ms" to "0",
            "notifications.onboarding.grace_ms" to "0",
            "notifications.guard_window_ms" to "0",
            "notifications.arbitration.enabled" to "false",
            "notifications.winback.inactivity_ms" to "0",
            "notifications.reminder.enabled" to "false",
            "notifications.pinned.enabled" to "false",
            "review.enabled" to "false",
        ) + extra
        profile = Profile(SharedPreferencesRetentionStore(application, FIXTURE_FILE),
            RetentionUserState(setupCompleted = setup, onboardingActive = !setup,
                entitlement = RetentionEntitlement.NON_SUBSCRIBER), overrides, notifications)
        val kit = checkNotNull(RetentionExample.install(application))
        // This control intentionally models an unfinished flow for engine-only Home tests.
        // A real Splash entry still runs the actual Onboard flow and terminal listener.
        if (!setup) kit.onboardingChanged(true)
        return kit
    }
    fun restore(application: Application) {
        handler.removeCallbacksAndMessages(null)
        RetentionRuntime.uninstallForTests()
        fixtureClock = null
        profile = null
        RetentionExample.install(application)
    }
    fun savedAlarm(campaign: NotificationCampaign): String {
        val runtime = checkNotNull(RetentionKit.get()).runtime
        return runtime.store.snapshot("notifications.state.v1").entries()
            .filterKeys { it.startsWith("schedule:") }.values
            .filter { JSONObject(it).getString("campaign") == campaign.name }
            .minByOrNull { JSONObject(it).getLong("due") } ?: error("No actual saved alarm for $campaign")
    }
    /** Engine-driven injection: exact persisted payload, actual receiver, actual permission/channel gates.
     * This verifies engine handling, NOT AlarmManager wake timing or OEM/Doze behavior. */
    fun deliverSavedAlarm(context: Context, raw: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val clock = checkNotNull(fixtureClock) { "Activate explicit QA profile first" }
        // A saved slot may already be due but still inside its TTL. Never rewind before setup or
        // make an expired occurrence fresh; future slots alone need an advance to their due time.
        clock.now = maxOf(clock.now, JSONObject(raw).getLong("due"))
        NotificationAlarmReceiver().onReceive(context, Intent(context, NotificationAlarmReceiver::class.java)
            .setAction("io.retentionkit.notifications.ALARM")
            .putExtra("io.retentionkit.notifications.alarm.v1", raw))
    }
    fun attach(activity: Activity, parent: LinearLayout) {
        val panel = LinearLayout(activity).apply { id = R.id.rk_qa_panel; orientation = LinearLayout.VERTICAL }
        val status = TextView(activity).apply { tag = "qa_status"; textSize = 13f }
        panel.addView(TextView(activity).apply { text = "DEBUG QA · explicit synthetic state; real Android transport. Calendar delivery is engine-driven, not OS wake evidence." })
        panel.addView(status)
        panel.addView(TextView(activity).apply { id = R.id.rk_route_debug })
        fun action(text: String, block: () -> Unit) { panel.addView(Button(activity).apply { this.text = text; setOnClickListener { runCatching(block).onFailure { status.text = it.message }; refresh(activity) } }) }
        action("QA: isolated completed setup / free user") { prepare(activity.application); activity.recreate() }
        action("QA: isolated unfinished active setup") { prepare(activity.application, setup = false); activity.recreate() }
        action("QA: complete fixture setup") { check(fixtureClock != null); RetentionKit.get()?.setupCompleted() }
        action("QA: subscriber gate") { check(fixtureClock != null); RetentionKit.get()?.entitlementChanged(RetentionEntitlement.SUBSCRIBER) }
        action("QA: non-subscriber gate") { check(fixtureClock != null); RetentionKit.get()?.entitlementChanged(RetentionEntitlement.NON_SUBSCRIBER) }
        action("QA: enable reminder + pinned") { check(fixtureClock != null); RetentionKit.get()?.runtime?.updateConfig(mapOf("notifications.reminder.enabled" to "true", "notifications.pinned.enabled" to "true")); RetentionKit.get()?.notifications?.refreshForegroundNotifications() }
        for (campaign in listOf(NotificationCampaign.DAILY, NotificationCampaign.WINBACK, NotificationCampaign.LOCKSCREEN)) {
            action("QA: ${campaign.key} in 5s — press Home") {
                check(fixtureClock != null) { "Activate the isolated QA profile first" }
                val raw = savedAlarm(campaign)
                val context = activity.applicationContext
                handler.postDelayed({ runCatching { deliverSavedAlarm(context, raw) } }, 5000)
            }
        }
        action("QA: synthetic ad click — press Home") { check(fixtureClock != null); RetentionKit.get()?.adClicked() }
        action("QA: onboarding abandonment — press Home") { check(fixtureClock != null); RetentionKit.get()?.onboardingChanged(true) }
        action("QA: restore production defaults") { restore(activity.application); activity.recreate() }
        panel.visibility = android.view.View.GONE
        parent.addView(Button(activity).apply {
            text = "DEBUG · engine controls"
            setOnClickListener { panel.visibility = if (panel.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        })
        parent.addView(panel)
        refresh(activity)
    }
    fun refresh(activity: Activity) {
        val runtime = RetentionKit.get()?.runtime
        activity.findViewById<LinearLayout>(R.id.rk_qa_panel)?.findViewWithTag<TextView>("qa_status")?.text =
            "profile=${if (fixtureClock == null) "standard" else "isolated engine QA"}\nstate=${runtime?.userState}\nrevision=${runtime?.config?.revision} foreground=${runtime?.isForeground}\nactive=${activity.getSystemService(NotificationManager::class.java).activeNotifications.count { it.tag == "io.retentionkit.notifications" }}\nlast=${events.lastOrNull()}"
    }
    fun route(activity: Activity, text: String) { activity.findViewById<TextView>(R.id.rk_route_debug)?.text = text }
}
