package io.onboardkit.ads

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.helper.adnative.NativeAdStyler
import com.google.android.gms.ads.nativead.MediaView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeTemplateIdTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private val cardSlots = mapOf(
        AdPlacement.Language1 to "native_lang",
        AdPlacement.WelcomeBack1 to "native_welcome1",
        AdPlacement.StepNative(StepId.OB1) to "native_ob1",
        AdPlacement.StepNative(StepId.PARTNER_PRIVACY) to "native_select",
        AdPlacement.SplashInlineNative to "native_splash",
    )

    private val cardDefaults = listOf(
        R.layout.ob_layout_native_lfo,
        R.layout.ob_layout_native_lfo,
        R.layout.ob_layout_native_lfo,
        R.layout.ob_layout_native_media_left,
        R.layout.ob_layout_native_media_left,
    )

    private val fixedSlots = mapOf(
        AdPlacement.LanguageConfirm to "native_popup_lang",
        AdPlacement.SplashNative to "native_fs",
        AdPlacement.StepFullScreen(StepId.FULL1) to "native_full1",
        AdPlacement.Ob5 to "native_onboarding_fullscreen_1_4",
    )

    private val fixedFrames = listOf(
        R.layout.ob_layout_native_dialog,
        R.layout.ob_layout_native_fullscreen,
        R.layout.ob_layout_native_fullscreen,
        R.layout.ob_layout_native_fullscreen,
    )

    @Before fun setup() {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        OnboardingSdk.install(app) {
            adProvider = FakeAdProvider()
            trackkitAutoTracking(false)
        }
        AdRemoteConfig.reset()
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
    }

    @After fun cleanup() {
        AdRemoteConfig.reset()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
    }

    private fun declare(slots: Map<AdPlacement, String>, fields: String) {
        AdRemoteConfig.initializeFromJson(slots.values.joinToString(prefix = "{", postfix = "}") {
            """"$it":{"id":"$it","isEnable":true$fields}"""
        })
    }

    private fun layouts(slots: Map<AdPlacement, String>) = slots.keys.map(NativeTemplates::layoutForPlacement)

    @Test fun `without templateId every slot renders its default frame`() {
        declare(cardSlots + fixedSlots, "")
        assertEquals(cardDefaults + fixedFrames, layouts(cardSlots + fixedSlots))
        assertEquals("TEMPLATE_1", NativeTemplates.variantFor(AdPlacement.Language1))
        assertEquals("TEMPLATE_2", NativeTemplates.variantFor(AdPlacement.SplashInlineNative))
        assertEquals("DIALOG", NativeTemplates.variantFor(AdPlacement.LanguageConfirm))
        assertEquals("FULL_SCREEN", NativeTemplates.variantFor(AdPlacement.Ob5))
    }

    @Test fun `each templateId picks its layout on every card slot`() {
        mapOf(
            1 to R.layout.ob_layout_native_lfo,
            2 to R.layout.ob_layout_native_media_left,
            3 to R.layout.ob_layout_native_med_1_91,
        ).forEach { (id, layout) ->
            declare(cardSlots, ""","templateId":$id""")
            assertEquals("templateId $id", cardSlots.map { layout }, layouts(cardSlots))
            assertEquals("TEMPLATE_$id", NativeTemplates.variantFor(AdPlacement.Language1))
        }
    }

    @Test fun `an unknown templateId falls back to the slot's default`() {
        declare(cardSlots, ""","templateId":99""")
        assertEquals(cardDefaults, layouts(cardSlots))
        assertEquals("TEMPLATE_1", NativeTemplates.variantFor(AdPlacement.Language1))
    }

    @Test fun `the modal and full-screen slots keep their fixed frame`() {
        declare(fixedSlots, ""","templateId":3""")
        assertEquals(fixedFrames, layouts(fixedSlots))
    }

    @Test fun `every template is an SDK frame, so a live templateId change can re-frame the slot`() {
        listOf(1, 2, 3).forEach { id ->
            assertTrue(NativeTemplates.isSdkLayout(NativeTemplates.layoutForTemplateId(id)!!))
        }
    }

    @Test fun `templates 1 and 3 carry every AdMob asset view they draw under the SDK ids`() {
        listOf(R.layout.ob_layout_native_lfo, R.layout.ob_layout_native_med_1_91).forEach { layout ->
            val root = inflate(layout)
            assertTrue(root.findViewById<View>(com.ads.module.R.id.ad_media) is MediaView)
            assertTrue(root.findViewById<View>(com.ads.module.R.id.ad_headline) is TextView)
            assertTrue(root.findViewById<View>(com.ads.module.R.id.ad_call_to_action) is TextView)
            assertTrue(root.findViewById<View>(com.ads.module.R.id.ad_icon) is TextView)
        }
        val med = inflate(R.layout.ob_layout_native_med_1_91)
        assertTrue(med.findViewById<View>(com.ads.module.R.id.ad_body) is TextView)
        assertTrue(med.findViewById<View>(com.ads.module.R.id.ad_app_icon) is ImageView)
    }

    @Test fun `with no components declared the CTA sits at the bottom of templates 1 and 3`() {
        declare(mapOf(AdPlacement.Language1 to "native_lang"), "")
        listOf(R.layout.ob_layout_native_lfo, R.layout.ob_layout_native_med_1_91).forEach { layout ->
            assertEquals(com.ads.module.R.id.ad_call_to_action, visibleOrder(layout).last())
        }
    }

    @Test fun `components arranges template 1 with the CTA on top or at the bottom`() {
        val head = com.ads.module.R.id.block_icon_headline
        val media = com.ads.module.R.id.block_media
        val cta = com.ads.module.R.id.ad_call_to_action
        mapOf(
            """["icon_headline","media","cta"]""" to listOf(head, media, cta),
            """["cta","media","icon_headline"]""" to listOf(cta, media, head),
            """["media","cta"]""" to listOf(media, cta),
        ).forEach { (components, order) ->
            declare(mapOf(AdPlacement.Language1 to "native_lang"), ""","components":$components""")
            assertEquals(components, order, visibleOrder(R.layout.ob_layout_native_lfo))
        }
    }

    @Test fun `components arranges template 3 into Figma layouts A to D`() {
        val head = com.ads.module.R.id.block_icon_headline
        val media = com.ads.module.R.id.block_media
        val cta = com.ads.module.R.id.ad_call_to_action
        mapOf(
            """["media","icon_headline","body","cta"]""" to listOf(media, head, cta),
            """["cta","icon_headline","body","media"]""" to listOf(cta, head, media),
            """["icon_headline","body","media","cta"]""" to listOf(head, media, cta),
            """["cta","media","icon_headline","body"]""" to listOf(cta, media, head),
        ).forEach { (components, order) ->
            declare(mapOf(AdPlacement.Language1 to "native_lang"), ""","templateId":3,"components":$components""")
            assertEquals(components, order, visibleOrder(R.layout.ob_layout_native_med_1_91))
        }
    }

    @Test fun `a layout without ad_container ignores components`() {
        declare(mapOf(AdPlacement.Language1 to "native_lang"), ""","components":["cta"]""")
        listOf(R.layout.ob_layout_native_media_left, R.layout.ob_layout_native_dialog).forEach { layout ->
            val root = inflate(layout)
            NativeAdStyler.applyLayout(root, AdRemoteConfig.getInstance().unit("native_lang").toNativeStyle())
            listOf(com.ads.module.R.id.ad_media, com.ads.module.R.id.ad_headline, com.ads.module.R.id.ad_body)
                .forEach { assertEquals(View.VISIBLE, root.findViewById<View>(it).visibility) }
        }
    }

    @Test fun `colorBackground paints the card of every SDK frame and of its skeleton`() {
        declare(mapOf(AdPlacement.Language1 to "native_lang"), ""","colorBackground":"#102030"""")
        val style = AdRemoteConfig.getInstance().unit("native_lang").toNativeStyle()
        val color = android.graphics.Color.parseColor("#102030")
        listOf(
            R.layout.ob_layout_native_lfo, R.layout.ob_layout_native_media_left,
            R.layout.ob_layout_native_med_1_91, R.layout.ob_layout_native_dialog,
            R.layout.ob_layout_native_fullscreen, R.layout.ob_layout_native_compact,
        ).forEach { layout ->
            val name = app.resources.getResourceEntryName(layout)
            val root = inflate(layout)
            val card = root.getChildAt(0)
            NativeAdStyler.applyAppearance(root, style)
            assertEquals(name, color, card.background.fillColor())

            val skeleton = com.ads.module.helper.adnative.NativeAdShimmer.from(app, layout, style)
            val skeletonCard = skeleton.findViewById<View>(com.ads.module.R.id.ad_background)
                ?: skeleton.findViewById(com.ads.module.R.id.ad_container)
            assertEquals("$name skeleton", color, skeletonCard.background.fillColor())
        }
    }

    @Test fun `without colorBackground every SDK frame keeps its XML background`() {
        declare(mapOf(AdPlacement.Language1 to "native_lang"), "")
        val style = AdRemoteConfig.getInstance().unit("native_lang").toNativeStyle()
        listOf(R.layout.ob_layout_native_lfo, R.layout.ob_layout_native_media_left, R.layout.ob_layout_native_med_1_91)
            .forEach { layout ->
                val root = inflate(layout)
                val before = root.getChildAt(0).background
                NativeAdStyler.applyAppearance(root, style)
                assertTrue(before === root.getChildAt(0).background)
            }
    }

    @Test fun `the Ad badge colors paint the badge of every SDK frame, the skeleton text stays hidden`() {
        declare(mapOf(AdPlacement.Language1 to "native_lang"), ""","colorAdBadge":"#102030","colorAdBadgeText":"#00FF00"""")
        val style = AdRemoteConfig.getInstance().unit("native_lang").toNativeStyle()
        listOf(
            R.layout.ob_layout_native_lfo, R.layout.ob_layout_native_media_left,
            R.layout.ob_layout_native_med_1_91, R.layout.ob_layout_native_dialog,
            R.layout.ob_layout_native_fullscreen, R.layout.ob_layout_native_compact,
        ).forEach { layout ->
            val name = app.resources.getResourceEntryName(layout)
            val root = inflate(layout)
            NativeAdStyler.applyAppearance(root, style)
            assertEquals(name, android.graphics.Color.GREEN, root.badgeLabel().currentTextColor)

            val skeleton = com.ads.module.helper.adnative.NativeAdShimmer.from(app, layout, style)
            assertEquals("$name skeleton", android.graphics.Color.TRANSPARENT, skeleton.badgeLabel().currentTextColor)
        }
    }

    @Test fun `template 3 Ad badge text is white by default`() {
        val badge = inflate(R.layout.ob_layout_native_med_1_91).findViewById<TextView>(com.ads.module.R.id.ad_icon)
        assertEquals(android.graphics.Color.WHITE, badge.currentTextColor)
    }

    private fun ViewGroup.badgeLabel(): TextView = findViewById<View>(com.ads.module.R.id.ad_icon).let { badge ->
        badge as? TextView ?: (badge as ViewGroup).getChildAt(0) as TextView
    }

    private fun android.graphics.drawable.Drawable.fillColor(): Int? = when (this) {
        is android.graphics.drawable.GradientDrawable -> color?.defaultColor
        is android.graphics.drawable.ColorDrawable -> color
        else -> null
    }

    @Test fun `template 3 media keeps the 1·91 to 1 ratio of the design`() {
        val root = inflate(R.layout.ob_layout_native_med_1_91)
        val width = (328 * app.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val media = root.findViewById<View>(com.ads.module.R.id.ad_media)
        assertEquals(1.91f, media.measuredWidth.toFloat() / media.measuredHeight, 0.02f)
    }

    @Test fun `template 1 media spans the full width at 1·68 to 1`() {
        val root = inflate(R.layout.ob_layout_native_lfo)
        val width = (360 * app.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val media = root.findViewById<View>(com.ads.module.R.id.ad_media)
        assertEquals(width, media.measuredWidth)
        assertEquals(1.68f, media.measuredWidth.toFloat() / media.measuredHeight, 0.02f)
    }

    private fun visibleOrder(layout: Int): List<Int> {
        val root = inflate(layout)
        NativeAdStyler.applyLayout(root, AdRemoteConfig.getInstance().unit("native_lang").toNativeStyle())
        val stack = root.findViewById<LinearLayout>(com.ads.module.R.id.ad_container)
        return (0 until stack.childCount).map(stack::getChildAt).filter { it.visibility == View.VISIBLE }.map { it.id }
    }

    private fun inflate(layout: Int): ViewGroup =
        LayoutInflater.from(app).inflate(layout, null) as ViewGroup
}
