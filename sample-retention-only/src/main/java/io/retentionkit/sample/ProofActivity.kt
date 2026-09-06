package io.retentionkit.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.retentionkit.core.*
import java.util.Locale
import java.util.UUID

/** A small real text utility and typed-route smoke surface, separate from the full SDK demo. */
class ProofActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var input: EditText
    private var destination = "uppercase"
    private var pendingToken: String? = null
    private val proofApp get() = application as ProofApplication
    private val runtime get() = RetentionRuntime.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destination = savedInstanceState?.getString("destination") ?: "uppercase"
        pendingToken = savedInstanceState?.getString("pending_token")
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { id = R.id.rk_proof_status; textSize = 16f }
        input = EditText(this).apply { id = R.id.rk_proof_input; setText("A useful text tool") }
        result = TextView(this).apply { id = R.id.rk_proof_result; textSize = 20f }
        content.addView(status); content.addView(input); content.addView(result)
        fun button(label: String, resourceId: Int? = null, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; resourceId?.let { id = it }; setOnClickListener { action(); refreshStatus() } })
        }
        button("Complete setup", R.id.rk_proof_setup) {
            runtime?.signal(RetentionSignal.SetupCompleted)
            consumePending()
        }
        button("Run text tool", R.id.rk_proof_run) { runTextTool() }
        button("Open word-count typed entry", R.id.rk_proof_entry) {
            val rt = runtime ?: return@button
            val entry = RetentionEntry(RetentionEntrySource.OTHER, "word_count", "open_word_count")
            rt.createEntryIntent(entry)?.let(::startActivity)
        }
        runtime?.let { rt ->
            proofApp.profile.actions(this, rt).forEach { action -> button(action.label) { result.text = action.run() } }
        }
        button("Refresh SDK status") { runtime?.reconcile("consumer_manual_refresh") }
        setContentView(ScrollView(this).apply { addView(content) })
        capture(intent)
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        capture(intent)
        refreshStatus()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("destination", destination)
        outState.putString("pending_token", pendingToken)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) refreshStatus() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runtime?.reconcile("consumer_permission_result")
        refreshStatus()
    }
    private fun capture(intent: Intent?) {
        when (val accepted = runtime?.entries?.capture(intent)) {
            is RetentionEntryAcceptance.Accepted -> {
                pendingToken = accepted.entry.token
                proofApp.profile.entryAccepted(accepted.entry)
                consumePending()
            }
            is RetentionEntryAcceptance.Rejected -> result.text = "Entry rejected: ${accepted.reason}"
            else -> if (pendingToken != null) consumePending()
        }
    }
    private fun consumePending() {
        val rt = runtime ?: return
        val token = pendingToken ?: return
        if (!rt.userState.setupCompleted) { result.text = "Complete setup to continue the pending entry."; return }
        val entry = rt.entries.pending(token) ?: return
        if (entry.destination !in setOf("uppercase", "word_count")) { result.text = "Unknown destination"; return }
        if (rt.entries.consume(token)) {
            destination = entry.destination
            pendingToken = null
            result.text = "Entry consumed once. Tool: $destination"
        }
    }
    private fun runTextTool() {
        val value = input.text.toString()
        if (value.isBlank()) { result.text = "Enter text first."; return }
        result.text = when (destination) {
            "word_count" -> "${value.trim().split(Regex("\\s+")).size} words"
            else -> value.uppercase(Locale.getDefault())
        }
        // This represents a completed operation, not an invented review/pin/delivery success.
        runtime?.signal(RetentionSignal.BusinessSuccess(destination, UUID.randomUUID().toString()))
    }
    private fun refreshStatus() {
        val rt = runtime
        status.text = buildString {
            appendLine("Profile: ${BuildConfig.RETENTION_PROFILE}")
            appendLine("Dependency: ${BuildConfig.RETENTION_DEPENDENCY_SOURCE} / ${BuildConfig.RETENTION_VERSION}")
            appendLine(proofApp.installStatus)
            appendLine("Setup: ${rt?.userState?.setupCompleted}; config revision: ${rt?.config?.revision}")
            appendLine("Tool: $destination; pending entry: ${pendingToken != null}")
            appendLine("Diagnostics: ${rt?.diagnostics?.snapshot()?.size ?: 0}")
            append("Build/launch alone does not prove notification delivery, widget pinning or a Play review.")
        }
    }
}
