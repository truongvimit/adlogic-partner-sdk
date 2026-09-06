package io.retentionkit.core

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Atomic within one namespace and app process. Never perform side effects inside [transaction]. */
interface RetentionStore {
    fun snapshot(namespace: String): RetentionState
    fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T
}

open class RetentionState internal constructor(protected val values: Map<String, String>) {
    fun string(key: String, default: String? = null): String? = values[key] ?: default
    fun long(key: String, default: Long = 0): Long = values[key]?.toLongOrNull() ?: default
    fun boolean(key: String, default: Boolean = false): Boolean = values[key]?.toBooleanStrictOrNull() ?: default
    fun entries(): Map<String, String> = values.toMap()
}

class RetentionTransaction internal constructor(private val mutable: MutableMap<String, String>) : RetentionState(mutable) {
    fun put(key: String, value: String) { mutable[key] = value }
    fun put(key: String, value: Long) = put(key, value.toString())
    fun put(key: String, value: Boolean) = put(key, value.toString())
    fun remove(key: String) { mutable.remove(key) }
    fun clear() { mutable.clear() }
}

class RetentionStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Uses synchronous commit of a whole namespace; observers cannot see a half-written revision. */
class SharedPreferencesRetentionStore @JvmOverloads constructor(context: Context, name: String = "retentionkit_state_v1") : RetentionStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val lock = locks.computeIfAbsent("${context.packageName}:$name") { Any() }

    override fun snapshot(namespace: String): RetentionState = synchronized(lock) {
        RetentionState(read(namespace))
    }

    override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T = synchronized(lock) {
        require(namespace.isNotBlank()) { "Store namespace must not be blank" }
        check(active.get() != true) { "Nested retention transactions are not supported" }
        active.set(true)
        try {
            val draft = read(namespace).toMutableMap()
            val result = block(RetentionTransaction(draft))
            // commit() changes SharedPreferences' memory before its disk write result. Keep the old
            // serialized value so a failed disk commit does not expose the draft to later reads.
            val before = prefs.getString(namespace, null)
            val encoded = JSONObject(draft as Map<*, *>).toString()
            if (!prefs.edit().putString(namespace, encoded).commit()) {
                val restore = prefs.edit()
                if (before == null) restore.remove(namespace) else restore.putString(namespace, before)
                restore.commit()
                throw RetentionStorageException("Could not persist namespace $namespace")
            }
            result
        } finally {
            active.remove()
        }
    }

    private fun read(namespace: String): Map<String, String> {
        require(namespace.isNotBlank())
        val raw = prefs.getString(namespace, null) ?: return emptyMap()
        try {
            val json = JSONObject(raw)
            return json.keys().asSequence().associateWith { key ->
                val value = json.get(key)
                if (value !is String) throw RetentionStorageException("Non-string state in $namespace")
                value
            }
        } catch (error: Exception) {
            throw RetentionStorageException("Invalid persisted retention state: $namespace", error)
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<String, Any>()
        val active = ThreadLocal<Boolean>()
    }
}
