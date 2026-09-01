package io.onboardkit.ui.splash

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.onboardkit.OnboardingSdk
import io.onboardkit.R

/**
 * The splash's offline prompt: the flow needs the network, so the only way out is to get one.
 *
 * A plain [Dialog] rather than a Material sheet — the SDK's theme is AppCompat, under which a
 * `BottomSheetDialog` needs a theme overlay the module does not otherwise carry.
 */
internal class ObNoInternetDialog(
    private val activity: Activity,
    private val onRetry: () -> Unit,
) {
    private var dialog: Dialog? = null

    fun show() {
        if (dialog?.isShowing == true || activity.isFinishing) return
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.ob_dialog_no_internet)
            // The splash cannot run offline, so neither back nor a stray tap may dismiss this.
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            window?.apply {
                // The card draws its own rounded background; the default window one would
                // square the corners off behind it.
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
                attributes = attributes.apply { horizontalMargin = HORIZONTAL_MARGIN }
                matchHostSystemBars()
            }
            findViewById<TextView>(R.id.ob_no_internet_action).setOnClickListener { onRetry() }
            show()
        }
    }

    fun dismiss() {
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
    }

    // While the dialog holds focus its own window's bar state applies, so bars the splash hid
    // would pop back for as long as the card is up unless this window hides them too.
    private fun Window.matchHostSystemBars() {
        val system = OnboardingSdk.configOrNull()?.system ?: return
        val controller = WindowInsetsControllerCompat(this, decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!system.showStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
        if (!system.showNavigationBar) controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private companion object {
        /** Fraction of the screen width left either side of the card. */
        const val HORIZONTAL_MARGIN = 0.06f
    }
}
