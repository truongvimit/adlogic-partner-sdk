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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdUnitConfigStyleTest {
    private fun parse(fields: String) = AdConfigParser.parse(
        """{"native_home":{"ids":[{"id":"x"}],"isEnable":true,$fields}}""".reader(),
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

    @Test fun `colorBackground recolors the card and keeps its shape`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shape = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12f; setStroke(2, Color.GRAY) }
        val card = LinearLayout(context).apply { id = R.id.ad_container; background = shape }
        val root = android.widget.FrameLayout(context).apply { addView(card) }

        NativeAdStyler.applyAppearance(root, parse(""""colorBackground":"#102030"""").toNativeStyle())
        val painted = card.background as GradientDrawable
        assertEquals(Color.parseColor("#102030"), painted.color?.defaultColor)
        assertEquals(12f, painted.cornerRadius)
    }

    @Test fun `ad_background is the card when the layout names one`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inner = LinearLayout(context).apply { id = R.id.ad_container; setBackgroundColor(Color.WHITE) }
        val card = android.widget.FrameLayout(context).apply { id = R.id.ad_background; addView(inner) }
        val root = android.widget.FrameLayout(context).apply { addView(card) }

        NativeAdStyler.applyAppearance(root, parse(""""colorBackground":"#102030"""").toNativeStyle())
        assertEquals(Color.parseColor("#102030"), (card.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(Color.WHITE, (inner.background as android.graphics.drawable.ColorDrawable).color)
    }

    @Test fun `without colorBackground the XML background is untouched`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shape = GradientDrawable().apply { setColor(Color.WHITE) }
        val card = LinearLayout(context).apply { id = R.id.ad_container; background = shape }
        NativeAdStyler.applyAppearance(android.widget.FrameLayout(context).apply { addView(card) }, parse(""""colorCTA":"#1E88E5"""").toNativeStyle())
        assertEquals(shape, card.background)
    }

    @Test fun `a listed subset still decides the blocks`() {
        assertEquals(
            listOf(NativeComponent.CTA, NativeComponent.BODY),
            parse(""""components":["cta","body"]""").toNativeStyle().components,
        )
    }

    private fun badgeRoot(): Pair<android.widget.FrameLayout, android.widget.TextView> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val badge = android.widget.TextView(context).apply {
            id = R.id.ad_icon
            background = GradientDrawable().apply { setColor(Color.YELLOW) }
            setTextColor(Color.BLACK)
        }
        return android.widget.FrameLayout(context).apply { addView(badge) } to badge
    }

    @Test fun `the Ad badge colors win over colorCTA on the badge, colorCTA alone still fills it`() {
        val (ctaOnly, plain) = badgeRoot()
        NativeAdStyler.applyAppearance(ctaOnly, parse(""""colorCTA":"#1E88E5"""").toNativeStyle())
        assertEquals(Color.BLACK, plain.currentTextColor)
        assertEquals(Color.parseColor("#1E88E5"), plain.background.pixel())

        val (both, badge) = badgeRoot()
        val unit = parse(""""colorCTA":"#1E88E5","colorAdBadge":"#102030","colorAdBadgeText":"#FFFFFF"""")
        NativeAdStyler.applyAppearance(both, unit.toNativeStyle())
        assertEquals(Color.WHITE, badge.currentTextColor)
        assertEquals(Color.parseColor("#102030"), badge.background.pixel())
    }

    @Test fun `without the Ad badge fields the badge keeps its XML colors`() {
        val (root, badge) = badgeRoot()
        val before = badge.background
        NativeAdStyler.applyAppearance(root, parse(""""heightCTA":44""").toNativeStyle())
        assertEquals(Color.BLACK, badge.currentTextColor)
        assertTrue(before === badge.background)
    }

    // GradientDrawable keeps setTint in its constant state; Robolectric does not rasterize it
    private fun android.graphics.drawable.Drawable.pixel(): Int? =
        org.robolectric.util.ReflectionHelpers.getField<android.content.res.ColorStateList?>(
            DrawableCompat.unwrap<android.graphics.drawable.Drawable>(this).constantState, "mTint",
        )?.defaultColor
}
