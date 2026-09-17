package com.ads.module.helper.adnative

/** One exclusive action per native click, captured until the destination returns. */
enum class NativeClickAction(val remoteValue: String) {
    AUTO_NEXT("auto_next"),
    NONE("none"),
    RELOAD("reload");

    companion object {
        fun fromRemote(value: String): NativeClickAction? = entries.firstOrNull { it.remoteValue == value }
    }
}
