package io.onboardkit.ads

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The binding contract for the native that can take the splash bottom slot.
 *
 * `Admob.populateUnifiedNativeAdView` finds its views by id and casts them — `ad_call_to_action`
 * to a `TextView`, `ad_media` to a `MediaView` — inside a `try/catch` that swallows the failure. A
 * renamed id or a retyped view therefore does not crash; it silently ships an ad with no headline,
 * on the one screen every launch passes through. This test is the only thing that notices.
 */
class SplashNativeLayoutTest {

    /** Repo-relative, from the `:onboardkitorigin` module directory the test runs in. */
    private val layout = File("src/main/res/layout/ob_layout_native_media_left.xml")

    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun `root is a NativeAdView`() {
        assertTrue("Layout not found — path drifted: $layout", layout.exists())
        assertEquals(
            "the ad view must be the root, or setNativeAd has nothing to register against",
            "com.google.android.gms.ads.nativead.NativeAdView",
            document().documentElement.tagName,
        )
    }

    @Test
    fun `every asset the populate binds is present and correctly typed`() {
        val byId = elementsById()
        // ad_app_icon / ad_price / ad_advertiser / ad_stars are deliberately absent: the design
        // has no slot for them, and populate's try/catch tolerates a missing view.
        assertEquals(
            "ad_media must be a MediaView or the ad renders without its image",
            "com.google.android.gms.ads.nativead.MediaView",
            byId["ad_media"]?.tagName,
        )
        assertEquals("TextView", byId["ad_headline"]?.tagName)
        assertEquals("TextView", byId["ad_body"]?.tagName)
        // populate casts this to TextView to setText; a LinearLayout here silently loses the label
        assertEquals("TextView", byId["ad_call_to_action"]?.tagName)
    }

    @Test
    fun `ad_media carries the rounded box, so hiding it hides the box`() {
        val media = elementsById()["ad_media"]!!
        // The MediaView's image is added as a child at bind time and would otherwise paint square
        // corners straight over the 4dp radius the background draws.
        assertEquals("true", media.getAttributeNS(android, "clipToOutline"))
        // With no `ad_container`, NativeAdStyler toggles `ad_media`'s own visibility for a remote
        // `components` list that drops "media". The background must ride on the view that gets
        // hidden — on the well it would leave an empty grey block holding half the card.
        assertEquals("@drawable/ob_bg_splash_native_media", media.getAttributeNS(android, "background"))
    }

    @Test
    fun `the ratio sits on the well and never on ad_media`() {
        val byId = elementsById()
        // The 4:3 belongs to the wrapper, not the MediaView: NativeAdShimmer.skeletonizeMedia
        // rewrites an unresolved `ad_media` height to a flat 160dp floor, so a ratio owned by
        // `ad_media` would leave the skeleton taller than the ad that replaces it and the slot
        // would jump on every launch. `ad_media` must stay match_parent inside the well.
        val auto = "http://schemas.android.com/apk/res-auto"
        assertEquals(
            "H,4:3",
            byId["ob_splash_native_media_well"]?.getAttributeNS(auto, "layout_constraintDimensionRatio"),
        )
        assertEquals("", byId["ad_media"]!!.getAttributeNS(auto, "layout_constraintDimensionRatio"))
        assertEquals("match_parent", byId["ad_media"]!!.getAttributeNS(android, "layout_height"))
    }


    @Test
    fun `the layout is not a reorderable stack`() {
        // No ad_container: NativeAdStyler reorders only inside one, and this card is a horizontal
        // split where reordering would put the CTA beside the media. `components` stays a
        // visibility switch here, and `positionCTA` has nothing to move — which is why neither is
        // declared on the native_splash entry in ad_config.
        assertTrue(
            "an ad_container would make `components` reorder this horizontal card",
            "ad_container" !in elementsById().keys,
        )
    }

    private fun document() = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(layout)

    private fun elementsById(): Map<String, Element> {
        val all = document().getElementsByTagName("*")
        return (0 until all.length)
            .map { all.item(it) as Element }
            .mapNotNull { element ->
                element.getAttributeNS(android, "id")
                    .takeIf { it.isNotEmpty() }
                    ?.substringAfterLast('/')
                    ?.let { it to element }
            }
            .toMap()
    }
}
