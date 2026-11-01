@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    @Test
    fun fixtureEndToEnd() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = context.repo
        repo.active.value?.let { repo.setSetting("offline", "false") }
        withContext(Dispatchers.IO) {
            repo.login(
                "Android fixture",
                "http://10.0.2.2:4003",
                "",
                false,
                "alice",
                "test-password",
                "Android integration",
                "",
            )
        }
        val tracks = repo.catalog.value.tracks
        assertTrue("Library sync", tracks.size >= 2)
        assertTrue("Recently added shelf", repo.catalog.value.recentAlbums.isNotEmpty())
        assertTrue(repo.catalog.value.stations.size >= 6)
        val key = repo.active.value!!.key
        android.util.Log.i("SubweazlTest", "Login and catalog passed")
        withContext(Dispatchers.IO) {
            repo.savePlaylist("Android duplicate test", listOf(tracks[0], tracks[0], tracks[1]))
            val playlist =
                repo.catalog.value.playlists.first { it.str("name") == "Android duplicate test" }
            assertEquals(3, repo.catalog.value.playlistTracks(playlist.str("id")).size)
            repo.savePlaylist(
                "Android reordered",
                listOf(tracks[1], tracks[0], tracks[0]),
                playlist,
            )
            assertThrows(ApiError::class.java) {
                runBlocking { repo.savePlaylist("Stale edit", tracks.take(1), playlist) }
            }
            repo.deletePlaylist(
                repo.catalog.value.playlists.first { it.str("id") == playlist.str("id") }
            )
        }
        val browser =
            withContext(Dispatchers.Main) {
                MediaBrowser.Builder(
                        context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                    )
                    .buildAsync()
                    .await()
            }
        try {
            withContext(Dispatchers.Main) {
                val root = browser.getLibraryRoot(null).await()
                assertEquals("root", root.value!!.mediaId)
                val folders = browser.getChildren("root", 0, 50, null).await().value!!
                assertEquals(listOf("home", "library", "radio", "saved"), folders.map { it.mediaId })
                val home = browser.getChildren("home", 0, 50, null).await().value!!
                assertTrue(home.isNotEmpty())
                assertTrue(home.all { it.mediaMetadata.artworkUri?.scheme == "content" })
                val groups = home.map { it.mediaMetadata.extras?.getString(
                    androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE) }
                assertTrue("Newest albums" in groups)
                assertTrue("Random albums" in groups)
                val artist = repo.catalog.value.artists.first()
                assertTrue(
                    "Artist album fallback",
                    browser
                        .getChildren("artist:${artist.str("id")}", 0, 50, null)
                        .await()
                        .value!!
                        .isNotEmpty(),
                )
                val songs = browser.getChildren("tracks", 0, 50, null).await().value!!
                assertTrue(songs.isNotEmpty())
                browser.search("Track 1", null).await()
                assertTrue(
                    browser.getSearchResult("Track 1", 0, 50, null).await().value!!.isNotEmpty()
                )
                browser.setMediaItems(tracks.take(2).map(MediaCatalog(repo)::track))
                browser.prepare()
                browser.play()
            }
            awaitPlaying(browser)
            android.util.Log.i("SubweazlTest", "Streaming passed")
            withContext(Dispatchers.Main) {
                assertTrue(browser.currentPosition > 0)
                browser.pause()
                browser.seekTo(2000)
            }
            repo.setSetting("wifiOnly", "false")
            repo.saveTracks(tracks.take(1))
            withTimeout(60000) { while (repo.savedTrack(tracks[0]) == null) delay(500) }
            repo.setSetting("offline", "true")
            withContext(Dispatchers.Main) {
                browser.setMediaItem(MediaCatalog(repo).track(tracks[0]))
                browser.prepare()
                browser.play()
            }
            awaitPlaying(browser)
            withContext(Dispatchers.Main) { browser.pause() }
            val before = "track:${tracks[1].id}" in repo.catalog.value.favorites
            withContext(Dispatchers.IO) { repo.favorite("track", tracks[1].id) }
            assertEquals(!before, "track:${tracks[1].id}" in repo.catalog.value.favorites)
            repo.setSetting("offline", "false")
            withContext(Dispatchers.IO) {
                repo.flushMutations(key)
                repo.sync()
            }
            assertEquals(!before, "track:${tracks[1].id}" in repo.catalog.value.favorites)
            withContext(Dispatchers.IO) {
                repo.favorite("track", tracks[1].id)
                repo.flushMutations(key)
            }
            android.util.Log.i(
                "SubweazlTest",
                "Offline music, playlist conflicts and favorite reconciliation passed",
            )
            val station = repo.catalog.value.stations.first()
            withContext(Dispatchers.Main) {
                browser.setMediaItem(MediaCatalog(repo).station(station))
                browser.prepare()
                browser.play()
            }
            awaitPlaying(browser)
            withContext(Dispatchers.Main) { browser.pause() }
            android.util.Log.i("SubweazlTest", "Live radio passed")
            val session =
                withContext(Dispatchers.IO) {
                    repo
                        .api()
                        .request(
                            "flight-recorder/sessions",
                            "POST",
                            obj(
                                "name" to "Android recorder test",
                                "stationIds" to
                                    JSONArray(
                                        repo.catalog.value.stations.take(2).map { it.str("id") }
                                    ),
                                "durationMs" to 12000,
                            ),
                        )
                        .data
                }
            val id = session.str("id")
            withTimeout(90000) {
                while (true) {
                    delay(2000)
                    val s =
                        withContext(Dispatchers.IO) {
                            repo.api().request("flight-recorder/sessions/${Api.path(id)}").data
                        }
                    if (s.str("state") in listOf("complete", "partial")) break
                    assertNotEquals("failed", s.str("state"))
                }
            }
            withContext(Dispatchers.IO) { repo.downloadFlight(id) }
            val flight = repo.flights.value.first { it.id == id }
            withTimeout(60000) { while (!repo.flightReady(flight)) delay(500) }
            android.util.Log.i("SubweazlTest", "Recording fully downloaded")
            repo.setSetting("offline", "true")
            withContext(Dispatchers.Main) {
                browser.setMediaItem(MediaCatalog(repo).flight(flight, flight.stations.first()))
                browser.prepare()
                browser.play()
            }
            awaitPlaying(browser)
            withContext(Dispatchers.Main) {
                browser.pause()
                browser.seekTo(3500)
            }
            delay(500)
            withContext(Dispatchers.Main) {
                browser.setMediaItem(MediaCatalog(repo).flight(flight, flight.stations[1]))
                browser.prepare()
            }
            delay(1500)
            withContext(Dispatchers.Main) {
                assertEquals(
                    "Shared recording position",
                    3500.0,
                    browser.currentPosition.toDouble(),
                    200.0,
                )
                assertFalse(browser.playWhenReady)
                browser.stop()
                browser.clearMediaItems()
            }
            repo.setSetting("offline", "false")
        } finally {
            withContext(Dispatchers.Main) { browser.release() }
            repo.setSetting("offline", "false")
        }
    }

    private suspend fun awaitPlaying(browser: MediaBrowser) {
        withTimeout(30000) {
            while (true) {
                delay(200)
                val status =
                    withContext(Dispatchers.Main) {
                        browser.playerError?.let { throw AssertionError(it) }
                        browser.isPlaying && browser.currentPosition > 100
                    }
                if (status) break
            }
        }
    }
}
