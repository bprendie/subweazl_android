@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

import android.os.Bundle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun RadioView(repo: Repository, controller: MediaController?, navigate: (String) -> Unit) {
    val c by repo.catalog.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("somafm") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val presets = c.stations.filter { it.optBoolean("preset") }.sortedBy { it.optInt("order") }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Heading("Internet Radio")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Add station", Icons.Default.Add) {
                    edit = null
                    name = ""
                    url = ""
                    showEditor = true
                }
                Action("Directory", Icons.Default.Search) { directory = !directory }
            }
            Action("Flight Recorder", Icons.Default.Flight) { navigate("Flight Recorder") }
        }
        if (directory)
            item {
                Field("Search stations", query, { query = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(provider) {
                        provider = if (provider == "somafm") "icecast" else "somafm"
                    }
                    Action("Search") {
                        repo.action {
                            val found =
                                api()
                                    .pages(
                                        "radio/directory",
                                        mapOf("provider" to provider, "query" to query),
                                    )
                            withContext(Dispatchers.Main) { results = found }
                        }
                    }
                }
                results.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.str("name"), Modifier.weight(1f))
                        IconAction("Add station", Icons.Default.Add) {
                            repo.action { saveStation(r.str("name"), r.str("url")) }
                        }
                    }
                }
            }
        if (c.stations.isEmpty()) item { Empty("No radio stations") }
        items(
            c.stations.sortedWith(
                compareByDescending<JSONObject> { it.optBoolean("preset") }
                    .thenBy { it.optInt("order") }
            )
        ) { s ->
            Column(
                Modifier.fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(1.dp, Weazl.line)
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.optBoolean("preset"))
                        Text(
                            "${presets.indexOfFirst { it.str("id") == s.str("id") }+1}",
                            color = Weazl.cyan,
                            fontSize = 22.sp,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    Text(s.str("name"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconAction("Play ${s.str("name")}", Icons.Default.PlayArrow, Weazl.amber) {
                        controller?.setMediaItem(MediaCatalog(repo).station(s))
                        controller?.prepare()
                        controller?.play()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconAction(
                        "Toggle preset",
                        if (s.optBoolean("preset")) Icons.Default.Star
                        else Icons.Default.StarBorder,
                        Weazl.amber,
                    ) {
                        repo.action {
                            val ids = presets.map { it.str("id") }
                            presets(
                                if (s.optBoolean("preset")) ids - s.str("id") else ids + s.str("id")
                            )
                        }
                    }
                    IconAction("Edit station", Icons.Default.Edit) {
                        edit = s
                        name = s.str("name")
                        url = s.str("streamURL")
                        showEditor = true
                    }
                    if (s.optBoolean("preset"))
                        IconAction(
                            "Move preset up",
                            Icons.Default.ArrowUpward,
                            enabled = presets.firstOrNull()?.str("id") != s.str("id"),
                        ) {
                            repo.action {
                                val ids = presets.map { it.str("id") }.toMutableList()
                                val index = ids.indexOf(s.str("id"))
                                if (index > 0) {
                                    ids.add(index - 1, ids.removeAt(index))
                                    presets(ids)
                                }
                            }
                        }
                    Spacer(Modifier.weight(1f))
                    IconAction("Delete station", Icons.Default.Delete) { deleting = s }
                }
            }
        }
    }
    if (showEditor)
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (edit == null) "Add station" else "Edit station") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Name", name, { name = it })
                    Field("Stream URL", url, { url = it })
                }
            },
            confirmButton = {
                TextButton(
                    {
                        repo.action { saveStation(name, url, edit) }
                        showEditor = false
                    },
                    enabled = name.isNotBlank() && url.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton({ showEditor = false }) { Text("Cancel") } },
        )
    deleting?.let { s ->
        Confirm("Delete ${s.str("name")}?", { deleting = null }) {
            repo.action { removeStation(s) }
            deleting = null
        }
    }
}

