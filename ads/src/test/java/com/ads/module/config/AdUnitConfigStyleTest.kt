package com.ads.module.config

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.helper.adnative.NativeAdStyler
import com.ads.module.helper.adnative.NativeComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `a listed subset still decides the blocks`() {
        assertEquals(
            listOf(NativeComponent.CTA, NativeComponent.BODY),
            parse(""""components":["cta","body"]""").toNativeStyle().components,
        )
    }
}
