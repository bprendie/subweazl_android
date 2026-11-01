package io.prendie.subweazl

import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Direct Subsonic transport. Never follows redirects or exposes authenticated URLs in errors. */
class Navidrome(private val base: HttpUrl, private val username: String,
    private val auth: JSONObject, private val headers: Map<String, String>) {
    companion object {
        fun authentication(password: String): JSONObject {
            val salt = UUID.randomUUID().toString()
            val token = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray())
                .joinToString("") { "%02x".format(it) }
            return obj("t" to token, "s" to salt)
        }
        fun track(j: JSONObject): JSONObject = obj(
            "id" to j.getString("id"), "title" to j.str("title"), "artist" to j.str("artist"),
            "album" to j.str("album"), "albumId" to j.str("albumId"), "artistId" to j.str("artistId"),
            "coverId" to j.str("coverArt"), "durationMs" to j.optLong("duration") * 1000,
            "discNumber" to j.optInt("discNumber", 1), "trackNumber" to j.optInt("track"),
            "starred" to j.has("starred"), "mediaRevision" to hash(j.str("id") + ":" + j.optLong("size") + ":" + j.str("created")),
            "contentType" to j.str("contentType"))
        fun album(j: JSONObject): JSONObject = JSONObject(j.toString())
            .put("coverId", j.str("coverArt")).put("addedAt", j.str("created"))
    }
    fun url(command: String, params: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().addPathSegment("rest").addPathSegment("$command.view")
            .addQueryParameter("u", username).addQueryParameter("t", auth.getString("t"))
            .addQueryParameter("s", auth.getString("s")).addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "Subweazl").addQueryParameter("f", "json")
            .apply { params.forEach { (k,v) -> addQueryParameter(k,v) } }.build()

    fun call(command: String, params: List<Pair<String,String>> = emptyList()): JSONObject {
        val authenticated = url(command, params)
        val form = FormBody.Builder().apply {
            for (i in 0 until authenticated.querySize) add(authenticated.queryParameterName(i), authenticated.queryParameterValue(i).orEmpty())
        }.build()
        val request = Request.Builder().url(authenticated.newBuilder().query(null).build()).post(form)
        headers.forEach { (k,v) -> request.header(k,v) }
        try {
            Api.http.newBuilder().retryOnConnectionFailure(false).build().newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiError("navidrome_http", "Navidrome request failed (HTTP ${response.code})", response.code)
                val root = try { JSONObject(response.body?.string().orEmpty()).getJSONObject("subsonic-response") }
                    catch (_: Exception) { throw ApiError("invalid_response", "The server did not return a Subsonic response") }
                if (root.str("status") != "ok") {
                    val code = root.optJSONObject("error")?.optInt("code") ?: 0
                    throw ApiError(if (code in listOf(40,41,44)) "unauthorized" else "navidrome_$code",
                        if (code in listOf(40,41,44)) "Navidrome sign-in failed. Check your username and password."
                        else "Navidrome could not complete the request (code $code).")
                }
                return root
            }
        } catch (e: ApiError) { throw e }
        catch (_: IOException) { throw IOException("Unable to connect to Navidrome. Check the server connection.") }
    }
    fun ping() { call("ping") }
    fun pages(command: String, container: String, item: String, params: List<Pair<String,String>> = emptyList(),
        offsetName: String = "offset", sizeName: String = "size"): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val batch = call(command, params + listOf(offsetName to offset.toString(), sizeName to "500"))
                .optJSONObject(container)?.rows(item).orEmpty()
            if (batch.isEmpty()) break
            for (row in batch) {
                if (!seen.add(row.getString("id"))) throw ApiError("unstable_library", "Navidrome library changed during sync. Please retry.")
                result.add(row)
            }
            offset += batch.size
            if (batch.size < 500) break
        }
        return result
    }
    fun playlist(id: String): JSONObject {
        val p = call("getPlaylist", listOf("id" to id)).getJSONObject("playlist")
        val entries = p.rows("entry").mapIndexed { index, song -> obj("id" to "$id:$index", "track" to track(song)) }
        return JSONObject(p.toString()).put("entries", JSONArray(entries))
            .put("canEdit", p.str("owner") == username)
            .put("version", hash(p.toString())).put("_etag", "\"${hash(p.toString())}\"")
    }
    fun catalog(): Catalog {
        val albums = pages("getAlbumList2", "albumList2", "album", listOf("type" to "alphabeticalByName")).map(::album)
        val songs = pages("search3", "searchResult3", "song",
            listOf("query" to "", "artistCount" to "0", "albumCount" to "0"), "songOffset", "songCount").map { Track.parse(track(it)) }
        val artists = call("getArtists").optJSONObject("artists")?.rows("index").orEmpty()
            .flatMap { it.rows("artist") }.map { JSONObject(it.toString()).put("coverId", it.str("coverArt")) }
        val playlists = call("getPlaylists").optJSONObject("playlists")?.rows("playlist").orEmpty()
            .map { playlist(it.getString("id")) }
        val starred = call("getStarred2").optJSONObject("starred2") ?: JSONObject()
        val favorites = listOf("song" to "track", "album" to "album", "artist" to "artist")
            .flatMap { (type, kind) -> starred.rows(type).map { "$kind:${it.getString("id")}" } }.toSet()
        val newest = call("getAlbumList2", listOf("type" to "newest", "size" to "12")).optJSONObject("albumList2")
            ?.rows("album").orEmpty().map(::album)
        return Catalog(songs, albums, artists, playlists, emptyList(), favorites, java.time.Instant.now().toString(), newest)
    }
    fun capabilities() = obj("library" to true, "scrobble" to false, "radio" to false, "mood" to false,
        "flightRecorder" to obj("enabled" to false), "mediaProfiles" to JSONArray(listOf(obj("id" to "original"))),
        "maxPlaylistTracks" to 1000, "serverType" to "navidrome")

    fun request(path: String, method: String, body: JSONObject?, etag: String?): Reply {
        fun reply(j: JSONObject = JSONObject()) = Reply(obj("data" to j), null)
        if (path == "auth/logout") return reply() // Subsonic tokens have no logout endpoint; local vault is removed.
        if (path.startsWith("library/favorites/")) {
            val parts = path.removePrefix("library/favorites/").split("/", limit=2)
            val parameter = when(parts[0]) { "track" -> "id"; "album" -> "albumId"; "artist" -> "artistId"; else -> error("Invalid favorite") }
            call(if (body?.optBoolean("enabled") == true) "star" else "unstar",
                listOf(parameter to java.net.URLDecoder.decode(parts[1], "UTF-8")))
            return reply()
        }
        if (path == "library/playlists" || path.startsWith("library/playlists/")) {
            val id = path.removePrefix("library/playlists").removePrefix("/").takeIf { it.isNotEmpty() }
                ?.let { java.net.URLDecoder.decode(it,"UTF-8") }
            if (method == "GET") return reply(playlist(requireNotNull(id)))
            if (id != null) {
                val current = playlist(id)
                if (!current.optBoolean("canEdit")) throw ApiError("forbidden", "This playlist is read-only")
                if (etag != null && etag != "\""+current.str("version")+"\"")
                    throw ApiError("version_conflict", "Playlist changed. Sync before editing.")
            }
            try {
                if (method == "DELETE") { call("deletePlaylist", listOf("id" to requireNotNull(id))); return reply() }
                val data = requireNotNull(body)
                val ids = data.getJSONArray("trackIds")
                val params = listOf("name" to data.getString("name")) +
                    (id?.let { listOf("playlistId" to it) } ?: emptyList()) +
                    (0 until ids.length()).map { "songId" to ids.getString(it) }
                val saved = call("createPlaylist", params).getJSONObject("playlist")
                if (id != null) call("updatePlaylist", listOf("playlistId" to id, "name" to data.getString("name")))
                return reply(playlist(saved.getString("id")))
            } catch (e: IOException) {
                throw ApiError("outcome_unknown", "Playlist save could not be confirmed. Sync before trying again.")
            }
        }
        throw ApiError("unsupported", "This feature requires a WeazlMusic server.")
    }
}
