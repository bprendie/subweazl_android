package io.prendie.subweazl

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule(order = 0)
    val permissions =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun browseSearchRadioAndSettings() {
        val repo = compose.activity.repo
        runBlocking {
            withContext(Dispatchers.IO) {
                repo.login(
                    "UI fixture",
                    "http://10.0.2.2:4003",
                    "",
                    false,
                    "bob",
                    "test-password",
                    "Android UI",
                    "",
                )
            }
        }
        compose.waitUntil(15000) {
            compose
                .onAllNodesWithText("Shuffle library")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNodeWithText("Shuffle library").assertIsDisplayed()
        // Regression: a large library must not block the UI when radio or navigation redraws it.
        val fixtureCatalog = repo.catalog.value
        val template = fixtureCatalog.tracks.first()
        repo.catalog.value = fixtureCatalog.copy(tracks = List(50000) {
            template.copy(id = "large-$it", title = "Écho Track $it")
        })
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Songs", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Albums").performClick()
        repo.catalog.value = fixtureCatalog
        compose.onNodeWithText("Albums").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Songs, albums, artists, playlists").performTextInput("Track 1")
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Radio").performClick()
        compose.onNodeWithText("Fixture station 1").assertIsDisplayed()
        compose.onNodeWithText("Flight Recorder").performClick()
        compose.onNodeWithText("New recording").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("W").performClick()
        compose.onNodeWithText("Offline mode").assertIsDisplayed()
        compose.onNodeWithText("Downloads").performScrollTo().performClick()
        compose
            .onNodeWithText("Save songs, albums, or playlists while connected.")
            .assertIsDisplayed()
    }
}
