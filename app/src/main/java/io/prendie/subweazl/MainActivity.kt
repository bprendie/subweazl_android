@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.prendie.subweazl

import android.Manifest
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import org.json.JSONObject

object Weazl {
    val background = Color(0xFF111215)
    val panel = Color(0xFF17181C)
    val raised = Color(0xFF1C1D21)
    val ink = Color(0xFFE9E8E1)
    val muted = Color(0xFF9798A5)
    val line = Color(0xFF2B2D33)
    val cyan = Color(0xFF57DBEE)
    val pink = Color(0xFFEF68C8)
    val violet = Color(0xFFB56AFF)
    val lime = Color(0xFF4EF2B0)
    val amber = Color(0xFFDEEF64)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle =
                androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle =
                androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Weazl.amber,
                        onPrimary = Weazl.background,
                        secondary = Weazl.cyan,
                        tertiary = Weazl.pink,
                        background = Weazl.background,
                        surface = Weazl.panel,
                        onSurface = Weazl.ink,
                        onBackground = Weazl.ink,
                    ),
                shapes =
                    Shapes(
                        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        small = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        medium = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        large = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    ),
            ) {
                Subweazl(intent.getStringExtra("query"))
            }
        }
    }
}

@Composable
fun Action(
    label: String,
    icon: ImageVector? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (filled)
        Button(
            onClick,
            enabled = enabled,
            shape = RectangleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            icon?.let {
                Icon(it, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label)
        }
    else
        OutlinedButton(
            onClick,
            enabled = enabled,
            shape = RectangleShape,
            border = BorderStroke(1.dp, Weazl.line),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Weazl.cyan),
        ) {
            icon?.let {
                Icon(it, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label)
        }
}

@Composable
fun IconAction(
    label: String,
    icon: ImageVector,
    tint: Color = Weazl.ink,
    enabled: Boolean = true,
    click: () -> Unit,
) {
    IconButton(onClick = click, enabled = enabled) {
        Icon(icon, label, tint = if (enabled) tint else Weazl.muted)
    }
}

@Composable
fun Heading(title: String) {
    Text(
        title,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun Empty(text: String) {
    Text(text, color = Weazl.muted, modifier = Modifier.padding(vertical = 20.dp))
}

@Composable
fun Field(label: String, value: String, change: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation =
            if (secret) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
    )
}

@Composable
fun Form(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change)
    }
}

@Composable
fun Art(id: String, modifier: Modifier = Modifier) {
    val repo = LocalContext.current.repo
    val account by repo.active.collectAsStateWithLifecycle()
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, id, account?.key) {
            value =
                withContext(Dispatchers.IO) {
                    if (id.isBlank() || account == null) null
                    else
                        runCatching {
                                val file =
                                    repo.artwork(account!!.key, id) ?: return@runCatching null
                                val bounds =
                                    BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(file.path, bounds)
                                BitmapFactory.decodeFile(
                                    file.path,
                                    BitmapFactory.Options().apply {
                                        inSampleSize =
                                            (maxOf(bounds.outWidth, bounds.outHeight) / 512)
                                                .coerceAtLeast(1)
                                    },
                                )
                            }
                            .getOrNull()
                }
        }
    Box(
        modifier.background(Weazl.raised).border(1.dp, Weazl.line),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null)
            Image(
                bitmap!!.asImageBitmap(),
                "Album artwork",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else
            Icon(
                Icons.Default.MusicNote,
                null,
                tint = Weazl.violet,
                modifier = Modifier.size(28.dp),
            )
    }
}

