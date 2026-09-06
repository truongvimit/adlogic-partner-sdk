package com.itg.template.retention

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.itg.template.R
import com.itg.template.data.pref.AppSharedPreferencesApp
import com.itg.template.ui.component.main.MainActivity
import com.itg.template.ui.component.splash.SplashActivity
import io.retentionkit.RetentionDispatchResult
import io.retentionkit.RetentionKit
import io.retentionkit.core.RetentionEntryAcceptance
import java.util.UUID

/** Working offline destinations. Host ads remain in MainActivity's unchanged showcase. */
class RetentionPlaygroundActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var featureBody: LinearLayout
    private lateinit var result: TextView
    private lateinit var title: TextView
    private lateinit var data: ExampleDataStore
    private var selectedFeature = "translate"
    private var selectedPhrase = "hello"
    private var toVietnamese = true
    private var pendingToken: String? = null
    private var lastRoute = ""
    private var external: AutoCloseable? = null
    private var leftForExternal = false
    private var routeRetries = 0
    private val routeRetry = Runnable { dispatchPending() }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(RetentionExampleContent.localizedContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = ExampleDataStore(this)
        selectedFeature = savedInstanceState?.getString("feature") ?: data.lastFeature()
        if (selectedFeature !in RetentionExampleContent.featureIds) selectedFeature = "translate"
        selectedPhrase = savedInstanceState?.getString("phrase") ?: "hello"
        toVietnamese = savedInstanceState?.getBoolean("to_vi") ?: true
        pendingToken = savedInstanceState?.getString("entry_token")
        lastRoute = savedInstanceState?.getString("route").orEmpty()
        buildScreen()
        capture(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        capture(intent)
        window.decorView.post(::dispatchPending)
    }
    override fun onPostResume() {
        super.onPostResume()
        routeRetries = 0
        if (leftForExternal) { external?.close(); external = null; leftForExternal = false; RetentionKit.get()?.permissionChanged() }
        window.decorView.post {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                dispatchPending()
                RetentionExample.flushSuccesses(this)
                ExampleQa.refresh(this)
            }
        }
    }
    override fun onPause() { window.decorView.removeCallbacks(routeRetry); if (external != null) leftForExternal = true; super.onPause() }
    override fun onDestroy() { external?.close(); external = null; super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("feature", selectedFeature); outState.putString("phrase", selectedPhrase)
        outState.putBoolean("to_vi", toVietnamese); outState.putString("entry_token", pendingToken); outState.putString("route", lastRoute)
        findViewById<EditText>(R.id.rk_text_input)?.let { runCatching { data.saveInput(it.text.toString()) } }
        super.onSaveInstanceState(outState)
    }
    private fun buildScreen() {
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(24)); insets
        }
        content.addView(label(getString(R.string.rk_example_title), 28f))
        content.addView(label(getString(R.string.rk_example_intro), 15f))
        button(content, R.string.rk_example_language) {
            AppSharedPreferencesApp(this).languageCode = if (RetentionExampleContent.isVietnamese(this)) "en" else "vi"
            RetentionKit.get()?.widgets?.refresh()
            recreate()
        }
        RetentionExampleContent.features(this).forEach { feature ->
            val choice = Button(this).apply {
                text = feature.label
                contentDescription = "feature:${feature.id}"
                setOnClickListener { showFeature(feature.id) }
            }
            content.addView(choice)
        }
        title = label("", 22f).apply { id = R.id.rk_feature_title }
        featureBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        result = label(data.lastResult(), 18f).apply { id = R.id.rk_result; setTextIsSelectable(true) }
        content.addView(title); content.addView(featureBody); content.addView(result)
        content.addView(label(getString(R.string.rk_example_actions), 22f))
        button(content, R.string.rk_example_widget, R.id.rk_open_widget) {
            val outcome = RetentionKit.get()?.widgets?.showPinInvitation()
            if (outcome !is io.retentionkit.widgets.WidgetInvitationResult.Shown) message(R.string.rk_example_unavailable)
        }
        button(content, R.string.rk_example_reminder) {
            val outcome = RetentionKit.get()?.notifications?.refreshForegroundNotifications()
            message(if (outcome?.values?.any { it is io.retentionkit.notifications.NotificationOutcome.PostSubmitted } == true)
                R.string.rk_example_notification_submitted else R.string.rk_example_unavailable)
        }
        button(content, R.string.rk_example_feedback, R.id.rk_open_feedback) { RetentionExample.showFeedback(this) }
        button(content, R.string.rk_example_rate, R.id.rk_open_rate) { RetentionExample.manualRate(this) }
        button(content, R.string.rk_example_permissions, R.id.rk_open_permissions) { openNotificationSettings() }
        button(content, R.string.rk_example_ads) { startActivity(Intent(this, MainActivity::class.java)); finish() }
        ExampleQa.attach(this, content)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
        showFeature(selectedFeature)
    }
    private fun showFeature(id: String) {
        if (id !in RetentionExampleContent.featureIds) { message(R.string.rk_example_unknown_route); return }
        selectedFeature = id
        title.text = RetentionExampleContent.features(this).first { it.id == id }.label
        featureBody.removeAllViews()
        when (id) {
            "translate" -> showTranslation()
            "saved_phrases" -> showSavedPhrases()
            "text_tools" -> showTextTools()
            "document" -> showDocument()
        }
    }
    private fun showTranslation() {
        featureBody.addView(label(getString(R.string.rk_example_offline_note), 14f))
        val direction = Switch(this).apply {
            text = getString(if (toVietnamese) R.string.rk_example_to_vi else R.string.rk_example_to_en)
            isChecked = toVietnamese
            setOnCheckedChangeListener { _, checked -> toVietnamese = checked; showTranslationAgain() }
        }
        featureBody.addView(direction)
        val phrases = ExampleUtilities.phrases
        val spinner = Spinner(this).apply {
            id = R.id.rk_phrase_spinner
            adapter = ArrayAdapter(this@RetentionPlaygroundActivity, android.R.layout.simple_spinner_dropdown_item,
                phrases.map { if (toVietnamese) it.english else it.vietnamese })
            setSelection(phrases.indexOfFirst { it.id == selectedPhrase }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedPhrase = phrases[position].id }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        featureBody.addView(spinner)
        button(featureBody, R.string.rk_example_translate_action, R.id.rk_phrase_translate) {
            operation("translate") { ExampleUtilities.translate(selectedPhrase, toVietnamese) }
        }
        button(featureBody, R.string.rk_example_save, R.id.rk_phrase_save) {
            guarded {
                val saved = data.savePhrase(selectedPhrase, UUID.randomUUID().toString())
                message(if (saved == null) R.string.rk_example_already_saved else R.string.rk_example_saved_ok)
                RetentionExample.flushSuccesses(this)
            }
        }
    }
    private fun showTranslationAgain() { featureBody.removeAllViews(); showTranslation() }
    private fun showSavedPhrases() {
        val saved = data.savedPhrases()
        if (saved.isEmpty()) featureBody.addView(label(getString(R.string.rk_example_no_saved), 16f))
        saved.forEach { phrase ->
            featureBody.addView(label("${phrase.english}\n${phrase.vietnamese}", 18f))
            button(featureBody, R.string.rk_example_remove) {
                guarded { data.removePhrase(phrase.id, UUID.randomUUID().toString()); RetentionExample.flushSuccesses(this); showFeature("saved_phrases"); message(R.string.rk_example_removed) }
            }
        }
    }
    private fun showTextTools() {
        val input = EditText(this).apply {
            id = R.id.rk_text_input; hint = getString(R.string.rk_example_input); minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(10_000)); setText(data.input())
        }
        featureBody.addView(input)
        button(featureBody, R.string.rk_example_analyze, R.id.rk_text_analyze) {
            operation("text_tools") {
                data.saveInput(input.text.toString())
                val analysis = ExampleUtilities.analyze(input.text.toString())
                "${analysis.normalized}\n${getString(R.string.rk_example_counts, analysis.words, analysis.characters)}"
            }
        }
    }
    private fun showDocument() {
        val asset = if (RetentionExampleContent.isVietnamese(this)) "retention_sample_vi.txt" else "retention_sample_en.txt"
        val document = assets.open(asset).bufferedReader().use { it.readText() }
        featureBody.addView(label(document, 17f).apply { setTextIsSelectable(true) })
        button(featureBody, R.string.rk_example_reading, R.id.rk_document_analyze) {
            operation("document") { getString(R.string.rk_example_minutes, ExampleUtilities.readingMinutes(document)) }
        }
    }
    private fun operation(feature: String, compute: () -> String) = guarded {
        val computed = compute()
        val operation = data.record(UUID.randomUUID().toString(), feature, computed)
        result.text = operation.result
        RetentionExample.flushSuccesses(this)
    }
    private fun capture(intent: Intent?) {
        when (val accepted = RetentionKit.get()?.capture(intent)) {
            is RetentionEntryAcceptance.Accepted -> {
                pendingToken = accepted.entry.token
                lastRoute = "${accepted.entry.source} → ${accepted.entry.destination}\n${accepted.entry.token}"
                ExampleQa.route(this, lastRoute)
            }
            is RetentionEntryAcceptance.Rejected -> { lastRoute = "Rejected: ${accepted.reason}"; ExampleQa.route(this, lastRoute) }
            else -> Unit
        }
    }
    private fun dispatchPending() {
        val kit = RetentionKit.get() ?: return
        val token = pendingToken ?: kit.runtime.entries.pending().firstOrNull()?.token ?: return
        if (!kit.runtime.userState.setupCompleted) {
            result.text = getString(R.string.rk_example_pending_setup)
            if (featureBody.findViewWithTag<View>("continue_setup") == null) {
                button(featureBody, R.string.rk_example_continue_setup) {
                    RetentionExample.continueSetup(this, token)
                }.tag = "continue_setup"
            }
            return
        }
        when (val dispatched = kit.dispatchPending(token)) {
            is RetentionDispatchResult.Navigate -> { pendingToken = null; showFeature(dispatched.entry.destination) }
            RetentionDispatchResult.SdkHandled -> pendingToken = null
            is RetentionDispatchResult.Unavailable -> {
                ExampleQa.route(this, "$lastRoute\nPending: ${dispatched.reason}")
                // The Activity callback can precede core's resumed tracker or an owned dialog close.
                if (kit.runtime.entries.pending(token) != null && routeRetries++ < 20 &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    window.decorView.removeCallbacks(routeRetry)
                    window.decorView.postDelayed(routeRetry, 250)
                }
            }
        }
    }
    private fun openNotificationSettings() {
        external?.close()
        external = RetentionExample.beginExternal("notification_settings")
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) { external?.close(); external = null; message(R.string.rk_example_unavailable) }
    }
    private fun guarded(action: () -> Unit) { try { action() } catch (_: Exception) { message(R.string.rk_example_error) } }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)) }
    private fun button(parent: LinearLayout, label: Int, id: Int? = null, action: () -> Unit): Button = Button(this).apply {
        text = getString(label); id?.let { this.id = it }; setOnClickListener { action() }; parent.addView(this)
    }
    private fun message(resource: Int) { Toast.makeText(this, resource, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
