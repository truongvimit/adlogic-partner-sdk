package io.paykit.config

import android.content.Context
import androidx.annotation.RawRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the paywall JSON comes from. Vendor adapters live in their own module so a host that
 * uses none of them never ships one.
 */
interface PaywallConfigSource {

    val id: String

    /** Raw JSON, or null on failure/timeout. Must never throw. */
    suspend fun fetch(timeoutMs: Long): String?
}

/** Config the host already holds in memory — the seam unit tests and A/B harnesses use. */
class StaticConfigSource(private val json: String) : PaywallConfigSource {

    override val id: String = ID

    override suspend fun fetch(timeoutMs: Long): String? = json.takeIf { it.isNotBlank() }

    private companion object {
        const val ID = "static"
    }
}

/** Config shipped as an app resource, for hosts with no remote config back end at all. */
class RawResourceConfigSource(
    context: Context,
    @RawRes private val resId: Int,
) : PaywallConfigSource {

    private val appContext = context.applicationContext

    override val id: String = ID

    override suspend fun fetch(timeoutMs: Long): String? {
        if (resId == 0) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                runCatching {
                    appContext.resources.openRawResource(resId)
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrNull()
            }
        }?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val ID = "raw"
    }
}
