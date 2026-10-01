package com.azlegend.wear.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One song on the desktop. [id] is what a launch names; [title] is display only. */
data class RemoteTrack(val id: String, val title: String)

data class RemoteAlbum(val id: String, val title: String, val tracks: List<RemoteTrack>)

/**
 * What the desktop says its music player is doing, from `/builders/<code>/music/playing`.
 *
 * [state] is always the truth about the player. A launch the desktop could not honour is reported
 * beside it in [failedId]/[detail] rather than by overwriting the state — a rejected press must not
 * make the wrist forget the song that is still playing.
 */
data class RemotePlaying(
    /** "idle" | "playing" | "paused". */
    val state: String = "idle",
    /** The launch this track answers, so the watch knows its press landed. */
    val launchId: String? = null,
    val albumId: String? = null,
    val trackId: String? = null,
    val title: String? = null,
    val albumTitle: String? = null,
    /** The launch the desktop refused, and why. */
    val failedId: String? = null,
    val detail: String? = null,
    val updatedAt: Long = 0L,
) {
    val isPaused: Boolean get() = state == "paused"
    val hasTrack: Boolean get() = trackId != null && (state == "playing" || state == "paused")
}

/** A song picked on the watch that the desktop has not answered yet. */
data class PendingLaunch(
    val id: String,
    val albumId: String,
    val trackId: String,
    /** Set once the press is known to have failed; null while still waiting. */
    val error: String? = null,
)

/**
 * The desktop's answer, applied to the press that is waiting for one.
 *
 * Only a record that names this press settles it. Anything else — the previous song still playing,
 * somebody pausing at the desktop — leaves it waiting, and the deadline in [RemoteBridge] is what
 * eventually gives up.
 */
internal fun settle(pending: PendingLaunch?, playing: RemotePlaying): PendingLaunch? = when {
    pending == null || pending.error != null -> pending
    playing.failedId == pending.id -> pending.copy(error = playing.detail ?: FAILED)
    playing.launchId == pending.id -> null
    else -> pending
}

internal const val FAILED = "failed"

/** Shown when the deadline passes with nothing from the desktop at all. */
internal const val NO_REPLY = "no reply"

/** The press never left the watch — no network, or the database refused it. */
internal const val NOT_SENT = "not sent"

/**
 * The wire format, both directions.
 *
 * Everything read here came out of a database anyone holding the code can write to, so it is bounded
 * and de-duplicated on the way in: the library feeds a lazy list, and a list of any size or with
 * repeated ids is not something to hand it on a stranger's say-so.
 */
internal object RemoteJson {

    const val MAX_ALBUMS = 50
    const val MAX_TRACKS = 500
    const val MAX_TEXT = 120

    /** Ids are echoed back to the desktop, so one is kept whole or not at all. */
    const val MAX_ID = 512

    fun parseLibrary(node: JsonElement?): List<RemoteAlbum> {
        val albums = (node as? JsonObject)?.get("albums")
        val seen = HashSet<String>()
        return children(albums).mapNotNull { album ->
            val id = album.id("id") ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val trackIds = HashSet<String>()
            val tracks = children(album["tracks"]).mapNotNull { track ->
                val trackId = track.id("id") ?: return@mapNotNull null
                if (!trackIds.add(trackId)) return@mapNotNull null
                RemoteTrack(trackId, track.text("title") ?: trackId.take(MAX_TEXT))
            }.take(MAX_TRACKS)
            RemoteAlbum(id, album.text("title") ?: id.take(MAX_TEXT), tracks)
        }.take(MAX_ALBUMS)
    }

    fun parsePlaying(node: JsonElement?): RemotePlaying {
        val o = node as? JsonObject ?: return RemotePlaying()
        return RemotePlaying(
            state = o.text("state") ?: "idle",
            launchId = o.id("id"),
            albumId = o.id("album"),
            trackId = o.id("track"),
            title = o.text("title"),
            albumTitle = o.text("album_title"),
            failedId = o.id("failed_id"),
            detail = o.text("detail"),
            updatedAt = o.scalar("updated_at")?.toLongOrNull() ?: 0L,
        )
    }

    /** One slot, latest press wins — see `music/launch` in [RemoteBridge]. */
    fun launchBody(id: String, albumId: String, trackId: String, now: Long): String = buildJsonObject {
        put("id", id)
        put("album", albumId)
        put("track", trackId)
        put("source", "watch")
        put("created_at", now)
    }.toString()

    fun controlBody(action: String, now: Long): String = buildJsonObject {
        put("action", action)
        put("source", "watch")
        put("created_at", now)
    }.toString()

    /**
     * A list as the database returns it. A dense list comes back as an array; a sparse one comes
     * back as an object keyed by index, with the holes gone.
     */
    private fun children(node: JsonElement?): List<JsonObject> = when (node) {
        is JsonArray -> node.mapNotNull { it as? JsonObject }
        is JsonObject -> node.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.mapNotNull { it.value as? JsonObject }
        else -> emptyList()
    }

    /** Display text: a non-blank string cut to [MAX_TEXT], or null. */
    private fun JsonObject.text(key: String): String? = scalar(key)?.take(MAX_TEXT)

    /** An id: never cut, because a shortened id names nothing on the desktop. */
    private fun JsonObject.id(key: String): String? = scalar(key)?.takeIf { it.length <= MAX_ID }

    /** Ids may arrive as numbers; both are text here. */
    private fun JsonObject.scalar(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.trim().takeIf { it.isNotEmpty() }
    }
}
