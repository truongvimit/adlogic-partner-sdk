package com.itg.template.retention

import android.content.Context
import org.json.JSONObject

/** Durable local results and an outbox. No user text is sent to telemetry. Main app process only. */
class ExampleDataStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("retention_example_data_v1", Context.MODE_PRIVATE)
    data class Operation(val id: String, val featureId: String, val result: String, val reported: Boolean)

    data class Note(val id: String, val text: String)
    data class SavedItem(val id: String, val text: String)
    fun notes(): List<Note> = synchronized(lock) {
        val notes = read().optJSONObject("notes") ?: return@synchronized emptyList()
        notes.keys().asSequence().map { Note(it, notes.getString(it)) }.toList()
    }
    /** Editing without a content change is not a new business success. */
    fun saveNote(id: String, text: String, operationId: String): Operation? = synchronized(lock) {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")))
        require(text.isNotBlank() && text.length <= 10_000)
        val state = read()
        val notes = state.optJSONObject("notes") ?: JSONObject().also { state.put("notes", it) }
        if (notes.optString(id) == text) return@synchronized null
        notes.put(id, text)
        record(state, operationId, "notes", text)
    }
    fun savedItems(): List<SavedItem> = synchronized(lock) {
        val saved = read().optJSONObject("saved_items") ?: return@synchronized emptyList()
        saved.keys().asSequence().map { SavedItem(it, saved.getString(it)) }.toList()
    }
    fun saveItem(noteId: String, operationId: String): Operation? = synchronized(lock) {
        val state = read()
        val text = state.optJSONObject("notes")?.optString(noteId)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Choose a saved note")
        val saved = state.optJSONObject("saved_items") ?: JSONObject().also { state.put("saved_items", it) }
        if (saved.optString(noteId) == text) return@synchronized null
        saved.put(noteId, text)
        record(state, operationId, "saved_items", "saved:$noteId")
    }
    fun removeItem(id: String, operationId: String): Operation? = synchronized(lock) {
        val state = read()
        val saved = state.optJSONObject("saved_items") ?: return@synchronized null
        if (!saved.has(id)) return@synchronized null
        saved.remove(id)
        record(state, operationId, "saved_items", "removed:$id")
    }
    fun record(operationId: String, featureId: String, result: String): Operation = synchronized(lock) {
        record(read(), operationId, featureId, result)
    }
    private fun record(state: JSONObject, operationId: String, featureId: String, result: String): Operation {
        require(operationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")))
        require(featureId in RetentionExampleContent.featureIds)
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
    fun lastFeature(): String = synchronized(lock) { canonicalFeature(read().optString("last_feature", "notes")) }
    fun saveInput(value: String) = synchronized(lock) {
        require(value.length <= 10_000)
        persist(read().put("input", value))
    }
    fun input(): String = synchronized(lock) { read().optString("input") }
    private fun decode(id: String, value: JSONObject) = Operation(id, value.getString("feature"), value.getString("result"), value.getBoolean("reported"))
    private fun read(): JSONObject {
        val state = preferences.getString("state", null)?.let(::JSONObject) ?: JSONObject()
        if (state.optInt("version") < 3) {
            // Existing outbox entries retain feature/result/event IDs: a replay is the same work.
            state.put("last_feature", canonicalFeature(state.optString("last_feature", "notes")))
            val items = state.optJSONObject("saved_items") ?: JSONObject().also { state.put("saved_items", it) }
            state.optJSONArray("saved")?.let { old ->
                for (index in 0 until old.length()) {
                    val id = old.getString(index)
                    val key = "legacy:$id"
                    if (!items.has(key)) items.put(key, ExampleLegacyData.text(id))
                }
            }
            state.remove("saved")
            state.put("version", 3)
            persist(state)
        }
        return state
    }
    private fun persist(state: JSONObject) {
        val previous = preferences.getString("state", null)
        if (!preferences.edit().putString("state", state.toString()).commit()) {
            // Android updates preference memory before returning a failed disk commit.
            // Restore that memory too, otherwise an unpersisted outbox acknowledgement disappears.
            val rollback = preferences.edit()
            if (previous == null) rollback.remove("state") else rollback.putString("state", previous)
            rollback.commit()
            error("Could not save example data")
        }
    }
    companion object {
        private val lock = Any()
        /** Only compatibility input accepts retired IDs; new business operations use the catalogue. */
        fun canonicalFeature(id: String): String = ExampleLegacyData.destination(id)
    }
}