@Composable
fun Subweazl(voiceQuery: String? = null) {
    val context = LocalContext.current
    val repo = context.repo
    val account by repo.active.collectAsStateWithLifecycle()
    val error by repo.message.collectAsStateWithLifecycle()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf("Home") }
    var tab by rememberSaveable { mutableStateOf("Home") }
    var selection by remember { mutableStateOf<JSONObject?>(null) }
    var detailTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var detailTitle by remember { mutableStateOf("") }
    var login by remember { mutableStateOf(false) }
    var editAccount by remember { mutableStateOf<Account?>(null) }
    val media = remember { MediaCatalog(repo) }
    fun navigate(to: String) {
        screen = to
    }
    fun play(tracks: List<Track>, index: Int = 0) {
        if (tracks.isNotEmpty()) {
            val player = controller
            if (
                player != null &&
                    ((tracks.size == 1 && player.currentMediaItem?.mediaId == "track:${tracks[0].id}") ||
                    (player.currentMediaItemIndex == index &&
                    player.mediaItemCount == tracks.size &&
                    tracks.indices.all {
                        player.getMediaItemAt(it).mediaId == "track:${tracks[it].id}"
                    }))
            ) {
                if (player.playWhenReady) player.pause()
                else {
                    player.prepare()
                    player.play()
                }
                return
            }
            controller?.setMediaItems(tracks.map(media::track), index, 0)
            controller?.prepare()
            controller?.play()
        }
    }
    fun openAlbum(album: JSONObject) {
        selection = album
        detailTitle = album.str("name")
        detailTracks = repo.catalog.value.albumTracks(album.str("id"))
        screen = "Album"
    }
    LaunchedEffect(Unit) {
        val future =
            MediaController.Builder(
                    context.applicationContext,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                )
                .buildAsync()
        try {
            controller = future.await()
            if (!voiceQuery.isNullOrBlank()) {
                controller?.setMediaItem(
                    MediaItem.Builder()
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder().setSearchQuery(voiceQuery).build()
                        )
                        .build()
                )
                controller?.prepare()
                controller?.play()
            }
            while (true) {
                tick++
                delay(500)
            }
        } finally {
            MediaController.releaseFuture(future)
        }
    }
    LaunchedEffect(account?.key) {
        if (account?.serverType == "navidrome" && tab == "Radio") tab = "Home"
        screen = tab
        selection = null
    }
    BackHandler(screen != tab || login) { if (login) login = false else screen = tab }
    val now = controller?.currentMediaItem
    tick.let {}
    Scaffold(
        containerColor = Weazl.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (screen != tab)
                    IconAction("Back", Icons.AutoMirrored.Filled.ArrowBack) { screen = tab }
                Image(painterResource(R.drawable.weazlmark), null, Modifier.size(30.dp))
                Spacer(Modifier.width(6.dp))
                Text("SUB", fontWeight = FontWeight.Black)
                Text("WEAZL", color = Weazl.amber, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                if (account != null && !login) VoiceButton(repo)
                OutlinedButton(
                    { screen = "Settings" },
                    shape = RectangleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp),
                    border = BorderStroke(1.dp, Weazl.violet.copy(alpha = .4f)),
                ) {
                    Text("W", color = Weazl.violet)
                }
            }
        },
        bottomBar = {
            if (account != null && !login)
                Column(Modifier.navigationBarsPadding()) {
                    if (now != null && screen != "Now Playing")
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .border(1.dp, Weazl.violet.copy(alpha = .5f))
                                .background(Weazl.violet.copy(alpha = .08f))
                                .padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f)
                                    .clickable { screen = "Now Playing" }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    now.mediaMetadata.title?.toString().orEmpty(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    now.mediaMetadata.artist?.toString().orEmpty(),
                                    color = Weazl.muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                            IconAction(
                                if (controller?.playWhenReady == true) "Pause" else "Play",
                                if (controller?.playWhenReady == true) Icons.Default.Pause
                                else Icons.Default.PlayArrow,
                            ) {
                                controller?.let {
                                    if (it.playWhenReady) it.pause()
                                    else {
                                        it.prepare()
                                        it.play()
                                    }
                                }
                            }
                            IconAction("Next", Icons.Default.SkipNext) { controller?.seekToNext() }
                        }
                    Row(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Weazl.line))) {
                        listOf(
                                "Home" to Icons.Default.Home,
                                "Library" to Icons.Default.LibraryMusic,
                                "Playlists" to Icons.AutoMirrored.Filled.QueueMusic,
                                "Search" to Icons.Default.Search,
                                "Radio" to Icons.Default.Radio,
                            )
                            .filter { it.first != "Radio" || account?.serverType != "navidrome" }
                            .forEach { (name, icon) ->
                                Column(
                                    Modifier.weight(1f)
                                        .clickable {
                                            tab = name
                                            screen = name
                                        }
                                        .padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        icon,
                                        name,
                                        tint = if (tab == name) Weazl.amber else Weazl.muted,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Text(
                                        name,
                                        fontSize = 11.sp,
                                        color = if (tab == name) Weazl.amber else Weazl.muted,
                                    )
                                }
                            }
                    }
                }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (account == null || login)
                ConnectView(
                    editAccount,
                    onDone = {
                        login = false
                        editAccount = null
                    },
                    onCancel = if (account != null) ({ login = false }) else null,
                )
            else
                when (screen) {
                    "Home" -> HomeView(repo, ::play, ::openAlbum, ::navigate)
                    "Library" -> LibraryView(repo, controller, ::play, ::openAlbum, ::navigate)
                    "Search" ->
                        SearchView(
                            repo,
                            controller,
                            ::play,
                            ::openAlbum,
                            { p ->
                                selection = p
                                detailTitle = p.str("name")
                                detailTracks = repo.catalog.value.playlistTracks(p.str("id"))
                                screen = "Playlist"
                            },
                        )
                    "Playlists" ->
                        PlaylistsView(repo) { p ->
                            selection = p
                            detailTitle = p.str("name")
                            detailTracks = repo.catalog.value.playlistTracks(p.str("id"))
                            screen = "Playlist"
                        }
                    "Album",
                    "Playlist" ->
                        CollectionView(
                            repo,
                            controller,
                            screen,
                            selection,
                            detailTitle,
                            detailTracks,
                            ::play,
                        ) {
                            screen = tab
                        }
                    "Radio" -> RadioView(repo, controller, ::navigate)
                    "Flight Recorder" -> FlightView(repo, controller)
                    "Downloads" -> DownloadsView(repo, controller)
                    "Queue" -> QueueView(repo, controller)
                    "Now Playing" -> PlayerView(repo, controller, tick, ::navigate)
                    "Settings" ->
                        SettingsView(repo, controller, ::navigate) { a ->
                            editAccount = a
                            login = true
                        }
                    "Mood & curator" -> MoodView(repo)
                    "Signed-in devices" -> DevicesView(repo)
                    "Changes needing review" -> MutationView(repo)
                    "About, privacy & help" -> AboutView()
                }
        }
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { repo.message.value = null },
            title = { Text("Subweazl") },
            text = { Text(it) },
            confirmButton = { TextButton({ repo.message.value = null }) { Text("OK") } },
        )
    }
}

