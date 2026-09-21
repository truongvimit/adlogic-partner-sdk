package com.ads.module.application

import android.app.Application

import com.ads.module.config.ERainAdConfig
import com.ads.module.util.SharePreferenceUtils

import java.util.ArrayList

// Name kept for source compatibility. minSdk 24 has native multidex, so the androidx
// MultiDexApplication superclass was a no-op.
abstract class AdsMultiDexApplication : Application() {

    @JvmField
    protected var mERainAdConfig: ERainAdConfig? = null

    @JvmField
    protected var listTestDevice: List<String>? = null

    override fun onCreate() {
        super.onCreate()
        listTestDevice = ArrayList<String>()
        mERainAdConfig = ERainAdConfig(this)
        if (SharePreferenceUtils.getInstallTime(this) == 0L) {
            SharePreferenceUtils.setInstallTime(this)
        }
    }
}
