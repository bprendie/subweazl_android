package io.prendie.subweazl

import java.security.MessageDigest
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

fun obj(vararg values: Pair<String, Any?>) =
    JSONObject().apply { values.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun JSONObject.rows(key: String) = optJSONArray(key)?.objects().orEmpty()

fun JSONObject.str(key: String, fallback: String = "") =
    if (isNull(key)) fallback else optString(key, fallback)

fun hash(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

private val combiningMarks = Regex("\\p{M}")
private val nonWordCharacters = Regex("[^\\p{L}\\p{N}]+")

fun normalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(combiningMarks, "")
        .lowercase()
        .replace(".", "")
        .replace(nonWordCharacters, " ")
        .trim()

fun matches(query: String, vararg fields: String): Boolean {
    if (query.isBlank()) return true
    val hay = normalize(fields.joinToString(" "))
    return normalize(query).split(' ').all { hay.contains(it) }
}

fun duration(ms: Long): String =
    "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val coverId: String,
    val durationMs: Long,
    val disc: Int,
    val number: Int,
    val starred: Boolean,
    val revision: String,
    val contentType: String,
) {
    fun json() =
        obj(
            "id" to id,
            "title" to title,
            "artist" to artist,
            "album" to album,
            "albumId" to albumId,
            "artistId" to artistId,
            "coverId" to coverId,
            "durationMs" to durationMs,
            "discNumber" to disc,
            "trackNumber" to number,
            "starred" to starred,
            "mediaRevision" to revision,
            "contentType" to contentType,
        )

    companion object {
        fun parse(j: JSONObject) =
            Track(
                j.getString("id"),
                j.str("title", "Untitled"),
                j.str("artist", "Unknown artist"),
                j.str("album"),
                j.str("albumId"),
                j.str("artistId"),
                j.str("coverId"),
                j.optLong("durationMs"),
                j.optInt("discNumber"),
                j.optInt("trackNumber"),
                j.optBoolean("starred"),
                j.str("mediaRevision"),
                j.str("contentType"),
            )
    }
}

data class Catalog(
    val tracks: List<Track> = emptyList(),
    val albums: List<JSONObject> = emptyList(),
    val artists: List<JSONObject> = emptyList(),
    val playlists: List<JSONObject> = emptyList(),
    val stations: List<JSONObject> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val synced: String = "",
    val recentAlbums: List<JSONObject> = emptyList(),
) {
    fun albumTracks(id: String) =
        tracks
            .filter { it.albumId == id }
            .sortedWith(compareBy({ it.disc }, { it.number }, { it.title }))

    fun playlistTracks(id: String) =
        playlists
            .firstOrNull { it.str("id") == id }
            ?.rows("entries")
            ?.map { Track.parse(it.getJSONObject("track")) }
            .orEmpty()

    fun json() =
        obj(
            "tracks" to JSONArray(tracks.map { it.json() }),
            "albums" to JSONArray(albums),
            "artists" to JSONArray(artists),
            "playlists" to JSONArray(playlists),
            "stations" to JSONArray(stations),
            "favorites" to JSONArray(favorites.toList()),
            "synced" to synced,
            "recentAlbums" to JSONArray(recentAlbums),
        )

    companion object {
        fun parse(j: JSONObject) =
            Catalog(
                j.rows("tracks").map(Track::parse),
                j.rows("albums"),
                j.rows("artists"),
                j.rows("playlists"),
                j.rows("stations"),
                j.optJSONArray("favorites")
                    ?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    .orEmpty(),
                j.str("synced"),
                j.rows("recentAlbums"),
            )
    }
}

data class Account(
    val key: String,
    val name: String,
    val home: String,
    val remote: String,
    val preferRemote: Boolean,
    val username: String,
    val installation: String,
    val accountId: String,
    val libraryId: String,
    val serverType: String = "weazlmusic",
) {
    fun json() =
        obj(
            "key" to key,
            "name" to name,
            "home" to home,
            "remote" to remote,
            "preferRemote" to preferRemote,
            "username" to username,
            "installation" to installation,
            "accountId" to accountId,
            "libraryId" to libraryId,
            "serverType" to serverType,
        )

    companion object {
        fun parse(j: JSONObject) =
            Account(
                j.getString("key"),
                j.str("name"),
                j.str("home"),
                j.str("remote"),
                j.optBoolean("preferRemote"),
                j.str("username"),
                j.str("installation"),
                j.str("accountId"),
                j.str("libraryId"),
                j.str("serverType", "weazlmusic"),
            )
    }
}

data class Segment(
    val id: String,
    val asset: String,
    val start: Long,
    val duration: Long,
    val mediaStart: Long,
    val bytes: Long,
    val sha: String,
) {
    val end
        get() = start + duration

    companion object {
        fun parse(j: JSONObject) =
            Segment(
                j.getString("id"),
                j.getString("assetId"),
                j.getLong("startMs"),
                j.getLong("durationMs"),
                j.optLong("mediaStartMs"),
                j.getLong("byteLength"),
                j.getString("sha256"),
            )
    }
}

data class FlightStation(
    val id: String,
    val name: String,
    val segments: List<Segment>,
    val metadata: List<JSONObject>,
)

data class Flight(
    val json: JSONObject,
    val id: String,
    val revision: String,
    val name: String,
    val duration: Long,
    val stations: List<FlightStation>,
) {
    val hasGaps
        get() = stations.any { it.segments.sumOf(Segment::duration) < duration }

    fun segmentAt(station: String, time: Long) =
        stations
            .firstOrNull { it.id == station }
            ?.segments
            ?.firstOrNull { time >= it.start && time < it.end }

    companion object {
        fun parse(j: JSONObject): Flight {
            require(j.getInt("schemaVersion") == 1) { "Unsupported recording manifest" }
            val length = j.getLong("durationMs")
            require(length in 1..43_200_000) { "Invalid recording duration" }
            val stations =
                j.rows("tracks")
                    .sortedBy { it.optInt("order") }
                    .map { s ->
                        val segments = s.rows("segments").map(Segment::parse).sortedBy { it.start }
                        require(segments.map { it.id }.distinct().size == segments.size) {
                            "Duplicate segment"
                        }
                        var end = 0L
                        segments.forEach {
                            require(
                                it.start >= end &&
                                    it.duration > 0 &&
                                    it.end <= length &&
                                    it.mediaStart >= 0 &&
                                    it.bytes > 0 &&
                                    it.sha.matches(Regex("[0-9a-fA-F]{64}"))
                            ) {
                                "Invalid recording coverage or integrity"
                            }
                            end = it.end
                        }
                        FlightStation(
                            s.getString("stationId"),
                            s.getString("name"),
                            segments,
                            s.rows("metadata"),
                        )
                    }
            require(
                stations.isNotEmpty() &&
                    stations.size <= 6 &&
                    stations.map { it.id }.distinct().size == stations.size
            ) {
                "Invalid recorded presets"
            }
            return Flight(
                j,
                j.getString("sessionId"),
                j.getString("revision"),
                j.getString("name"),
                length,
                stations,
            )
        }
    }
}