@Composable
fun ConnectView(edit: Account?, onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val repo = LocalContext.current.repo
    val scope = repo.scope
    val active by repo.active.collectAsStateWithLifecycle()
    val accounts by repo.accounts.collectAsStateWithLifecycle()
    var name by remember(edit) { mutableStateOf(edit?.name ?: "") }
    var serverType by remember(edit) { mutableStateOf(edit?.serverType ?: "weazlmusic") }
    var home by remember(edit) { mutableStateOf(edit?.home ?: "") }
    var remote by remember(edit) { mutableStateOf(edit?.remote ?: "") }
    var prefer by remember(edit) { mutableStateOf(edit?.preferRemote ?: false) }
    var user by remember(edit) { mutableStateOf(edit?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(android.os.Build.MODEL) }
    var headers by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(edit?.key) {
        if (edit != null)
            headers =
                withContext(Dispatchers.IO) {
                    repo.vault
                        .read(edit.key)
                        ?.optJSONObject("headers")
                        ?.let { j ->
                            j.keys().asSequence().joinToString("\n") { "$it: ${j.getString(it)}" }
                        }
                        .orEmpty()
                }
    }
    Form {
        Heading("Connect")
        Row {
            Action("WeazlMusic", filled = serverType == "weazlmusic") { serverType = "weazlmusic" }
            Action("Navidrome", filled = serverType == "navidrome") { serverType = "navidrome"; remote = ""; prefer = false }
        }
        Text(if (serverType == "navidrome") "Connect directly to Navidrome. Enhanced radio, recording and Mood require WeazlMusic."
            else "Connect to your WeazlMusic server.", color = Weazl.muted)
        if (active == null)
            accounts.forEach { a ->
                Action("Open ${a.name}") {
                    repo.action { activate(a) }
                    onDone()
                }
            }
        Field("Account name", name, { name = it })
        Field("Home URL", home, { home = it })
        if (serverType == "weazlmusic") {
            Field("Remote URL (optional)", remote, { remote = it })
            Toggle("Prefer remote URL", prefer, { prefer = it })
        }
        Field("Username", user, { user = it })
        Field("Password", password, { password = it }, true)
        Field("Device name", device, { device = it })
        OutlinedTextField(
            headers,
            { headers = it },
            label = { Text("Custom headers (optional)") },
            supportingText = { Text("One Name: value per line.") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (home.startsWith("http://"))
            Text("This connection uses unencrypted HTTP.", color = Weazl.pink, fontSize = 12.sp)
        Action(
            if (busy) "Connecting…" else "Connect and save",
            filled = true,
            enabled = !busy && home.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
        ) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        repo.login(name, home, remote, prefer, user, password, device, headers, serverType)
                    }
                    withContext(Dispatchers.Main) {
                        password = ""
                        onDone()
                    }
                } catch (e: Exception) {
                    repo.message.value = repo.friendly(e)
                } finally {
                    busy = false
                }
            }
        }
        onCancel?.let { Action("Cancel", onClick = it) }
        Text(
            "Use an existing account on the selected server. WeazlMusic supplies the enhanced services.",
            color = Weazl.muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun TrackRow(repo: Repository, track: Track, controller: MediaController?, onPlay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    val catalog by repo.catalog.collectAsStateWithLifecycle()
    val current = controller?.currentMediaItem?.mediaId == "track:${track.id}"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(track.coverId, Modifier.size(44.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clickable(onClick = onPlay).padding(vertical = 8.dp)) {
            Text(
                track.title,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (current) Weazl.amber else Weazl.ink,
            )
            Text(
                track.artist,
                fontSize = 12.sp,
                color = Weazl.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconAction(
            "Play ${track.title}",
            Icons.Default.PlayArrow,
            tint = Weazl.amber,
            click = onPlay,
        )
        Box {
            IconAction("More for ${track.title}", Icons.Default.MoreVert) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    {
                        Text(
                            if ("track:${track.id}" in catalog.favorites) "Remove favorite"
                            else "Favorite"
                        )
                    },
                    {
                        repo.favorite("track", track.id)
                        menu = false
                    },
                )
                DropdownMenuItem(
                    { Text("Play next") },
                    {
                        controller?.addMediaItem(
                            (controller.currentMediaItemIndex + 1).coerceAtLeast(0),
                            MediaCatalog(repo).track(track),
                        )
                        menu = false
                    },
                )
                DropdownMenuItem(
                    { Text("Add to queue") },
                    {
                        controller?.addMediaItem(MediaCatalog(repo).track(track))
                        menu = false
                    },
                )
                DropdownMenuItem(
                    { Text("Download offline") },
                    {
                        repo.saveTracks(listOf(track))
                        menu = false
                    },
                )
                DropdownMenuItem(
                    { Text("Add to playlist") },
                    {
                        add = true
                        menu = false
                    },
                )
            }
        }
    }
    if (add)
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("Add to playlist") },
            text = {
                LazyColumn {
                    items(catalog.playlists.filter { it.optBoolean("canEdit") }) { p ->
                        TextButton({
                            repo.action {
                                savePlaylist(
                                    p.str("name"),
                                    catalog.playlistTracks(p.str("id")) + track,
                                    p,
                                )
                            }
                            add = false
                        }) {
                            Text(p.str("name"))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ add = false }) { Text("Cancel") } },
        )
}

@Composable
fun AlbumShelf(title: String, albums: List<JSONObject>, open: (JSONObject) -> Unit) {
    Text(
        title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
    )
    if (albums.isEmpty()) Empty("No albums available.")
    else
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums) { a ->
                Column(Modifier.width(140.dp).clickable { open(a) }) {
                    Art(a.str("coverId"), Modifier.size(140.dp))
                    Text(
                        a.str("name"),
                        maxLines = 2,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(a.str("artist"), fontSize = 12.sp, maxLines = 1, color = Weazl.muted)
                }
            }
        }
}

