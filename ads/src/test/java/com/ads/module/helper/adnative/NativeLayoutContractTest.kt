package com.ads.module.helper.adnative

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The geometry contract every reorderable native layout must keep.
 *
 * [NativeAdStyler] reorders blocks with `removeView` + `addView`, and `removeView` preserves each
 * child's LayoutParams. A margin left on a block therefore travels with it: move the CTA to the top
 * and it brings its `marginTop` along, while whatever lands last loses its `marginBottom`. The frame
 * then changes with the order, which is exactly what the config must never do.
 *
 * So spacing belongs to the container: symmetric `padding` for the frame, a fixed-height `divider`
 * with `showDividers="middle"` for the gaps. LinearLayout re-measures both at draw time against the
 * children that are visible *right now*, in the order they sit — which is what makes the layout
 * identical whichever order `components` asks for, and what makes a hidden block take its gap with it.
 *
 * A layout that is not a vertical stack — a full-screen overlay, a two-row compact card — is not
 * listed here on purpose: reordering has no meaning there, so it keeps visibility toggles only.
 */
class NativeLayoutContractTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val blockIds = setOf("block_icon_headline", "ad_body", "ad_media", "ad_call_to_action")

    /** Repo-relative, from the `:ads` module directory the test runs in. */
    private val layouts = listOf(
        "src/main/res/layout/custom_native_admob_medium.xml",
        "src/main/res/layout/custom_native_admob_free_size.xml",
        "../app/src/main/res/layout/layout_native_ad_full.xml",
        "../app/src/main/res/layout/layout_native_ad_small.xml",
        "../app/src/main/res/layout/layout_native_ad_large.xml",
        "../app/src/main/res/layout/layout_native_ad_medium.xml",
        "../app/src/main/res/layout/layout_native_permission.xml",
        "../app/src/main/res/layout/layout_native_welcome.xml",
    )

    @Test
    fun `every reorderable layout keeps spacing on the container, not on the blocks`() {
        val missing = layouts.filterNot { File(it).exists() }
        assertTrue("Layout not found — path drifted: $missing", missing.isEmpty())

        layouts.forEach { path ->
            val container = containerOf(path)
            assertEquals("$path: ad_container must be a LinearLayout", "LinearLayout", container.tagName)
            assertEquals("$path: ad_container must stack vertically", "vertical", attr(container, "orientation"))
            // Horizontal inset must come from the container, not from each block: that is what
            // keeps every block's left edge on the same line whichever order they end up in.
            // A bottom-anchored overlay column declares it side by side rather than as one
            // shorthand, so accept either spelling.
            val hasHorizontalPadding = attr(container, "padding") != null ||
                attr(container, "paddingHorizontal") != null ||
                (attr(container, "paddingStart") != null && attr(container, "paddingEnd") != null) ||
                (attr(container, "paddingLeft") != null && attr(container, "paddingRight") != null)
            assertTrue("$path: ad_container owns the horizontal inset — declare a padding", hasHorizontalPadding)
            assertTrue("$path: ad_container needs a divider — it is the gap between blocks", attr(container, "divider") != null)
            assertEquals(
                "$path: showDividers must be \"middle\" so the gap follows the order and disappears with a hidden block",
                "middle",
                attr(container, "showDividers"),
            )

            blocks(container).forEach { block ->
                val margins = (0 until block.attributes.length)
                    .map { block.attributes.item(it).nodeName }
                    .filter { it.substringAfter(':') .startsWith("layout_margin") }
                assertTrue(
                    "$path: ${idOf(block)} still carries $margins — reordering would move that spacing with it",
                    margins.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `every reorderable layout exposes the blocks the styler looks up`() {
        layouts.forEach { path ->
            val present = blocks(containerOf(path)).map { idOf(it) }
            assertEquals("$path: duplicate block id", present.size, present.toSet().size)
            assertTrue(
                "$path: has no block the styler can order — components would be inert",
                present.isNotEmpty(),
            )
            // ad_media is optional: a layout may deliberately ship without one.
            assertTrue(
                "$path: missing block_icon_headline / ad_call_to_action",
                present.containsAll(listOf("block_icon_headline", "ad_call_to_action")),
            )
        }
    }

    private fun containerOf(path: String): Element {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(path))
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            if (idOf(e) == "ad_container") return e
        }
        throw AssertionError("$path: no ad_container — the styler falls back to visibility-only there")
    }

    private fun blocks(container: Element): List<Element> =
        (0 until container.childNodes.length)
            .mapNotNull { container.childNodes.item(it) as? Element }
            .filter { idOf(it) in blockIds }

    private fun idOf(e: Element): String = attr(e, "id")?.substringAfterLast('/').orEmpty()

    private fun attr(e: Element, name: String): String? =
        e.getAttributeNS(android, name).takeIf { it.isNotEmpty() }
}
