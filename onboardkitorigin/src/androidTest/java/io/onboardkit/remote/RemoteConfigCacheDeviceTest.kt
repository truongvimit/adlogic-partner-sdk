package io.onboardkit.remote

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Default run checks the public snapshot/cache round trip. For a disk-backed cold read, invoke
 * once with `-e cachePhase write`, force-stop the test APK, then invoke with `-e cachePhase read`.
 */
@RunWith(AndroidJUnit4::class)
class RemoteConfigCacheDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expected = RemoteFlags(
        adsSplashBanner = false,
        adsSplashInter = false,
        adsAppResume = false,
        splashAdBudgetMs = 9_876,
        splashBannerWaitMs = 321,
    )

    @Test
    fun snapshotSurvivesNewSyncerOrColdProcess() {
        when (InstrumentationRegistry.getArguments().getString("cachePhase")) {
            "read" -> readSnapshotInNewProcess()
            else -> writeSnapshot()
        }
    }

    private fun writeSnapshot() {
        RemoteConfigSyncer(context) { null }.applySnapshot(expected)
        assertEquals(expected, RemoteConfigSyncer(context) { null }.flags.value)
        val prefs = context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE)
        assertEquals(ObRemoteKeys.ALL.map { it.key }.toSet(), prefs.all.keys)
        // commit flushes pending apply writes before the test runner exits its process.
        assertEquals(true, prefs.edit().commit())
        context.getSharedPreferences("p0_test_process", Context.MODE_PRIVATE)
            .edit().putInt("writer_pid", Process.myPid()).commit()
    }

    private fun readSnapshotInNewProcess() {
        val writerPid = context.getSharedPreferences("p0_test_process", Context.MODE_PRIVATE)
            .getInt("writer_pid", -1)
        assertNotEquals("Run writeSnapshot first", -1, writerPid)
        assertNotEquals("Must read in a fresh process", writerPid, Process.myPid())
        assertEquals(expected, RemoteConfigSyncer(context) { null }.flags.value)
    }
}
