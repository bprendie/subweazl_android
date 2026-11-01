package io.prendie.subweazl

import org.junit.Assert.*
import org.junit.Test

class RelatedQueueTests {
    private fun song(id: String, album: String = "album", artist: String = "artist", number: Int = 1) =
        Track(id, id, artist, album, album, artist, "", 60000, 1, number, false, "", "audio/mpeg")
    @Test fun selectedSongFirstThenAlbumContinuationAndArtistBeforeVariety() {
        val a = song("one", number=1); val b = song("two", number=2); val c = song("three", number=3)
        val d = song("artist-other-album", album="other"); val e = song("unrelated", album="unrelated", artist="other")
        val queue = relatedQueue(b, listOf(e,d,c,b,a))
        assertEquals(listOf(b,c,a,d,e), queue)
    }
    @Test fun fortyTracksUniqueAndOnlyFromAvailableLibrary() {
        val songs = (1..100).map { song("$it", number=it) }
        val queue = relatedQueue(songs[10], songs + songs)
        assertEquals(40, queue.size)
        assertEquals(40, queue.map { it.id }.toSet().size)
        assertEquals(songs[10], queue.first())
        assertEquals(listOf(songs[10]), relatedQueue(songs[10], listOf(songs[10])))
    }
    @Test fun fallbackRequiresHttpsAndNoEmbeddedAccountCredentials() {
        assertNull(directRadioUrl("http://example.com/radio.mp3"))
        assertNull(directRadioUrl("https://user:secret@example.com/radio.mp3"))
        assertNull(directRadioUrl("file:///private/music"))
        assertEquals("https://synthwave.prendie.io/radio.mp3", directRadioUrl("https://synthwave.prendie.io/radio.mp3"))
    }
}
