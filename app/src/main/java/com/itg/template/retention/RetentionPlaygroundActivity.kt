package com.itg.template.retention

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
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

/** Real offline business destinations; entry ad placement is supplied by the existing suite. */
class RetentionPlaygroundActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var featureBody: LinearLayout
    private lateinit var result: TextView
    private lateinit var title: TextView
    private lateinit var data: ExampleDataStore
    private var selectedFeature = "notes"
    private var selectedNoteId: String? = null
    private var noteDraft = ""
    private var pendingToken: String? = null
    private var entryNative: AutoCloseable? = null
    private var nativePlacement: String? = null
    private lateinit var nativeContainer: FrameLayout
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
        selectedFeature = ExampleDataStore.canonicalFeature(selectedFeature)
        if (selectedFeature !in RetentionExampleContent.featureIds) selectedFeature = "notes"
        selectedNoteId = savedInstanceState?.getString("note_id")
        noteDraft = savedInstanceState?.getString("note_draft").orEmpty()
        pendingToken = savedInstanceState?.getString("entry_token")
        lastRoute = savedInstanceState?.getString("route").orEmpty()
        buildScreen()
        val restored = io.retentionkit.core.RetentionEntryCodec.decode(savedInstanceState?.getString("retention.feature.entry"))
        if (restored is io.retentionkit.core.RetentionEntryDecodeResult.Valid) io.retentionkit.core.RetentionEntryCodec.write(intent, restored.entry)
        capture(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        capture(intent)
        window.decorView.removeCallbacks(routeRetry)
        window.decorView.post(routeRetry)
    }
    override fun onPostResume() {
        super.onPostResume()
        routeRetries = 0
        if (leftForExternal) { external?.close(); external = null; leftForExternal = false; RetentionKit.get()?.permissionChanged() }
        window.decorView.post {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                dispatchPending()
                showEntryNative()
                RetentionExample.flushSuccesses(this)
                ExampleQa.refresh(this)
            }
        }
    }
    override fun onPause() { window.decorView.removeCallbacks(routeRetry); if (external != null) leftForExternal = true; super.onPause() }
    override fun onDestroy() { entryNative?.close(); entryNative = null; external?.close(); external = null; super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        val captured = io.retentionkit.core.RetentionEntryCodec.read(intent)
        if (captured is io.retentionkit.core.RetentionEntryDecodeResult.Valid) {
            outState.putString("retention.feature.entry", io.retentionkit.core.RetentionEntryCodec.encode(captured.entry))
        }
        outState.putString("feature", selectedFeature)
        outState.putString("note_id", selectedNoteId)
        outState.putString("note_draft", findViewById<EditText>(R.id.rk_note_input)?.text?.toString() ?: noteDraft)
        outState.putString("entry_token", pendingToken); outState.putString("route", lastRoute)
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
        nativeContainer = FrameLayout(this).apply { id = R.id.rk_entry_native }
        content.addView(nativeContainer)
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
    private fun showEntryNative() {
        val entry = (io.retentionkit.core.RetentionEntryCodec.read(intent) as? io.retentionkit.core.RetentionEntryDecodeResult.Valid)?.entry
        val placement = ExampleEntryNative.placement(entry?.source)
        if (nativePlacement == placement) return
        entryNative?.close()
        nativeContainer.removeAllViews()
        nativePlacement = placement
        entryNative = ExampleEntryNative.attach(this, this, nativeContainer, placement)
    }
    private fun showFeature(id: String) {
        if (id !in RetentionExampleContent.featureIds) { message(R.string.rk_example_unknown_route); return }
        selectedFeature = id
        title.text = RetentionExampleContent.features(this).first { it.id == id }.label
        // Collection operation IDs belong to the durable outbox, not to the user-facing result.
        result.text = if (id != "saved_items" && data.lastFeature() == id) data.lastResult() else ""
        featureBody.removeAllViews()
        when (id) {
            "notes" -> showNotes()
            "saved_items" -> showSavedItems()
            "text_tools" -> showTextTools()
            "guide" -> showGuide()
        }
    }
    private fun showNotes() {
        val input = EditText(this).apply {
            id = R.id.rk_note_input; hint = getString(R.string.rk_example_note_hint); minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(10_000)); setText(noteDraft)
            doAfterTextChanged { noteDraft = it?.toString().orEmpty() }
        }
        featureBody.addView(input)
        button(featureBody, R.string.rk_example_note_save, R.id.rk_note_save) {
            guarded {
                val id = selectedNoteId ?: UUID.randomUUID().toString()
                noteDraft = input.text.toString()
                val operation = data.saveNote(id, noteDraft, UUID.randomUUID().toString())
                selectedNoteId = id
                result.text = operation?.result ?: noteDraft
                RetentionExample.flushSuccesses(this)
                showFeature("notes")
            }
        }
        button(featureBody, R.string.rk_example_new_note, R.id.rk_note_new) {
            selectedNoteId = null; noteDraft = ""; showFeature("notes")
        }
        button(featureBody, R.string.rk_example_save, R.id.rk_item_save) {
            guarded {
                val id = selectedNoteId ?: error("Save the note first")
                val saved = data.saveItem(id, UUID.randomUUID().toString())
                message(if (saved == null) R.string.rk_example_already_saved else R.string.rk_example_saved_ok)
                RetentionExample.flushSuccesses(this)
            }
        }.isEnabled = selectedNoteId != null
        val notes = data.notes()
        if (notes.isEmpty()) featureBody.addView(label(getString(R.string.rk_example_no_notes), 16f))
        notes.forEach { note ->
            featureBody.addView(Button(this).apply {
                text = note.text.take(80); contentDescription = "note:${note.id}"
                setOnClickListener { selectedNoteId = note.id; noteDraft = note.text; showFeature("notes") }
            })
        }
    }
    private fun showSavedItems() {
        val saved = data.savedItems()
        if (saved.isEmpty()) featureBody.addView(label(getString(R.string.rk_example_no_saved), 16f))
        saved.forEach { item ->
            featureBody.addView(label(item.text, 18f).apply { setTextIsSelectable(true) })
            button(featureBody, R.string.rk_example_remove) {
                guarded { data.removeItem(item.id, UUID.randomUUID().toString()); RetentionExample.flushSuccesses(this); showFeature("saved_items"); message(R.string.rk_example_removed) }
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
    private fun showGuide() {
        val asset = if (RetentionExampleContent.isVietnamese(this)) "retention_guide_vi.txt" else "retention_guide_en.txt"
        val document = assets.open(asset).bufferedReader().use { it.readText() }
        featureBody.addView(label(document, 17f).apply { setTextIsSelectable(true) })
        button(featureBody, R.string.rk_example_reading, R.id.rk_guide_analyze) {
            operation("guide") { getString(R.string.rk_example_minutes, ExampleUtilities.readingMinutes(document)) }
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
                routeRetries = 0
                window.decorView.removeCallbacks(routeRetry)
                lastRoute = "${accepted.entry.source} → ${accepted.entry.destination}\n${accepted.entry.token}"
                ExampleQa.route(this, lastRoute)
            }
            is RetentionEntryAcceptance.Rejected -> { lastRoute = "Rejected: ${accepted.reason}"; ExampleQa.route(this, lastRoute) }
            else -> Unit
        }
    }
    private fun dispatchPending() {
        val kit = RetentionKit.get() ?: return
        // Only this Activity's captured/restored selection can navigate. An unrelated older entry
        // in the durable ledger must not override a newer explicit Intent after it is consumed.
        val token = pendingToken ?: return
        if (!kit.runtime.userState.setupCompleted) {
            result.text = getString(R.string.rk_example_pending_setup)
            if (featureBody.findViewWithTag<View>("continue_setup") == null) {
                button(featureBody, R.string.rk_example_continue_setup) {
                    RetentionExample.continueSetup(this, token)
                }.tag = "continue_setup"
            }
            return
        }
        // Compatibility for already-persisted envelopes: preserve the old token and event
        // identity, validate the retired destination explicitly, and claim only at this final UI.
        val pending = kit.runtime.entries.pending(token)
        val canonical = pending?.destination?.let(ExampleDataStore::canonicalFeature)
        if (pending != null && canonical != pending.destination && canonical in RetentionExampleContent.featureIds &&
            kit.runtime.ui.eligibility() is io.retentionkit.core.RetentionEligibility.Allowed) {
            if (kit.consume(token) != null) { pendingToken = null; showFeature(checkNotNull(canonical)); showEntryNative() }
            return
        }
        when (val dispatched = kit.dispatchPending(token)) {
            is RetentionDispatchResult.Navigate -> { pendingToken = null; showFeature(dispatched.entry.destination); showEntryNative() }
            RetentionDispatchResult.SdkHandled -> {
                // Scheduled internal UI can still be blocked/disabled before consuming the entry.
                pendingToken = token.takeIf { kit.runtime.entries.pending(it) != null }
            }
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
