package com.azlegend.wear.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader

/** One server-sent event off a Realtime Database REST stream. */
internal data class SseEvent(val type: String, val data: String)

/**
 * Reads `text/event-stream` until the stream ends, handing over each event.
 *
 * Hand-rolled because the alternative is an HTTP client library in a watch APK for the sake of forty
 * lines. Blocks; returning means the server closed the stream, which for this database is routine
 * and means "reconnect".
 */
internal fun readSse(reader: BufferedReader, onEvent: (SseEvent) -> Unit) {
    var type: String? = null
    val data = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: return
        when {
            // A blank line ends the event.
            line.isEmpty() -> {
                if (type != null || data.isNotEmpty()) onEvent(SseEvent(type ?: "message", data.toString()))
                type = null
                data.setLength(0)
            }
            line.startsWith(":") -> Unit // comment
            line.startsWith("event:") -> type = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
    }
}

/**
 * The watch's copy of `/builders/<code>/music`, kept current from stream events.
 *
 * The database sends `put` (replace at a path) and `patch` (merge at a path), and the path is
 * relative to the node being watched. Decoding every frame as a whole record is the obvious mistake:
 * a one-field write arrives as just that field, and the title would vanish mid-song.
 *
 * Only the two nodes the desktop writes are mirrored. `launch` and `control` are the watch's own
 * words coming back, and are ignored.
 */
internal class MusicMirror {

    var playing: JsonObject? = null
        private set
    var library: JsonObject? = null
        private set

    /**
     * Applies one `put`/`patch` payload — `{"path": …, "data": …}`.
     *
     * Returns true when the event is the desktop speaking *now*: a write to `playing` or `library`
     * on its own, as opposed to the snapshot of the whole node that every connection opens with,
     * which is history and says nothing about whether a desktop is there.
     */
    fun apply(type: String, payload: JsonObject): Boolean {
        val merge = type == "patch"
        val data = payload["data"].takeUnless { it is JsonNull }
        val path = ((payload["path"] as? JsonPrimitive)?.content ?: "/").split('/').filter { it.isNotEmpty() }

        if (path.isEmpty()) {
            val node = data as? JsonObject
            if (!merge) {
                playing = node?.get("playing") as? JsonObject
                library = node?.get("library") as? JsonObject
            } else if (node != null) {
                if ("playing" in node) playing = node["playing"] as? JsonObject
                if ("library" in node) library = node["library"] as? JsonObject
            }
            return false
        }

        when (path[0]) {
            "playing" -> when (path.size) {
                1 -> playing = if (merge) merged(playing, data as? JsonObject) else data as? JsonObject
                // A single field written on its own, e.g. `/playing/state`.
                2 -> if (!merge) playing = with(playing.orEmpty(), path[1], data)
                else -> return false
            }
            // The desktop only ever writes the library whole; a deeper write is not something this
            // mirror can place, so it is left alone.
            "library" -> if (path.size == 1 && !merge) library = data as? JsonObject else return false
            else -> return false
        }
        return true
    }

    private fun merged(base: JsonObject?, patch: JsonObject?): JsonObject? {
        if (patch == null) return base
        var out = base.orEmpty()
        for ((key, value) in patch) out = with(out, key, value.takeUnless { it is JsonNull })
        return JsonObject(out)
    }

    private fun with(base: Map<String, JsonElement>, key: String, value: JsonElement?): JsonObject =
        JsonObject(if (value == null) base - key else base + (key to value))
}
