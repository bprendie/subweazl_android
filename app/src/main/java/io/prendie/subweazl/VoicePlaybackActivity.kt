@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

import android.app.SearchManager
import android.content.ComponentName
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await

fun voiceRequest(query: String, extras: Bundle?): VoiceRequest {
    fun field(key: String) = extras?.getString(key).orEmpty().take(256)
    val focus = field(MediaStore.EXTRA_MEDIA_FOCUS)
    val title = field(MediaStore.EXTRA_MEDIA_TITLE)
    val artist = field(MediaStore.EXTRA_MEDIA_ARTIST)
    val album = field(MediaStore.EXTRA_MEDIA_ALBUM)
    val playlist = field(MediaStore.EXTRA_MEDIA_PLAYLIST)
    val kind = when (focus) {
        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> VoiceKind.ALBUM
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> VoiceKind.ARTIST
        MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> VoiceKind.SONG
        MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> VoiceKind.PLAYLIST
        else -> when {
            title.isNotBlank() -> VoiceKind.ANY
            album.isNotBlank() -> VoiceKind.ALBUM
            playlist.isNotBlank() -> VoiceKind.PLAYLIST
            artist.isNotBlank() -> VoiceKind.ARTIST
            else -> VoiceKind.ANY
        }
    }
    return VoiceRequest(query.take(256), kind, title, artist, album, playlist)
}

/** Phone assistant intents join the existing session; Auto uses the same resolver directly. */
class VoicePlaybackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        android.util.Log.i("SubweazlVoice", "Phone voice intent received")
        val args = Bundle()
        listOf(SearchManager.QUERY, MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.EXTRA_MEDIA_TITLE,
            MediaStore.EXTRA_MEDIA_ARTIST, MediaStore.EXTRA_MEDIA_ALBUM, MediaStore.EXTRA_MEDIA_PLAYLIST)
            .forEach { key -> intent.getStringExtra(key)?.let { args.putString(key, it.take(256)) } }
        lifecycleScope.launch {
            val future = MediaController.Builder(applicationContext,
                SessionToken(this@VoicePlaybackActivity, ComponentName(this@VoicePlaybackActivity, PlaybackService::class.java)))
                .buildAsync()
            try {
                val result = withTimeout(20000) {
                    future.await().sendCustomCommand(SessionCommand("voice-play", Bundle.EMPTY), args).await()
                }
                if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                    val message = result.extras.getString("message") ?: "Unable to play that request"
                    val heard = args.getString(SearchManager.QUERY).orEmpty()
                    // Show recognition mistakes to the user without persisting raw speech in logs.
                    val detail = if (heard.isBlank()) message else "$message\nHeard: “$heard”"
                    applicationContext.repo.message.value = detail
                    Toast.makeText(this@VoicePlaybackActivity, detail, Toast.LENGTH_LONG).show()
                }
            } catch (_: TimeoutCancellationException) {
                Toast.makeText(this@VoicePlaybackActivity, "Playback took too long to respond. Try again.", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                Toast.makeText(this@VoicePlaybackActivity, "Unable to connect to Subweazl playback", Toast.LENGTH_LONG).show()
            } finally { MediaController.releaseFuture(future); finish() }
        }
    }
}
