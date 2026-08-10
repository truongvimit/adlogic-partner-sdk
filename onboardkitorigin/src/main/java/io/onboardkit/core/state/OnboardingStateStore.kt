package io.onboardkit.core.state

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import io.onboardkit.core.StepId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Single source of truth for onboarding progress, backed by JSON DataStore.
 *
 * Reads are suspending by design — no blocking bridge, no in-memory mirror to keep in sync.
 * Callers that need a decision (splash) are already coroutines, so they simply await [current].
 */
class OnboardingStateStore internal constructor(context: Context) {

    private val dataStore = DataStoreFactory.create(
        serializer = StateSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { OnboardingState() },
        produceFile = { File(context.applicationContext.filesDir, FILE_NAME) },
    )

    /** Observable state; emits on every write. */
    val state: Flow<OnboardingState> = dataStore.data

    /** Latest persisted state. Cached in memory by DataStore after the first read. */
    suspend fun current(): OnboardingState = dataStore.data.first()

    suspend fun setLanguage(code: String) = update { it.copy(languageSelected = code) }

    suspend fun markLfoCompleted(nowMs: Long = System.currentTimeMillis()) =
        update { if (it.isLfoCompleted) it else it.copy(lfoCompletedAtMs = nowMs) }

    suspend fun markStepCompleted(stepId: StepId) =
        update { it.copy(lastCompletedStep = stepId.value) }

    suspend fun markFlowCompleted(nowMs: Long = System.currentTimeMillis()) =
        update { if (it.isFlowCompleted) it else it.copy(flowCompletedAtMs = nowMs) }

    suspend fun saveAnswers(answers: List<StoredAnswer>) =
        update { it.copy(questionAnswers = answers) }

    suspend fun reset() = update { OnboardingState() }

    private suspend fun update(transform: (OnboardingState) -> OnboardingState) {
        dataStore.updateData { transform(it) }
    }

    private object StateSerializer : Serializer<OnboardingState> {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        override val defaultValue: OnboardingState = OnboardingState()

        override suspend fun readFrom(input: InputStream): OnboardingState = try {
            json.decodeFromString(OnboardingState.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("$FILE_NAME is corrupted", e)
        }

        override suspend fun writeTo(t: OnboardingState, output: OutputStream) {
            val bytes = json.encodeToString(OnboardingState.serializer(), t).encodeToByteArray()
            withContext(Dispatchers.IO) { output.write(bytes) }
        }
    }

    private companion object {
        const val FILE_NAME = "ob_onboarding_state.json"
    }
}
