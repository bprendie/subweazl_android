@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package io.prendie.subweazl

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.resume

class AutoBrowseIntegrationTest {
    @Test fun playlistOccurrencesArtworkAndNeedleUseSharedSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = context.repo
        withContext(Dispatchers.IO) {
            repo.login("Auto fixture", "http://10.0.2.2:4003", "", false,
                "alice", "test-password", "Auto browse test", "")
        }
        repo.setSetting("offline", "false")
        val original = repo.catalog.value
        val tracks = original.tracks.take(3)
        val ordered = listOf(tracks[0], tracks[1], tracks[0], tracks[2])
        val playlistId = "auto:list / duplicates"
        val playlist = obj("id" to playlistId, "name" to "Auto playlist", "entries" to JSONArray(
            ordered.mapIndexed { i, t -> obj("id" to "entry:$i /", "track" to t.json()) }))
        repo.catalog.value = original.copy(playlists = listOf(playlist))
        val catalog = MediaCatalog(repo)
        val browser = withContext(Dispatchers.Main) {
            MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .buildAsync().await()
        }
        var legacy: MediaBrowserCompat? = null
        try {
            val controls = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<MediaControllerCompat.TransportControls> { continuation ->
                    val connection = object : MediaBrowserCompat.ConnectionCallback() {
                        override fun onConnected() { continuation.resume(MediaControllerCompat(context, legacy!!.sessionToken).transportControls) }
                        override fun onConnectionFailed() { continuation.cancel(AssertionError("Legacy browser failed")) }
                    }
                    legacy = MediaBrowserCompat(context, ComponentName(context, PlaybackService::class.java), connection, null)
                    legacy!!.connect()
                }
            }
            suspend fun children(id: String) = withContext(Dispatchers.Main) {
                browser.getChildren(id, 0, 100, null).await().value!!
            }
            val folder = children("playlists").single()
            assertNotNull(folder.mediaMetadata.artworkUri)
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(folder.mediaMetadata.artworkUri!!)!!.use {
                    assertNotNull("Playlist artwork must decode", BitmapFactory.decodeStream(it))
                }
            }
            val rows = children("playlist:$playlistId")
            assertEquals(4, rows.size)
            assertEquals(4, rows.map { it.mediaId }.distinct().size)
            rows.forEachIndexed { index, row ->
                withContext(Dispatchers.Main) {
                    assertEquals(row.mediaId, browser.getItem(row.mediaId).await().value!!.mediaId)
                    browser.shuffleModeEnabled = true
                    controls.playFromMediaId(row.mediaId, null)
                }
                withTimeout(15000) {
                    while (!withContext(Dispatchers.Main) {
                        browser.isPlaying && browser.mediaItemCount == ordered.size && browser.currentMediaItemIndex == index &&
                            ordered.indices.all { browser.getMediaItemAt(it).mediaId == "track:${ordered[it].id}" }
                    }) delay(100)
                }
                withContext(Dispatchers.Main) { assertFalse(browser.shuffleModeEnabled) }
            }
            withContext(Dispatchers.Main) { controls.skipToPrevious() }
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { browser.currentMediaItemIndex == 2 }) delay(50) }
            withContext(Dispatchers.Main) { controls.skipToNext() }
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { browser.currentMediaItemIndex == 3 }) delay(50) }

            // A stale occurrence must not silently play a different song or a related mix.
            repo.catalog.value = repo.catalog.value.copy(playlists = listOf(obj("id" to playlistId,
                "entries" to JSONArray(playlist.rows("entries").filterIndexed { i, _ -> i != 2 }))))
            assertThrows(VoiceSearchFailure::class.java) { catalog.collectionQueue(rows[2].mediaId) }
            repo.catalog.value = original.copy(playlists = listOf(playlist))
            val needle = children("home").first { it.mediaId == "drop-the-needle" }
            assertTrue(needle.mediaMetadata.isPlayable == true)
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(needle.mediaMetadata.artworkUri!!)!!.use {
                    assertNotNull(BitmapFactory.decodeStream(it))
                }
            }
            withContext(Dispatchers.Main) { controls.playFromMediaId(needle.mediaId, null) }
            withTimeout(15000) { while (!withContext(Dispatchers.Main) {
                browser.isPlaying && browser.mediaItemCount == minOf(40, original.tracks.distinctBy { it.id }.size) &&
                    (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId }.distinct().size == browser.mediaItemCount
            }) delay(100) }
            withContext(Dispatchers.Main) { assertFalse(browser.shuffleModeEnabled); controls.pause() }

            // Enforce the cap even when the library is larger than the fixture's audio set.
            repo.catalog.value = original.copy(tracks = List(55) { tracks[0].copy(id = "needle-$it") })
            assertEquals(40, catalog.collectionQueue("drop-the-needle")!!.mediaItems.size)
            repo.setSetting("offline", "true")
            assertThrows(VoiceSearchFailure::class.java) { catalog.collectionQueue("drop-the-needle") }
            // Missing/uncached covers always have a decodable local fallback, including offline.
            withContext(Dispatchers.IO) {
                val uri = artworkUri(repo.active.value!!.key, "missing-test-cover").buildUpon()
                    .appendQueryParameter("fallback", "playlist").build()
                context.contentResolver.openInputStream(uri)!!.use { assertNotNull(BitmapFactory.decodeStream(it)) }
                context.contentResolver.openInputStream(autoIconUri("playlist"))!!.use { assertNotNull(BitmapFactory.decodeStream(it)) }
            }
        } finally {
            repo.catalog.value = original
            repo.setSetting("offline", "false")
            withContext(Dispatchers.Main) { browser.pause(); legacy?.disconnect(); browser.release() }
        }
    }
}
