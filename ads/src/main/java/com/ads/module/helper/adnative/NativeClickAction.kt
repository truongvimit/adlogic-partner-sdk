package com.ads.module.helper.adnative

/** One exclusive action per native click, captured until the destination returns. */
enum class NativeClickAction(val remoteValue: String) {
    AUTO_NEXT("auto_next"),
    NONE("none"),

    /** Replacement requests only the lowest floor (all price), so a quick return still finds it. */
    RELOAD("reload"),

    /** Replacement walks the whole waterfall, highest floor first. */
    RELOAD_WATERFALL("reload_waterfall");

    val reloads: Boolean get() = this == RELOAD || this == RELOAD_WATERFALL

    companion object {
        fun fromRemote(value: String): NativeClickAction? = entries.firstOrNull { it.remoteValue == value }
    }
}
