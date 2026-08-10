package com.itg.template.ui.component.main.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import com.bumptech.glide.Glide
import com.itg.template.BuildConfig
import com.itg.template.R
import com.itg.template.data.model.ForceUpdateConfig
import com.itg.template.databinding.DialogForceUpdateBinding
import com.itg.template.ui.bases.ext.click
import timber.log.Timber

class ForceUpdateDialog(private val activity: Activity) {
    private var dialog: Dialog? = null

    fun show(config: ForceUpdateConfig) {
        if (dialog?.isShowing == true) return
        val binding = DialogForceUpdateBinding.inflate(activity.layoutInflater)
        val alertDialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        alertDialog.setContentView(binding.root)
        alertDialog.setCancelable(!config.force)

        binding.txtForceUpdateTitle.text =
            config.title.ifBlank { activity.getString(R.string.app_name) }
        binding.txtForceUpdateDescription.text =
            config.description.ifBlank { activity.getString(R.string.force_update_message) }

        if (config.icon.isNotBlank()) {
            Glide.with(activity)
                .load(config.icon)
                .placeholder(R.mipmap.ic_launcher)
                .into(binding.imgForceUpdate)
        } else {
            binding.imgForceUpdate.setImageResource(R.mipmap.ic_launcher)
        }

        binding.btnUpdateNow.click {
            val finalLink = config.storeLink.ifBlank { defaultStoreLink() }
            openStoreLink(finalLink, config.force.not(), alertDialog)
        }

        alertDialog.show()
        dialog = alertDialog
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun openStoreLink(link: String, canDismiss: Boolean, alertDialog: Dialog?) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            activity.startActivity(intent)
            if (canDismiss) {
                alertDialog?.dismiss()
            }
        } catch (ex: Exception) {
            Timber.e(ex)
        }
    }

    private fun defaultStoreLink(): String =
        "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
}

