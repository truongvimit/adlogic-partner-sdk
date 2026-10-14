package com.ads.module.helper.adnative.pool

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * The gate and the selection, each derived from the snapshot either on [dispatcher] (so it can
 * lag the main thread, as DashDrama's do) or synchronously in [onPublish]. The two choices are
 * independent and fixed for the life of the engine.
 */
internal class PoolDerived<H : Any>(
    snapshot: StateFlow<PoolSnapshot<H>>,
    asyncGate: Boolean,
    asyncSelector: Boolean,
    dispatcher: CoroutineDispatcher,
    private val gateOf: (PoolSnapshot<H>) -> Boolean,
    private val selectionOf: (PoolSnapshot<H>) -> PoolSelection<H>,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val syncGate: MutableStateFlow<Boolean>? = if (asyncGate) null else MutableStateFlow(true)
    private val syncSelection: MutableStateFlow<PoolSelection<H>>? =
        if (asyncSelector) null else MutableStateFlow(selectionOf(snapshot.value))

    val gate: StateFlow<Boolean> = syncGate ?: asyncGate(snapshot, scope, gateOf)

    val selection: Flow<PoolSelection<H>> = syncSelection ?: asyncSelection(snapshot, scope, selectionOf)

    fun onPublish(snapshot: PoolSnapshot<H>) {
        syncGate?.value = gateOf(snapshot)
        syncSelection?.value = selectionOf(snapshot)
    }

    fun close() = scope.cancel()
}

/** TRUE until the first snapshot is mapped, and for an empty pool. */
internal fun <H : Any> asyncGate(
    snapshot: StateFlow<PoolSnapshot<H>>,
    scope: CoroutineScope,
    gateOf: (PoolSnapshot<H>) -> Boolean,
): StateFlow<Boolean> = snapshot.map(gateOf).stateIn(scope, SharingStarted.Eagerly, true)

/** No initial value: a collector waits for the first mapped snapshot, then gets the latest replayed. */
internal fun <H : Any> asyncSelection(
    snapshot: StateFlow<PoolSnapshot<H>>,
    scope: CoroutineScope,
    selectionOf: (PoolSnapshot<H>) -> PoolSelection<H>,
): Flow<PoolSelection<H>> =
    snapshot.map(selectionOf).distinctUntilChanged().shareIn(scope, SharingStarted.Eagerly, replay = 1)