@Composable
fun HomeView(
    repo: Repository,
    play: (List<Track>, Int) -> Unit,
    album: (JSONObject) -> Unit,
    navigate: (String) -> Unit,
) {
    val active by repo.active.collectAsStateWithLifecycle()
    val c by repo.catalog.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    var seed by remember { mutableIntStateOf(0) }
    val randomTracks = remember(c.tracks, seed) { c.tracks.shuffled().take(12) }
    val randomAlbums = remember(c.albums, seed) { c.albums.shuffled().take(12) }
    val recent =
        remember(history, c.tracks) {
            c.tracks
                .filter { history.has(it.id) }
                .sortedByDescending { history.optJSONObject(it.id)?.str("last") }
                .take(12)
        }
    val frequent =
        remember(history, c.tracks) {
            c.tracks
                .filter { history.has(it.id) }
                .sortedByDescending { history.optJSONObject(it.id)?.optInt("count") }
                .take(12)
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Heading("Home") }
                IconAction("Refresh library", Icons.Default.Refresh, enabled = !syncing) {
                    repo.action { sync() }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Shuffle library", Icons.Default.Shuffle, true, c.tracks.isNotEmpty()) {
                    play(c.tracks.shuffled(), 0)
                }
                if (active?.serverType != "navidrome")
                    Action("Internet Radio", Icons.Default.Radio) { navigate("Radio") }
            }
        }
        if (active?.serverType != "navidrome") item {
            Row(
                Modifier.fillMaxWidth()
                    .background(Weazl.raised)
                    .border(1.dp, Weazl.line)
                    .clickable { navigate("Flight Recorder") }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Flight, null, tint = Weazl.cyan)
                Spacer(Modifier.width(12.dp))
                Text("Flight Recorder", Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null)
            }
        }
        item {
            AlbumShelf(
                "Recently added",
                c.recentAlbums.ifEmpty {
                    c.albums.sortedByDescending { it.str("addedAt") }.take(12)
                },
                album,
            )
        }
        listOf(
                "Random tracks" to randomTracks,
                "Recently played" to recent,
                "Frequently played" to frequent,
            )
            .forEach { (title, tracks) ->
                if (title == "Frequently played")
                    item { AlbumShelf("Random albums", randomAlbums, album) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (title == "Random tracks")
                            IconAction("Refresh random tracks", Icons.Default.Refresh, Weazl.cyan) {
                                seed++
                            }
                        IconAction(
                            "Play $title",
                            Icons.Default.PlayArrow,
                            Weazl.amber,
                            tracks.isNotEmpty(),
                        ) {
                            play(tracks, 0)
                        }
                    }
                    if (title != "Random tracks")
                        Text("Played in Subweazl", fontSize = 12.sp, color = Weazl.muted)
                }
                if (tracks.isEmpty())
                    item {
                        Empty(
                            if (title == "Random tracks") "Sync your library to discover tracks."
                            else "Your listening history will appear here."
                        )
                    }
                else
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(tracks.chunked(3)) { chunk ->
                                Column(Modifier.width(310.dp)) {
                                    chunk.forEach { t ->
                                        TrackRow(repo, t, null) { play(tracks, tracks.indexOf(t)) }
                                    }
                                }
                            }
                        }
                    }
            }

        item { Text("Favorites", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(c.tracks.filter { "track:${it.id}" in c.favorites }.take(8)) { t ->
            TrackRow(repo, t, null) { play(listOf(t), 0) }
        }
    }
}

