package com.ads.module.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle

import com.ads.module.R

open class ResumeLoadingDialog(context: Context) : Dialog(context, R.style.AppTheme) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_resume_loading)
    }

    // After attach: hiding bars on a not-yet-attached dialog window is silently dropped.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        HostWindowBars.mirror(this)
    }
}