@Composable
fun DownloadsView(repo: Repository, controller: MediaController?) {
    val rows by repo.downloads.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Heading("Downloads") }
        if (rows.isEmpty()) item { Empty("Save songs, albums, or playlists while connected.") }
        items(rows.filter { it.str("kind") != "recordingSegment" }) { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(r.str("title"), fontWeight = FontWeight.SemiBold)
                Text(
                    "${if(r.str("status") == "complete") { if(r.str("quality") == repo.setting("downloadQuality","original")) "Saved" else "Available offline" } else r.str("status").replaceFirstChar { it.uppercase() }} · ${r.str("quality")}",
                    fontSize = 12.sp,
                    color = Weazl.cyan,
                )
                if (r.str("status") == "downloading")
                    LinearProgressIndicator(
                        progress = {
                            if (r.optLong("expectedBytes") > 0)
                                r.optLong("bytes").toFloat() / r.optLong("expectedBytes")
                            else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                if (r.str("error").isNotBlank())
                    Text(r.str("error"), color = Weazl.pink, fontSize = 12.sp)
                if (
                    r.str("status") == "complete" &&
                        r.str("quality") != repo.setting("downloadQuality", "original")
                )
                    Action("Upgrade saved quality") {
                        repo.catalog.value.tracks
                            .firstOrNull { it.id == r.str("resourceId") }
                            ?.let { repo.saveTracks(listOf(it)) }
                    }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.str("status") in listOf("queued", "downloading"))
                        Action("Pause") { repo.pauseDownload(r) }
                    else if (r.str("status") != "complete")
                        Action("Retry") { repo.retryDownload(r) }
                    else
                        Action("Play") {
                            val t =
                                repo.catalog.value.tracks.firstOrNull {
                                    it.id == r.str("resourceId")
                                }
                            t?.let {
                                controller?.setMediaItem(MediaCatalog(repo).track(it))
                                controller?.prepare()
                                controller?.play()
                            }
                        }
                    Action("Remove") {
                        if (controller?.currentMediaItem?.mediaId == "track:${r.str("resourceId")}")
                            repo.message.value =
                                "Change the current track before removing its file."
                        else repo.removeDownload(r)
                    }
                }
            }
        }
    }
}

@Composable
fun QueueView(repo: Repository, controller: MediaController?) {
    var tick by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val items =
        remember(tick) {
            (0 until (controller?.mediaItemCount ?: 0)).map { controller!!.getMediaItemAt(it) }
        }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Heading("Queue")
            Text("${items.size} tracks", color = Weazl.muted)
        }
        itemsIndexed(items) { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f)
                        .clickable {
                            controller?.seekTo(index, 0)
                            controller?.play()
                        }
                        .padding(vertical = 14.dp)
                ) {
                    Text(
                        item.mediaMetadata.title?.toString().orEmpty(),
                        color =
                            if (index == controller?.currentMediaItemIndex) Weazl.amber
                            else Weazl.ink,
                    )
                    Text(
                        item.mediaMetadata.artist?.toString().orEmpty(),
                        color = Weazl.muted,
                        fontSize = 12.sp,
                    )
                }
                IconAction("Move up", Icons.Default.ArrowUpward, enabled = index > 0) {
                    controller?.moveMediaItem(index, index - 1)
                    tick++
                }
                IconAction("Remove from queue", Icons.Default.Close) {
                    controller?.removeMediaItem(index)
                    tick++
                }
            }
        }
        item {
            Toggle("Shuffle", controller?.shuffleModeEnabled ?: false) {
                controller?.shuffleModeEnabled = it
                tick++
            }
            Action(
                "Repeat: ${when(controller?.repeatMode) { Player.REPEAT_MODE_ALL -> "All"
 Player.REPEAT_MODE_ONE -> "One"
 else -> "Off" }}"
            ) {
                controller?.repeatMode = ((controller?.repeatMode ?: 0) + 1) % 3
                tick++
            }
            Action("Save queue as playlist") { saving = true }
            Action("Resume library") {
                controller?.sendCustomCommand(
                    SessionCommand("resume-library", Bundle.EMPTY),
                    Bundle.EMPTY,
                )
            }
        }
    }
    if (saving)
        NameDialog("Save queue", name, { name = it }, { saving = false }) {
            val tracks =
                items.mapNotNull { item ->
                    repo.catalog.value.tracks.firstOrNull { item.mediaId == "track:${it.id}" }
                }
            repo.action { savePlaylist(name, tracks) }
            saving = false
        }
}

