package io.onboardkit.remote

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.ui.widget.ObPrimaryButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingPrimaryColorTest {
    @After fun reset() { OnboardingSettings.document.acceptSuccessfulFetch(null) }

    @Test fun `bundled primary color is the shared fallback`() {
        assertEquals(Color.parseColor("#FF375E"), OnboardingSettings.onboardingPrimaryColor())
    }

    @Test fun `remote primary color overrides both button states`() {
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"primary_color":"#1E88E5"}}"""))
        assertEquals(Color.parseColor("#1E88E5"), OnboardingSettings.onboardingPrimaryColor())
        val button = ObPrimaryButton(ApplicationProvider.getApplicationContext<Application>())
        button.overrideLabels(null, null, OnboardingSettings.onboardingPrimaryColor())
        assertEquals(Color.parseColor("#1E88E5"), button.currentTextColor)
        button.state = ObPrimaryButton.State.LAST
        assertEquals(Color.parseColor("#1E88E5"), button.currentTextColor)
    }

    @Test fun `invalid or empty remote color leaves local color in charge`() {
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"onboarding":{"primary_color":"not-a-color"}}""",
        )
        assertEquals(Color.parseColor("#FF375E"), OnboardingSettings.onboardingPrimaryColor())
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"primary_color":""}}""")
        assertNull(OnboardingSettings.onboardingPrimaryColor())
    }
}
