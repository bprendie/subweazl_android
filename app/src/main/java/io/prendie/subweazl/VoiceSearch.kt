package io.prendie.subweazl

import java.text.Normalizer
import java.util.Locale

/** Voice fields stay separate: album text must never match every song title. */
enum class VoiceKind { ANY, SONG, ALBUM, ARTIST, PLAYLIST, RADIO, MUSIC }
data class VoiceRequest(
    val query: String = "", val kind: VoiceKind = VoiceKind.ANY,
    val title: String = "", val artist: String = "", val album: String = "", val playlist: String = "",
)
data class VoiceSelection(val kind: VoiceKind, val id: String, val name: String, val artist: String, val tracks: List<Track>)
class VoiceSearchFailure(message: String) : IllegalArgumentException(message)

private val marks = Regex("\\p{M}+")
private val separators = Regex("[^\\p{L}\\p{N}]+")
// Applied only after Android has already routed a media request to this app.
// These suffixes cannot teach Google speech recognition or change its app routing.
private val routing = Regex(
    "\\s+(?:on|in)\\s+(?:sub[\\s-]*(?:weazl|weasel|weasle|weezel)|subways\\s+all|subway)(?:\\s+app)?[.!?,]*\\s*$",
    RegexOption.IGNORE_CASE,
)
private fun cleanVoice(value: String) = value.trim().replace(routing, "").take(256)
fun voiceWantsShuffle(request: VoiceRequest): Boolean =
    Regex("^(?:please\\s+)?shuffle(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(request.query.trim())

private fun voiceWords(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(marks, "").lowercase(Locale.ROOT).replace("&", " and ")
    .replace("'", "").replace("’", "").replace(".", "").replace(separators, " ").trim()

private data class VoiceField(val text: String) {
    val words = voiceWords(text).split(' ').filter { it.isNotEmpty() }
    val compact = words.joinToString("")
    fun cost(query: VoiceField): Int? {
        if (query.words.isEmpty() || query.words.size > 16 || query.compact.length > 128) return null
        if (words == query.words) return 0
        if (compact == query.compact) return 1
        val used = mutableSetOf<Int>()
        var total = 3 + (words.size - query.words.size).coerceAtLeast(0)
        for (q in query.words) {
            val best = words.withIndex().filter { it.index !in used }.mapNotNull { (index, w) ->
                val cost = when {
                    w == q -> 0
                    q.length >= 3 && w.startsWith(q) -> 2
                    q.length >= 4 && w.length >= 4 -> distance(q, w, if (q.length >= 8) 2 else 1)?.let { 20 + it * 5 }
                    else -> null
                }
                cost?.let { it to index }
            }.minByOrNull { it.first } ?: return null
            total += best.first
            used.add(best.second)
        }
        return total
    }
}
private fun distance(a: String, b: String, limit: Int): Int? {
    if (kotlin.math.abs(a.length - b.length) > limit) return null
    var previous = IntArray(b.length + 1) { it }
    var before = previous
    for (i in 1..a.length) {
        val row = IntArray(b.length + 1); row[0] = i
        for (j in 1..b.length) {
            row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) row[j] = minOf(row[j], before[j - 2] + 1)
        }
        if (row.min() > limit) return null
        before = previous; previous = row
    }
    return previous[b.length].takeIf { it <= limit }
}

/** Prepared per account/catalog snapshot; all matching runs on a background dispatcher. */
class VoiceSearchIndex(private val catalog: Catalog) {
    private data class Entry(val selection: VoiceSelection) {
        val name = VoiceField(selection.name)
        val artist = VoiceField(selection.artist)
    }
    private val entries: List<Entry>
    init {
        fun ordered(tracks: List<Track>) = tracks.sortedWith(compareBy({ it.albumId }, { it.disc }, { it.number }, { it.id }))
        val songs = catalog.tracks.map { VoiceSelection(VoiceKind.SONG, it.id, it.title, it.artist, listOf(it)) }
        val albumMetadata = catalog.albums.associateBy { it.str("id") }
        val albums = catalog.tracks.groupBy { it.albumId.ifBlank { "${voiceWords(it.album)}|${voiceWords(it.artist)}" } }.map { (id, tracks) ->
            val album = albumMetadata[id]
            VoiceSelection(VoiceKind.ALBUM, id, album?.str("name") ?: tracks.first().album,
                album?.str("artist") ?: tracks.first().artist, ordered(tracks))
        }
        val artists = catalog.tracks.groupBy { voiceWords(it.artist) }.map { (id, tracks) ->
            VoiceSelection(VoiceKind.ARTIST, id, tracks.first().artist, "", ordered(tracks))
        }
        val playlists = catalog.playlists.map { VoiceSelection(VoiceKind.PLAYLIST, it.str("id"), it.str("name"), "", catalog.playlistTracks(it.str("id"))) }
        val radio = catalog.stations.map { VoiceSelection(VoiceKind.RADIO, it.str("id"), it.str("name"), "", emptyList()) }
        entries = (songs + albums + artists + playlists.filter { it.tracks.isNotEmpty() } + radio).map(::Entry)
    }
    fun resolve(request: VoiceRequest): VoiceSelection {
        var kind = request.kind
        var title = cleanVoice(when (kind) {
            VoiceKind.ALBUM -> request.album.ifBlank { request.title }.ifBlank { request.query }
            VoiceKind.PLAYLIST -> request.playlist.ifBlank { request.title }.ifBlank { request.query }
            VoiceKind.ARTIST -> request.artist.ifBlank { request.title }.ifBlank { request.query }
            else -> request.title.ifBlank { request.query }
        })
        var artist = if (kind == VoiceKind.ARTIST) "" else cleanVoice(request.artist)
        if (request.title.isBlank() && request.album.isBlank() && request.playlist.isBlank()) {
            title = title.replace(Regex("^(?:please\\s+)?(?:play|listen to|shuffle)\\s+", RegexOption.IGNORE_CASE), "")
            for ((prefix, target) in listOf("album" to VoiceKind.ALBUM, "song" to VoiceKind.SONG,
                "track" to VoiceKind.SONG, "playlist" to VoiceKind.PLAYLIST, "artist" to VoiceKind.ARTIST,
                "radio station" to VoiceKind.RADIO)) {
                if (title.startsWith("$prefix ", true)) { title = title.drop(prefix.length + 1); kind = target; break }
            }
        }
        val by = title.lastIndexOf(" by ", ignoreCase = true)
        if (artist.isBlank() && by > 0) {
            val performer = VoiceField(title.substring(by + 4))
            // Don't split titles such as Stand by Me unless the suffix is a known performer.
            if (entries.any { it.selection.kind == VoiceKind.ARTIST && it.name.cost(performer) != null }) {
                artist = title.substring(by + 4); title = title.substring(0, by)
            }
        }
        if (kind == VoiceKind.ANY && artist.isNotBlank() && voiceWords(title) in listOf("", "music", "some music")) {
            kind = VoiceKind.ARTIST; title = artist; artist = ""
        }
        if (voiceWords(title) in listOf("", "music", "some music") && artist.isBlank() && kind in listOf(VoiceKind.ANY, VoiceKind.MUSIC)) {
            if (catalog.tracks.isEmpty()) throw VoiceSearchFailure("Your library is empty. Sync it on your phone.")
            return VoiceSelection(VoiceKind.MUSIC, "library", "Library", "", catalog.tracks)
        }
        val preset = Regex("(?:radio )?preset (\\d+)").matchEntire(voiceWords(title))
        if (preset != null && kind in listOf(VoiceKind.ANY, VoiceKind.RADIO)) {
            val station = catalog.stations.filter { it.optBoolean("preset") }.sortedBy { it.optInt("order") }
                .getOrNull(preset.groupValues[1].toIntOrNull()?.minus(1) ?: -1)
                ?: throw VoiceSearchFailure("That radio preset is unavailable.")
            return VoiceSelection(VoiceKind.RADIO, station.str("id"), station.str("name"), "", emptyList())
        }
        val name = VoiceField(title); val performer = VoiceField(artist)
        val albumConstraint = if (kind == VoiceKind.SONG) VoiceField(cleanVoice(request.album)) else null
        val scored = entries.mapNotNull { entry ->
            val selection = entry.selection
            if (kind !in listOf(VoiceKind.ANY, VoiceKind.MUSIC) && selection.kind != kind) return@mapNotNull null
            var score = entry.name.cost(name) ?: return@mapNotNull null
            if (performer.words.isNotEmpty()) score += entry.artist.cost(performer) ?: return@mapNotNull null
            if (albumConstraint != null && albumConstraint.words.isNotEmpty())
                score += VoiceField(selection.tracks.first().album).cost(albumConstraint) ?: return@mapNotNull null
            // A requested song's title must match its title, never its album text.
            val versions = setOf("live", "remix", "acoustic", "instrumental", "karaoke", "remaster", "remastered")
            if (selection.kind == VoiceKind.SONG && name.words.none { it in versions } && entry.name.words.any { it in versions }) score += 10
            score to selection
        }
        val best = scored.minOfOrNull { it.first } ?: throw VoiceSearchFailure("No matching music found in your Subweazl library.")
        val selections = scored.filter { it.first == best }.map { it.second }
            .sortedBy { s -> if (s.kind == VoiceKind.SONG && Regex("best of|greatest hits|compilation|anthology", RegexOption.IGNORE_CASE).containsMatchIn(s.tracks.first().album)) 1 else 0 }
            .distinctBy { if (it.kind == VoiceKind.SONG) "song:${voiceWords(it.name)}|${voiceWords(it.artist)}" else "${it.kind}:${it.id}" }
        if (selections.size != 1) throw VoiceSearchFailure("Several matches found. Say the album or song name and artist.")
        return selections.single()
    }
}
