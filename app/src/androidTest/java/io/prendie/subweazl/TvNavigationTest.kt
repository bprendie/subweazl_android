@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.prendie.subweazl

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only in the TV build, using the disposable loopback fixture on an emulator. */
@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    @get:Rule(order = 0)
    val permissions = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun remoteNavigationAndSharedPlayback() = runBlocking {
        assumeTrue(BuildConfig.FIRE_TV)
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val activity = compose.activity
        val repo = activity.repo
        withContext(Dispatchers.IO) {
            repo.login("TV fixture", "http://10.0.2.2:4003", "", false,
                "alice", "test-password", "TV emulator", "")
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithContentDescription("Navigate to Home")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        val home = compose.onNodeWithContentDescription("Navigate to Home")
        // Switch the touchscreen emulator into hardware-keyboard input mode, as a TV remote does.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        home.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        home.assertIsFocused()
        home.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Filter library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Navigate to Library").assertIsFocused()
        // Reach the content with the remote, then return to the navigation rail.
        compose.onNodeWithContentDescription("Navigate to Library").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Navigate to Library").assertIsNotFocused()

        fun open(name: String) {
            compose.onNodeWithContentDescription("Navigate to $name")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput { pressKey(Key.DirectionCenter) }
        }
        open("Search")
        compose.onNodeWithText("Songs, albums, artists, playlists").performClick()
        compose.onNodeWithTag("tv-editor-input").performTextInput("Track 1")
        compose.onNodeWithTag("tv-editor-input").performImeAction()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty() }
        open("Radio")
        compose.onNodeWithText("Fixture station 1").assertIsDisplayed()
        open("Settings")
        compose.onNodeWithText("Reconnect and sync").assertIsDisplayed()
        compose.onNodeWithText("Downloads").assertDoesNotExist()
        compose.onNodeWithText("Offline mode").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Drop the Needle").assertIsDisplayed()
        compose.onNodeWithText("Flight Recorder").assertDoesNotExist()
        compose.onNodeWithContentDescription("Navigate to Downloads").assertDoesNotExist()

        // The TV controls, system media keys and background playback use PlaybackService.
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(activity, SessionToken(activity, ComponentName(activity, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.await()
        try {
            compose.onNodeWithText("Drop the Needle")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput { pressKey(Key.DirectionCenter) }
            withTimeout(15000) {
                while (!withContext(Dispatchers.Main) { controller.isPlaying }) delay(100)
            }
            withContext(Dispatchers.Main) {
                val ids = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
                assertEquals(minOf(40, repo.catalog.value.tracks.distinctBy { it.id }.size), ids.size)
                assertEquals(ids.size, ids.distinct().size)
                assertFalse(controller.shuffleModeEnabled)
            }
            // Drop the Needle opens Now Playing automatically.
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.onNodeWithContentDescription("Pause")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput { pressKey(Key.DirectionCenter) }
            withTimeout(3000) {
                while (withContext(Dispatchers.Main) { controller.playWhenReady }) delay(100)
            }
            withContext(Dispatchers.Main) { controller.play() }
            compose.activityRule.scenario.onActivity { it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
            delay(500)
            withContext(Dispatchers.Main) { assertTrue(controller.playWhenReady); assertEquals(Player.STATE_READY, controller.playbackState) }
        } finally {
            withContext(Dispatchers.Main) { controller.pause(); MediaController.releaseFuture(future) }
        }
    }
}
