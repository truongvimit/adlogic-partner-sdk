package com.ads.module.funtion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the daily click window.
 *
 * The cap this guards shipped inert for a long time: the rollover was gated on a flag that could
 * only become true inside the branch it guarded, so the window never opened, the timestamp was
 * never written and the counters never reset. Every assertion below fails against that version.
 */
class AdmobHelperTest {

    private val context = FakeContext()

    private fun windowStart(): Long = context.prefs.getLong(KEY_FIRST_TIME, 0L)

    @Test
    fun `first read opens the window and starts at zero`() {
        assertEquals(0, AdmobHelper.getNumClickAdsPerDay(context, UNIT))
        assertEquals(
            "the window timestamp must be persisted, or the 24h check can never come due",
            true,
            windowStart() > 0L,
        )
    }

    @Test
    fun `clicks accumulate per ad unit`() {
        repeat(3) { AdmobHelper.increaseNumClickAdsPerDay(context, UNIT) }
        AdmobHelper.increaseNumClickAdsPerDay(context, OTHER_UNIT)

        assertEquals(3, AdmobHelper.getNumClickAdsPerDay(context, UNIT))
        assertEquals(1, AdmobHelper.getNumClickAdsPerDay(context, OTHER_UNIT))
    }

    @Test
    fun `counts survive inside the window`() {
        AdmobHelper.increaseNumClickAdsPerDay(context, UNIT)
        context.prefs.edit().putLong(KEY_FIRST_TIME, System.currentTimeMillis() - HOUR_MS * 23)
            .apply()

        assertEquals(1, AdmobHelper.getNumClickAdsPerDay(context, UNIT))
    }

    @Test
    fun `counts reset once the window is 24h old`() {
        AdmobHelper.increaseNumClickAdsPerDay(context, UNIT)
        AdmobHelper.increaseNumClickAdsPerDay(context, OTHER_UNIT)
        context.prefs.edit().putLong(KEY_FIRST_TIME, System.currentTimeMillis() - HOUR_MS * 25)
            .apply()

        assertEquals(0, AdmobHelper.getNumClickAdsPerDay(context, UNIT))
        assertEquals(0, AdmobHelper.getNumClickAdsPerDay(context, OTHER_UNIT))
        assertEquals(
            "a rollover must open a fresh window, not leave the expired timestamp behind",
            true,
            System.currentTimeMillis() - windowStart() < HOUR_MS,
        )
    }

    /** A device clock moved backwards must not freeze the window open forever. */
    @Test
    fun `a backwards clock rolls the window over instead of stalling it`() {
        AdmobHelper.increaseNumClickAdsPerDay(context, UNIT)
        context.prefs.edit().putLong(KEY_FIRST_TIME, System.currentTimeMillis() + HOUR_MS * 48)
            .apply()

        assertEquals(0, AdmobHelper.getNumClickAdsPerDay(context, UNIT))
    }

    private companion object {
        const val UNIT = "ca-app-pub-0000000000000000/1111111111"
        const val OTHER_UNIT = "ca-app-pub-0000000000000000/2222222222"
        const val KEY_FIRST_TIME = "KEY_FIRST_TIME"
        const val HOUR_MS = 60L * 60 * 1000
    }
}

/** AdmobHelper only needs `getSharedPreferences`, so no Robolectric and no mocking framework. */
private class FakeContext : ContextWrapper(null) {

    val prefs = FakePrefs()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

private class FakePrefs : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

/** Mirrors the real editor's ordering: `clear()` is applied before the staged puts. */
private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {

    private val staged = mutableMapOf<String, Any?>()
    private val removed = mutableSetOf<String>()
    private var cleared = false

    override fun putString(key: String, value: String?) = stage(key, value)

    override fun putStringSet(key: String, value: MutableSet<String>?) = stage(key, value)

    override fun putInt(key: String, value: Int) = stage(key, value)

    override fun putLong(key: String, value: Long) = stage(key, value)

    override fun putFloat(key: String, value: Float) = stage(key, value)

    override fun putBoolean(key: String, value: Boolean) = stage(key, value)

    override fun remove(key: String): SharedPreferences.Editor {
        removed += key
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        cleared = true
        return this
    }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (cleared) values.clear()
        removed.forEach(values::remove)
        values.putAll(staged)
        staged.clear()
        removed.clear()
        cleared = false
    }

    private fun stage(key: String, value: Any?): SharedPreferences.Editor {
        staged[key] = value
        return this
    }
}
