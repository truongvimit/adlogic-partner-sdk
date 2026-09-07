package com.itg.template.retention

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import io.retentionkit.RetentionKit
import java.util.UUID

/** Real offline business destinations; entry ad placement is supplied by the existing suite. */
class RetentionPlaygroundActivity : AppCompatActivity(), io.retentionkit.RetentionFeatureHost {
    private lateinit var content: LinearLayout
    private lateinit var featureBody: LinearLayout
    private lateinit var result: TextView
    private lateinit var title: TextView
    private lateinit var data: ExampleDataStore
    private var selectedFeature = "notes"
    private var selectedNoteId: String? = null
    private var noteDraft = ""
    private var entryNative: AutoCloseable? = null
    private var nativePlacement: String? = null
    private lateinit var nativeContainer: FrameLayout

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(RetentionExampleContent.localizedContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = ExampleDataStore(this)
        selectedFeature = savedInstanceState?.getString("feature") ?: data.lastFeature()
        selectedFeature = ExampleDataStore.canonicalFeature(selectedFeature)
        if (selectedFeature !in RetentionExampleContent.featureIds) selectedFeature = "notes"
        selectedNoteId = savedInstanceState?.getString("note_id")
        noteDraft = savedInstanceState?.getString("note_draft").orEmpty()
        buildScreen()
    }
    override fun onRetentionFeature(entry: io.retentionkit.core.RetentionEntry, destination: String) {
        showFeature(destination)
        showEntryNative()
        ExampleQa.route(this, "${entry.source} → ${entry.destination}\n${entry.token}")
    }
    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                showEntryNative()
                RetentionExample.flushSuccesses(this)
                ExampleQa.refresh(this)
            }
        }
    }
    override fun onDestroy() { entryNative?.close(); entryNative = null; super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("feature", selectedFeature)
        outState.putString("note_id", selectedNoteId)
        outState.putString("note_draft", findViewById<EditText>(R.id.rk_note_input)?.text?.toString() ?: noteDraft)
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
        button(content, R.string.rk_example_enable_notifications, R.id.rk_open_permissions) {
            io.retentionkit.integration.RetentionSuite.get()?.requestNotifications(this)
        }
        button(content, R.string.rk_example_notification_status) { RetentionExample.showNotificationStatus(this) }
        button(content, R.string.rk_example_feedback, R.id.rk_open_feedback) { RetentionExample.showFeedback(this) }
        button(content, R.string.rk_example_rate, R.id.rk_open_rate) { RetentionExample.manualRate(this) }
        button(content, R.string.rk_example_permissions) { io.retentionkit.integration.RetentionSuite.get()?.openNotificationSettings(this) }
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
    private fun guarded(action: () -> Unit) { try { action() } catch (_: Exception) { message(R.string.rk_example_error) } }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)) }
    private fun button(parent: LinearLayout, label: Int, id: Int? = null, action: () -> Unit): Button = Button(this).apply {
        text = getString(label); id?.let { this.id = it }; setOnClickListener { action() }; parent.addView(this)
    }
    private fun message(resource: Int) { Toast.makeText(this, resource, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
