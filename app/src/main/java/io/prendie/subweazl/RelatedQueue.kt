package io.prendie.subweazl

/** A fresh single-song selection continues with its album, artist, then library variety.
 * Explicit collections and enqueue operations keep their exact occurrences elsewhere. */
fun relatedQueue(selected: Track, available: List<Track>, limit: Int = 40): List<Track> {
    val album = available.filter {
        if (selected.albumId.isNotBlank()) it.albumId == selected.albumId
        else selected.album.isNotBlank() && it.album == selected.album && it.artist == selected.artist
    }.sortedWith(compareBy<Track> { it.disc }.thenBy { it.number }.thenBy { it.title })
    val position = album.indexOfFirst { it.id == selected.id }
    val albumContinuation = if (position >= 0) album.drop(position + 1) + album.take(position) else album
    val artist = available.filter {
        if (selected.artistId.isNotBlank()) it.artistId == selected.artistId
        else selected.artist.isNotBlank() && normalize(selected.artist) != "unknown artist" && it.artist == selected.artist
    }.shuffled(kotlin.random.Random(selected.id.hashCode()))
    val variety = available.shuffled(kotlin.random.Random(selected.id.hashCode() xor 0x51a7))
    return (sequenceOf(selected) + albumContinuation.asSequence() + artist.asSequence() + variety.asSequence())
        .distinctBy { it.id }.take(limit.coerceAtLeast(1)).toList()
}
