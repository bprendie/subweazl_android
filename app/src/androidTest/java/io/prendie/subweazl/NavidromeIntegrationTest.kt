@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package io.prendie.subweazl

import android.os.Bundle
import android.app.SearchManager
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only run on the emulator against the isolated Navidrome fixture on port 4537. */
@RunWith(AndroidJUnit4::class)
class NavidromeIntegrationTest {
    @Test fun directServerUsesExistingLibraryPlayerAndDownloads() = runBlocking {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = context.repo
        withContext(Dispatchers.IO) {
            repo.login("Navidrome fixture", "http://10.0.2.2:4537", "", false,
                "fixture", "fixture-only-password", "Integration", "", "navidrome")
        }
        val track = repo.catalog.value.tracks.first()
        assertEquals("Fixture Song", track.title)
        assertTrue(repo.catalog.value.albums.isNotEmpty())
        assertFalse(repo.capabilities.value.optBoolean("radio"))
        withContext(Dispatchers.IO) {
            assertNotNull("Navidrome artwork", repo.artwork(repo.active.value!!.key, track.coverId))
            repo.savePlaylist("Duplicate test", listOf(track, track))
            var playlist = repo.catalog.value.playlists.first { it.str("name") == "Duplicate test" }
            assertEquals(2, repo.catalog.value.playlistTracks(playlist.str("id")).size)
            repo.sync()
            playlist = repo.catalog.value.playlists.first { it.str("id") == playlist.str("id") }
            repo.savePlaylist("Renamed test", listOf(track), playlist)
            playlist = repo.catalog.value.playlists.first { it.str("name") == "Renamed test" }
            assertEquals(1, repo.catalog.value.playlistTracks(playlist.str("id")).size)
            repo.deletePlaylist(playlist)
            val before = "track:${track.id}" in repo.catalog.value.favorites
            repo.favorite("track", track.id)
            repo.flushMutations(repo.active.value!!.key)
            repo.sync()
            assertEquals(!before, "track:${track.id}" in repo.catalog.value.favorites)
            repo.favorite("track", track.id)
            repo.flushMutations(repo.active.value!!.key)
        }
        val browser = withContext(Dispatchers.Main) {
            MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().await()
        }
        suspend fun playing() = withTimeout(20000) {
            while (!withContext(Dispatchers.Main) { browser.isPlaying && browser.currentPosition > 0 }) delay(100)
        }
        try {
            withContext(Dispatchers.Main) {
                val root = browser.getChildren("root", 0, 50, null).await().value!!
                assertEquals(listOf("home", "library", "saved"), root.map { it.mediaId })
                assertTrue(browser.getChildren("tracks", 0, 50, null).await().value!!.isNotEmpty())
                browser.setMediaItem(MediaCatalog(repo).track(track)); browser.prepare(); browser.play()
            }
            playing()
            withContext(Dispatchers.Main) {
                val result = browser.sendCustomCommand(SessionCommand("voice-play", Bundle.EMPTY),
                    Bundle().apply { putString(SearchManager.QUERY, "shuffle Fixture Artist") }).await()
                assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
                assertTrue(browser.shuffleModeEnabled)
            }
            playing()
            withContext(Dispatchers.Main) { browser.pause(); browser.seekTo(3000); browser.play() }
            playing()
            withContext(Dispatchers.Main) { browser.pause(); assertTrue(browser.currentPosition >= 2900) }
            repo.setSetting("wifiOnly", "false")
            repo.saveTracks(listOf(track))
            withTimeout(60000) { while (repo.savedTrack(track) == null) delay(250) }
            repo.setSetting("offline", "true")
            withContext(Dispatchers.Main) {
                browser.setMediaItem(MediaCatalog(repo).track(track)); browser.prepare(); browser.play()
            }
            playing()
        } finally {
            withContext(Dispatchers.Main) { browser.pause(); browser.release() }
            repo.setSetting("offline", "false")
        }
    }
}
