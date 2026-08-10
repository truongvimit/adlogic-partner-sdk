package io.adtracer

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class EventDraft(
    val wallMs: Long,
    val monoMs: Long,
    val type: String,
    val placement: String,
    val format: String,
    val adUnitId: String?,
    val reason: String?,
    val code: Int?,
    val message: String?,
    val synthetic: Boolean,
    val approx: Boolean,
)

/**
 * Single-writer pipeline: callers enqueue drafts, one background thread assigns the gapless
 * seq, journals to disk and fans out to SSE clients. Single writer keeps seq gapless AND the
 * journal in seq order without locks on the hot path. The journal file is the replay source
 * of truth, so browser reconnects can never observe a gap between replay and live.
 */
internal class EventCollector(context: Context, val sessionId: String) {

    private val thread = HandlerThread("AdTracerCollector").apply { start() }
    private val handler = Handler(thread.looper)
    private val clients = ArrayList<SseClient>()
    private val pending = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private var nextSeq = 1L
    private var journal: BufferedWriter? = null

    val journalDir: File = File(context.filesDir, "adtracer")
    val journalFileName: String = "s-$sessionId.ndjson"

    init {
        handler.post { safe { openJournal() } }
        handler.postDelayed({ safe { heartbeat() } }, HEARTBEAT_MS)
    }

    fun emit(draft: EventDraft) {
        // Bounded hand-off: dropping with a visible counter beats an unbounded queue in a stuck app
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            return
        }
        handler.post {
            pending.decrementAndGet()
            safe { process(draft) }
        }
    }

    /** Server hands over an SSE socket after writing headers; the collector owns it from here. */
    fun attachClient(socket: Socket, output: OutputStream, afterSeq: Long) {
        handler.post { safe { doAttachClient(socket, output, afterSeq) } }
    }

    fun listSessionFiles(): List<File> =
        journalDir.listFiles { file -> file.name.endsWith(".ndjson") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()

    private fun process(draft: EventDraft) {
        val lost = dropped.getAndSet(0)
        if (lost > 0) {
            deliver(
                AdEvent(
                    nextSeq++, sessionId, draft.wallMs, draft.monoMs, "tracer_overflow", "_tracer",
                    "OTHER", null, null, lost, "events dropped by bounded queue", false, false,
                ),
            )
        }
        deliver(
            AdEvent(
                nextSeq++, sessionId, draft.wallMs, draft.monoMs, draft.type, draft.placement,
                draft.format, draft.adUnitId, draft.reason, draft.code, draft.message,
                draft.synthetic, draft.approx,
            ),
        )
    }

    private fun deliver(event: AdEvent) {
        val json = event.toJson()
        writeJournal(json)
        val frame = frameFor(event.seq, json)
        clients.retainAll { it.offer(frame) }
    }

    // Session-qualified SSE id so a browser reconnecting across app restarts cannot replay-skip
    // the new session's earliest events
    private fun frameFor(seq: Long, json: String): String = "id: $sessionId:$seq\ndata: $json\n\n"

    private fun doAttachClient(socket: Socket, output: OutputStream, afterSeq: Long) {
        val client = SseClient(socket, output)
        var ok = true
        val file = File(journalDir, journalFileName)
        if (file.exists()) {
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val seq = SEQ_PATTERN.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                        if (seq > afterSeq && !client.offer(frameFor(seq, line))) {
                            ok = false
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Replay failed: ${e.message}")
            }
        }
        if (ok) clients.add(client) else client.close()
    }

    private fun heartbeat() {
        clients.retainAll { it.offer(": ping\n\n") }
        handler.postDelayed({ safe { heartbeat() } }, HEARTBEAT_MS)
    }

    private fun openJournal() {
        try {
            journalDir.mkdirs()
            // Keep the newest sessions only; the dashboard replays history from these files
            listSessionFiles().drop(MAX_KEPT_SESSIONS - 1).forEach(File::delete)
            journal = BufferedWriter(FileWriter(File(journalDir, journalFileName), true))
        } catch (e: IOException) {
            Log.w(TAG, "Journal disabled: ${e.message}")
        }
    }

    private fun writeJournal(json: String) {
        val writer = journal ?: return
        try {
            writer.write(json)
            writer.write("\n")
            writer.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Journal write failed, disabling: ${e.message}")
            journal = null
        }
    }

    // The collector thread must survive any single failure — it sits behind ad callbacks
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Collector step failed: $t")
        }
    }

    /**
     * Socket writes happen on a per-client thread behind a bounded queue: a stalled browser or
     * wedged adb forward must never block the collector thread (java.net has no write timeout).
     */
    private class SseClient(private val socket: Socket, private val output: OutputStream) {

        @Volatile private var closed = false
        private val queue = ArrayBlockingQueue<String>(CLIENT_QUEUE_CAPACITY)

        init {
            thread(name = "AdTracerSseWriter", isDaemon = true) {
                while (!closed) {
                    val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    try {
                        output.write(frame.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } catch (_: IOException) {
                        closed = true
                    }
                }
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }

        fun offer(frame: String): Boolean {
            if (closed) return false
            if (!queue.offer(frame)) {
                // A client that cannot keep up is disconnected rather than allowed to backlog
                closed = true
                return false
            }
            return true
        }

        fun close() {
            closed = true
        }
    }

    private companion object {
        const val TAG = "AdTracer"
        const val MAX_PENDING = 10_000
        const val MAX_KEPT_SESSIONS = 10
        const val HEARTBEAT_MS = 15_000L
        const val CLIENT_QUEUE_CAPACITY = 2_000
        val SEQ_PATTERN = Regex("\"q\":(\\d+)")
    }
}
