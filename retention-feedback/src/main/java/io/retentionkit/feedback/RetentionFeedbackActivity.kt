package io.retentionkit.feedback

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.retentionkit.core.RetentionSignal
import io.retentionkit.core.RetentionSubscription

/** Internal host for both default and custom views; state survives recreation in the module store. */
class RetentionFeedbackActivity : Activity(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val main = Handler(Looper.getMainLooper())
    private var controller: FeedbackController? = null
    private var module: RetentionFeedbackModule? = null
    private var token: String? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var resumed = false
    private var readinessDeadline = 0L
    private var renewalPending = false
    private var readinessSubscription: RetentionSubscription? = null
    private val resumeWork = Runnable { activateWhenReady() }
    private val readinessTimeout = Runnable {
        if (resumed && !isFinishing && !isDestroyed) {
            controller?.let { module?.cancelReadiness(it) }
            finish()
        }
    }
    private val renewLease = Runnable {
        renewalPending = false
        controller?.pause()
        requestActivation()
    }

    private fun requestActivation() {
        main.removeCallbacks(resumeWork)
        main.post(resumeWork)
    }

    private fun activateWhenReady() {
        if (!resumed || isFinishing || isDestroyed) return
        val control = controller ?: return
        if (readinessDeadline != 0L && SystemClock.elapsedRealtime() >= readinessDeadline) {
            readinessTimeout.run()
            return
        }
        try {
            when (control.activate()) {
                FeedbackActivation.TERMINAL -> finish()
                FeedbackActivation.READY -> {
                    readinessDeadline = 0
                    main.removeCallbacks(readinessTimeout)
                    if (!renewalPending) {
                        renewalPending = true
                        main.postDelayed(renewLease, 45_000)
                    }
                }
                FeedbackActivation.WAITING -> {
                    main.removeCallbacks(renewLease)
                    renewalPending = false
                    if (readinessDeadline == 0L) readinessDeadline = SystemClock.elapsedRealtime() + READINESS_TIMEOUT
                    main.removeCallbacks(readinessTimeout)
                    main.postDelayed(readinessTimeout, (readinessDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    // Briefly cover host state settling without a signal. Later focus/core changes
                    // still retry until the deadline, without an unbounded foreground poll.
                    if (SystemClock.elapsedRealtime() < readinessDeadline - READINESS_TIMEOUT + 10_000) {
                        main.removeCallbacks(resumeWork)
                        main.postDelayed(resumeWork, 250)
                    }
                }
            }
        } catch (error: Exception) { module?.diagnostic("activate", error); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val attached = RetentionFeedbackModule.get()
        val session = savedInstanceState?.getString("session") ?: intent.getStringExtra(RetentionFeedbackModule.EXTRA_SESSION)
        if (attached == null || session == null || attached.session(session) == null) { finish(); return }
        module = attached
        token = session
        readinessDeadline = savedInstanceState?.getLong("readiness_deadline") ?: 0L
        attached.trackUi(this)
        readinessSubscription = attached.runtime?.subscribe("feedback.ui.$session") { signal ->
            when (signal) {
                is RetentionSignal.ConfigurationChanged, is RetentionSignal.HostUiChanged,
                is RetentionSignal.ExternalTransitionStarted, is RetentionSignal.ExternalTransitionFinished,
                is RetentionSignal.OnboardingChanged, RetentionSignal.SetupCompleted,
                RetentionSignal.ProcessForeground, RetentionSignal.ProcessBackground -> requestActivation()
                else -> Unit
            }
        }
        val control = FeedbackController(attached, session, this).also { controller = it }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        try {
            val content = attached.content()
            val view = attached.options.uiFactory?.create(this, control, content) ?: defaultView(attached, control, content)
            val outer = FrameLayout(this)
            outer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ViewCompat.setOnApplyWindowInsetsListener(outer) { target, insets ->
                val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                target.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                insets
            }
            setContentView(outer)
            ViewCompat.requestApplyInsets(outer)
            if (Build.VERSION.SDK_INT >= 33) {
                val callback = android.window.OnBackInvokedCallback { leave() }
                backCallback = callback
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
        } catch (error: Exception) { attached.diagnostic("ui", error); finish() }
    }
    override fun onStart() { super.onStart(); registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
    override fun onStop() { registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP); super.onStop() }
    override fun onResume() {
        super.onResume()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // Application.ActivityLifecycleCallbacks updates the core's resumed Activity after onResume.
        resumed = true
        requestActivation()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) controller?.pause()
        requestActivation()
    }
    override fun onPause() {
        resumed = false
        main.removeCallbacks(readinessTimeout)
        main.removeCallbacks(resumeWork)
        main.removeCallbacks(renewLease)
        renewalPending = false
        controller?.pause()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("session", token)
        outState.putLong("readiness_deadline", readinessDeadline)
        super.onSaveInstanceState(outState)
    }
    override fun finish() {
        readinessSubscription?.close(); readinessSubscription = null
        main.removeCallbacksAndMessages(null)
        controller?.pause()
        super.finish()
    }
    override fun onDestroy() {
        readinessSubscription?.close(); readinessSubscription = null
        main.removeCallbacksAndMessages(null)
        module?.releaseUi(this)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        controller?.detach(); controller = null
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        super.onDestroy()
    }
    private companion object { const val READINESS_TIMEOUT = 60_000L }
    @Deprecated("Legacy back dispatch") override fun onBackPressed() = leave()
    private fun leave() { controller?.keep(); finish() }

    private fun defaultView(module: RetentionFeedbackModule, control: FeedbackController, content: FeedbackContent): View {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.WHITE) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        scroll.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val icon = ImageView(this).apply {
            val resource = module.options.appIconRes ?: applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.rk_ic_feedback
            runCatching { setImageResource(resource) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(text(applicationInfo.loadLabel(packageManager).toString(), 19, Color.rgb(24, 30, 43), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        column.addView(header)
        column.addView(text(content.title, 28, Color.rgb(18, 25, 38), true), rowParams(24))
        column.addView(text(content.body, 16, Color.rgb(65, 75, 90)), rowParams(12))
        if (content.reasons.isNotEmpty()) {
            column.addView(text(content.reasonsLabel, 16, Color.rgb(24, 30, 43), true), rowParams(24))
            val selected = control.state()?.selectedReasons.orEmpty()
            content.reasons.forEach { reason ->
                val box = CheckBox(this).apply {
                    text = reason.label
                    textSize = 15f
                    minHeight = dp(48)
                    tag = "rk_feedback_reason_${reason.id}"
                    isChecked = reason.id in selected
                    buttonTintList = ColorStateList.valueOf(module.options.brandColor)
                    setOnCheckedChangeListener { _, checked -> control.selectReason(reason.id, checked) }
                }
                column.addView(box, rowParams(4))
            }
        }
        control.features().forEach { feature ->
            val label = module.runtime!!.localizedContext().getString(R.string.rk_feedback_try_feature, feature.label)
            column.addView(button(label, "rk_feedback_feature_${feature.id}", module.options.brandColor, false) { control.tryFeature(feature.id) }, rowParams(12))
        }
        if (module.options.nativeContent != null) {
            val slot = FrameLayout(this).apply { tag = "rk_feedback_native" }
            column.addView(slot, rowParams(16))
            control.bindNative(slot)
        }
        column.addView(button(content.keepLabel, "rk_feedback_keep", module.options.brandColor, true) { control.keep() }, rowParams(24))
        column.addView(button(content.continueLabel, "rk_feedback_continue", module.options.brandColor, false) { control.continueToSystem() }, rowParams(8))
        column.addView(text(content.systemExplanation, 14, Color.rgb(80, 90, 104)), rowParams(12))
        return scroll
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(label: String, id: String, brand: Int, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; tag = id; isAllCaps = false; textSize = 16f; minHeight = dp(52)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(if (primary) Color.WHITE else brand)
        backgroundTintList = ColorStateList.valueOf(if (primary) brand else Color.rgb(239, 243, 251))
        setOnClickListener { action() }
    }
    private fun rowParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
