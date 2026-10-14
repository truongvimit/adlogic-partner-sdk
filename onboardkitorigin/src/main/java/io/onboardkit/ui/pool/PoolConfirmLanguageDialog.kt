package io.onboardkit.ui.pool

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.util.AdSystemBars
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.config.ObLanguage
import io.onboardkit.core.analytics.AnalyticsEvent

/**
 * The pooled flow's copy of the LFO confirm modal; the classic one is untouched. Same chrome,
 * sizing, scrim and analytics, but its ad is an owner slot of the pool instead of a kept provider
 * buffer, so it is a [ComponentDialog]: the slot follows its lifecycle.
 *
 * The LFO claims [AdPlacement.LanguageConfirm] before [show] and takes the owner back in
 * [onClosed], which runs exactly once however the modal goes away.
 */
internal class PoolConfirmLanguageDialog(
    private val activity: ComponentActivity,
    private val language: ObLanguage,
    private val destroyOnTeardown: Boolean,
    private val onConfirmed: () -> Unit,
    private val onClosed: () -> Unit,
) {

    private var dialog: ComponentDialog? = null
    val isShowing: Boolean get() = dialog?.isShowing == true

    /** Guards the dismiss funnel: exactly one of accept/dismiss is reported per raise. */
    private var settled = false
    private var closed = false

    fun show() {
        if (dialog != null) return
        if (activity.isFinishing || activity.isDestroyed) {
            close()
            return
        }
        dialog = ComponentDialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.ob_dialog_confirm_language)
            // Back and the outside tap are both "no", reported under one reason as in the classic modal.
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { settle(REASON_DISMISS) }
            // Dialog stops synchronously on every dismiss; its dismiss listener would run a frame later.
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) close()
            })
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

    private fun bindContent(dialog: ComponentDialog) {
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
     * The block starts collapsed and follows the slot: open for a skeleton or an ad, closed when
     * the pool has nothing to show. `has_ad` is the selection at the moment the modal opens, so a
     * skeleton that may never fill is not counted as an ad.
     */
    private fun fillAdSlot(dialog: ComponentDialog) {
        val adBlock = dialog.findViewById<FrameLayout>(R.id.ob_confirm_ad_block)
        val container = dialog.findViewById<FrameLayout>(R.id.ob_confirm_native_container)
        PoolNativeSlot(
            owner = dialog,
            placement = AdPlacement.LanguageConfirm,
            kind = PoolNativeSlot.Kind.OWNER,
            minState = Lifecycle.State.STARTED,
            container = container,
            callbacks = StepSlotCallbacks(
                onLoading = { adBlock.isVisible = true },
                onBound = { adBlock.isVisible = true },
                onUnavailable = { adBlock.isVisible = false },
            ),
            destroyOnTeardown = destroyOnTeardown,
        ).start()
        OnboardingSdk.track(AnalyticsEvent.LanguageConfirmShown(language.code, hasAd = selectionHasAd()))
    }

    /** What the owner slot would draw now: an ad, unless the flow or this placement is refused. */
    private fun selectionHasAd(): Boolean {
        if (OnboardingSdk.guard().resumeEntrySkipReason(activity) != null) return false
        if (OnboardingPool.lastResult(AdPlacement.LanguageConfirm) is LoadResult.SkippedByConfig) return false
        val selection = OnboardingPool.engineOrNull()?.currentSelection() ?: return false
        return selection.show && selection.ad != null
    }

    fun dismiss() {
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
        close()
    }

    private fun close() {
        if (closed) return
        closed = true
        onClosed()
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

    /** Figma's 328dp card is a maximum: a narrower device keeps a side margin. */
    private fun cardWidthPx(): Int {
        val metrics = activity.resources.displayMetrics
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
        val design = activity.resources.getDimensionPixelSize(R.dimen.ob_confirm_dialog_width)
        val available = metrics.widthPixels - (2 * dp(MIN_SIDE_MARGIN_DP)).toInt()
        return minOf(design, available)
    }

    // While this window holds focus its own bar state applies; it must match the host's.
    private fun Window.matchHostSystemBars() {
        val system = OnboardingSdk.configOrNull()?.system ?: return
        AdSystemBars.setFullscreen(this, system.showStatusBar, system.showNavigationBar, system.showCaptionBar)
    }

    private companion object {
        const val REASON_CANCEL = "cancel"
        const val REASON_CLOSE = "close"

        /** Back or a tap outside the card: one reason, because Dialog reports them alike. */
        const val REASON_DISMISS = "dismiss"

        const val SCRIM_ALPHA = 0.4f
        const val MIN_SIDE_MARGIN_DP = 16f
    }
}
