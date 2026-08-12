package io.trackkit

/**
 * Maps an ad unit id back to the placement that requested it.
 *
 * AdMob's `OnPaidEventListener` only knows the ad unit, so without this every revenue event is
 * blind to the screen that produced it — the exact gap that makes the previous generation unable to
 * say whether an impression came from the language screen or from a full-screen onboarding step.
 * The ad callback decorator registers the mapping when a load is requested; the paid-event bridge
 * reads it back.
 */
object PlacementRegistry {

    private const val MAX_ENTRIES = 64

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_ENTRIES
    }

    private val lock = Any()

    @JvmStatic
    fun register(adUnitId: String?, placement: String?) {
        if (adUnitId.isNullOrBlank() || placement.isNullOrBlank()) return
        synchronized(lock) { map[adUnitId] = placement }
    }

    @JvmStatic
    @JvmOverloads
    fun placementOf(adUnitId: String?, fallback: String = "unknown"): String {
        if (adUnitId.isNullOrBlank()) return fallback
        return synchronized(lock) { map[adUnitId] } ?: fallback
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) { map.clear() }
    }
}
