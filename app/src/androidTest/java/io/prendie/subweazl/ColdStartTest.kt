@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

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

/** Run prepare, force-stop the app from adb, then run verify. No activity is launched. */
@RunWith(AndroidJUnit4::class)
class ColdStartTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun browser() =
        withContext(Dispatchers.Main) {
            MediaBrowser.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                )
                .buildAsync()
                .await()
        }

    @Test
    fun prepare() = runBlocking {
        val repo = context.repo
        withContext(Dispatchers.IO) {
            repo.login(
                "Cold-start fixture",
                "http://10.0.2.2:4003",
                "",
                false,
                "alice",
                "test-password",
                "Cold start test",
                "",
            )
        }
        repo.setSetting("offline", "false")
        repo.setSetting("wifiOnly", "false")
        val track = repo.catalog.value.tracks.first()
        repo.saveTracks(listOf(track))
        withTimeout(30000) { while (repo.savedTrack(track) == null) delay(200) }
        val browser = browser()
        withContext(Dispatchers.Main) {
            browser.setMediaItems(
                listOf(MediaCatalog(repo).track(track), MediaCatalog(repo).track(track)),
                1,
                2000,
            )
            browser.prepare()
        }
        delay(1000)
        repo.setSetting("offline", "true")
        delay(500)
        withContext(Dispatchers.Main) {
            assertEquals(1, browser.currentMediaItemIndex)
            assertFalse(browser.playWhenReady)
            browser.release()
        }
    }

    @Test
    fun verify() = runBlocking {
        val repo = context.repo
        assertNotNull(repo.active.value)
        assertTrue(repo.offline.value)
        val browser = browser()
        try {
            withTimeout(10000) {
                while (withContext(Dispatchers.Main) { browser.mediaItemCount != 2 }) delay(100)
            }
            withContext(Dispatchers.Main) {
                assertEquals(1, browser.currentMediaItemIndex)
                assertEquals(2000.0, browser.currentPosition.toDouble(), 150.0)
                assertFalse(browser.playWhenReady)
                assertTrue(
                    browser.getChildren("saved-music", 0, 100, null).await().value!!.isNotEmpty()
                )
                browser.prepare()
                browser.play()
            }
            withTimeout(15000) {
                while (
                    !withContext(Dispatchers.Main) {
                        browser.isPlaying && browser.currentPosition > 2100
                    }
                ) delay(100)
            }
            withContext(Dispatchers.Main) { browser.pause() }
        } finally {
            withContext(Dispatchers.Main) { browser.release() }
        }
    }
}
