package com.ads.module.consent

import android.content.Context
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/** Boundary around UMP's static entry points; the consent policy remains in [ConsentCenter]. */
internal interface UmpClient {
    fun consentInformation(context: Context): ConsentInformation

    fun loadForm(context: Context, onLoaded: (ConsentForm) -> Unit, onError: (FormError) -> Unit)
}

internal object PlatformUmpClient : UmpClient {
    override fun consentInformation(context: Context): ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    override fun loadForm(
        context: Context,
        onLoaded: (ConsentForm) -> Unit,
        onError: (FormError) -> Unit,
    ) {
        UserMessagingPlatform.loadConsentForm(context, onLoaded, onError)
    }
}
