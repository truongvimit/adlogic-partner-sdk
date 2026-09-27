package io.onboardkit.ui.splash

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.ui.splash.SplashAttempt.InterResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SplashAttemptTest {
    private lateinit var attempt: SplashAttempt

    @Before fun setUp() {
        attempt = SplashAttempt(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        attempt.allowAdRequests()
    }

    @Test fun `a fill delivered at the budget deadline settles as timed out`() {
        attempt.budgetDeadlineMs = SystemClock.elapsedRealtime() - 1
        attempt.onInterResult(InterResult.LOADED)
        assertEquals(InterResult.TIMED_OUT, runBlocking { attempt.interstitialSettled.await() })
    }

    @Test fun `a fill before the deadline settles as loaded`() {
        attempt.budgetDeadlineMs = SystemClock.elapsedRealtime() + 1
        attempt.onInterResult(InterResult.LOADED)
        assertEquals(InterResult.LOADED, runBlocking { attempt.interstitialSettled.await() })
    }
}
