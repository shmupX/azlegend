package com.azlegend.wear.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The bridge against a stand-in for the Realtime Database: a real HTTP server that streams events on
 * GET and records PUTs, so the reconnect loop and the press-and-wait-for-an-answer path are
 * exercised end to end without a watch, a desktop or a network.
 */
class RemoteBridgeTest {

    /**
     * Just enough HTTP for the two things the bridge does: a GET that streams whatever is queued in
     * [frames] ([CLOSE] ends the stream the way the real database does on its own schedule), and
     * PUTs, which are recorded. Raw sockets because the unit-test classpath has no HTTP server.
     */
    private class FakeDatabase {
        val frames = LinkedBlockingQueue<String>()
        val puts = LinkedBlockingQueue<Pair<String, JsonObject>>()

        @Volatile
        var streamsOpened = 0
            private set

        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

        val url = "http://127.0.0.1:${socket.localPort}"

        init {
            pool.execute {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    pool.execute { runCatching { client.use(::serve) } }
                }
            }
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream().buffered()
            val (method, path) = readLine(input).split(' ')
            var length = 0
            while (true) {
                val header = readLine(input)
                if (header.isEmpty()) break
                if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
            }
            val out = client.getOutputStream()
            if (method == "GET" && path == "$ROOT.json") {
                streamsOpened++
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                while (true) {
                    val frame = frames.take()
                    if (frame == CLOSE) break
                    out.write(frame.toByteArray())
                    out.flush()
                }
            } else if (method == "PUT" && path.startsWith("$ROOT/")) {
                val body = input.readNBytes(length)
                puts.add(path.removePrefix("$ROOT/").removeSuffix(".json") to Json.parseToJsonElement(body.decodeToString()).jsonObject)
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            }
        }

        private fun readLine(input: InputStream): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            return line.toString()
        }

        fun stop() {
            frames.add(CLOSE)
            socket.close()
            pool.shutdownNow()
        }

        fun send(type: String, path: String, data: String) {
            frames.add("event: $type\ndata: {\"path\":\"$path\",\"data\":$data}\n\n")
        }

        fun nextPut(): Pair<String, JsonObject> =
            puts.poll(WAIT_MS, TimeUnit.MILLISECONDS) ?: error("no PUT arrived within ${WAIT_MS}ms")
    }

    private val db = FakeDatabase()
    private var bridge: RemoteBridge? = null

    private fun bridge(replyDeadlineMs: Long = WAIT_MS, resyncAfterMs: Long = WAIT_MS): RemoteBridge =
        RemoteBridge(db.url, "abcd-efgh", replyDeadlineMs, resyncAfterMs, warn = {}).also {
            bridge = it
            it.start()
        }

    @After
    fun tearDown() {
        bridge?.stop()
        db.stop()
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private val snapshot =
        """{"playing":{"state":"playing","track":"old","title":"Left behind"},""" +
            """"library":{"albums":[{"id":"demo","title":"Demo","tracks":[{"id":"t1","title":"One"},{"id":"t2","title":"Two"}]}]}}"""

    /** Connects, and consumes the `sync` every connection opens with. */
    private suspend fun connected(bridge: RemoteBridge) {
        db.send("put", "/", snapshot)
        withTimeout(WAIT_MS) { bridge.connection.first { it == RemoteBridge.Connection.CONNECTED } }
        val (node, body) = db.nextPut()
        assertEquals("control", node)
        assertEquals("sync", body.text("action"))
    }

    @Test
    fun `a snapshot alone does not prove a desktop is there`() = runBlocking {
        val bridge = bridge()
        connected(bridge)

        // The library left in the database is shown, but nothing has spoken.
        assertEquals(listOf("One", "Two"), bridge.library.value.single().tracks.map { it.title })
        assertFalse(bridge.hostSeen.value)

        // The desktop answers the sync.
        db.send("put", "/playing", """{"state":"idle","updated_at":5}""")
        withTimeout(WAIT_MS) { bridge.hostSeen.first { it } }
        assertEquals("idle", bridge.playing.value.state)
    }

    @Test
    fun `a desktop that turns up late is greeted again`() = runBlocking {
        // The hello went out to nobody; the launcher is switched on afterwards and announces
        // itself. Only a second sync gets its albums.
        val bridge = bridge(resyncAfterMs = 0)
        connected(bridge)

        db.send("put", "/playing", """{"state":"idle","updated_at":9}""")
        val (node, body) = db.nextPut()
        assertEquals("control", node)
        assertEquals("sync", body.text("action"))

        // Its answer to that is not another reason to ask.
        db.send("put", "/playing", """{"state":"idle","updated_at":10}""")
        bridge.control("pause")
        assertEquals("pause", db.nextPut().second.text("action"))
    }

    @Test
    fun `a press is settled by the desktop naming it`() = runBlocking {
        val bridge = bridge()
        connected(bridge)

        bridge.launch("demo", "t2")
        val (node, body) = db.nextPut()
        assertEquals("launch", node)
        assertEquals("demo", body.text("album"))
        assertEquals("t2", body.text("track"))
        assertEquals("t2", bridge.pending.value?.trackId)

        // Somebody pausing the old song is not an answer to this press.
        db.send("patch", "/playing", """{"state":"paused"}""")
        withTimeout(WAIT_MS) { bridge.playing.first { it.isPaused } }
        assertNotNull(bridge.pending.value)

        db.send("put", "/playing", """{"state":"playing","id":"${body.text("id")}","album":"demo","track":"t2","title":"Two"}""")
        withTimeout(WAIT_MS) { bridge.pending.first { it == null } }
        assertEquals("Two", bridge.playing.value.title)
    }

    @Test
    fun `a press nobody answers says so`() = runBlocking {
        val bridge = bridge(replyDeadlineMs = 200)
        connected(bridge)

        bridge.launch("demo", "t1")
        db.nextPut()
        val failed = withTimeout(WAIT_MS) { bridge.pending.first { it?.error != null } }
        assertEquals(NO_REPLY, failed?.error)

        bridge.dismissError()
        assertNull(bridge.pending.value)
    }

    @Test
    fun `a press the desktop refuses carries its reason`() = runBlocking {
        val bridge = bridge()
        connected(bridge)

        bridge.launch("demo", "gone")
        val (_, body) = db.nextPut()
        db.send("put", "/playing", """{"state":"idle","failed_id":"${body.text("id")}","detail":"not in library"}""")
        val failed = withTimeout(WAIT_MS) { bridge.pending.first { it?.error != null } }
        assertEquals("not in library", failed?.error)
    }

    @Test
    fun `a stream the server closes is reopened and greeted again`() = runBlocking {
        val bridge = bridge()
        connected(bridge)
        db.send("put", "/playing", """{"state":"idle"}""")
        withTimeout(WAIT_MS) { bridge.hostSeen.first { it } }

        db.frames.add(CLOSE)
        // A closed stream is a disconnect: what the desktop said before it no longer proves
        // anything about now.
        withTimeout(WAIT_MS) { bridge.hostSeen.first { !it } }

        connected(bridge)
        assertEquals(2, db.streamsOpened)
    }

    @Test
    fun `stopping forgets a press that can no longer be answered`() = runBlocking {
        val bridge = bridge()
        connected(bridge)
        bridge.launch("demo", "t1")
        db.nextPut()

        bridge.stop()
        assertEquals(RemoteBridge.Connection.DISCONNECTED, bridge.connection.value)
        assertNull(bridge.pending.value)
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val CLOSE = "\u0000close"
        const val ROOT = "/builders/ABCDEFGH/music"
    }
}
