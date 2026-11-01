@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class VoiceIntegrationTest {
    @Test fun assistantSessionAndPhoneIntentUseSharedPlayer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = context.repo
        withContext(Dispatchers.IO) {
            repo.login("Voice fixture", "http://10.0.2.2:4003", "", false, "alice", "test-password", "Voice test", "")
        }
        repo.setSetting("offline", "false")
        val original = repo.catalog.value
        // Real fixture audio IDs, in-memory voice metadata only. No server library mutations.
        val tracks = original.tracks.take(3).mapIndexed { i, t -> t.copy(title = listOf("Hello", "Roll With It", "Wonderwall")[i],
            artist = "Oasis", album = "(What's the Story) Morning Glory?", albumId = "voice-album", disc = 1, number = i + 1) }
        repo.catalog.value = original.copy(tracks = tracks.reversed(), albums = listOf(obj("id" to "voice-album", "name" to tracks[0].album, "artist" to "Oasis")))
        lateinit var legacy: MediaBrowserCompat
        val browser = withContext(Dispatchers.Main) {
            MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().await()
        }
        try {
            val controls = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<MediaControllerCompat.TransportControls> { continuation ->
                    legacy = MediaBrowserCompat(context, ComponentName(context, PlaybackService::class.java), object : MediaBrowserCompat.ConnectionCallback() {
                        override fun onConnected() { continuation.resume(MediaControllerCompat(context, legacy.sessionToken).transportControls) }
                        override fun onConnectionFailed() { continuation.cancel(AssertionError("Legacy browser connection failed")) }
                    }, null)
                    legacy.connect()
                }
            }
            suspend fun waitForQueue(ids: List<String>) = withTimeout(15000) {
                while (!withContext(Dispatchers.Main) {
                    browser.mediaItemCount == ids.size && ids.indices.all { browser.getMediaItemAt(it).mediaId == "track:${ids[it]}" } && browser.isPlaying
                }) delay(100)
            }
            // A dashboard search/browse row sends only its media ID. The service fills the queue.
            withContext(Dispatchers.Main) { controls.playFromMediaId("track:${tracks[1].id}", null) }
            waitForQueue(listOf(tracks[1].id, tracks[2].id, tracks[0].id))
            withContext(Dispatchers.Main) { controls.skipToNext() }
            withTimeout(10000) {
                while (!withContext(Dispatchers.Main) { browser.currentMediaItem?.mediaId == "track:${tracks[2].id}" && browser.isPlaying }) delay(100)
            }
            // Explicit multi-item queues and enqueue commands retain duplicate occurrences.
            withContext(Dispatchers.Main) {
                browser.setMediaItems(listOf(tracks[0], tracks[0], tracks[1]).map(MediaCatalog(repo)::track))
                browser.prepare(); browser.play()
            }
            waitForQueue(listOf(tracks[0].id, tracks[0].id, tracks[1].id))
            withContext(Dispatchers.Main) { browser.addMediaItem(MediaCatalog(repo).track(tracks[0])) }
            waitForQueue(listOf(tracks[0].id, tracks[0].id, tracks[1].id, tracks[0].id))
            withContext(Dispatchers.Main) {
                controls.playFromSearch("play whats the story morning glory by oasis on subweazl", null)
            }
            waitForQueue(tracks.map { it.id })
            withContext(Dispatchers.Main) { assertFalse(browser.shuffleModeEnabled); controls.pause() }
            delay(200)
            val albumExtras = Bundle().apply {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ALBUM, "What's the Story Morning Glory")
                putString(MediaStore.EXTRA_MEDIA_ARTIST, "Oasis")
            }
            withContext(Dispatchers.Main) { controls.playFromSearch("album request", albumExtras) }
            waitForQueue(tracks.map { it.id })
            withContext(Dispatchers.Main) { controls.playFromSearch("shuffle Oasis on subways all", null) }
            withTimeout(15000) {
                while (!withContext(Dispatchers.Main) { browser.shuffleModeEnabled && browser.mediaItemCount == 3 && browser.isPlaying }) delay(100)
            }
            val songExtras = Bundle().apply {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_TITLE, "Wonderwall")
                putString(MediaStore.EXTRA_MEDIA_ARTIST, "Oasis")
            }
            withContext(Dispatchers.Main) { controls.playFromSearch("song request", songExtras) }
            waitForQueue(listOf(tracks[2].id, tracks[0].id, tracks[1].id))
            withContext(Dispatchers.Main) { assertFalse(browser.shuffleModeEnabled) }
            withContext(Dispatchers.Main) { browser.seekTo(3000); controls.pause() }
            delay(200)
            withContext(Dispatchers.Main) { controls.playFromSearch("", null) }
            waitForQueue(listOf(tracks[2].id, tracks[0].id, tracks[1].id))
            withContext(Dispatchers.Main) { assertTrue(browser.currentPosition >= 2900) }
            withContext(Dispatchers.Main) { controls.playFromSearch("a nonexistent album by someone unknown", null) }
            delay(700)
            withContext(Dispatchers.Main) { assertEquals("track:${tracks[2].id}", browser.currentMediaItem?.mediaId) }
            withContext(Dispatchers.Main) {
                controls.pause()
                context.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(context.packageName)
                    .putExtra(SearchManager.QUERY, "play whats the story morning glory by oasis on subweazl")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            waitForQueue(tracks.map { it.id })
            withContext(Dispatchers.Main) { controls.pause() }
        } finally {
            withContext(Dispatchers.Main) { runCatching { legacy.disconnect() }; browser.release() }
            repo.catalog.value = original
        }
    }
}
