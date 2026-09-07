package com.itg.template.retention

import android.content.Context
import android.content.res.Configuration
import com.itg.template.R
import com.itg.template.data.pref.AppSharedPreferencesApp
import io.retentionkit.core.RetentionFeature
import java.util.Locale

object RetentionExampleContent {
    val featureIds = setOf("notes", "saved_items", "text_tools", "guide")
    fun localizedContext(context: Context): Context {
        val code = AppSharedPreferencesApp(context).languageCode
        val locale = Locale.forLanguageTag(code.replace('_', '-'))
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }
    fun isVietnamese(context: Context) = AppSharedPreferencesApp(context).languageCode.startsWith("vi")
    fun features(context: Context) = listOf(
        RetentionFeature("notes", context.getString(R.string.rk_example_notes), R.drawable.rk_example_notes, context.getString(R.string.rk_example_notes_desc)),
        RetentionFeature("saved_items", context.getString(R.string.rk_example_saved), R.drawable.rk_example_saved, context.getString(R.string.rk_example_saved_desc)),
        RetentionFeature("text_tools", context.getString(R.string.rk_example_text), R.drawable.rk_example_text, context.getString(R.string.rk_example_text_desc)),
        RetentionFeature("guide", context.getString(R.string.rk_example_guide), R.drawable.rk_example_guide, context.getString(R.string.rk_example_guide_desc)),
    )
}
