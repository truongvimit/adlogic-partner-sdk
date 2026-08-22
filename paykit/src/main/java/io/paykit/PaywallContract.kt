package io.paykit

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import io.paykit.ui.PaywallActivity

/**
 * `registerForActivityResult` entry point for hosts that would rather have a result than a
 * listener; [PayKit.addListener] keeps working alongside it.
 */
class PaywallContract : ActivityResultContract<PaywallPlacement, PaywallResult>() {

    override fun createIntent(context: Context, input: PaywallPlacement): Intent =
        intentFor(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): PaywallResult =
        resultOf(resultCode, intent)

    companion object {

        internal const val EXTRA_PLACEMENT = "io.paykit.extra.PLACEMENT"
        internal const val EXTRA_TOKEN = "io.paykit.extra.TOKEN"
        internal const val EXTRA_STATUS = "io.paykit.extra.STATUS"
        internal const val EXTRA_PRODUCT_ID = "io.paykit.extra.PRODUCT_ID"
        internal const val EXTRA_ERROR_CODE = "io.paykit.extra.ERROR_CODE"
        internal const val EXTRA_ERROR_MESSAGE = "io.paykit.extra.ERROR_MESSAGE"

        private const val STATUS_PURCHASED = "purchased"
        private const val STATUS_CONTINUE_WITH_ADS = "continue_with_ads"
        private const val STATUS_DISMISSED = "dismissed"
        private const val STATUS_ERROR = "error"

        // The token travels in the Intent so it survives the Activity being re-created; the
        // contract path has none, and those presentations only reach global listeners.
        internal fun intentFor(
            context: Context,
            placement: PaywallPlacement,
            token: String? = null,
        ): Intent = Intent(context, PaywallActivity::class.java)
            .putExtra(EXTRA_PLACEMENT, placement.key)
            .putExtra(EXTRA_TOKEN, token)

        internal fun tokenOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_TOKEN)

        // An intent with no placement was built by hand; OTHER keeps analytics honest instead
        // of attributing that view to a real checkpoint.
        internal fun placementOf(intent: Intent?): PaywallPlacement =
            intent?.getStringExtra(EXTRA_PLACEMENT)
                ?.let(PaywallPlacement::fromKey)
                ?: PaywallPlacement.OTHER

        /** The one result format, so `setResult` and [parseResult] can never drift apart. */
        internal fun resultIntent(result: PaywallResult): Intent = Intent().apply {
            when (result) {
                is PaywallResult.Purchased -> {
                    putExtra(EXTRA_STATUS, STATUS_PURCHASED)
                    putExtra(EXTRA_PRODUCT_ID, result.productId)
                }

                PaywallResult.ContinueWithAds -> putExtra(EXTRA_STATUS, STATUS_CONTINUE_WITH_ADS)

                PaywallResult.Dismissed -> putExtra(EXTRA_STATUS, STATUS_DISMISSED)

                is PaywallResult.Error -> {
                    putExtra(EXTRA_STATUS, STATUS_ERROR)
                    putExtra(EXTRA_ERROR_CODE, result.code)
                    putExtra(EXTRA_ERROR_MESSAGE, result.message)
                }
            }
        }

        // Back, a swipe-away or a process kill lands here with no extras at all; reading that as
        // Dismissed is what lets onFinished fire exactly once on every exit path.
        internal fun resultOf(resultCode: Int, intent: Intent?): PaywallResult {
            if (resultCode != Activity.RESULT_OK || intent == null) return PaywallResult.Dismissed
            return when (intent.getStringExtra(EXTRA_STATUS)) {
                STATUS_PURCHASED ->
                    PaywallResult.Purchased(intent.getStringExtra(EXTRA_PRODUCT_ID).orEmpty())

                STATUS_CONTINUE_WITH_ADS -> PaywallResult.ContinueWithAds

                STATUS_ERROR -> PaywallResult.Error(
                    intent.getIntExtra(EXTRA_ERROR_CODE, 0),
                    intent.getStringExtra(EXTRA_ERROR_MESSAGE).orEmpty(),
                )

                else -> PaywallResult.Dismissed
            }
        }
    }
}
