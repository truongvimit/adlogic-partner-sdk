package io.onboardkit.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.R
import io.onboardkit.config.FullScreenSkipPosition
import io.onboardkit.config.FullScreenSkipStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullScreenSkipPositionTest {

    private fun skipButton(): FrameLayout {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.ob_Theme_OnboardKit,
        )
        val root = LayoutInflater.from(context)
            .inflate(R.layout.ob_activity_fullscreen_ad, null) as FrameLayout
        return root.findViewById(R.id.ob_skip_button)
    }

    private fun place(position: FullScreenSkipPosition, style: FullScreenSkipStyle) =
        skipButton().apply { applyFullScreenSkip(style, position) }
            .layoutParams as FrameLayout.LayoutParams

    @Test fun `the two sides are mirrors - same inset from their edge, same top margin`() {
        val right = place(FullScreenSkipPosition.RIGHT, FullScreenSkipStyle.CLOSE_ICON)
        val left = place(FullScreenSkipPosition.LEFT, FullScreenSkipStyle.CLOSE_ICON)

        assertEquals(Gravity.TOP or Gravity.END, right.gravity)
        assertEquals(Gravity.TOP or Gravity.START, left.gravity)
        // The layout only declares the end inset; LEFT flush against the edge was the bug.
        assertTrue(right.marginEnd > 0)
        assertEquals(right.marginEnd, right.marginStart)
        assertEquals(right.marginEnd, left.marginStart)
        assertEquals(right.marginStart, left.marginEnd)
        assertEquals(right.topMargin, left.topMargin)
    }

    @Test fun `the text variant takes the same side and inset as the close icon`() {
        val icon = place(FullScreenSkipPosition.LEFT, FullScreenSkipStyle.CLOSE_ICON)
        val text = place(FullScreenSkipPosition.LEFT, FullScreenSkipStyle.TEXT)

        assertEquals(icon.gravity, text.gravity)
        assertEquals(icon.marginStart, text.marginStart)
        assertEquals(icon.marginEnd, text.marginEnd)
        assertEquals(icon.topMargin, text.topMargin)
    }

    @Test fun `applying a side twice does not grow the inset`() {
        val button = skipButton()
        button.applyFullScreenSkip(FullScreenSkipStyle.CLOSE_ICON, FullScreenSkipPosition.LEFT)
        val once = (button.layoutParams as FrameLayout.LayoutParams).marginStart
        button.applyFullScreenSkip(FullScreenSkipStyle.CLOSE_ICON, FullScreenSkipPosition.RIGHT)
        val params = button.layoutParams as FrameLayout.LayoutParams
        assertEquals(once, params.marginStart)
        assertEquals(once, params.marginEnd)
    }
}
