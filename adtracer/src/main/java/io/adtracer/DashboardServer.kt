package io.adtracer

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Minimal embedded HTTP server for the dashboard. Binds loopback only — reachable via
 * `adb forward` and never exposed on the LAN. Serves the bundled SPA, session history and
 * a Server-Sent-Events stream of live ad events.
 */
internal class DashboardServer(private val context: Context, private val collector: EventCollector) {

    @Volatile private var indexHtml: ByteArray? = null

    fun start(): Int {
        for (port in PORT_RANGE) {
            val socket = try {
                ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
            } catch (_: IOException) {
                continue
            }
            thread(name = "AdTracerHttp", isDaemon = true) { acceptLoop(socket) }
            return port
        }
        Log.w(TAG, "No free port in $PORT_RANGE — dashboard disabled, journal still recording")
        return -1
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            thread(name = "AdTracerHttpClient", isDaemon = true) { handle(client) }
        }
    }

    // Catches Throwable: any process on this device can hit the loopback port, and an uncaught
    // exception on this thread would kill the whole host app
    private fun handle(socket: Socket) {
        var keepOpen = false
        try {
            socket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respond(socket.getOutputStream(), "405 Method Not Allowed", "text/plain", "GET only".toByteArray())
                return
            }
            var lastEventId: String? = null
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Last-Event-ID:", ignoreCase = true)) {
                    lastEventId = header.substringAfter(':').trim()
                }
            }

            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val output = socket.getOutputStream()

            when {
                path == "/" || path == "/index.html" ->
                    respond(output, "200 OK", "text/html; charset=utf-8", loadIndex())

                path == "/events" -> {
                    val after = resolveAfterSeq(lastEventId, queryParam(query, "after"))
                    socket.soTimeout = 0
                    output.write(SSE_HEADERS.toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                    collector.attachClient(socket, output, after)
                    keepOpen = true
                }

                path == "/api/sessions" ->
                    respond(output, "200 OK", "application/json", sessionsJson())

                path.startsWith("/api/session/") -> {
                    val name = path.removePrefix("/api/session/")
                    val file = collector.listSessionFiles().find { it.name == name }
                    if (name.matches(SAFE_NAME) && file != null) {
                        respond(output, "200 OK", "application/x-ndjson", file.readBytes())
                    } else {
                        respond(output, "404 Not Found", "text/plain", "unknown session".toByteArray())
                    }
                }

                else -> respond(output, "404 Not Found", "text/plain", "not found".toByteArray())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Request handling failed: $t")
        } finally {
            if (!keepOpen) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun loadIndex(): ByteArray =
        indexHtml ?: context.assets.open("adtracer/index.html").use { it.readBytes() }.also { indexHtml = it }

    private fun sessionsJson(): ByteArray = buildString {
        append('[')
        collector.listSessionFiles().forEachIndexed { index, file ->
            if (index > 0) append(',')
            append("{\"file\":").jsonValue(file.name)
            append(",\"sizeBytes\":").append(file.length())
            append(",\"modifiedMs\":").append(file.lastModified())
            append(",\"current\":").append(file.name == collector.journalFileName)
            append('}')
        }
        append(']')
    }.toByteArray()

    // The SSE id is "sessionId:seq"; an id carried over from a previous app process must not
    // skip the new session's earliest events
    private fun resolveAfterSeq(lastEventId: String?, afterParam: String?): Long {
        if (lastEventId != null) {
            val parts = lastEventId.split(':')
            return if (parts.size == 2 && parts[0] == collector.sessionId) {
                parts[1].toLongOrNull() ?: 0L
            } else {
                0L
            }
        }
        return afterParam?.toLongOrNull() ?: 0L
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&').firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.let { value -> runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() }

    private fun respond(output: OutputStream, status: String, contentType: String, body: ByteArray) {
        val headers = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private companion object {
        const val TAG = "AdTracer"
        val PORT_RANGE = 8686..8695
        val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
        const val SSE_HEADERS = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n\r\n"
    }
}
