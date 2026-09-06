package io.retentionkit.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional source module. Startup only restores core's synchronous cache and schedules a fetch.
 * One owner/generation, bounded timeout and captured revision protect against stale/duplicate work.
 */
class RetentionRemoteConfig @JvmOverloads constructor(
    private val source: RetentionConfigSource,
    private val timeoutMillis: Long = 10_000,
) : RetentionModule {
    init { require(timeoutMillis in 1..300_000) }
    override val id = "config-source"
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "retention-config").apply { isDaemon = true } }
    private lateinit var runtime: RetentionRuntime
    private var generation = 0L
    private var active: Long? = null
    private var handle: AutoCloseable? = null
    private var invocation: Future<*>? = null
    private var timeout: Runnable? = null
    @Volatile private var closed = true
    @Volatile var lastResult: RetentionConfigSyncResult? = null; private set

    override fun attach(runtime: RetentionRuntime) {
        this.runtime = runtime
        require(validId(source.id)) { "Invalid config source ID" }
        closed = false
        refresh()
    }
    /** Supersedes older fetches; manual/runtime updates made during this request win over it. */
    fun refresh() {
        if (!closed) handler.post { if (!closed) begin() }
    }
    private fun begin() {
        cancelActive()
        val request = ++generation
        val revision = runtime.config.revision
        active = request
        val expiry = Runnable {
            if (!closed && active == request) finish(RetentionConfigSyncResult.Failed("timeout"))
        }
        timeout = expiry
        handler.postDelayed(expiry, timeoutMillis)
        invocation = executor.submit {
            try {
                val returned = source.fetch(timeoutMillis) { result ->
                    handler.post { if (!closed && active == request) receive(result, revision) }
                }
                val once = AtomicBoolean()
                val bounded = AutoCloseable { if (once.compareAndSet(false, true)) returned.close() }
                handler.post {
                    if (!closed && active == request) handle = bounded else closeSafely(bounded)
                }
            } catch (error: Exception) {
                handler.post {
                    if (!closed && active == request) {
                        runtime.diagnostics.record("core.config_source", "Source callback failed", RetentionDiagnosticLevel.ERROR, error)
                        finish(RetentionConfigSyncResult.Failed("source_exception"))
                    }
                }
            }
        }
    }
    private fun receive(result: RetentionConfigFetchResult, capturedRevision: Long) {
        if (runtime.config.revision != capturedRevision) {
            finish(RetentionConfigSyncResult.Skipped("stale_revision")); return
        }
        val sync = when (result) {
            RetentionConfigFetchResult.Missing -> RetentionConfigSyncResult.Skipped("missing_remote_value")
            is RetentionConfigFetchResult.Failed -> RetentionConfigSyncResult.Failed(result.reason)
            is RetentionConfigFetchResult.Document -> when (val parsed = RetentionConfigParser.parse(result.json)) {
                is RetentionConfigParseResult.Invalid -> RetentionConfigSyncResult.Failed("invalid_document:${parsed.reason}")
                is RetentionConfigParseResult.Valid -> {
                    if (parsed.document.overrides.isEmpty() && parsed.document.removeKeys.isEmpty()) RetentionConfigSyncResult.Skipped("empty_patch")
                    else when (val applied = runtime.updateConfigAtRevision(parsed.document.overrides, parsed.document.removeKeys, capturedRevision)) {
                        is RetentionConfigResult.Applied -> RetentionConfigSyncResult.Applied(applied.revision)
                        is RetentionConfigResult.Rejected -> if (applied.reasons == listOf("stale_revision")) RetentionConfigSyncResult.Skipped("stale_revision")
                            else RetentionConfigSyncResult.Failed("invalid_configuration:${applied.reasons.joinToString()}")
                    }
                }
            }
        }
        finish(sync)
    }
    private fun finish(result: RetentionConfigSyncResult) {
        lastResult = result
        cancelActive()
        val kind = when (result) {
            is RetentionConfigSyncResult.Applied -> "applied"
            is RetentionConfigSyncResult.Skipped -> "skipped"
            is RetentionConfigSyncResult.Failed -> "failed"
        }
        val detail = when (result) {
            is RetentionConfigSyncResult.Applied -> result.revision.toString()
            is RetentionConfigSyncResult.Skipped -> result.reason
            is RetentionConfigSyncResult.Failed -> result.reason
        }
        runtime.emit(RetentionEvent("retention_config_$kind", mapOf("source" to source.id, "detail" to detail)))
    }
    private fun cancelActive() {
        active = null
        timeout?.let(handler::removeCallbacks); timeout = null
        invocation?.cancel(true); invocation = null
        handle?.let(::closeSafely); handle = null
    }
    private fun closeSafely(resource: AutoCloseable) {
        try { resource.close() }
        catch (error: Exception) { runtime.diagnostics.record("core.config_source", "Source cancellation failed", RetentionDiagnosticLevel.ERROR, error) }
    }
    override fun shutdown() {
        closed = true
        val cleanup = Runnable { cancelActive() }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else handler.post(cleanup)
        executor.shutdownNow()
    }
}
