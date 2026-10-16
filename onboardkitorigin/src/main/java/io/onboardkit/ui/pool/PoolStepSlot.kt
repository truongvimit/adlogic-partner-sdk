package io.onboardkit.ui.pool

import com.ads.module.helper.adnative.NativeClickAction
import io.onboardkit.ads.AdSkipReason

/**
 * What a pooled slot tells its page, with the lambda types of the classic `showNativeAd`, so a
 * page hands either path the same callbacks. Pooled slots always engage with
 * [NativeClickAction.NONE]: `click_action` does not apply to the pool (deviation D-CLICK).
 */
internal data class StepSlotCallbacks(
    val onLoading: () -> Unit = {},
    val onBound: () -> Unit = {},
    val onShown: () -> Unit = {},
    val onUnavailable: (AdSkipReason) -> Unit = {},
    val onAdEngaged: (NativeClickAction) -> Unit = {},
)
