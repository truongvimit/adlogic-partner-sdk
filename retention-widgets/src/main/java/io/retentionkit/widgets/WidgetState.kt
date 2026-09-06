package io.retentionkit.widgets

import io.retentionkit.core.RetentionStore
import org.json.JSONArray
import org.json.JSONObject

internal class WidgetState(private val store: RetentionStore) {
    fun instances(): List<WidgetInstance> = store.snapshot(INSTANCES).entries().values.map(::decodeInstance)
    fun instance(id: Int): WidgetInstance? = store.snapshot(INSTANCES).string(id.toString())?.let(::decodeInstance)
    fun save(instance: WidgetInstance) = store.transaction(INSTANCES) { it.put(instance.appWidgetId.toString(), encodeInstance(instance)) }
    fun delete(ids: IntArray) = store.transaction(INSTANCES) { state -> ids.forEach { state.remove(it.toString()) } }
    fun restore(oldIds: IntArray, newIds: IntArray) = store.transaction(INSTANCES) { state ->
        // Read the whole mapping first: restore IDs can overlap old IDs.
        val restored = oldIds.zip(newIds).map { (old, new) ->
            new to state.string(old.toString())?.let(::decodeInstance)?.copy(appWidgetId = new)
        }
        oldIds.forEach { state.remove(it.toString()) }
        restored.forEach { (id, instance) -> if (instance != null) state.put(id.toString(), encodeInstance(instance)) }
    }
    fun pins(): List<PinRecord> = store.snapshot(PINS).entries().values.map(::decodePin)
    fun pin(token: String): PinRecord? = store.snapshot(PINS).string(token)?.let(::decodePin)
    fun savePin(pin: PinRecord) = store.transaction(PINS) { state ->
        // Bounded callback capability ledger. Evicted/expired capabilities fail closed.
        val records = state.entries().values.map(::decodePin).sortedBy { it.created }
        if (state.string(pin.token) == null && records.size >= 32) {
            records.filter { it.status != "pending" }.firstOrNull()?.let { state.remove(it.token) }
            check(state.entries().size < 32) { "Pin request capacity reached" }
        }
        state.put(pin.token, encodePin(pin))
    }
    private fun encodeInstance(instance: WidgetInstance): String = JSONObject().apply {
        put("id", instance.appWidgetId); put("features", JSONArray(instance.featureIds))
        put("width", instance.minWidthDp); put("height", instance.minHeightDp)
    }.toString()
    private fun decodeInstance(raw: String): WidgetInstance = JSONObject(raw).let { json ->
        WidgetInstance(json.getInt("id"), json.getJSONArray("features").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }, json.optInt("width"), json.optInt("height"))
    }
    private fun encodePin(pin: PinRecord): String = JSONObject().apply {
        put("token", pin.token); put("provider", pin.provider); put("created", pin.created); put("deadline", pin.deadline)
        put("baseline", JSONArray(pin.baseline)); put("status", pin.status); put("widget", pin.widgetId); put("reason", pin.reason)
    }.toString()
    private fun decodePin(raw: String): PinRecord = JSONObject(raw).let { json ->
        PinRecord(json.getString("token"), json.getString("provider"), json.getLong("created"), json.getLong("deadline"),
            json.getJSONArray("baseline").let { array -> (0 until array.length()).map { array.getInt(it) } },
            json.getString("status"), json.optInt("widget", -1), json.optString("reason"))
    }
    private companion object {
        const val INSTANCES = "widgets.instances"
        const val PINS = "widgets.pins"
    }
}

internal data class PinRecord(
    val token: String, val provider: String, val created: Long, val deadline: Long, val baseline: List<Int>,
    val status: String = "pending", val widgetId: Int = -1, val reason: String = "",
) {
    fun result(): WidgetPinResult = when (status) {
        "confirmed" -> WidgetPinResult.Confirmed(token, widgetId)
        "pending" -> WidgetPinResult.Requested(token)
        "unknown" -> WidgetPinResult.Unknown(token, reason)
        "unsupported" -> WidgetPinResult.Unavailable(reason)
        else -> WidgetPinResult.Failed(reason)
    }
}
