@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

import android.os.Bundle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun SettingsView(
    repo: Repository,
    controller: MediaController?,
    navigate: (String) -> Unit,
    connect: (Account?) -> Unit,
) {
    val accounts by repo.accounts.collectAsStateWithLifecycle()
    val active by repo.active.collectAsStateWithLifecycle()
    val offline by repo.offline.collectAsStateWithLifecycle()
    val revision by repo.revision.collectAsStateWithLifecycle()
    val caps by repo.capabilities.collectAsStateWithLifecycle()
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    val catalog by repo.catalog.collectAsStateWithLifecycle()
    val savedDownloads by repo.downloads.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    revision.let {}
    Form {
        Heading("Settings")
        Text("Connection", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        Text(active?.name.orEmpty(), fontWeight = FontWeight.SemiBold)
        Toggle("Offline mode", offline) {
            repo.setSetting("offline", it.toString())
            if (!it) repo.action { sync() }
        }
        Action(if (syncing) "Syncing…" else "Reconnect and sync", enabled = !offline && !syncing) {
            repo.action { sync() }
        }
        if (catalog.synced.isNotBlank())
            Text("Last sync: ${catalog.synced}", color = Weazl.muted, fontSize = 12.sp)
        Text("Accounts", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        accounts.forEach { a ->
            Column {
                TextButton({
                    repo.action {
                        activate(a)
                        sync()
                    }
                }) {
                    Text(a.name + (if (a.key == active?.key) " · active" else ""))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Edit / sign in") { connect(a) }
                    Action("Remove") {
                        confirm =
                            "Remove ${a.name} and its local downloads? Server files stay unchanged." to
                                {
                                    repo.action { removeAccount(a) }
                                }
                    }
                }
            }
        }
        Action("Add server") { connect(null) }
        if (active?.serverType != "navidrome") Action("Signed-in devices") { navigate("Signed-in devices") }
        active?.let { a ->
            Action("Sign out") {
                confirm =
                    "Sign out? Saved music stays on this device." to { repo.action { signOut(a) } }
            }
        }
        if (active?.serverType == "navidrome")
            Text("Navidrome connected. Enhanced radio, Flight Recorder and Mood require WeazlMusic.", color = Weazl.muted)
        Text("Listening", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        listOf("Queue", "Downloads", "Changes needing review", "Mood & curator").filter { it != "Mood & curator" || active?.serverType != "navidrome" }.forEach { title ->
            Action(title) { navigate(title) }
        }
        Toggle("Downloads on Wi-Fi only", repo.setting("wifiOnly", "true").toBoolean()) {
            repo.setSetting("wifiOnly", it.toString())
        }
        Text("Audio quality", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        val profiles =
            caps.rows("mediaProfiles").map { it.str("id") }.ifEmpty { listOf("original") }
        listOf(
                "Wi-Fi streaming" to "wifiQuality",
                "Cellular streaming" to "cellularQuality",
                "Saved music" to "downloadQuality",
            )
            .forEach { (label, key) ->
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Action("$label: ${repo.setting(key,"original")}") { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) {
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                { Text(p) },
                                {
                                    repo.setSetting(key, p)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        Text("Storage", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        Action("Audio limit: ${repo.setting("budgetGiB","8")} GiB") {
            val choices = listOf("1", "2", "4", "8", "16")
            repo.setSetting(
                "budgetGiB",
                choices[(choices.indexOf(repo.setting("budgetGiB", "8")) + 1) % choices.size],
            )
        }
        Text(
            "Saved: ${android.text.format.Formatter.formatFileSize(repo.context,savedDownloads.sumOf { it.optLong("bytes") })}",
            color = Weazl.muted,
        )
        Action("Clear temporary audio") {
            if (controller?.isPlaying == true)
                repo.message.value = "Pause playback before clearing temporary audio."
            else
                controller?.sendCustomCommand(
                    SessionCommand("clear-cache", Bundle.EMPTY),
                    Bundle.EMPTY,
                )
        }
        Action("About, privacy & help") { navigate("About, privacy & help") }
        Text("Playback", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        Text(
            when (controller?.playbackState) {
                2 -> "Buffering"
                3 -> if (controller.isPlaying) "Playing" else "Paused"
                4 -> "Ended"
                else -> "Idle"
            }
        )
        Text(
            "Android Auto uses the same library, queue, and saved recordings. Sign in here before connecting to your car.",
            fontSize = 12.sp,
            color = Weazl.muted,
        )
    }
    confirm?.let { (title, action) ->
        Confirm(title, { confirm = null }) {
            action()
            confirm = null
        }
    }
}

@Composable
fun DevicesView(repo: Repository) {
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        repo.action {
            val devices = api().request("auth/devices").json.rows("data")
            withContext(Dispatchers.Main) { rows = devices }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Heading("Signed-in devices") }
        items(rows) { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(d.str("name", d.str("deviceName")))
                    Text(d.str("lastSeen"), color = Weazl.muted, fontSize = 12.sp)
                }
                Action("Revoke") { removing = d }
            }
        }
    }
    removing?.let { d ->
        Confirm("Revoke this device session?", { removing = null }) {
            repo.action {
                api().request("auth/devices/${Api.path(d.str("id"))}", "DELETE")
                val fresh = api().request("auth/devices").json.rows("data")
                withContext(Dispatchers.Main) { rows = fresh }
            }
            removing = null
        }
    }
}

@Composable
fun MutationView(repo: Repository) {
    val rows by repo.mutations.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Heading("Changes needing review")
            Action("Retry pending changes") {
                repo.action { flushMutations(repo.active.value!!.key) }
            }
        }
        if (rows.isEmpty()) item { Empty("All changes are synchronized.") }
        items(rows) { r ->
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(r.str("path").substringBeforeLast('/'))
                Text(r.str("state"), color = Weazl.cyan)
                if (r.str("error").isNotBlank()) Text(r.str("error"), color = Weazl.pink)
                if (r.str("state") == "review")
                    Text(
                        "Check the server before retrying an uncertain change.",
                        color = Weazl.muted,
                    )
                Action("Dismiss") { repo.discardMutation(r.str("id")) }
            }
        }
    }
}

@Composable
fun MoodView(repo: Repository) {
    val capabilities by repo.capabilities.collectAsStateWithLifecycle()
    val c by repo.catalog.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var seed by remember { mutableStateOf<Track?>(null) }
    var provider by remember { mutableStateOf("off") }
    var url by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var job by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        repo.action {
            val p = api().request("preferences/curator").data
            val saved = readJson(file(repo.active.value!!.key, "mood.json"))?.str("jobId")
            val existing =
                saved
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        runCatching { api().request("mood/jobs/${Api.path(it)}").data }.getOrNull()
                    }
            withContext(Dispatchers.Main) {
                provider = p.str("provider", "off")
                url = p.str("url")
                model = p.str("model")
                hasKey = p.optBoolean("hasKey")
                job = existing
            }
        }
    }
    LaunchedEffect(query, c.tracks) {
        delay(180)
        results =
            withContext(Dispatchers.Default) {
                if (query.isBlank()) emptyList()
                else c.tracks.filter { matches(query, it.title, it.artist) }.take(30)
            }
    }
    LaunchedEffect(job?.str("jobId")) {
        while (job?.str("state") == "running") {
            delay(2000)
            try {
                job =
                    withContext(Dispatchers.IO) {
                        repo.api().request("mood/jobs/${Api.path(job!!.str("jobId"))}").data
                    }
                if (job?.str("state") != "running") repo.action { sync() }
            } catch (e: Exception) {
                repo.message.value = repo.friendly(e)
                break
            }
        }
    }
    fun draft() =
        obj(
            "provider" to provider,
            "url" to url,
            "model" to model,
            "apiKey" to apiKey.takeIf { it.isNotEmpty() },
        )
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Heading("Mood & curator")
            Text("Curator", color = Weazl.cyan)
            Action("Provider: $provider") {
                provider =
                    when (provider) {
                        "off" -> "ollama"
                        "ollama" -> "vllm"
                        else -> "off"
                    }
            }
            Field("Provider URL", url, { url = it })
            Field("Model", model, { model = it })
            Field(
                if (hasKey) "API key (saved; leave blank to keep)" else "API key (optional)",
                apiKey,
                { apiKey = it },
                true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Fetch models") {
                    repo.action {
                        val p =
                            api()
                                .request("preferences/curator/models", "POST", draft())
                                .data
                                .optJSONArray("models")
                        withContext(Dispatchers.Main) {
                            models = p?.let { (0 until it.length()).map(it::getString) }.orEmpty()
                        }
                    }
                }
                Action("Save curator") {
                    repo.action {
                        api().request("preferences/curator", "PUT", draft())
                        message.value = "Curator preferences saved"
                    }
                }
            }
            if (hasKey)
                Action("Clear saved API key") {
                    repo.action {
                        api().request("preferences/curator", "PUT", draft().put("apiKey", ""))
                        withContext(Dispatchers.Main) { hasKey = false }
                    }
                }
        }
        items(models) { m -> TextButton({ model = m }) { Text(m) } }
        item {
            Text("Generate a playlist", color = Weazl.cyan)
            Field("Find a seed song", query, { query = it })
            seed?.let { Text("Seed: ${it.title} · ${it.artist}", color = Weazl.amber) }
        }
        items(results, key = { it.id }) { t ->
            TextButton({
                seed = t
                query = ""
            }) {
                Text("${t.title} · ${t.artist}")
            }
        }
        item {
            Action(
                if (busy) "Starting…" else "Start Mood",
                Icons.Default.AutoAwesome,
                true,
                !busy &&
                    seed != null &&
                    job?.str("state") != "running" &&
                    capabilities.optBoolean("mood"),
            ) {
                busy = true
                scope.launch {
                    try {
                        val created =
                            withContext(Dispatchers.IO) {
                                val result =
                                    repo
                                        .api()
                                        .request(
                                            "mood/jobs",
                                            "POST",
                                            obj("seedTrackId" to seed!!.id),
                                        )
                                        .data
                                atomicWrite(
                                    repo.file(repo.active.value!!.key, "mood.json"),
                                    result.toString(),
                                )
                                result
                            }
                        job = created
                    } catch (e: Exception) {
                        repo.message.value = repo.friendly(e)
                    } finally {
                        busy = false
                    }
                }
            }
        }
        job?.let { j ->
            item {
                Text("Mood: ${j.str("state")}", fontWeight = FontWeight.Bold)
                if (j.str("state") == "running")
                    Action("Stop generation") {
                        repo.action {
                            api().request("mood/jobs/${Api.path(j.str("jobId"))}", "DELETE")
                            val updated =
                                api().request("mood/jobs/${Api.path(j.str("jobId"))}").data
                            withContext(Dispatchers.Main) { job = updated }
                        }
                    }
            }
            items(j.rows("events").takeLast(30)) { event ->
                Text(
                    event.str("message").ifBlank {
                        event.optJSONObject("track")?.str("title") ?: event.str("type")
                    },
                    fontSize = 13.sp,
                    color = Weazl.muted,
                )
            }
        }
    }
}

