package io.prendie.subweazl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val LocalTvMode = staticCompositionLocalOf { false }

/** Browsing a TV form must never start text input; only selecting a field opens its editor. */
@Composable
fun TvEditableField(label: String, value: String, change: (String) -> Unit, secret: Boolean = false, multiline: Boolean = false) {
    var editing by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val cardFocus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (!editing && restoreFocus) {
            withFrameNanos { }
            cardFocus.requestFocus()
            restoreFocus = false
        }
    }
    OutlinedButton(
        onClick = { editing = true; restoreFocus = true },
        modifier = Modifier.fillMaxWidth().focusRequester(cardFocus).tvFocusOutline(),
        shape = RectangleShape,
        border = BorderStroke(1.dp, Weazl.line),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Weazl.cyan)
            Text(
                when { value.isBlank() -> "Not set"; secret -> "••••••"; else -> value },
                color = if (value.isBlank()) Weazl.muted else Weazl.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Edit", color = Weazl.amber)
    }
    if (editing) {
        var draft by remember { mutableStateOf(value) }
        val inputFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        fun close(save: Boolean) {
            if (save) change(draft)
            keyboard?.hide()
            editing = false
        }
        AlertDialog(
            onDismissRequest = { close(false) },
            title = { Text("Edit $label") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(label) },
                    singleLine = !multiline,
                    minLines = if (multiline) 3 else 1,
                    maxLines = if (multiline) 5 else 1,
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).tvFocusOutline()
                        .testTag("tv-editor-input"),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                        imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { close(true) }),
                )
            },
            confirmButton = { FocusTextButton({ close(true) }) { Text("Save") } },
            dismissButton = { FocusTextButton({ close(false) }) { Text("Cancel") } },
        )
        LaunchedEffect(Unit) {
            withFrameNanos { }
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }
}

@Composable
fun FocusTextButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier = Modifier.tvFocusOutline(), enabled = enabled, content = content)
}

@Composable
fun FocusFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, shape: Shape = RectangleShape) {
    FilterChip(selected, onClick, label, modifier = Modifier.tvFocusOutline(), shape = shape)
}

/** Decorates the focus target supplied by a button/clickable, without adding another focus stop. */
fun Modifier.tvFocusOutline(): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    var focused by remember { mutableStateOf(false) }
    this.onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused) drawRect(Weazl.amber.copy(alpha = .16f))
        }
        .border(3.dp, if (focused) Weazl.amber else Color.Transparent, RectangleShape)
}

@Composable
fun TvNavigation(selected: String, navidrome: Boolean, navigate: (String) -> Unit) {
    val names = remember(navidrome) {
        listOf("Home", "Library", "Playlists", "Search", "Radio", "Now Playing", "Settings")
            .filter { it != "Radio" || !navidrome }
    }
    val focusTargets = remember(names) { names.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { focusTargets[selected]?.requestFocus() }
    Column(
        Modifier.width(156.dp).fillMaxHeight().background(Weazl.panel)
            .padding(8.dp).verticalScroll(rememberScrollState()).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        names.forEachIndexed { index, name ->
                OutlinedButton(
                    onClick = { navigate(name) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                        .focusRequester(focusTargets.getValue(name))
                        .focusProperties {
                            up = names.getOrNull(index - 1)?.let(focusTargets::getValue) ?: FocusRequester.Cancel
                            down = names.getOrNull(index + 1)?.let(focusTargets::getValue) ?: FocusRequester.Cancel
                            left = FocusRequester.Cancel
                        }
                        .tvFocusOutline()
                        .semantics { contentDescription = "Navigate to $name" },
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, if (name == selected) Weazl.cyan else Weazl.line),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (name == selected) Weazl.raised else Color.Transparent,
                        contentColor = if (name == selected) Weazl.cyan else Weazl.ink,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) { Text(name, maxLines = 1) }
            }
    }
}
