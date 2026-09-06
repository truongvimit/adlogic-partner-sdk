package io.retentionkit.review

import android.os.Handler
import android.os.Looper
import io.retentionkit.core.*
import java.security.MessageDigest
import java.util.UUID

/** Automatic review is driven by business success signals, never by a pre-review star question. */
class RetentionReviewModule @JvmOverloads constructor(
    private val defaults: ReviewOptions = ReviewOptions(),
    private val transportFactory: ReviewTransportFactory = ReviewTransportFactory.PLAY,
) : RetentionModule {
    override val id = "review"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var runtime: RetentionRuntime? = null
    private lateinit var transport: ReviewTransport
    private class Flight(val token: String, val revision: Long, val lease: RetentionUiLease) {
        var phase = "requesting"
        var timeout: Runnable? = null
        var scope: ReviewHandoffScope? = null
    }
    private var flight: Flight? = null
    private val scopes = mutableMapOf<String, ReviewHandoffScope>()

    override fun validateConfig(config: RetentionConfigSnapshot): List<String> {
        val errors = defaults.validation().toMutableList()
        config.values.filterKeys { it in NUMBER_KEYS }.forEach { (key, value) ->
            val number = value.toLongOrNull()
            if (number == null || number !in 0..Int.MAX_VALUE.toLong()) errors.add("$key must be a bounded integer")
        }
        config.values["review.enabled"]?.let { if (it.toBooleanStrictOrNull() == null) errors.add("review.enabled must be boolean") }
        errors.addAll(defaults.resolved(config).validation())
        return errors.distinct()
    }

    override fun attach(runtime: RetentionRuntime) {
        this.runtime = runtime
        transport = transportFactory.create(runtime.application)
        val recovered = runtime.store.transaction(STATE) { state ->
            val phase = state.string("flight_phase")
            if (phase != null) {
                if (phase == "requesting") state.put("retry_after", runtime.clock.wallTimeMillis() + options().retryBackoffMillis)
                clearFlight(state)
            }
            phase
        }
        if (recovered != null) event("recovered", mapOf("phase" to recovered, "outcome" to "unknown"))
    }

    override fun onSignal(signal: RetentionSignal) = onMain {
        if (runtime == null) return@onMain
        when (signal) {
            is RetentionSignal.BusinessSuccess -> { recordSuccess(signal); attempt() }
            is RetentionSignal.ConfigurationChanged -> if (flight?.phase == "requesting") finish("cancelled", "config_changed", backoff = true)
            RetentionSignal.ProcessBackground -> if (flight?.phase == "requesting") finish("cancelled", "background", backoff = true)
            is RetentionSignal.HostUiChanged -> if (signal.visible && flight?.phase == "requesting") finish("cancelled", "host_ui", backoff = true)
            is RetentionSignal.ExternalTransitionStarted -> if (!signal.token.startsWith("review." ) && flight?.phase == "requesting") finish("cancelled", "external_transition", backoff = true)
            is RetentionSignal.OnboardingChanged -> if (signal.active && flight?.phase == "requesting") finish("cancelled", "onboarding", backoff = true)
            else -> Unit
        }
    }
    override fun reconcile(reason: String) { /* Never prompt merely because an app is opened. */ }

    fun requestIfEligible(): ReviewActionResult {
        if (runtime == null) return ReviewActionResult.Unavailable("not_attached")
        onMain { attempt() }
        return ReviewActionResult.Scheduled
    }

    /** Manual Rate goes straight to the Store and never consumes automatic review counters. */
    fun openStore(): ReviewActionResult {
        if (runtime == null) return ReviewActionResult.Unavailable("not_attached")
        onMain {
            val rt = runtime ?: return@onMain
            val acquired = rt.ui.acquire("review.manual") as? RetentionUiLeaseResult.Acquired
            if (acquired == null) { event("skipped", mapOf("reason" to "manual_ui_blocked")); return@onMain }
            val activity = acquired.lease.activity()
            if (activity == null) { acquired.lease.close(); return@onMain }
            val token = "review.store.${UUID.randomUUID()}"
            // Check the lease first. Beginning this scope intentionally revokes it.
            val scope = beginScope(rt, activity, token)
            try {
                if (transport.openStore(activity)) event("store_handoff")
                else { scope.close(); event("failed", mapOf("reason" to "store_unavailable")) }
            } catch (error: Exception) { scope.close(); diagnostic("manual_store", error); event("failed", mapOf("reason" to "store_exception")) }
            finally { acquired.lease.close() }
        }
        return ReviewActionResult.Scheduled
    }

    fun snapshot(): ReviewSnapshot? = runtime?.store?.snapshot(STATE)?.let {
        ReviewSnapshot(it.long("successes"), it.long("attempts"), it.long("last_attempt"), it.long("retry_after"), it.string("flight_phase"))
    }

    private fun recordSuccess(signal: RetentionSignal.BusinessSuccess) {
        val rt = runtime ?: return
        if (!options().enabled || signal.eventId.isBlank() || signal.eventId.length > 512 || signal.featureId.isBlank()) return
        val hash = MessageDigest.getInstance("SHA-256").digest(signal.eventId.toByteArray()).joinToString("") { "%02x".format(it) }
        val outcome = rt.store.transaction(STATE) { state ->
            when {
                state.string("seen:$hash") != null -> "duplicate"
                state.long("attempts") >= options().maxAttempts -> "cap"
                state.entries().keys.count { it.startsWith("seen:") } >= MAX_SUCCESS_IDS -> "dedupe_capacity"
                else -> {
                    state.put("seen:$hash", true)
                    state.put("successes", minOf(1000, state.long("successes") + 1))
                    "recorded"
                }
            }
        }
        event(if (outcome == "recorded") "success" else "skipped", mapOf("reason" to outcome))
    }

    private fun attempt() {
        val rt = runtime ?: return
        val policy = options()
        if (flight != null) return
        val state = rt.store.snapshot(STATE)
        val now = rt.clock.wallTimeMillis()
        val reason = when {
            !policy.enabled -> "disabled"
            !rt.userState.setupCompleted || rt.userState.onboardingActive -> "setup"
            state.long("successes") < policy.successThreshold -> "threshold"
            state.long("attempts") >= policy.maxAttempts -> "cap"
            state.long("retry_after") > now -> "retry_backoff"
            state.long("last_attempt") > 0 && (now < state.long("last_attempt") || now - state.long("last_attempt") < policy.cooldownDays * DAY) -> "cooldown"
            else -> null
        }
        if (reason != null) { event("skipped", mapOf("reason" to reason)); return }
        val acquired = rt.ui.acquire("review.auto", policy.requestTimeoutMillis + policy.flowTimeoutMillis) as? RetentionUiLeaseResult.Acquired
        if (acquired == null) { event("skipped", mapOf("reason" to "ui_blocked")); return }
        val current = Flight(UUID.randomUUID().toString(), rt.config.revision, acquired.lease)
        val claimed = try {
            rt.store.transaction(STATE) { values ->
                if (values.string("flight_token") != null && values.long("flight_deadline") > rt.clock.wallTimeMillis()) false else {
                    values.put("flight_token", current.token)
                    values.put("flight_phase", "requesting")
                    values.put("flight_deadline", rt.clock.wallTimeMillis() + policy.requestTimeoutMillis)
                    true
                }
            }
        } catch (error: Exception) { acquired.lease.close(); throw error }
        if (!claimed) { acquired.lease.close(); return }
        flight = current
        setTimeout(current, policy.requestTimeoutMillis)
        event("requested")
        try {
            transport.request { info -> onMain { onInfo(current.token, info) } }
        } catch (error: Exception) { diagnostic("request", error); finish("failed", "request_exception", backoff = true) }
    }

    private fun onInfo(token: String, info: ReviewInfoResult) {
        val rt = runtime ?: return
        val current = flight?.takeIf { it.token == token && it.phase == "requesting" } ?: return
        if (info is ReviewInfoResult.Failed) { finish("failed", info.reason, backoff = true); return }
        val activity = current.lease.activity()
        if (activity == null || !rt.isForeground || !options().enabled || rt.config.revision != current.revision || !rt.userState.setupCompleted || rt.userState.onboardingActive) {
            finish("cancelled", "launch_gate_changed", backoff = true); return
        }
        val reserved = rt.store.transaction(STATE) { state ->
            if (state.string("flight_token") != token || state.long("attempts") >= options().maxAttempts) false
            else {
                state.put("attempts", state.long("attempts") + 1)
                state.put("last_attempt", rt.clock.wallTimeMillis())
                state.put("successes", maxOf(0, state.long("successes") - options().successThreshold))
                state.put("flight_phase", "launching")
                state.put("flight_deadline", rt.clock.wallTimeMillis() + options().flowTimeoutMillis)
                true
            }
        }
        if (!reserved) { finish("cancelled", "reservation_lost", backoff = false); return }
        current.phase = "launching"
        setTimeout(current, options().flowTimeoutMillis)
        event("launch_attempt")
        current.scope = beginScope(rt, activity, "review.flow.$token")
        try {
            transport.launch(activity, (info as ReviewInfoResult.Ready).token) { result -> onMain {
                if (flight?.token != token || flight?.phase != "launching") return@onMain
                when (result) {
                    ReviewFlowResult.FinishedOutcomeUnknown -> finish("flow_unknown", "finished_outcome_unknown", backoff = false)
                    is ReviewFlowResult.Failed -> finish("failed", result.reason, backoff = false)
                }
            } }
        } catch (error: Exception) { diagnostic("launch", error); finish("failed", "launch_exception", backoff = false) }
    }

    private fun setTimeout(current: Flight, delay: Long) {
        current.timeout?.let(main::removeCallbacks)
        val timeout = Runnable { if (flight?.token == current.token) finish("timeout", current.phase, backoff = current.phase == "requesting") }
        current.timeout = timeout
        main.postDelayed(timeout, delay)
    }
    private fun finish(outcome: String, reason: String, backoff: Boolean) {
        val rt = runtime ?: return
        val current = flight ?: return
        flight = null
        current.timeout?.let(main::removeCallbacks)
        current.lease.close()
        current.scope?.close()
        rt.store.transaction(STATE) { state ->
            if (state.string("flight_token") == current.token) {
                clearFlight(state)
                if (backoff) state.put("retry_after", rt.clock.wallTimeMillis() + options().retryBackoffMillis)
            }
        }
        event(outcome, mapOf("reason" to reason))
    }
    private fun beginScope(rt: RetentionRuntime, activity: android.app.Activity, token: String): ReviewHandoffScope =
        ReviewHandoffScope(rt, activity, token, main) { scopes.remove(token) }.also { scopes[token] = it }
    private fun options(): ReviewOptions = runtime?.let { defaults.resolved(it.config) } ?: defaults
    private fun event(suffix: String, attributes: Map<String, String> = emptyMap()) { runtime?.emit(RetentionEvent("retention_review_$suffix", attributes)) }
    private fun diagnostic(where: String, error: Exception) { runtime?.diagnostics?.record("review.$where", error.message ?: where, RetentionDiagnosticLevel.ERROR, error) }
    private fun onMain(block: () -> Unit) {
        val guarded = Runnable {
            try { block() } catch (error: Exception) {
                diagnostic("state_machine", error)
                flight?.timeout?.let(main::removeCallbacks)
                flight?.lease?.close()
                flight?.scope?.close()
                flight = null
                // A durable claim has an expiry; a storage outage cannot hold the UI lease forever.
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run() else main.post(guarded)
    }
    override fun shutdown() {
        val rt = runtime ?: return
        // Runtime becomes unavailable immediately, including callbacks already posted on main.
        runtime = null
        onMain {
            main.removeCallbacksAndMessages(null)
            flight?.lease?.close()
            flight = null
            scopes.values.toList().forEach { it.close() }
            scopes.clear()
            rt.store.transaction(STATE) { clearFlight(it) }
        }
    }
    private companion object {
        const val STATE = "review.state.v1"
        const val DAY = 86_400_000L
        const val MAX_SUCCESS_IDS = 4096
        val NUMBER_KEYS = setOf("review.success_threshold", "review.cooldown_days", "review.max_attempts", "review.retry_backoff_ms", "review.request_timeout_ms", "review.flow_timeout_ms")
        fun clearFlight(state: RetentionTransaction) { state.remove("flight_token"); state.remove("flight_phase"); state.remove("flight_deadline") }
    }
}
