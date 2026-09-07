package com.itg.template.retention

import android.content.Context
import android.content.res.Configuration
import com.itg.template.R
import com.itg.template.data.pref.AppSharedPreferencesApp
import io.retentionkit.core.RetentionFeature
import java.util.Locale

object RetentionExampleContent {
    private data class FeatureDefinition(val id: String, val labelRes: Int, val iconRes: Int, val descriptionRes: Int)
    private val catalogue = listOf(
        FeatureDefinition("notes", R.string.rk_example_notes, R.drawable.rk_example_notes, R.string.rk_example_notes_desc),
        FeatureDefinition("saved_items", R.string.rk_example_saved, R.drawable.rk_example_saved, R.string.rk_example_saved_desc),
        FeatureDefinition("text_tools", R.string.rk_example_text, R.drawable.rk_example_text, R.string.rk_example_text_desc),
        FeatureDefinition("guide", R.string.rk_example_guide, R.drawable.rk_example_guide, R.string.rk_example_guide_desc),
    )
    val featureIds = catalogue.map { it.id }.toSet()
    fun localizedContext(context: Context): Context {
        val code = AppSharedPreferencesApp(context).languageCode
        val locale = Locale.forLanguageTag(code.replace('_', '-'))
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }
    fun isVietnamese(context: Context) = AppSharedPreferencesApp(context).languageCode.startsWith("vi")
    fun features(context: Context) = catalogue.map { feature ->
        RetentionFeature(feature.id, context.getString(feature.labelRes), feature.iconRes, context.getString(feature.descriptionRes))
    }
}
