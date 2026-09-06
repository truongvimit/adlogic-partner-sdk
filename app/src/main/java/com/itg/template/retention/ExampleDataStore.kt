package com.itg.template.retention

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable local results and an outbox. No user text is sent to telemetry. Main app process only. */
class ExampleDataStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("retention_example_data_v1", Context.MODE_PRIVATE)
    data class Operation(val id: String, val featureId: String, val result: String, val reported: Boolean)

    fun savedPhrases(): List<ExamplePhrase> = synchronized(lock) {
        val saved = read().optJSONArray("saved") ?: JSONArray()
        (0 until saved.length()).mapNotNull { index -> ExampleUtilities.phrases.firstOrNull { it.id == saved.getString(index) } }
    }
    fun savePhrase(id: String, operationId: String): Operation? = updateSaved(id, operationId, true)
    fun removePhrase(id: String, operationId: String): Operation? = updateSaved(id, operationId, false)
    private fun updateSaved(id: String, operationId: String, save: Boolean): Operation? = synchronized(lock) {
        ExampleUtilities.phrase(id)
        val state = read()
        val ids = state.optJSONArray("saved")?.let { a -> (0 until a.length()).map(a::getString).toMutableSet() } ?: mutableSetOf()
        val changed = if (save) ids.add(id) else ids.remove(id)
        if (!changed) return@synchronized null
        state.put("saved", JSONArray(ids.sorted()))
        record(state, operationId, "saved_phrases", "${if (save) "saved" else "removed"}:$id")
    }
    fun record(operationId: String, featureId: String, result: String): Operation = synchronized(lock) {
        record(read(), operationId, featureId, result)
    }
    private fun record(state: JSONObject, operationId: String, featureId: String, result: String): Operation {
        require(operationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")))
        require(featureId in setOf("translate", "saved_phrases", "text_tools", "document"))
        require(result.length <= 12_000)
        val operations = state.optJSONObject("operations") ?: JSONObject().also { state.put("operations", it) }
        operations.optJSONObject(operationId)?.let { previous ->
            val old = decode(operationId, previous)
            require(old.featureId == featureId && old.result == result) { "Operation ID reused for different work" }
            return old
        }
        if (operations.length() >= 128) {
            operations.keys().asSequence().firstOrNull { operations.getJSONObject(it).optBoolean("reported") }?.let(operations::remove)
        }
        check(operations.length() < 128) { "Pending operation outbox is full" }
        val entry = JSONObject().put("feature", featureId).put("result", result).put("reported", false)
        operations.put(operationId, entry)
        state.put("last_result", result).put("last_feature", featureId)
        persist(state)
        return decode(operationId, entry)
    }
    fun pendingSuccesses(): List<Operation> = synchronized(lock) {
        val operations = read().optJSONObject("operations") ?: return@synchronized emptyList()
        operations.keys().asSequence().map { decode(it, operations.getJSONObject(it)) }.filter { !it.reported }.toList()
    }
    fun markReported(operationId: String) = synchronized(lock) {
        val state = read()
        val operation = state.optJSONObject("operations")?.optJSONObject(operationId) ?: return@synchronized
        operation.put("reported", true)
        persist(state)
    }
    fun lastResult(): String = synchronized(lock) { read().optString("last_result") }
    fun lastFeature(): String = synchronized(lock) { read().optString("last_feature", "translate") }
    fun saveInput(value: String) = synchronized(lock) {
        require(value.length <= 10_000)
        persist(read().put("input", value))
    }
    fun input(): String = synchronized(lock) { read().optString("input") }
    private fun decode(id: String, value: JSONObject) = Operation(id, value.getString("feature"), value.getString("result"), value.getBoolean("reported"))
    private fun read(): JSONObject = preferences.getString("state", null)?.let(::JSONObject) ?: JSONObject()
    private fun persist(state: JSONObject) { check(preferences.edit().putString("state", state.toString()).commit()) { "Could not save example data" } }
    private companion object { val lock = Any() }
}
