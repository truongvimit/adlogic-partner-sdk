package io.onboardkit.remote

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.ui.widget.ObPrimaryButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NextButtonTextColorTest {
    @After fun reset() { OnboardingSettings.document.acceptSuccessfulFetch(null) }

    @Test fun `remote Next text color overrides legacy color without changing last state`() {
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"next_button":{"text_color":"#1E88E5"}}}"""))
        val button = ObPrimaryButton(ApplicationProvider.getApplicationContext<Application>())
        button.overrideLabels(null, null, Color.RED)
        button.overrideNextTextColor(OnboardingSettings.nextButtonTextColor(Color.RED))
        assertEquals(Color.parseColor("#1E88E5"), button.currentTextColor)
        button.state = ObPrimaryButton.State.LAST
        assertEquals(Color.RED, button.currentTextColor)
    }

    @Test fun `invalid or empty remote color preserves existing color`() {
        for (color in listOf("not-a-color", "")) {
            OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"next_button":{"text_color":"$color"}}}""")
            assertEquals(Color.RED, OnboardingSettings.nextButtonTextColor(Color.RED))
        }
    }
}
