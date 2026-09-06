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
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit debug harness. Android notification transport and module receiver remain real. */
object ExampleQa {
    const val FIXTURE_FILE = "retention_example_engine_qa_v1"
    private val handler = Handler(Looper.getMainLooper())
    private var fixtureClock: QaClock? = null
    val events = CopyOnWriteArrayList<RetentionEvent>()
    class QaClock(var now: Long = System.currentTimeMillis()) : RetentionClock {
        override fun wallTimeMillis() = now
        override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
    }
    fun clock(context: Context): RetentionClock = RetentionClock.System
    fun store(context: Context): RetentionStore? = null

    /** Explicit QA action only. Erases this harness's file, never normal SDK or business state. */
    fun prepare(application: Application, setup: Boolean = true, extra: Map<String, String> = emptyMap(), clear: Boolean = true): RetentionKit {
        handler.removeCallbacksAndMessages(null)
        RetentionRuntime.uninstallForTests()
        if (clear) check(application.getSharedPreferences(FIXTURE_FILE, Context.MODE_PRIVATE).edit().clear().commit())
        fixtureClock = QaClock()
        events.clear()
        val overrides = mapOf(
            "notifications.setup_grace_ms" to "0",
            "notifications.winback.inactivity_ms" to "0",
            "notifications.reminder.enabled" to "false",
            "notifications.pinned.enabled" to "false",
            "review.enabled" to "false",
        ) + extra
        val installed = RetentionKit.install(application, RetentionKitOptions(
            featureProvider = RetentionFeatureProvider(RetentionExampleContent::features),
            localeProvider = RetentionLocaleProvider(RetentionExampleContent::localizedContext),
            router = RetentionRouter { context, _ -> Intent(context, RetentionPlaygroundActivity::class.java) },
            initialUserState = RetentionUserState(setupCompleted = setup, onboardingActive = !setup,
                entitlement = RetentionEntitlement.NON_SUBSCRIBER),
            initialOverrides = overrides,
            clock = fixtureClock!!,
            store = SharedPreferencesRetentionStore(application, FIXTURE_FILE),
            eventSink = RetentionEventSink { events.add(it) },
        ))
        check(installed is RetentionKitInstallResult.Installed) { installed.toString() }
        RetentionExample.attachSuccessAcknowledgement(application, installed.kit.runtime)
        return installed.kit
    }
    fun restore(application: Application) {
        handler.removeCallbacksAndMessages(null)
        RetentionRuntime.uninstallForTests()
        fixtureClock = null
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
        checkNotNull(fixtureClock) { "Activate explicit QA profile first" }.now = JSONObject(raw).getLong("due")
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
