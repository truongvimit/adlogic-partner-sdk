package io.paykit.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.paykit.R

/**
 * Opens the legal links.
 *
 * A device with no browser, a work profile that blocks the intent, or a misconfigured URL must
 * degrade to a localised message — the previous generation of this screen shipped a hardcoded
 * English toast and, when the URL was blank, silently did nothing at all.
 */
internal object PaywallLinks {

    fun open(context: Context, url: String) {
        if (url.isBlank()) {
            reportFailure(context)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (notFound: ActivityNotFoundException) {
            reportFailure(context)
        }
    }

    private fun reportFailure(context: Context) {
        Toast.makeText(context, R.string.pw_error_generic, Toast.LENGTH_SHORT).show()
    }
}