@Composable
fun PlayerView(
    repo: Repository,
    controller: MediaController?,
    tick: Int,
    navigate: (String) -> Unit,
) {
    tick.let {}
    val item = controller?.currentMediaItem
    val c by repo.catalog.collectAsStateWithLifecycle()
    val id = item?.mediaId.orEmpty()
    val track = c.tracks.firstOrNull { id == "track:${it.id}" }
    val allFlights by repo.flights.collectAsStateWithLifecycle()
    val flight = allFlights.firstOrNull { id.startsWith("flight:${it.id}:") }
    val station = flight?.stations?.firstOrNull { id == "flight:${flight.id}:${it.id}" }
    var seek by remember { mutableStateOf<Float?>(null) }
    val position = controller?.currentPosition ?: 0
    val length = (controller?.duration ?: 0).coerceAtLeast(1)
    Form {
        Heading("Now Playing")
        if (item == null) Empty("Choose music or a station to play.")
        else {
            if (track != null) {
                Text(track.album, color = Weazl.muted)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Art(
                        track.coverId,
                        Modifier.widthIn(max = 310.dp).fillMaxWidth().aspectRatio(1f),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.mediaMetadata.title?.toString().orEmpty(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(item.mediaMetadata.artist?.toString().orEmpty(), color = Weazl.muted)
                }
                if (track != null)
                    IconAction(
                        "Favorite",
                        if ("track:${track.id}" in c.favorites) Icons.Default.Favorite
                        else Icons.Default.FavoriteBorder,
                        Weazl.pink,
                    ) {
                        repo.favorite("track", track.id)
                    }
            }
            if (flight != null && station != null) {
                Text("Flight Recorder · ${flight.name}", color = Weazl.cyan)
                if (flight.segmentAt(station.id, position) == null)
                    Text("No recording at this time", color = Weazl.muted)
                else
                    station.metadata
                        .lastOrNull { it.optLong("atMs") <= position }
                        ?.let {
                            Text(
                                listOf(it.str("title"), it.str("artist"))
                                    .filter(String::isNotBlank)
                                    .joinToString(" · ")
                            )
                        }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(flight.stations) { s ->
                        FilterChip(
                            s.id == station.id,
                            {
                                val playing = controller?.playWhenReady == true
                                controller?.setMediaItem(MediaCatalog(repo).flight(flight, s))
                                controller?.prepare()
                                if (playing) controller?.play()
                            },
                            label = { Text(s.name) },
                            shape = RectangleShape,
                        )
                    }
                }
            }
            if (!id.startsWith("radio:")) {
                Slider(
                    value = seek ?: position.toFloat().coerceIn(0f, length.toFloat()),
                    onValueChange = { seek = it },
                    onValueChangeFinished = {
                        controller?.seekTo(seek?.toLong() ?: position)
                        seek = null
                    },
                    valueRange = 0f..length.toFloat(),
                )
                Row {
                    Text(duration(position))
                    Spacer(Modifier.weight(1f))
                    Text(duration(length))
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(
                    "Shuffle",
                    Icons.Default.Shuffle,
                    if (controller?.shuffleModeEnabled == true) Weazl.amber else Weazl.muted,
                ) {
                    controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false)
                }
                IconAction("Previous", Icons.Default.SkipPrevious) { controller?.seekToPrevious() }
                Button(
                    {
                        if (controller?.playWhenReady == true) controller.pause()
                        else {
                            controller?.prepare()
                            controller?.play()
                        }
                    },
                    shape = RectangleShape,
                    modifier = Modifier.size(64.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        if (controller?.playWhenReady == true) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        if (controller?.playWhenReady == true) "Pause" else "Play",
                        Modifier.size(32.dp),
                    )
                }
                IconAction("Next", Icons.Default.SkipNext) { controller?.seekToNext() }
                IconAction(
                    "Repeat",
                    if (controller?.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                    else Icons.Default.Repeat,
                    if (controller?.repeatMode == Player.REPEAT_MODE_OFF) Weazl.muted
                    else Weazl.amber,
                ) {
                    controller?.repeatMode = ((controller?.repeatMode ?: 0) + 1) % 3
                }
            }
            if (controller?.playbackState == Player.STATE_BUFFERING)
                Text("Buffering…", color = Weazl.muted)
            if (controller?.playerError != null) {
                Text(
                    "Playback unavailable. Check your connection or choose saved music.",
                    color = Weazl.pink,
                )
                Action("Play next saved") {
                    val found =
                        ((controller.currentMediaItemIndex + 1) until controller.mediaItemCount)
                            .firstOrNull { i ->
                                c.tracks
                                    .firstOrNull {
                                        controller.getMediaItemAt(i).mediaId == "track:${it.id}"
                                    }
                                    ?.let { repo.savedTrack(it) != null } == true
                            }
                    found?.let {
                        controller.seekTo(it, 0)
                        controller.prepare()
                        controller.play()
                    }
                }
            }
            Action("Queue", Icons.AutoMirrored.Filled.QueueMusic) { navigate("Queue") }
            if (!id.startsWith("track:"))
                Action("Resume library") {
                    controller?.sendCustomCommand(
                        SessionCommand("resume-library", Bundle.EMPTY),
                        Bundle.EMPTY,
                    )
                }
        }
    }
}

@Composable
fun FlightView(repo: Repository, controller: MediaController?) {
    val sessions by repo.sessions.collectAsStateWithLifecycle()
    val schedules by repo.schedules.collectAsStateWithLifecycle()
    val flights by repo.flights.collectAsStateWithLifecycle()
    val downloads by repo.downloads.collectAsStateWithLifecycle()
    val offline by repo.offline.collectAsStateWithLifecycle()
    val caps by repo.capabilities.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val account by repo.active.collectAsStateWithLifecycle()
    val localRevision by repo.revision.collectAsStateWithLifecycle()
    localRevision.let {}
    LaunchedEffect(offline, account?.key) {
        while (!offline) {
            runCatching { withContext(Dispatchers.IO) { repo.refreshFlights() } }
            delay(15000)
        }
    }
    if (editor) {
        FlightEditor(repo, editing) { editor = false }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Heading("Flight Recorder")
            Action(
                "New recording",
                Icons.Default.Add,
                enabled =
                    !offline && caps.optJSONObject("flightRecorder")?.optBoolean("enabled") == true,
            ) {
                editing = null
                editor = true
            }
            if (offline) Text("Offline · saved recordings", color = Weazl.cyan)
        }
        val pending = account?.let { readJson(repo.file(it.key, "pending-flight.json")) }
        if (pending != null)
            item {
                Text("Unconfirmed recording request", color = Weazl.pink)
                Action("Resume recording request", enabled = !offline) {
                    repo.action {
                        val account = repo.active.value!!.key
                        api()
                            .request(
                                pending.str("path"),
                                pending.str("method"),
                                pending.getJSONObject("body"),
                                key = pending.str("key"),
                                etag = pending.str("etag").takeIf { it.isNotBlank() },
                            )
                        file(account, "pending-flight.json").delete()
                        refreshFlights()
                    }
                }
                Action("Discard local request") {
                    repo.file(repo.active.value!!.key, "pending-flight.json").delete()
                    repo.revision.value++
                }
            }
        if (flights.isNotEmpty()) item { Text("On this device", fontWeight = FontWeight.Bold) }
        items(flights) { f ->
            val ready = repo.flightReady(f)
            downloads.size.let {}
            Column(
                Modifier.fillMaxWidth().border(1.dp, Weazl.line).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(f.name, fontWeight = FontWeight.Bold)
                Text(
                    if (ready) if (f.hasGaps) "Ready offline · gaps" else "Ready offline"
                    else "Download incomplete",
                    color = Weazl.cyan,
                )
                val ids =
                    f.stations
                        .flatMap { it.segments }
                        .map {
                            repo.downloadId("recordingSegment", it.asset, "original", f.revision)
                        }
                        .toSet()
                val rows = downloads.filter { it.str("id") in ids }
                if (!ready) {
                    Text(
                        "${rows.count { it.str("status") == "complete" }}/${ids.size} segments saved",
                        fontSize = 12.sp,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (ids.isEmpty()) 0f
                            else rows.count { it.str("status") == "complete" }.toFloat() / ids.size
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Action("Resume download", enabled = !offline) {
                        repo.action { downloadFlight(f.id) }
                    }
                    Action("Pause download") {
                        rows
                            .filter { it.str("status") in listOf("queued", "downloading") }
                            .forEach(repo::pauseDownload)
                    }
                } else
                    Action("Play recording", Icons.Default.PlayArrow, true) {
                        controller?.setMediaItem(MediaCatalog(repo).flight(f, f.stations.first()))
                        controller?.prepare()
                        controller?.play()
                    }
                Action("Remove from this device") {
                    confirm =
                        "Remove ${f.name} from this device?" to
                            {
                                if (
                                    controller
                                        ?.currentMediaItem
                                        ?.mediaId
                                        ?.startsWith("flight:${f.id}:") == true
                                )
                                    repo.message.value =
                                        "Change playback before removing its recording."
                                else repo.removeFlight(f)
                            }
                }
            }
        }
        item { Text("Server recordings", fontWeight = FontWeight.Bold) }
        if (sessions.isEmpty()) item { Empty("No server recordings") }
        items(sessions) { s ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, Weazl.line).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(s.str("name"), fontWeight = FontWeight.Bold)
                Text("${s.str("state")} · ${s.optInt("stationCount")} presets", color = Weazl.muted)
                Text(s.str("startsAt"), fontSize = 12.sp)
                s.rows("stations").forEach { station ->
                    Text(
                        "${station.optJSONObject("station")?.str("name") ?: station.str("stationId")}: ${station.str("state")}",
                        fontSize = 12.sp,
                    )
                    if (station.str("error").isNotBlank())
                        Text(station.str("error"), color = Weazl.pink, fontSize = 12.sp)
                }
                if (s.str("state") in listOf("complete", "partial"))
                    Action("Download to play", Icons.Default.Download, enabled = !offline) {
                        repo.action { downloadFlight(s.str("id")) }
                    }
                if (s.str("state") in listOf("recording", "scheduled"))
                    Action("Stop and finalize", enabled = !offline) {
                        repo.action {
                            api()
                                .request(
                                    "flight-recorder/sessions/${Api.path(s.str("id"))}/stop",
                                    "POST",
                                )
                            refreshFlights()
                        }
                    }
                else if (s.str("state") != "finalizing")
                    Action("Delete server recording", enabled = !offline) {
                        confirm =
                            "Delete ${s.str("name")} on the server? Saved copies stay on this device." to
                                {
                                    repo.action {
                                        val path =
                                            "flight-recorder/sessions/${Api.path(s.str("id"))}"
                                        val r = api().request(path)
                                        api().request(path, "DELETE", etag = r.etag)
                                        refreshFlights()
                                    }
                                }
                    }
            }
        }
        item { Text("Upcoming", fontWeight = FontWeight.Bold) }
        items(schedules) { s ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(s.str("name"), fontWeight = FontWeight.Bold)
                Text(
                    "${s.str("state")} · ${s.str("nextStartsAt")}",
                    color = Weazl.muted,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Edit", enabled = !offline) {
                        editing = s
                        editor = true
                    }
                    Action(
                        "Cancel future recordings",
                        enabled = !offline && s.str("state") == "active",
                    ) {
                        confirm =
                            "Cancel future recordings for ${s.str("name")}?" to
                                {
                                    repo.action {
                                        val path =
                                            "flight-recorder/schedules/${Api.path(s.str("id"))}"
                                        val r = api().request(path)
                                        api().request(path, "DELETE", etag = r.etag)
                                        refreshFlights()
                                    }
                                }
                    }
                }
            }
        }
    }
    confirm?.let { (title, action) ->
        Confirm(title, { confirm = null }) {
            action()
            confirm = null
        }
    }
}

