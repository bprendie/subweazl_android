@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.prendie.subweazl

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No sign-in or server mutation: exercise only form navigation and local draft editing. */
@RunWith(AndroidJUnit4::class)
class TvCredentialFormTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun blankFieldsAreSkippableAndOnlySelectStartsEditing() {
        compose.setContent {
            CompositionLocalProvider(LocalTvMode provides true) {
                MaterialTheme { ConnectView(null, {}, {}) }
            }
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        fun field(label: String) = compose.onNodeWithText(label)
        fun focus(label: String) = field(label).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        fun down(from: String, to: String) {
            field(from).performKeyInput { pressKey(Key.DirectionDown) }
            field(to).assertIsFocused()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        }
        focus("Account name (optional)")
        down("Account name (optional)", "Home URL")
        down("Home URL", "Username")
        down("Username", "Password")
        down("Password", "Advanced connection settings")
        field("Advanced connection settings").performKeyInput { pressKey(Key.DirectionCenter) }
        down("Hide advanced connection settings", "Remote URL (optional)")
        // Skip a blank remote URL, the preference switch, the default device name and blank headers.
        field("Remote URL (optional)").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        field("Device name").assertIsFocused()
        down("Device name", "Custom headers (optional)")
        down("Custom headers (optional)", "Cancel")

        focus("Home URL")
        field("Home URL").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("tv-editor-input").performTextInput("https://fixture.invalid")
        compose.onNodeWithTag("tv-editor-input").performImeAction()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        field("Home URL").assertIsFocused()
        compose.onNodeWithText("https://fixture.invalid").assertExists()

        field("Home URL").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("tv-editor-input").performTextReplacement("https://discard.invalid")
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("https://fixture.invalid").assertExists()
        compose.onNodeWithText("https://discard.invalid").assertDoesNotExist()
        field("Home URL").assertIsFocused()
    }
}
