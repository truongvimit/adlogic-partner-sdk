package io.retentionkit.core

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionRemoteConfigTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }
    class Source : RetentionConfigSource {
        override val id = "test"
        val callbacks = LinkedBlockingQueue<RetentionConfigCallback>()
        val closes = AtomicInteger()
        var throws = false
        override fun fetch(timeoutMillis: Long, callback: RetentionConfigCallback): AutoCloseable {
            if (throws) throw IllegalStateException("source broke")
            callbacks.put(callback)
            return AutoCloseable { closes.incrementAndGet() }
        }
        fun callback(): RetentionConfigCallback = callbacks.poll(3, TimeUnit.SECONDS) ?: error("source did not start")
    }
    private fun start(source: Source, store: RetentionStore = testStore(), timeout: Long = 10_000): Pair<RetentionRuntime, RetentionRemoteConfig> {
        val module = RetentionRemoteConfig(source, timeout)
        val runtime = installed(RetentionOptions(store = store, modules = listOf(module), initialOverrides = mapOf("widgets.enabled" to "true")))
        shadowOf(Looper.getMainLooper()).idle()
        return runtime to module
    }
    private fun send(callback: RetentionConfigCallback, json: String) {
        callback.onResult(RetentionConfigFetchResult.Document(json))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun absentFieldsPreserveDefaultsAndLastGoodPatchSurvivesRestartMissingRemote() {
        val store = testStore()
        val source = Source()
        val (runtime, _) = start(source, store)
        send(source.callback(), """{"version":1,"overrides":{"review.max_attempts":2}}""")
        assertTrue(runtime.config.boolean("widgets.enabled", false))
        assertEquals(2, runtime.config.long("review.max_attempts", 3))
        RetentionRuntime.uninstallForTests()
        val restoredSource = Source()
        val (restored, restoredModule) = start(restoredSource, store)
        restoredSource.callback().onResult(RetentionConfigFetchResult.Missing)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, restored.config.long("review.max_attempts", 3))
        assertEquals(RetentionConfigSyncResult.Skipped("missing_remote_value"), restoredModule.lastResult)
    }

    @Test fun newerManualRevisionAndSupersedingFetchRejectOldAndDuplicateCallbacks() {
        val source = Source()
        val (runtime, module) = start(source)
        val stale = source.callback()
        runtime.updateConfig(mapOf("widgets.enabled" to "false"))
        send(stale, """{"version":1,"overrides":{"widgets.enabled":true}}""")
        assertFalse(runtime.config.boolean("widgets.enabled", true))
        assertEquals(RetentionConfigSyncResult.Skipped("stale_revision"), module.lastResult)
        module.refresh(); shadowOf(Looper.getMainLooper()).idle()
        val old = source.callback()
        module.refresh(); shadowOf(Looper.getMainLooper()).idle()
        val latest = source.callback()
        send(old, """{"version":1,"overrides":{"widgets.enabled":true}}""")
        assertFalse(runtime.config.boolean("widgets.enabled", true))
        send(latest, """{"version":1,"overrides":{"widgets.enabled":true}}""")
        val revision = runtime.config.revision
        send(latest, """{"version":1,"overrides":{"widgets.enabled":false}}""")
        assertEquals(revision, runtime.config.revision)
        assertTrue(runtime.config.boolean("widgets.enabled", false))
    }

    @Test fun malformedAndRejectedDocumentsDoNotPartiallyApplyAndExplicitRemovalRestoresDefault() {
        val source = Source()
        val module = RetentionRemoteConfig(source)
        val validator = object : RetentionModule {
            override val id = "validator"
            override fun attach(runtime: RetentionRuntime) {}
            override fun validateConfig(config: RetentionConfigSnapshot) = if (config.string("widgets.enabled") == "maybe") listOf("bad boolean") else emptyList()
        }
        val runtime = installed(RetentionOptions(store = testStore(), modules = listOf(module, validator), initialOverrides = mapOf("widgets.enabled" to "false")))
        shadowOf(Looper.getMainLooper()).idle()
        val revision = runtime.config.revision
        send(source.callback(), """{"version":1,"overrides":{"widgets.enabled":"maybe","review.max_attempts":1}}""")
        assertEquals(revision, runtime.config.revision)
        assertNull(runtime.config.string("review.max_attempts"))
        module.refresh(); shadowOf(Looper.getMainLooper()).idle()
        send(source.callback(), """{"version":1,"removeKeys":["widgets.enabled"]}""")
        assertTrue(runtime.config.boolean("widgets.enabled", true))
        assertTrue(RetentionConfigParser.parse("""{"version":1,"overrides":{"x":null}}""") is RetentionConfigParseResult.Invalid)
        assertTrue(RetentionConfigParser.parse("""{"version":2}""") is RetentionConfigParseResult.Invalid)
        assertTrue(RetentionConfigParser.parse("""{"version":1,"overrides":{"x":true},"removeKeys":["x"]}""") is RetentionConfigParseResult.Invalid)
    }

    @Test fun timeoutAndShutdownRejectLateCallbackAndCloseSourceResource() {
        val source = Source()
        val (runtime, module) = start(source, timeout = 100)
        val callback = source.callback()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        assertEquals(RetentionConfigSyncResult.Failed("timeout"), module.lastResult)
        send(callback, """{"version":1,"overrides":{"widgets.enabled":false}}""")
        assertTrue(runtime.config.boolean("widgets.enabled", false))
        module.refresh(); shadowOf(Looper.getMainLooper()).idle()
        val later = source.callback()
        RetentionRuntime.uninstallForTests()
        send(later, """{"version":1,"overrides":{"widgets.enabled":false}}""")
        assertTrue(runtime.config.boolean("widgets.enabled", false))
    }

    @Test fun throwingSourceFinishesWithDiagnosticsAndDoesNotCrashInstall() {
        val source = Source().apply { throws = true }
        val (runtime, module) = start(source)
        repeat(100) {
            if (module.lastResult == null) {
                Thread.sleep(2)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        assertEquals(RetentionConfigSyncResult.Failed("source_exception"), module.lastResult)
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "core.config_source" })
    }
}
