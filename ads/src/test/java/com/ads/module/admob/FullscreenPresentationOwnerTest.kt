package com.ads.module.admob

import android.os.Looper
import android.os.SystemClock
import io.trackkit.AdFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real ownership contract; only Android's clock and main queue are controlled by Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FullscreenPresentationOwnerTest {
    private val acquired = mutableListOf<FullscreenPresentationOwner.Lease>()
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `a reservation excludes other formats and identifies the owning family`() = withOwner { owner ->
        val interstitial = reserve(owner, AdFormat.INTERSTITIAL)
        assertTrue(owner.isBusy())
        assertTrue(owner.isInterstitialBusy())
        assertFalse(owner.isAppOpenBusy())
        assertTrue(interstitial.isCurrent())
        assertNull(owner.tryAcquire(AdFormat.APP_OPEN, Runnable {}))

        assertTrue(interstitial.finish())
        assertFalse(owner.isBusy())
        assertFalse(owner.isInterstitialBusy())
        val appOpen = reserve(owner, AdFormat.APP_OPEN)
        assertTrue(owner.isBusy())
        assertTrue(owner.isAppOpenBusy())
        assertFalse(owner.isInterstitialBusy())
        assertTrue(appOpen.isCurrent())
    }

    @Test
    fun `reservation expiry releases A once before its cleanup acquires B`() = withOwner { owner ->
        var expiredA = 0
        var expiredB = 0
        lateinit var next: FullscreenPresentationOwner.Lease
        val first = reserve(owner, AdFormat.INTERSTITIAL) {
            expiredA++
            assertFalse("Expired A must be released before adapter cleanup", owner.isBusy())
            next = reserve(owner, AdFormat.APP_OPEN) { expiredB++ }
        }

        mainLooper.idleFor(89_999, TimeUnit.MILLISECONDS)
        assertTrue(first.isCurrent())
        assertEquals(0, expiredA)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)

        assertEquals(1, expiredA)
        assertFalse(first.isCurrent())
        assertTrue(next.isCurrent())
        assertTrue(owner.isAppOpenBusy())
        assertFalse(first.start())
        assertFalse(first.presented())
        assertFalse(first.finish())
        assertTrue(next.start())
        mainLooper.idleFor(90_000, TimeUnit.MILLISECONDS)
        assertTrue(next.isCurrent())
        assertEquals(1, expiredA)
        assertEquals(0, expiredB)
    }

    @Test
    fun `deep sleep expires reservation at start even before its main queue timeout runs`() = withOwner { owner ->
        var expired = 0
        val lease = reserve(owner, AdFormat.APP_OPEN) { expired++ }
        val elapsedBefore = SystemClock.elapsedRealtime()
        val uptimeBefore = SystemClock.uptimeMillis()

        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(90_000))

        assertEquals(elapsedBefore + 90_000, SystemClock.elapsedRealtime())
        assertEquals(uptimeBefore, SystemClock.uptimeMillis())
        assertEquals("The handler deadline has not run during deep sleep", 0, expired)
        assertFalse(lease.start())
        assertEquals(1, expired)
        assertFalse(lease.isCurrent())
        assertFalse(owner.isBusy())
        // Preserve the simulated sleep offset: PAUSED idleFor would reset elapsed to uptime.
        SystemClock.sleep(90_000)
        mainLooper.idle()
        assertEquals(1, expired)
        assertFalse(lease.start())
        assertFalse(lease.finish())
    }

    @Test
    fun `a dispatched ad stays exclusive past ninety seconds and failed acquisition has no expiry`() = withOwner { owner ->
        var expiredA = 0
        var rejectedExpiry = 0
        val lease = reserve(owner, AdFormat.INTERSTITIAL) { expiredA++ }
        assertTrue(lease.start())
        assertFalse(lease.start())
        assertNull(owner.tryAcquire(AdFormat.APP_OPEN, Runnable { rejectedExpiry++ }))

        mainLooper.idleFor(180_000, TimeUnit.MILLISECONDS)

        assertTrue(lease.isCurrent())
        assertTrue(owner.isBusy())
        assertTrue(owner.isInterstitialBusy())
        assertEquals(0, expiredA)
        assertEquals(0, rejectedExpiry)
        assertTrue(lease.finish())
        assertFalse(owner.isBusy())
        mainLooper.idleFor(90_000, TimeUnit.MILLISECONDS)
        assertEquals(0, rejectedExpiry)
    }

    @Test
    fun `presented accepts only the first signal after start and never times out an active ad`() = withOwner { owner ->
        var expired = 0
        val lease = reserve(owner, AdFormat.REWARDED) { expired++ }
        assertFalse(lease.presented())
        assertTrue(lease.isCurrent())
        assertTrue(lease.start())
        assertTrue(lease.presented())
        assertFalse(lease.presented())

        mainLooper.idleFor(180_000, TimeUnit.MILLISECONDS)

        assertTrue(lease.isCurrent())
        assertTrue(owner.isBusy())
        assertEquals(0, expired)
        assertTrue(lease.finish())
        assertFalse(lease.isCurrent())
        assertFalse(lease.presented())
        assertFalse(lease.start())
        assertFalse(lease.finish())
        assertFalse(owner.isBusy())
    }

    @Test
    fun `A old deadline and obsolete transitions cannot release a later B reservation`() = withOwner { owner ->
        var expiredA = 0
        var expiredB = 0
        val first = reserve(owner, AdFormat.INTERSTITIAL) { expiredA++ }
        mainLooper.idleFor(89_000, TimeUnit.MILLISECONDS)
        assertTrue(first.finish())
        val second = reserve(owner, AdFormat.APP_OPEN) { expiredB++ }

        mainLooper.idleFor(1_001, TimeUnit.MILLISECONDS)

        assertTrue(second.isCurrent())
        assertTrue(owner.isAppOpenBusy())
        assertEquals(0, expiredA)
        assertEquals(0, expiredB)
        assertFalse(first.finish())
        assertFalse(first.presented())
        assertFalse(first.start())
        assertTrue(second.isCurrent())
        assertTrue(second.start())
        assertTrue(second.presented())
        mainLooper.idleFor(90_000, TimeUnit.MILLISECONDS)
        assertTrue(second.isCurrent())
        assertEquals(0, expiredB)
    }

    @Test
    fun `legacy suppression blocks acquisition but clearing it cannot release an SDK lease`() = withOwner { owner ->
        var rejectedExpiry = 0
        owner.setLegacyInterstitialSuppressed(true)
        assertTrue(owner.isBusy())
        assertNull(owner.tryAcquire(AdFormat.APP_OPEN, Runnable { rejectedExpiry++ }))
        owner.setLegacyInterstitialSuppressed(false)
        assertFalse(owner.isBusy())

        val lease = reserve(owner, AdFormat.APP_OPEN)
        assertTrue(lease.start())
        owner.setLegacyInterstitialSuppressed(true)
        owner.setLegacyInterstitialSuppressed(false)

        assertTrue(lease.isCurrent())
        assertTrue(owner.isBusy())
        assertTrue(owner.isAppOpenBusy())
        assertEquals(0, rejectedExpiry)
        assertTrue(lease.finish())
        assertFalse(owner.isBusy())
    }

    @Test
    fun `legacy suppression timer cannot unlock a dispatched SDK lease`() = withOwner { owner ->
        var expired = 0
        val lease = reserve(owner, AdFormat.INTERSTITIAL) { expired++ }
        assertTrue(lease.start())
        owner.setLegacyInterstitialSuppressed(true)

        mainLooper.idleFor(90_000, TimeUnit.MILLISECONDS)

        assertTrue(lease.isCurrent())
        assertTrue(owner.isBusy())
        assertEquals(0, expired)
        assertTrue(lease.finish())
        // The legacy bit has expired, but it did not release the SDK's independent lease.
        assertFalse(owner.isBusy())
    }

    private fun reserve(
        owner: FullscreenPresentationOwner,
        format: AdFormat,
        onExpired: () -> Unit = {},
    ): FullscreenPresentationOwner.Lease =
        requireNotNull(owner.tryAcquire(format, Runnable { onExpired() })).also { acquired += it }

    private fun withOwner(block: (FullscreenPresentationOwner) -> Unit) {
        val owner = FullscreenPresentationOwner.getInstance()
        try {
            assertFalse("Every test must release its own leases", owner.isBusy())
            block(owner)
        } finally {
            acquired.toList().asReversed().forEach { it.finish() }
            owner.setLegacyInterstitialSuppressed(false)
            mainLooper.idle()
        }
    }
}
