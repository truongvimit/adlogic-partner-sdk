package io.onboardkit.ui.base

import android.app.Application
import android.os.Build
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class ContentInsetsReaderTest {
    private val originalMask = WindowInsetsCompat.Type.systemBars() or
        WindowInsetsCompat.Type.displayCutout()
    private val fallbackMask = WindowInsetsCompat.Type.statusBars() or
        WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.captionBar() or
        WindowInsetsCompat.Type.displayCutout()

    @Test
    @Config(sdk = [28, 33, 34])
    fun healthyPlatformKeepsSystemOverlayInsetsAndExcludesKeyboard() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsetsCompat.Type.systemOverlays(), Insets.of(0, 0, 0, 80))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
            .build()
        val bottom = if (Build.VERSION.SDK_INT >= 34) 80 else 48
        assertEquals(Insets.of(0, 24, 0, bottom), ContentInsetsReader().getInsets(insets))
    }

    @Test
    fun missingSystemOverlaysRetriesAndUsesFreshInsetsWithoutRepeatingBrokenCall() {
        val brokenInsets = mock(WindowInsetsCompat::class.java)
        `when`(brokenInsets.getInsets(originalMask)).thenThrow(NoSuchMethodError(
            "No static method systemOverlays()I in class Landroid/view/WindowInsets\$Type;",
        ))
        val cutoutAndBars = Insets.of(12, 40, 0, 48)
        `when`(brokenInsets.getInsets(fallbackMask)).thenReturn(cutoutAndBars)
        val reader = ContentInsetsReader()
        repeat(3) { assertEquals(cutoutAndBars, reader.getInsets(brokenInsets)) }
        verify(brokenInsets, times(1)).getInsets(originalMask)

        val hiddenBars = mock(WindowInsetsCompat::class.java)
        `when`(hiddenBars.getInsets(fallbackMask)).thenReturn(Insets.of(12, 40, 0, 0))
        assertEquals(Insets.of(12, 40, 0, 0), reader.getInsets(hiddenBars))
    }

    @Test
    fun unrelatedMissingMethodIsNotSwallowed() {
        val insets = mock(WindowInsetsCompat::class.java)
        val failure = NoSuchMethodError("No virtual method getInsets(I)")
        `when`(insets.getInsets(originalMask)).thenThrow(failure)
        assertSame(failure, assertThrows(NoSuchMethodError::class.java) {
            ContentInsetsReader().getInsets(insets)
        })
    }

    @Test
    fun fallbackFailureIsNotSwallowed() {
        val insets = mock(WindowInsetsCompat::class.java)
        `when`(insets.getInsets(originalMask)).thenThrow(NoSuchMethodError("systemOverlays()I"))
        val failure = IllegalStateException("unexpected framework failure")
        `when`(insets.getInsets(fallbackMask)).thenThrow(failure)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            ContentInsetsReader().getInsets(insets)
        })
    }
}