@Composable
fun AboutView() {
    Form {
        Heading("Subweazl")
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("A native music client for your WeazlMusic server.")
        Text("Server requirements", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        Text(
            "Connect directly to Navidrome for music, or use WeazlMusic for enhanced radio, recording and Mood."
        )
        Text("Privacy", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        Text(
            "Subweazl has no advertising, tracking SDKs, or automatic developer analytics. Your selected server receives login, playback, artwork, download, favorites, playlist, listening-history, and recording requests. Optional Mood generation sends music metadata to the curator configured on your server."
        )
        Text(
            "Device tokens and proxy headers are encrypted using Android Keystore. Music metadata, playback history, settings, artwork, and saved audio remain in app-private storage. Android media services, Android Auto, and voice assistants receive music metadata to provide playback controls. Their handling follows your Android and Google settings."
        )
        Text(
            "Sign out revokes your device session when connected and clears local credentials while retaining saved music. Remove account deletes local credentials, metadata, and downloaded files. Server files and accounts are unchanged. Contact your server administrator for server data deletion."
        )
        Text("Support", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        Text(
            "bprendie@icloud.com\nhttps://github.com/bprendie/weazlmusic/issues\nDo not include passwords, tokens, private library exports, or recordings in public reports."
        )
        Text("Open-source software", color = Weazl.cyan, fontWeight = FontWeight.Bold)
        Text(
            "AndroidX / Media3 / Compose / WorkManager, Kotlin coroutines, and OkHttp are used under Apache License 2.0. Music and artwork are supplied by your server; Subweazl provides no catalog or subscription."
        )
    }
}
