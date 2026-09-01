package io.onboardkit.ui.language

import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule that keeps the confirm modal's ad alive across dismissals.
 *
 * Releasing the ad when the dialog closed made every re-tap load from scratch, and on a waterfall
 * that did not fill the user watched a skeleton until it timed out. These cases pin the hand-off:
 * the ad view survives its dialog and lands in the next one without a request.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.13 ships framework jars up to 34; the module compiles against 36.
@Config(sdk = [34])
class ConfirmAdSlotTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun container() = FrameLayout(context)

    private fun containerWithAd(): Pair<FrameLayout, TextView> {
        val c = container()
        val ad = TextView(context)
        c.addView(ad)
        return c to ad
    }

    @Test
    fun `an empty slot asks the caller to load`() {
        assertFalse("nothing kept yet — the dialog must request", ConfirmAdSlot().attach(container()))
    }

    @Test
    fun `the captured ad is re-shown in the next dialog without reloading`() {
        val slot = ConfirmAdSlot()
        val (first, ad) = containerWithAd()
        slot.capture(first)
        slot.detach()

        val second = container()
        assertTrue(slot.attach(second))
        assertSame("the same ad view, not a reload", ad, second.getChildAt(0))
    }

    @Test
    fun `attach re-parents even when the previous dialog never detached`() {
        // The dismiss path detaches, but a load landing after dismiss captures into a container
        // that is still the view's parent. A View may only have one, so attach has to move it.
        val slot = ConfirmAdSlot()
        val (first, ad) = containerWithAd()
        slot.capture(first)

        val second = container()
        assertTrue(slot.attach(second))
        assertSame(ad, second.getChildAt(0))
        assertNull("must be off the old dialog's container", ad.parent?.takeIf { it === first })
    }

    @Test
    fun `attach replaces whatever the container already held`() {
        // The container may still hold a skeleton from an earlier raise.
        val slot = ConfirmAdSlot()
        val (first, ad) = containerWithAd()
        slot.capture(first)
        slot.detach()

        val second = container().apply { addView(TextView(context)) }
        assertTrue(slot.attach(second))
        assertSame(ad, second.getChildAt(0))
        assertSame("the skeleton must be gone, not stacked under the ad", 1, second.childCount)
    }

    @Test
    fun `the slot survives repeated open and close`() {
        val slot = ConfirmAdSlot()
        val (first, ad) = containerWithAd()
        slot.capture(first)

        repeat(3) {
            slot.detach()
            val next = container()
            assertTrue("raise ${it + 2} must still re-show the kept ad", slot.attach(next))
            assertSame(ad, next.getChildAt(0))
        }
    }

    @Test
    fun `clear releases the slot so the provider may destroy the ad`() {
        val slot = ConfirmAdSlot()
        val (first, ad) = containerWithAd()
        slot.capture(first)
        slot.clear()

        assertFalse("a cleared slot must not hand the ad back", slot.attach(container()))
        assertNull("and it must not be left attached anywhere", ad.parent)
    }

    @Test
    fun `capturing an empty container keeps nothing`() {
        // onBound fires only after a real bind, but a defensive capture must not store null-ness
        // as if it were an ad.
        val slot = ConfirmAdSlot()
        slot.capture(container())
        assertFalse(slot.attach(container()))
    }
}
