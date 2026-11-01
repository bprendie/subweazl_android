package io.prendie.subweazl

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Uses the installed speech UI; Subweazl never records or stores microphone audio. */
@Composable
fun VoiceButton(repo: Repository) {
    val context = LocalContext.current
    val recognition = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val query = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim()?.take(256)
            if (!query.isNullOrBlank()) {
                android.util.Log.i("SubweazlVoice", "Speech result received")
                context.startActivity(Intent(context, VoicePlaybackActivity::class.java)
                    .putExtra(SearchManager.QUERY, query))
            } else repo.message.value = "No speech recognized. Try again."
        }
    }
    IconAction("Play by voice", Icons.Default.Mic, Weazl.cyan) {
        try {
            android.util.Log.i("SubweazlVoice", "Speech recognition opened")
            recognition.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song, album, artist or playlist")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        } catch (_: ActivityNotFoundException) {
            repo.message.value = "No speech recognition app is available on this phone."
        } catch (_: SecurityException) {
            repo.message.value = "Speech recognition is unavailable. Check your phone's speech settings."
        }
    }
}
