package io.onboardkit.ads

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The binding contract for the Confirm Language modal's native.
 *
 * `Admob.populateUnifiedNativeAdView` finds its views by id and casts them — `ad_stars` to a
 * `RatingBar`, `ad_call_to_action` to a `TextView` — inside a `try/catch` that swallows the
 * failure. A renamed id or a retyped view therefore does not crash; it silently ships an ad with
 * no headline. This test is the only thing that notices.
 */
class DialogNativeLayoutTest {

    /** Repo-relative, from the `:onboardkitorigin` module directory the test runs in. */
    private val layout = File("src/main/res/layout/ob_layout_native_dialog.xml")

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
        // ad_app_icon / ad_price / ad_advertiser are deliberately absent: the design has no slot
        // for them, and populate's try/catch tolerates a missing view.
        assertEquals(
            "ad_media must be a MediaView or the ad renders without its image",
            "com.google.android.gms.ads.nativead.MediaView",
            byId["ad_media"]?.tagName,
        )
        assertEquals("TextView", byId["ad_headline"]?.tagName)
        assertEquals("TextView", byId["ad_body"]?.tagName)
        // populate casts this to TextView to setText; a LinearLayout here silently loses the label
        assertEquals("TextView", byId["ad_call_to_action"]?.tagName)
        assertEquals(
            "populate casts the star view to RatingBar",
            "RatingBar",
            byId["ad_stars"]?.tagName,
        )
    }

    @Test
    fun `the media well clips its child to the rounded background`() {
        val media = elementsById()["ad_media"]!!
        // The MediaView's image is added as a child at bind time and would otherwise paint square
        // corners straight over the 4dp radius the background draws.
        assertEquals("true", media.getAttributeNS(android, "clipToOutline"))
    }

    @Test
    fun `the layout is not a reorderable stack`() {
        // No ad_container: NativeAdStyler reorders only inside one, and this card is a horizontal
        // split where reordering would put the CTA beside the media. `components` stays a
        // visibility switch here, which is what the absence of the id declares.
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
