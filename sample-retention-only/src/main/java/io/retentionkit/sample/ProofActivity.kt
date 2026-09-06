package io.retentionkit.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private val main = Handler(Looper.getMainLooper())
    private val resumeEntry = Runnable { consumePending(); refreshStatus() }
    private val proofApp get() = application as ProofApplication
    private val runtime get() = RetentionRuntime.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destination = savedInstanceState?.getString("destination") ?: "uppercase"
        pendingToken = savedInstanceState?.getString("pending_token")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        status = TextView(this).apply { id = R.id.rk_proof_status; textSize = 16f }
        input = EditText(this).apply { id = R.id.rk_proof_input; setText(savedInstanceState?.getString("input") ?: "A useful text tool") }
        result = TextView(this).apply { id = R.id.rk_proof_result; textSize = 20f; text = savedInstanceState?.getString("result").orEmpty() }
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
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        val outer = FrameLayout(this).apply {
            id = R.id.rk_proof_insets
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(outer)
        ViewCompat.requestApplyInsets(outer)
        if (savedInstanceState == null) capture(intent)
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
        outState.putString("input", input.text.toString())
        outState.putString("result", result.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        // Core's ActivityLifecycleCallbacks observes this Activity after Activity.onResume.
        if (::status.isInitialized) main.post(resumeEntry)
    }
    override fun onPause() { main.removeCallbacks(resumeEntry); super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runtime?.reconcile("consumer_permission_result")
        refreshStatus()
    }
    private fun capture(intent: Intent?) {
        val rt = runtime ?: return
        when (val accepted = proofApp.profile.capture(rt, intent)) {
            is RetentionEntryAcceptance.Accepted -> {
                pendingToken = accepted.entry.token
                main.post(resumeEntry)
            }
            is RetentionEntryAcceptance.Rejected -> {
                if (pendingToken?.let { rt.entries.pending(it) } == null) pendingToken = null
                result.text = "Entry rejected: ${accepted.reason}"
            }
            else -> if (pendingToken != null) consumePending()
        }
    }
    private fun consumePending() {
        val rt = runtime ?: return
        val token = pendingToken ?: return
        when (val route = proofApp.profile.dispatchPending(rt, token)) {
            is ProofRoute.Navigate -> {
                destination = route.entry.destination
                pendingToken = null
                result.text = "Entry consumed once. Tool: $destination"
            }
            ProofRoute.SdkHandled -> {
                // Accepted SDK work may still be blocked before it claims the staged entry.
                if (rt.entries.pending(token) == null) pendingToken = null
                result.text = "SDK route accepted; inspect its actual screen/outcome."
            }
            is ProofRoute.Blocked -> {
                if (rt.entries.pending(token) == null) pendingToken = null
                result.text = "Entry pending: ${route.reason}"
            }
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun refreshStatus() {
        val rt = runtime
        status.text = buildString {
            appendLine("Profile: ${BuildConfig.RETENTION_PROFILE}")
            appendLine("Dependency: ${BuildConfig.RETENTION_DEPENDENCY_SOURCE} / ${BuildConfig.RETENTION_VERSION}")
            appendLine(proofApp.installStatus)
            appendLine("Setup: ${rt?.userState?.setupCompleted}; config revision: ${rt?.config?.revision}")
            appendLine("Entitlement: ${rt?.userState?.entitlement}")
            appendLine("Tool: $destination; pending entry: ${pendingToken != null}")
            proofApp.recentEvents().takeLast(3).forEach { appendLine("Event: $it") }
            appendLine("Diagnostics: ${rt?.diagnostics?.snapshot()?.size ?: 0}")
            append("Build/launch alone does not prove notification delivery, widget pinning or a Play review.")
        }
    }
}
