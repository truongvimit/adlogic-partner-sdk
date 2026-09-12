package com.ads.module.helper.reward

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.consent.ConsentCenter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lambda overload must answer the same outcomes as the callback overload, exactly once.
 * Rewarded is the format where that is not free: `onClosed` and `onFailedToShow` are separate
 * vendor paths with no latch between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RewardCompletionOverloadTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
        RewardAdManager.releaseAll()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        RewardAdManager.releaseAll()
        AdRemoteConfig.reset()
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
    }

    private fun install(enabled: Boolean) = AdRemoteConfig.update(
        AdRemoteConfig(mapOf("reward_example" to AdUnitConfig(id = "unit", isEnable = enabled))),
    )

    @Test
    fun `both overloads report one failure when nothing is buffered`() {
        install(enabled = true)
        var codes = mutableListOf<Int>()
        RewardAdManager.show(activity, "reward_example", object : RewardShowCallback() {
            override fun onFailedToShow(codeError: Int) { codes += codeError }
        })

        val earnedResults = mutableListOf<Boolean>()
        RewardAdManager.show(activity, "reward_example") { earnedResults += it }

        assertEquals(listOf(0), codes)
        assertEquals("a failure to show earned nothing", listOf(false), earnedResults)
    }

    @Test
    fun `a disabled placement completes once through the lambda overload`() {
        install(enabled = false)

        val earnedResults = mutableListOf<Boolean>()
        RewardAdManager.show(activity, "reward_example") { earnedResults += it }

        assertEquals(listOf(false), earnedResults)
    }

    @Test
    fun `duplicate vendor terminals cannot complete the lambda twice`() {
        install(enabled = true)
        val earnedResults = mutableListOf<Boolean>()
        // The wrapper the lambda overload builds, driven the way a misbehaving vendor would.
        val callback = RewardAdManager.javaClass
            .getDeclaredMethod("completionOnly", Function1::class.java)
            .apply { isAccessible = true }
            .invoke(RewardAdManager, { earned: Boolean -> earnedResults += earned }) as RewardShowCallback

        callback.onClosed(earned = true)
        callback.onClosed(earned = true)
        callback.onFailedToShow(0)

        assertEquals(listOf(true), earnedResults)
    }
}