@Composable
fun LibraryView(
    repo: Repository,
    controller: MediaController?,
    play: (List<Track>, Int) -> Unit,
    album: (JSONObject) -> Unit,
    navigate: (String) -> Unit,
) {
    val c by repo.catalog.collectAsStateWithLifecycle()
    val rev by repo.revision.collectAsStateWithLifecycle()
    var scope by rememberSaveable { mutableStateOf("Albums") }
    var query by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf(false) }
    var artist by remember { mutableStateOf<JSONObject?>(null) }
    var sort by rememberSaveable { mutableStateOf("Name") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Heading("Library")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Albums", "Artists", "Songs", "Favorites")) { s ->
                FilterChip(
                    scope == s,
                    {
                        scope = s
                        artist = null
                    },
                    label = { Text(s) },
                    shape = RectangleShape,
                )
            }
        }
        Action("Downloads", Icons.Default.Download) { navigate("Downloads") }
        Field("Filter library", query, { query = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                saved,
                { saved = !saved },
                label = { Text("Available offline") },
                shape = RectangleShape,
            )
            Spacer(Modifier.weight(1f))
            TextButton({ sort = if (sort == "Name") "Newest" else "Name" }) { Text(sort) }
        }
        if (artist != null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(artist!!.str("name"), Modifier.weight(1f))
                IconAction(
                    "Favorite artist",
                    if ("artist:${artist!!.str("id")}" in c.favorites) Icons.Default.Favorite
                    else Icons.Default.FavoriteBorder,
                    Weazl.pink,
                ) {
                    repo.favorite("artist", artist!!.str("id"))
                }
                TextButton({ artist = null }) { Text("All artists") }
            }
        val tracks by produceState<List<Track>>(emptyList(), c, query, scope, saved, rev, artist) {
            value = withContext(Dispatchers.Default) {
                if (scope != "Songs" && scope != "Favorites" || artist != null) emptyList()
                else c.tracks.filter {
                    matches(query, it.title, it.artist, it.album) &&
                        (scope != "Favorites" || "track:${it.id}" in c.favorites) &&
                        (!saved || repo.savedTrack(it) != null)
                }.map { normalize(it.title) to it }.sortedBy { it.first }.map { it.second }
            }
        }
        if (scope == "Albums" || artist != null) {
            val selectedArtist = artist
            val albums by produceState<List<JSONObject>>(emptyList(), c, query, selectedArtist, saved, rev, sort) {
                value = withContext(Dispatchers.Default) {
                    val savedAlbums = if (saved) c.tracks.filter { repo.savedTrack(it) != null }
                        .map { it.albumId }.toSet() else emptySet()
                    c.albums.filter {
                        matches(query, it.str("name"), it.str("artist")) &&
                            (selectedArtist == null ||
                                it.str("artistId") == selectedArtist.str("id") ||
                                it.str("artist") == selectedArtist.str("name")) &&
                            (!saved || it.str("id") in savedAlbums)
                    }.let { albums ->
                        if (sort == "Newest") albums.sortedByDescending { it.str("addedAt") }
                        else albums.map { normalize(it.str("name")) to it }
                            .sortedBy { it.first }.map { it.second }
                    }
                }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(140.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(albums) { a ->
                    Column(Modifier.clickable { album(a) }) {
                        Art(a.str("coverId"), Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(a.str("name"), maxLines = 2, fontSize = 14.sp)
                        Text(a.str("artist"), maxLines = 1, fontSize = 12.sp, color = Weazl.muted)
                    }
                }
            }
        } else if (scope == "Artists")
            LazyColumn {
                items(c.artists.filter { matches(query, it.str("name")) }) { a ->
                    ListItem(
                        headlineContent = { Text(a.str("name")) },
                        supportingContent = { Text("${a.optInt("albumCount")} albums") },
                        modifier = Modifier.clickable { artist = a },
                        colors = ListItemDefaults.colors(containerColor = Weazl.background),
                    )
                }
            }
        else
            LazyColumn {
                if (tracks.isEmpty()) item { Empty("No matching songs") }
                itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
                    TrackRow(repo, t, controller) { play(tracks, index) }
                }
            }
    }
}

