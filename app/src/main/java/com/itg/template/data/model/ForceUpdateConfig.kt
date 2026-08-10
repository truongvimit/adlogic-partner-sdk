package com.itg.template.data.model

import androidx.annotation.Keep

@Keep
data class ForceUpdateConfig(
    val icon: String = "",
    val title: String = "",
    val description: String = "",
    val storeLink: String = "",
    val minVersionCode: Int = 0,
    val force: Boolean = false
)

