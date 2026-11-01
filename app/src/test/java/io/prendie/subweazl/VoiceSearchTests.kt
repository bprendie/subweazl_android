package io.prendie.subweazl

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class VoiceSearchTests {
    private fun track(id: String, title: String, artist: String = "Oasis", album: String = "(What's the Story) Morning Glory?", albumId: String = "oasis", number: Int = 1) =
        Track(id, title, artist, album, albumId, "", "cover", 180000, 1, number, false, "r", "audio/mpeg")
    private val oasis = listOf(track("two", "Roll With It", number = 2), track("one", "Hello"), track("three", "Wonderwall", number = 3))
    private fun index(tracks: List<Track> = oasis) = VoiceSearchIndex(Catalog(tracks = tracks))
    @Test fun spokenAlbumArtistAndAppRoutingResolveOrderedAlbum() {
        for (query in listOf("play whats the story morning glory by oasis on subweazl",
            "What's the Story Morning Glory by Oasis", "play album What's the Story Morning Glory by Oasis in Subweazl",
            "whats the story morning glroy by oasis",
            "play whats the story morning glory by oasis on sub weasel",
            "play whats the story morning glory by oasis on subway")) {
            val selection = index().resolve(VoiceRequest(query))
            assertEquals(query, VoiceKind.ALBUM, selection.kind)
            assertEquals(listOf("one", "two", "three"), selection.tracks.map { it.id })
        }
    }
    @Test fun appSuffixAliasesDoNotRewriteSongNames() {
        val tracks = listOf(track("subway", "Subway Song"), track("weasel", "Sub Weasel"))
        assertEquals("subway", index(tracks).resolve(VoiceRequest("play Subway Song")).id)
        assertEquals("weasel", index(tracks).resolve(VoiceRequest(kind = VoiceKind.SONG, title = "Sub Weasel")).id)
    }
    @Test fun structuredAlbumAndArtistRemainSeparate() {
        val other = track("cover", "Hello", "Someone Else", albumId = "other")
        val match = index(oasis + other).resolve(VoiceRequest(kind = VoiceKind.ALBUM,
            album = "What's the Story Morning Glory", artist = "Oasis on Subweazl"))
        assertEquals("oasis", match.id)
        assertThrows(VoiceSearchFailure::class.java) {
            index().resolve(VoiceRequest(kind = VoiceKind.ALBUM, album = "What's the Story Morning Glory", artist = "Blur"))
        }
    }
    @Test fun songDoesNotExpandAlbumAndPrefersBaseVersion() {
        val tracks = oasis + track("remix", "Wonderwall (Remix)") + track("wrong", "Other Song", album = "Wonderwall")
        assertEquals(listOf("three"), index(tracks).resolve(VoiceRequest("play Wonderwall by Oasis on Subweazl")).tracks.map { it.id })
        assertEquals(listOf("remix"), index(tracks).resolve(VoiceRequest(kind = VoiceKind.SONG, title = "Wonderwall Remix", artist = "Oasis")).tracks.map { it.id })
        assertThrows(VoiceSearchFailure::class.java) { index().resolve(VoiceRequest(kind = VoiceKind.SONG, title = "What's the Story Morning Glory", artist = "Oasis")) }
    }
    @Test fun realAmbiguityAndNoMatchAreNotArbitraryPlayback() {
        val tracks = listOf(track("a", "Hello"), track("b", "Hello", "Adele", albumId = "adele"))
        assertThrows(VoiceSearchFailure::class.java) { index(tracks).resolve(VoiceRequest("Hello")) }
        assertThrows(VoiceSearchFailure::class.java) { index().resolve(VoiceRequest("completely missing title")) }
        assertThrows(VoiceSearchFailure::class.java) { index().resolve(VoiceRequest("What's the Story Morning Glory by Blur")) }
    }
    @Test fun titlesContainingByAndUnicodePunctuation() {
        val tracks = listOf(track("stand", "Stand by Me", "Ben E. King", albumId = "stand"), track("deja", "Déjà Vu", "Beyoncé", albumId = "deja"))
        assertEquals("stand", index(tracks).resolve(VoiceRequest("Stand by Me")).id)
        assertEquals("deja", index(tracks).resolve(VoiceRequest("deja vu by beyonce on SUBWEAZL")).id)
    }
    @Test fun artistAndPlaylistRequestsPreserveCollectionOccurrences() {
        assertEquals(VoiceKind.ARTIST, index().resolve(VoiceRequest("play Oasis on Subweazl")).kind)
        assertEquals(VoiceKind.ARTIST, index().resolve(VoiceRequest("play music by Oasis")).kind)
        val playlist = obj("id" to "p", "name" to "Night Drive", "entries" to JSONArray(listOf(oasis[0], oasis[1], oasis[0]).map { obj("track" to it.json()) }))
        val match = VoiceSearchIndex(Catalog(tracks = oasis, playlists = listOf(playlist)))
            .resolve(VoiceRequest("play playlist Night Drive on Subweazl"))
        assertEquals(listOf("two", "one", "two"), match.tracks.map { it.id })
    }
    @Test fun shuffleArtistKeepsTheInArtistName() {
        val request = VoiceRequest("shuffle the midnight on subweazl")
        val selection = index(listOf(track("sunset", "Sunset", "The Midnight", album = "Endless Summer"),
            track("days", "Days of Thunder", "The Midnight", album = "Days of Thunder", albumId = "days")))
            .resolve(request)
        assertEquals(VoiceKind.ARTIST, selection.kind)
        assertEquals("The Midnight", selection.name)
        assertEquals(2, selection.tracks.size)
        assertTrue(voiceWantsShuffle(request))
        assertFalse(voiceWantsShuffle(VoiceRequest("play Shuffle by Bombay Bicycle Club")))
        assertEquals(VoiceKind.ALBUM, index().resolve(VoiceRequest("shuffle album whats the story morning glory by oasis")).kind)
    }
    @Test fun spokenAppSuffixPunctuationAndSpacing() {
        val library = index(listOf(track("sunset", "Sunset", "The Midnight", album = "Endless Summer")))
        for (suffix in listOf("Subweazl.", "Sub Weasel.", "Sub-weasel!", "Subweasel app", "Subway?", "Sub Weasle", "Sub Weezel", "subways all")) {
            val request = VoiceRequest("shuffle The Midnight on $suffix")
            assertEquals(suffix, VoiceKind.ARTIST, library.resolve(request).kind)
            assertTrue(voiceWantsShuffle(request))
        }
        assertThrows(VoiceSearchFailure::class.java) {
            library.resolve(VoiceRequest("shuffle The Midnight on Spotify"))
        }
    }
    @Test fun radioAndEmptyMusicUseExistingCatalog() {
        val radio = obj("id" to "radio", "name" to "Groove Salad", "preset" to true, "order" to 1)
        val resolver = VoiceSearchIndex(Catalog(tracks = oasis, stations = listOf(radio)))
        assertEquals("radio", resolver.resolve(VoiceRequest("play Groove Salad on Subweazl")).id)
        assertEquals("radio", resolver.resolve(VoiceRequest("preset 1")).id)
        assertEquals(VoiceKind.MUSIC, resolver.resolve(VoiceRequest()).kind)
    }
}
