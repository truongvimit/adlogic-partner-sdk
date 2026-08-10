package io.onboardkit.ui.language

enum class LanguageScreenMode {
    /** First-open flow: ads on, completing writes flow state. */
    FIRST_OPEN,

    /** Settings screen: no ads, real back button, only the language itself is saved. */
    SETTINGS,
}
