package io.onboardkit.ui.language

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.ads.module.util.AdSystemBars
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.config.ObLanguage
import io.onboardkit.core.analytics.AnalyticsEvent

/**
 * Keeps the provider's actual binding container across popup openings. Moving only its child
 * would leave the helper reloading into the dismissed dialog after an ad click return.
 */
internal class ConfirmAdSlot {
    private var boundContainer: ViewGroup? = null

    fun capture(container: ViewGroup) {
        if (container.childCount > 0) boundContainer = container
    }

    fun attach(container: ViewGroup): Boolean {
        val bound = boundContainer ?: return false
        if (bound === container) return true
        (bound.parent as? ViewGroup)?.removeView(bound)
        container.removeAllViews()
        container.addView(bound)
        return true
    }

    fun detach() {
        val bound = boundContainer ?: return
        (bound.parent as? ViewGroup)?.removeView(bound)
    }

    fun clear() {
        detach()
        boundContainer = null
    }
}

/**
 * Figma "Modal" (node 3584:24369) — the LFO's Confirm Language prompt.
 *
 * Raised from the fourth language-item tap onward, including the selected language.
 *
 * The ad is optional by construction. [AdPlacement.LanguageConfirm] going unfilled collapses the
 * slot and leaves a plain two-button confirm — a prompt that trapped the user because its ad did
 * not load would cost far more than the impression is worth.
 *
 * A plain [Dialog] rather than a Material one, for the same reason [ObNoInternetDialog] is: the
 * SDK's theme is AppCompat and a Material dialog needs a theme overlay this module does not carry.
 */
internal class ObConfirmLanguageDialog(
    private val activity: Activity,
    private val language: ObLanguage,
    private val adSlot: ConfirmAdSlot,
    private val onConfirmed: () -> Unit,
) {

    private var dialog: Dialog? = null
    val isShowing: Boolean get() = dialog?.isShowing == true

    /** Guards the dismiss funnel: exactly one of accept/dismiss is reported per raise. */
    private var settled = false

    fun show() {
        if (dialog?.isShowing == true || activity.isFinishing || activity.isDestroyed) return
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.ob_dialog_confirm_language)
            // Back and the outside tap are both "no" — the same answer Cancel gives. They
            // share one reason because Dialog cannot tell them apart; the deliberate exits
            // (Cancel, the X) each report their own.
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { settle(REASON_DISMISS) }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(cardWidthPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
                setDimAmount(SCRIM_ALPHA)
                matchHostSystemBars()
            }
            bindContent(this)
            show()
        }
    }

    private fun bindContent(dialog: Dialog) {
        dialog.findViewById<TextView>(R.id.ob_confirm_message).text =
            activity.getString(R.string.ob_confirm_language_message, language.displayName)

        dialog.findViewById<TextView>(R.id.ob_confirm_accept).setOnClickListener {
            settle(null)
            dismiss()
            onConfirmed()
        }
        dialog.findViewById<TextView>(R.id.ob_confirm_cancel).setOnClickListener {
            settle(REASON_CANCEL)
            dismiss()
        }
        dialog.findViewById<ImageView>(R.id.ob_confirm_close).setOnClickListener {
            settle(REASON_CLOSE)
            dismiss()
        }

        fillAdSlot(dialog)
    }

    /**
     * The slot starts collapsed and expands as soon as there is something to show — the ad kept
     * from a previous raise, the newly bound one, or the skeleton standing in for it.
     *
     * A kept ad short-circuits everything: no request, no shimmer, no second impression. That is
     * what stops the second raise from spinning a skeleton until the waterfall times out.
     *
     * The block has to follow the *container's* contents, not just [showNativeAd]'s `onBound`:
     * that call puts its shimmer straight into the container and makes the container visible, but
     * it knows nothing about this block. Expanding only on `onBound` left the skeleton animating
     * inside a `GONE` parent, so the modal showed no loading state at all.
     *
     * A blocked placement never reaches that point: [showNativeAd] asks the guard before it builds
     * a skeleton, so `onUnavailable` fires with the container still empty and the modal stays a
     * plain two-button confirm — no ad, and no shimmer either, exactly as every other slot behaves
     * when its flag is off.
     *
     * `has_ad` answers "did the modal open with an ad in it", which is a synchronous question: a
     * kept or buffered ad binds inside this call, so only a genuine first-time load reports
     * `false` — counting the shimmer as an ad would report an impression the user may never see.
     */
    private fun fillAdSlot(dialog: Dialog) {
        val adBlock = dialog.findViewById<FrameLayout>(R.id.ob_confirm_ad_block)
        val container = dialog.findViewById<FrameLayout>(R.id.ob_confirm_native_container)

        if (adSlot.attach(container)) {
            adBlock.visibility = View.VISIBLE
            OnboardingSdk.track(AnalyticsEvent.LanguageConfirmShown(language.code, hasAd = true))
            return
        }

        var boundOnOpen = false
        activity.showNativeAd(
            placement = AdPlacement.LanguageConfirm,
            unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(AdPlacement.LanguageConfirm),
            container = container,
            onBound = {
                boundOnOpen = true
                adBlock.visibility = View.VISIBLE
                adSlot.capture(container)
            },
            onUnavailable = { adBlock.visibility = View.GONE },
        )
        // Anything in the container is either the bound ad or its skeleton, and both earn the
        // space. Empty means the guard declined before a skeleton was ever built.
        if (container.childCount > 0) adBlock.visibility = View.VISIBLE
        OnboardingSdk.track(AnalyticsEvent.LanguageConfirmShown(language.code, boundOnOpen))
    }

    fun dismiss() {
        // The ad outlives this dialog: detached, not released. Releasing here is what made the
        // next raise start from an empty buffer and shimmer until the waterfall gave up. The
        // Activity releases it for real in onDestroy, when the LFO itself is finished.
        adSlot.detach()
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
    }

    /** [reason] null means the user confirmed; anything else is one of the three ways out. */
    private fun settle(reason: String?) {
        if (settled) return
        settled = true
        val event = if (reason == null) {
            AnalyticsEvent.LanguageConfirmAccepted(language.code)
        } else {
            AnalyticsEvent.LanguageConfirmDismissed(language.code, reason)
        }
        OnboardingSdk.track(event)
    }

    /**
     * Figma's card is 328dp. On a narrower device that would run under the screen edges, so the
     * design width is a maximum rather than a fixed size.
     */
    private fun cardWidthPx(): Int {
        val metrics = activity.resources.displayMetrics
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
        val design = activity.resources.getDimensionPixelSize(R.dimen.ob_confirm_dialog_width)
        val available = metrics.widthPixels - (2 * dp(MIN_SIDE_MARGIN_DP)).toInt()
        return minOf(design, available)
    }

    // The LFO hides the navigation bar; while this window holds focus its own bar state applies,
    // so the bars would pop back for as long as the card is up unless it matches the host.
    private fun Window.matchHostSystemBars() {
        val system = OnboardingSdk.configOrNull()?.system ?: return
        AdSystemBars.setFullscreen(
            this, system.showStatusBar, system.showNavigationBar, system.showCaptionBar,
        )
    }

    private companion object {
        const val REASON_CANCEL = "cancel"
        const val REASON_CLOSE = "close"

        /** Back or a tap outside the card — one reason, because Dialog reports them alike. */
        const val REASON_DISMISS = "dismiss"

        /** Figma Shadow/xl reads as a soft scrim rather than the platform's default 0.6. */
        const val SCRIM_ALPHA = 0.4f
        const val MIN_SIDE_MARGIN_DP = 16f
    }
}
