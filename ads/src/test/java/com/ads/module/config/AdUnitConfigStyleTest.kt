package com.ads.module.config

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.drawable.DrawableCompat
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.helper.adnative.NativeAdStyler
import com.ads.module.helper.adnative.NativeComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdUnitConfigStyleTest {
    private fun parse(fields: String) = AdConfigParser.parse(
        """{"native_home":{"id":"x","isEnable":true,$fields}}""".reader(),
    ).getValue("native_home")

    @Test fun `an empty components array keeps every block of the layout`() {
        val unit = parse(""""components":[]""")
        assertEquals(emptyList<String>(), unit.components)
        val style = unit.toNativeStyle()
        assertNull(style.components)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = listOf(R.id.block_icon_headline, R.id.ad_body, R.id.ad_media, R.id.ad_call_to_action)
        val container = LinearLayout(context).apply {
            id = R.id.ad_container
            orientation = LinearLayout.VERTICAL
            ids.forEach { blockId -> addView(View(context).apply { id = blockId }) }
        }
        val root = LinearLayout(context).apply { addView(container) }
        NativeAdStyler.applyLayout(root, style)
        ids.forEachIndexed { index, blockId ->
            assertEquals(View.VISIBLE, root.findViewById<View>(blockId).visibility)
            assertEquals(blockId, container.getChildAt(index).id)
        }
    }

    @Test fun `remote CTA color also fills the Ad badge without replacing its shape`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val badgeShape = GradientDrawable().apply { setColor(Color.YELLOW); cornerRadius = 4f }
        val badge = View(context).apply { id = R.id.ad_icon; background = badgeShape }
        val cta = View(context).apply { id = R.id.ad_call_to_action }
        val root = LinearLayout(context).apply { addView(badge); addView(cta) }
        assertNotNull(root.findViewById<View>(R.id.ad_icon))
        val style = parse("\"colorCTA\":\"#1E88E5\"").toNativeStyle()
        assertEquals(Color.parseColor("#1E88E5"), style.ctaBackgroundColor)
        NativeAdStyler.applyAppearance(root, style)
        assertNotNull(badge.background)
        assertEquals(badgeShape, DrawableCompat.unwrap(badge.background))
        val ctaBackground = cta.background as GradientDrawable
        assertEquals(Color.parseColor("#1E88E5"), ctaBackground.color?.defaultColor)
        assertEquals(4f, (DrawableCompat.unwrap(badge.background) as GradientDrawable).cornerRadius)
    }

    @Test fun `block_media stands in for ad_media when the media sits in a ratio well`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val media = View(context).apply { id = R.id.ad_media }
        val well = android.widget.FrameLayout(context).apply { id = R.id.block_media; addView(media) }
        val container = LinearLayout(context).apply {
            id = R.id.ad_container
            orientation = LinearLayout.VERTICAL
            addView(View(context).apply { id = R.id.block_icon_headline })
            addView(well)
            addView(View(context).apply { id = R.id.ad_call_to_action })
        }
        val root = LinearLayout(context).apply { addView(container) }

        NativeAdStyler.applyLayout(root, parse(""""components":["media","icon_headline","cta"]""").toNativeStyle())
        assertEquals(listOf(R.id.block_media, R.id.block_icon_headline, R.id.ad_call_to_action),
            (0 until container.childCount).map { container.getChildAt(it).id })

        NativeAdStyler.applyLayout(root, parse(""""components":["icon_headline","cta"]""").toNativeStyle())
        assertEquals(View.GONE, well.visibility)
    }

    @Test fun `a listed subset still decides the blocks`() {
        assertEquals(
            listOf(NativeComponent.CTA, NativeComponent.BODY),
            parse(""""components":["cta","body"]""").toNativeStyle().components,
        )
    }
}
