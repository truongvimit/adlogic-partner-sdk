package com.ads.module.admob

import com.ads.module.config.settings.AdBehavior

/**
 * What a return from the background shows, selected by `app_resume.type` in ad_behavior_config.
 * Each value is also the ad_config key it spends: a type whose key is off or undeclared shows
 * nothing rather than falling back to another type.
 */
enum class ResumeAdType(val remoteValue: String) {
    OPEN("open_resume"),
    NATIVE("native_resume"),
    INTER("inter_resume"),
    NONE("none");

    /** The ad_config key this type spends, or null for [NONE]. */
    val placement: String? get() = remoteValue.takeUnless { this == NONE }

    companion object {
        const val SETTING = "app_resume.type"

        @JvmStatic
        fun current(): ResumeAdType = fromRemote(AdBehavior.text(SETTING)) ?: NATIVE

        @JvmStatic
        fun fromRemote(value: String?): ResumeAdType? = entries.firstOrNull { it.remoteValue == value }
    }
}