@Composable
fun FlightEditor(repo: Repository, editing: JSONObject?, done: () -> Unit) {
    val catalog by repo.catalog.collectAsStateWithLifecycle()
    val presets =
        catalog.stations.filter { it.optBoolean("preset") }.sortedBy { it.optInt("order") }
    var name by remember { mutableStateOf(editing?.str("name") ?: "Flight recording") }
    var selected by remember {
        mutableStateOf(
            editing?.optJSONArray("stationIds")?.let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            } ?: presets.take(6).map { it.str("id") }.toSet()
        )
    }
    var now by remember { mutableStateOf(editing == null) }
    var hours by remember {
        mutableStateOf(
            editing?.let {
                runCatching {
                        (java.time.Duration.between(
                                    Instant.parse(it.str("startsAt")),
                                    Instant.parse(it.str("endsAt")),
                                )
                                .toMillis() / 3_600_000.0)
                            .toString()
                    }
                    .getOrNull()
            } ?: "4"
        )
    }
    var start by remember {
        mutableStateOf(
            editing?.str("nextStartsAt", editing.str("startsAt"))?.let {
                runCatching {
                        Instant.parse(it)
                            .atZone(
                                ZoneId.of(editing?.str("timeZone") ?: ZoneId.systemDefault().id)
                            )
                            .toLocalDateTime()
                            .toString()
                            .take(16)
                    }
                    .getOrNull()
            } ?: LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString()
        )
    }
    var zone by remember { mutableStateOf(editing?.str("timeZone") ?: ZoneId.systemDefault().id) }
    var recurrence by remember {
        mutableStateOf(editing?.optJSONObject("recurrence")?.str("kind") ?: "once")
    }
    var weekdays by remember {
        mutableStateOf(
            editing?.optJSONObject("recurrence")?.optJSONArray("weekdays")?.let { a ->
                (0 until a.length()).joinToString(",") { a.getInt(it).toString() }
            } ?: "1,2,3,4,5"
        )
    }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    val key = remember { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    Form {
        Heading(if (editing == null) "New recording" else "Edit schedule")
        Field("Name", name, { name = it })
        if (editing == null) Toggle("Record now", now, { now = it })
        Field("Duration (hours)", hours, { hours = it })
        if (!now) {
            Field("Local start (YYYY-MM-DDTHH:MM)", start, { start = it })
            Field("Time zone", zone, { zone = it })
            Action("Repeat: $recurrence") {
                recurrence =
                    when (recurrence) {
                        "once" -> "daily"
                        "daily" -> "weekly"
                        else -> "once"
                    }
            }
            if (recurrence == "weekly")
                Field("ISO weekdays (1=Monday)", weekdays, { weekdays = it })
        }
        Text("Choose up to six presets", color = Weazl.cyan)
        presets.forEach { s ->
            Toggle(s.str("name"), s.str("id") in selected) { checked ->
                if (!checked) selected = selected - s.str("id")
                else if (selected.size < 6) selected = selected + s.str("id")
            }
        }
        Action("Review recording", filled = true, enabled = !busy && selected.isNotEmpty()) {
            try {
                val duration = (hours.toDouble() * 3_600_000).toLong()
                require(
                    duration in
                        1..(repo.capabilities.value
                                .optJSONObject("flightRecorder")
                                ?.optLong("maxDurationMs", 43_200_000) ?: 43_200_000)
                ) {
                    "Choose a duration within the server limit"
                }
                val body =
                    obj(
                        "name" to name,
                        "stationIds" to JSONArray(selected.toList()),
                        "durationMs" to duration,
                    )
                if (!now) {
                    val local = LocalDateTime.parse(start)
                    val z = ZoneId.of(zone)
                    val offsets = z.rules.getValidOffsets(local)
                    val begin = local.atZone(z)
                    val end = begin.plusNanos(duration * 1_000_000)
                    require(begin.toInstant().isAfter(Instant.now())) {
                        "Start time must be in the future"
                    }
                    body
                        .put("startsAt", begin.toInstant().toString())
                        .put("endsAt", end.toInstant().toString())
                        .put("timeZone", zone)
                    body.put(
                        "recurrence",
                        obj(
                            "kind" to recurrence,
                            "localStart" to begin.toLocalTime().toString().take(5),
                            "localEnd" to end.toLocalTime().toString().take(5),
                            "weekdays" to
                                JSONArray(
                                    weekdays.split(',').map {
                                        it.trim().toInt().also { d -> require(d in 1..7) }
                                    }
                                ),
                            "endDate" to null,
                        ),
                    )
                    if (offsets.size != 1)
                        body.put(
                            "_notice",
                            "Daylight-saving adjustment: ${begin.toOffsetDateTime()}",
                        )
                    body.remove("durationMs")
                }
                preview = body
            } catch (e: Exception) {
                repo.message.value = repo.friendly(e)
            }
        }
        Action("Cancel", onClick = done)
    }
    preview?.let { body ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Confirm recording") },
            text = {
                Text(
                    "${body.str("name")}\n${selected.size} presets\n" +
                        (if (now) "Start now · $hours hours"
                        else
                            "${body.str("startsAt")} → ${body.str("endsAt")}\n${body.str("_notice")}")
                )
            },
            confirmButton = {
                TextButton({
                    busy = true
                    preview = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                body.remove("_notice")
                                val account = repo.active.value!!.key
                                val path =
                                    if (now) "flight-recorder/sessions"
                                    else
                                        "flight-recorder/schedules" +
                                            (editing?.let { "/${Api.path(it.str("id"))}" } ?: "")
                                val etag = editing?.let { "\"v${it.optInt("version")}\"" }
                                val pending =
                                    obj(
                                        "path" to path,
                                        "method" to if (editing == null) "POST" else "PATCH",
                                        "body" to body,
                                        "key" to key,
                                        "etag" to etag,
                                    )
                                atomicWrite(
                                    repo.file(account, "pending-flight.json"),
                                    pending.toString(),
                                )
                                repo
                                    .api()
                                    .request(
                                        path,
                                        if (editing == null) "POST" else "PATCH",
                                        body,
                                        key = key,
                                        etag = etag,
                                    )
                                repo.file(account, "pending-flight.json").delete()
                                repo.refreshFlights()
                            }
                            done()
                        } catch (e: Exception) {
                            repo.message.value = repo.friendly(e)
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton({ preview = null }) { Text("Back") } },
        )
    }
}
