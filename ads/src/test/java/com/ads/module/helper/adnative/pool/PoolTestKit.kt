package com.ads.module.helper.adnative.pool

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Identity-equal, like ApNativeAd; [destroyed] counts calls so double destroys show up. */
class FakeAd(val key: String, val id: String, private val serial: Int) {
    var destroyed = 0
    override fun toString(): String = "FakeAd($key/$id#$serial)"
}

class FakeClock(var now: Long = 1_000L) : PoolClock {
    override fun nowMs(): Long = now
}

/** Fires due blocks in due-time order (ties: post order), moving [clock] to each block's time. */
class ManualScheduler(private val clock: FakeClock) : PoolScheduler {
    private class Task(val dueMs: Long, val seq: Long, val block: () -> Unit)

    private val tasks = mutableListOf<Task>()
    private var seq = 0L

    val pending: Int get() = tasks.size

    override fun postDelayed(delayMs: Long, block: () -> Unit): PoolCancellable {
        val task = Task(clock.now + delayMs, seq++, block)
        tasks += task
        return PoolCancellable { tasks.remove(task) }
    }

    fun advanceBy(ms: Long) = advanceTo(clock.now + ms)

    fun advanceTo(targetMs: Long) {
        require(targetMs >= clock.now) { "time runs forward: ${clock.now} -> $targetMs" }
        while (true) {
            val due = tasks.filter { it.dueMs <= targetMs }.minWithOrNull(compareBy({ it.dueMs }, { it.seq })) ?: break
            tasks.remove(due)
            clock.now = due.dueMs
            due.block()
        }
        clock.now = targetMs
    }
}

/** Records every (key, id) request; the test settles each attempt through its sink. */
class FakeLoader : PoolAdLoader<FakeAd> {
    inner class Attempt(val request: PoolRequest, val id: String, val sink: PoolLoadSink<FakeAd>) {
        var ad: FakeAd? = null
            private set

        fun fill(): FakeAd = FakeAd(request.key, id, ++serial).also {
            ad = it
            sink.onLoaded(it)
        }

        fun fail() = sink.onFailed()
    }

    private var serial = 0
    val attempts = mutableListOf<Attempt>()
    var throwOnLoad: Exception? = null

    val calls: List<Pair<String, String>> get() = attempts.map { it.request.key to it.id }

    override fun load(request: PoolRequest, id: String, sink: PoolLoadSink<FakeAd>) {
        attempts += Attempt(request, id, sink)
        throwOnLoad?.let { throw it }
    }

    fun last(key: String): Attempt = attempts.last { it.request.key == key }
    fun callsFor(key: String): List<String> = attempts.filter { it.request.key == key }.map { it.id }
    fun fill(key: String): FakeAd = last(key).fill()
    fun fail(key: String) = last(key).fail()
    fun attemptOf(ad: FakeAd): Attempt = attempts.first { it.ad === ad }
    fun impress(ad: FakeAd) = attemptOf(ad).sink.onImpression(ad)
    fun click(ad: FakeAd) = attemptOf(ad).sink.onClicked(ad)
}

class FakePolicy : PoolRequestPolicy {
    val configByKey = mutableMapOf<String, String>()
    val transientByKey = mutableMapOf<String, String>()
    var transientAll: String? = null

    override fun configRefusal(request: PoolRequest): String? = configByKey[request.key]
    override fun transientRefusal(request: PoolRequest): String? = transientAll ?: transientByKey[request.key]
}

/** Sync parity by default: gate and selection update inside every publish. */
val SYNC = PoolParity(asyncGate = false, asyncSelector = false)

val TIERS = listOf("t0", "t1")

fun request(key: String, ids: List<String> = TIERS, deadlineMs: Long = 0L) =
    PoolRequest(key, ids, layoutRes = 0, adConfigKey = key, tierDeadlineMs = deadlineMs)

class PoolHarness(
    parity: PoolParity = SYNC,
    var maxAgeMs: Long = 0L,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    stuckLoadingLogMs: Long = 60_000L,
) {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val loader = FakeLoader()
    val policy = FakePolicy()
    val destroyed = mutableListOf<FakeAd>()
    val traces = mutableListOf<String>()
    val events = mutableListOf<PoolEvent<FakeAd>>()

    val engine = NativePoolEngine(
        loader = loader,
        policy = policy,
        parity = parity,
        clock = clock,
        scheduler = scheduler,
        expiry = PoolExpiry { maxAgeMs },
        derivedDispatcher = dispatcher,
        destroyAd = { it.destroyed++; destroyed += it },
        trace = PoolTraceSink { traces += it },
        stuckLoadingLogMs = stuckLoadingLogMs,
    ).also { engine -> engine.addEventListener { events += it } }

    fun view(key: String): PoolLoaderView<FakeAd>? = engine.debugSnapshot().loader(key)
    fun state(key: String): PoolState? = view(key)?.state
    fun selection(): PoolSelection<FakeAd> = engine.currentSelection()

    /** Requests [key] FORCED and fills its first id; the ad is Loaded. */
    fun loaded(key: String, ids: List<String> = TIERS): FakeAd {
        engine.load(request(key, ids), LoadMode.FORCED)
        return loader.fill(key)
    }

    /** [loaded], then an impression: the ad is Showing. */
    fun showing(key: String): FakeAd = loaded(key).also { loader.impress(it) }
}