@Composable
fun SearchView(
    repo: Repository,
    controller: MediaController?,
    play: (List<Track>, Int) -> Unit,
    album: (JSONObject) -> Unit,
    playlist: (JSONObject) -> Unit,
) {
    val c by repo.catalog.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var artists by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedArtist by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(query, c) {
        delay(150)
        withContext(Dispatchers.Default) {
            val t =
                if (query.isBlank()) emptyList()
                else c.tracks.filter { matches(query, it.title, it.artist, it.album) }.take(200)
            val a =
                if (query.isBlank()) emptyList()
                else c.albums.filter { matches(query, it.str("name"), it.str("artist")) }.take(30)
            withContext(Dispatchers.Main) {
                results = t
                albums = a
                artists =
                    if (query.isBlank()) emptyList()
                    else c.artists.filter { matches(query, it.str("name")) }.take(30)
                playlists =
                    if (query.isBlank()) emptyList()
                    else c.playlists.filter { matches(query, it.str("name")) }.take(30)
            }
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Heading("Search")
        Field("Songs, albums, artists, playlists", query, { query = it })
        LazyColumn {
            if (artists.isNotEmpty())
                item {
                    Text(
                        "Artists",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            items(artists) { artist ->
                TextButton({ selectedArtist = artist }) { Text(artist.str("name")) }
            }
            item { if (albums.isNotEmpty()) AlbumShelf("Albums", albums, album) }
            if (playlists.isNotEmpty())
                item {
                    Text(
                        "Playlists",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            items(playlists) { p -> TextButton({ playlist(p) }) { Text(p.str("name")) } }
            if (query.isBlank()) item { Empty("Search your library, online or offline.") }
            else if (
                results.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
            )
                item { Empty("No matches") }
            itemsIndexed(results) { index, t ->
                TrackRow(repo, t, controller) { play(results, index) }
            }
        }
    }
    selectedArtist?.let { artist ->
        AlertDialog(
            onDismissRequest = { selectedArtist = null },
            title = { Text(artist.str("name")) },
            text = {
                LazyColumn {
                    items(
                        c.albums.filter {
                            it.str("artistId") == artist.str("id") ||
                                it.str("artist") == artist.str("name")
                        }
                    ) { a ->
                        TextButton({
                            selectedArtist = null
                            album(a)
                        }) {
                            Text(a.str("name"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ repo.favorite("artist", artist.str("id")) }) {
                    Text(
                        if ("artist:${artist.str("id")}" in c.favorites) "Remove favorite"
                        else "Favorite artist"
                    )
                }
            },
            dismissButton = { TextButton({ selectedArtist = null }) { Text("Done") } },
        )
    }
}

@Composable
fun PlaylistsView(repo: Repository, open: (JSONObject) -> Unit) {
    val c by repo.catalog.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Heading("Playlists") }
                IconAction("Create playlist", Icons.Default.Add) { create = true }
            }
        }
        if (c.playlists.isEmpty()) item { Empty("No playlists") }
        items(c.playlists) { p ->
            ListItem(
                headlineContent = { Text(p.str("name")) },
                supportingContent = {
                    Text("${p.rows("entries").size} tracks · ${p.str("owner")}")
                },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Weazl.cyan)
                },
                modifier = Modifier.clickable { open(p) },
                colors = ListItemDefaults.colors(containerColor = Weazl.background),
            )
        }
    }
    if (create)
        NameDialog("Create playlist", name, { name = it }, { create = false }) {
            repo.action { savePlaylist(name, emptyList()) }
            create = false
        }
}

@Composable
fun NameDialog(
    title: String,
    name: String,
    change: (String) -> Unit,
    dismiss: () -> Unit,
    save: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Field("Name", name, change) },
        confirmButton = { TextButton(save, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

@Composable
fun CollectionView(
    repo: Repository,
    controller: MediaController?,
    kind: String,
    initial: JSONObject?,
    title: String,
    initialTracks: List<Track>,
    play: (List<Track>, Int) -> Unit,
    back: () -> Unit,
) {
    val c by repo.catalog.collectAsStateWithLifecycle()
    val downloads by repo.downloads.collectAsStateWithLifecycle()
    val current =
        if (kind == "Playlist")
            c.playlists.firstOrNull { it.str("id") == initial?.str("id") } ?: initial
        else initial
    val tracks =
        if (kind == "Playlist" && current != null) c.playlistTracks(current.str("id"))
        else initialTracks
    var editing by remember { mutableStateOf(false) }
    var editTracks by remember { mutableStateOf(tracks) }
    var name by remember { mutableStateOf(title) }
    var rename by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Heading(current?.str("name") ?: title)
            if (kind == "Album")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Art(current?.str("coverId").orEmpty(), Modifier.size(120.dp))
                    Column(Modifier.padding(16.dp)) {
                        Text(current?.str("artist").orEmpty())
                        Text("${tracks.size} songs", color = Weazl.muted)
                        Action("Favorite") { initial?.let { repo.favorite("album", it.str("id")) } }
                    }
                }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Play", Icons.Default.PlayArrow, true, tracks.isNotEmpty()) {
                    play(tracks, 0)
                }
                Action("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) {
                    controller?.addMediaItems(tracks.map(MediaCatalog(repo)::track))
                }
            }
            Action("Download offline", Icons.Default.Download) { repo.saveTracks(tracks) }
            Text(
                "${tracks.count { repo.savedTrack(it) != null }}/${tracks.size} available offline",
                color = Weazl.muted,
                fontSize = 12.sp,
            )
            downloads.size.let {}
        }
        if (kind == "Playlist" && current?.optBoolean("canEdit") == true)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (editing) "Save order" else "Edit") {
                        if (editing)
                            repo.action { savePlaylist(current.str("name"), editTracks, current) }
                        editTracks = tracks
                        editing = !editing
                    }
                    Action("Rename") { rename = true }
                    Action("Delete") { deleting = true }
                }
            }
        itemsIndexed(if (editing) editTracks else tracks) { index, t ->
            if (editing)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, Modifier.weight(1f))
                    IconAction("Move up", Icons.Default.ArrowUpward, enabled = index > 0) {
                        editTracks =
                            editTracks.toMutableList().apply { add(index - 1, removeAt(index)) }
                    }
                    IconAction("Remove", Icons.Default.Close) {
                        editTracks = editTracks.toMutableList().apply { removeAt(index) }
                    }
                }
            else TrackRow(repo, t, controller) { play(tracks, index) }
        }
    }
    if (rename)
        NameDialog("Rename playlist", name, { name = it }, { rename = false }) {
            repo.action { savePlaylist(name, tracks, current) }
            rename = false
        }
    if (deleting)
        Confirm("Delete this playlist?", { deleting = false }) {
            repo.action { deletePlaylist(current!!) }
            deleting = false
            back()
        }
}

@Composable
fun Confirm(title: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        confirmButton = { TextButton(confirm) { Text("Confirm") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}
