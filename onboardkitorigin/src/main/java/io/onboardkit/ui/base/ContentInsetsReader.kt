package io.onboardkit.ui.base

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

/** Reads the visible bars and cutout used to pad an onboarding content root. */
internal class ContentInsetsReader {
    private var typeMask = WindowInsetsCompat.Type.systemBars() or
        WindowInsetsCompat.Type.displayCutout()

    fun getInsets(insets: WindowInsetsCompat): Insets {
        try {
            return insets.getInsets(typeMask)
        } catch (error: NoSuchMethodError) {
            // Some frameworks report API 34 without providing its systemOverlays() method.
            // Keep overlays on valid platforms and let unrelated linkage errors surface.
            val includesOverlays = typeMask and WindowInsetsCompat.Type.systemOverlays() != 0
            if (!includesOverlays || error.message?.contains("systemOverlays") != true) throw error
            typeMask = WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.captionBar() or
                WindowInsetsCompat.Type.displayCutout()
        }
        // Remember only the supported types for this listener, never the insets themselves:
        // bar visibility and cutout padding can change on every dispatch.
        return insets.getInsets(typeMask)
    }
}
