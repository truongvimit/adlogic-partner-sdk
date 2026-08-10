package com.itg.template.ui.bases

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.itg.template.R
import com.itg.template.app.AppConstants
import com.itg.template.data.pref.AppSharedPref
import com.itg.template.utils.EasyPreferences
import java.util.Locale
import javax.inject.Inject

abstract class BaseDialog<VB : ViewDataBinding>(
    context: Context,
    themeResId: Int = R.style.ThemeDialog
) :
    Dialog(context, themeResId) {
    lateinit var mBinding: VB

    @Inject
    lateinit var appSharedPref: AppSharedPref

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        createContentView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setLocal()
        initViews()
        onResizeViews()
        onClickViews()
    }

    private fun createContentView() {
        val layoutView = getLayoutDialog()
        mBinding = DataBindingUtil.inflate(LayoutInflater.from(context), layoutView, null, false)
        setContentView(mBinding.root)
    }

    abstract fun getLayoutDialog(): Int

    open fun initViews() {}

    open fun onResizeViews() {}

    open fun onClickViews() {}


    fun setDialogBottom() {
        window?.run {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun setLocal() {
        val languageCode = appSharedPref.languageCode
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration()
        config.locale = locale
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }
}