package io.onboardkit.ads

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.ads.nativead.MediaView
import io.onboardkit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DialogNativeGeometryTest {
    @Test
    fun `popup video media meets the minimum size within the actual dialog layout`() {
        assertPopupGeometry()
    }

    @Test
    fun `popup video media still fits a dialog on a 320dp screen`() {
        // ObConfirmLanguageDialog keeps 16dp of screen margin on each side.
        assertPopupGeometry(cardWidthDp = 288)
    }

    private fun assertPopupGeometry(cardWidthDp: Int? = null) {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.ob_Theme_OnboardKit,
        )
        val inflater = LayoutInflater.from(context)
        val dialog = inflater.inflate(R.layout.ob_dialog_confirm_language, null)
        val container = dialog.findViewById<FrameLayout>(R.id.ob_confirm_native_container)
        inflater.inflate(R.layout.ob_layout_native_dialog, container, true)
        val headline = dialog.findViewById<TextView>(R.id.ad_headline).apply {
            text = "Test Ad : App Install"
        }
        val cta = dialog.findViewById<TextView>(R.id.ad_call_to_action).apply { text = "Install" }
        dialog.findViewById<View>(R.id.ob_confirm_ad_block).visibility = View.VISIBLE
        val density = context.resources.displayMetrics.density
        val width = cardWidthDp?.let { (it * density).toInt() }
            ?: context.resources.getDimensionPixelSize(R.dimen.ob_confirm_dialog_width)
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        dialog.layout(0, 0, dialog.measuredWidth, dialog.measuredHeight)
        val media = dialog.findViewById<MediaView>(R.id.ad_media)

        // Native video requires at least 120dp on each axis, including inside a popup.
        // https://support.google.com/admanager/answer/7031536?hl=en
        assertTrue("MediaView width was ${media.width / density}dp", media.width / density >= 120f)
        assertTrue("MediaView height was ${media.height / density}dp", media.height / density >= 120f)
        listOf(headline, cta).forEach { asset ->
            val textLayout = requireNotNull(asset.layout)
            assertTrue("Asset text must fit inside its view", textLayout.height <= asset.height)
            for (line in 0 until textLayout.lineCount) {
                assertEquals("Asset text must remain readable at ${width / density}dp", 0, textLayout.getEllipsisCount(line))
            }
        }
    }
}
