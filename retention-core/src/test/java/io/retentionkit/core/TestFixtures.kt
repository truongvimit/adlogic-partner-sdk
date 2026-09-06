package io.retentionkit.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.TimeZone
import java.util.UUID

internal class TestClock(@Volatile var now: Long = 1_800_000_000_000, @Volatile var elapsed: Long = 1000) : RetentionClock {
    override fun wallTimeMillis() = now
    override fun elapsedRealtimeMillis() = elapsed
    override fun timeZone(): TimeZone = TimeZone.getTimeZone("Europe/Paris")
    fun advance(millis: Long) { now += millis; elapsed += millis }
}

internal fun testApplication(): Application = ApplicationProvider.getApplicationContext()
internal fun testStore() = SharedPreferencesRetentionStore(testApplication(), "test_${UUID.randomUUID()}")
internal fun installed(options: RetentionOptions = RetentionOptions(store = testStore())): RetentionRuntime {
    val result = RetentionRuntime.install(testApplication(), options)
    check(result is RetentionInstallResult.Installed) { "Installation failed: $result; actual process=${Application.getProcessName()}, expected=${testApplication().applicationInfo.processName}" }
    return result.runtime
}
