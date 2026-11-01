package io.prendie.subweazl

import android.app.Application
import android.content.Context
import androidx.work.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class SubweazlApp : Application() {
    lateinit var repository: Repository

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
    }
}

val Context.repo
    get() = (applicationContext as SubweazlApp).repository

class Repository(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val vault = Vault(context)
    private val prefs = context.getSharedPreferences("subweazl", Context.MODE_PRIVATE)
    val accounts =
        MutableStateFlow(
            runCatching {
                    JSONArray(prefs.getString("accounts", "[]")).objects().map(Account::parse)
                }
                .getOrDefault(emptyList())
        )
    val active =
        MutableStateFlow(accounts.value.firstOrNull { it.key == prefs.getString("active", null) })
    val catalog = MutableStateFlow(Catalog())
    val capabilities = MutableStateFlow(JSONObject())
    val flights = MutableStateFlow<List<Flight>>(emptyList())
    val sessions = MutableStateFlow<List<JSONObject>>(emptyList())
    val schedules = MutableStateFlow<List<JSONObject>>(emptyList())
    val downloads = MutableStateFlow<List<JSONObject>>(emptyList())
    val history = MutableStateFlow(JSONObject())
    val mutations = MutableStateFlow<List<JSONObject>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val radioMetadata = MutableStateFlow(JSONObject())
    val syncing = MutableStateFlow(false)
    private val syncMutex = Mutex()
    val revision = MutableStateFlow(0)
    val apiClients = mutableMapOf<String, Api>()
    val offline = MutableStateFlow(false)
    var stopPlayback: (() -> Unit)? = null
    var restorePlayback: (() -> Unit)? = null
    var offlineChanged: (() -> Unit)? = null

    init {
        active.value?.let { loadLocal(it) }
        scope.launch {
            if (active.value != null && !offline.value)
                runCatching { sync() }.onFailure { message.value = friendly(it) }
        }
    }

    fun accountDir(key: String) = File(context.filesDir, "accounts/${hash(key)}").apply { mkdirs() }

    fun file(key: String, name: String) = File(accountDir(key), name)

    fun setting(key: String, fallback: String = "") =
        prefs.getString("${active.value?.key}:$key", fallback) ?: fallback

    fun setSetting(key: String, value: String) {
        prefs.edit().putString("${active.value?.key}:$key", value).apply()
        if (key == "offline") {
            offline.value = value.toBoolean()
            scope.launch(Dispatchers.Main) { offlineChanged?.invoke() }
        }
        revision.update { it + 1 }
    }

    fun accountSetting(account: String, key: String, fallback: String = "") =
        prefs.getString("$account:$key", fallback) ?: fallback

    fun api(key: String = active.value?.key ?: error("Connect to a server first")): Api =
        synchronized(apiClients) {
            apiClients.getOrPut(key) {
                Api(accounts.value.first { it.key == key }, vault) {
                    accountSetting(key, "offline", "false") != "true"
                }
            }
        }

    fun action(block: suspend Repository.() -> Unit): Job =
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = friendly(e)
            }
        }

    private val artworkLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun artwork(key: String, id: String): File? {
        if (id.isBlank()) return null
        return synchronized(artworkLocks.getOrPut("$key:$id") { Any() }) {
            val target = file(key, "art/${hash(id)}")
            if (!target.exists()) {
                val client = api(key)
                val lease = client.lease("cover", id)
                val request = okhttp3.Request.Builder().url(lease.getString("url"))
                client.mediaHeaders().forEach { (k, v) -> request.header(k, v) }
                Api.http.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful) return@synchronized null
                    val input = response.body?.byteStream() ?: return@synchronized null
                    val bytes = java.io.ByteArrayOutputStream()
                    input.use { stream ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            require(bytes.size() + n <= 8 * 1024 * 1024) { "Artwork is too large" }
                            bytes.write(buffer, 0, n)
                        }
                    }
                    target.parentFile?.mkdirs()
                    val part = File(target.path + ".part")
                    part.writeBytes(bytes.toByteArray())
                    check(part.renameTo(target)) { "Unable to save artwork" }
                }
            }
            target.takeIf { it.exists() }
        }
    }

    fun friendly(e: Throwable) =
        when (e) {
            is ApiError -> e.message ?: "Server request failed"
            is java.net.UnknownHostException ->
                "Server unavailable. Saved music is still available."
            is java.net.SocketTimeoutException -> "Connection timed out. Please retry."
            else ->
                e.message?.takeUnless { it.contains("lease=") || it.contains("Bearer ") }
                    ?: "The operation could not be completed."
        }

    suspend fun login(
        name: String,
        home: String,
        remote: String,
        prefer: Boolean,
        username: String,
        password: String,
        device: String,
        headers: String,
        serverType: String = "weazlmusic",
    ) {
        val account =
            withContext(Dispatchers.IO) {
                Api.login(
                    vault,
                    name,
                    home,
                    remote,
                    prefer,
                    username,
                    password,
                    device,
                    Api.parseHeaders(headers),
                    serverType,
                )
            }
        accounts.value = accounts.value.filterNot { it.key == account.key } + account
        prefs
            .edit()
            .putString("accounts", JSONArray(accounts.value.map { it.json() }).toString())
            .apply()
        synchronized(apiClients) { apiClients.remove(account.key) }
        prefs.edit().putString("${account.key}:offline", "false").apply()
        activate(account)
        sync()
    }

    suspend fun activate(account: Account) {
        withContext(Dispatchers.Main) { stopPlayback?.invoke() }
        active.value = account
        prefs.edit().putString("active", account.key).apply()
        loadLocal(account)
        withContext(Dispatchers.Main) { restorePlayback?.invoke() }
    }

    private fun loadLocal(account: Account) {
        val key = account.key
        try {
            catalog.value = readJson(file(key, "catalog.json"))?.let(Catalog::parse) ?: Catalog()
            capabilities.value = readJson(file(key, "capabilities.json")) ?: JSONObject()
            history.value = readJson(file(key, "history.json")) ?: JSONObject()
            mutations.value = readJson(file(key, "mutations.json"))?.rows("rows").orEmpty()
            sessions.value = readJson(file(key, "flights.json"))?.rows("sessions").orEmpty()
            schedules.value = readJson(file(key, "flights.json"))?.rows("schedules").orEmpty()
            offline.value = accountSetting(key, "offline", "false").toBoolean()
            refreshLocalDownloads(key)
        } catch (e: Exception) {
            message.value = "Local storage could not be read: ${friendly(e)}"
        }
    }

    @Synchronized
    fun refreshLocalDownloads(key: String = active.value?.key ?: "") {
        val records =
            File(accountDir(key), "downloads")
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { runCatching { readJson(it) }.getOrNull() }
                .orEmpty()
        val manifests =
            File(accountDir(key), "flights")
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { runCatching { readJson(it)?.let(Flight::parse) }.getOrNull() }
                .orEmpty()
        if (active.value?.key == key) {
            downloads.value = records
            flights.value = manifests
            revision.update { it + 1 }
        }
    }

    suspend fun sync() {
        val account = active.value ?: return
        if (offline.value) return
        syncMutex.withLock {
            if (active.value?.key != account.key) return@withLock
            syncing.value = true
            try {
                val client = api(account.key)
                if (account.serverType == "navidrome") {
                    val nav = client.navidrome()
                    nav.ping()
                    flushMutations(account.key)
                    var fresh = nav.catalog()
                    readJson(file(account.key, "mutations.json"))?.rows("rows")
                        ?.filter { it.str("path").startsWith("library/favorites/") }
                        ?.forEach { row ->
                            val parts = row.str("path").removePrefix("library/favorites/").split('/', limit = 2)
                            if (parts.size == 2) {
                                val ref = parts[0] + ":" + java.net.URLDecoder.decode(parts[1], "UTF-8")
                                fresh = fresh.copy(favorites = if (row.getJSONObject("body").optBoolean("enabled"))
                                    fresh.favorites + ref else fresh.favorites - ref)
                            }
                        }
                    val caps = nav.capabilities()
                    atomicWrite(file(account.key, "catalog.json"), fresh.json().toString())
                    atomicWrite(file(account.key, "capabilities.json"), caps.toString())
                    if (active.value?.key == account.key) { catalog.value = fresh; capabilities.value = caps }
                    return@withLock
                }
                val me = client.request("me").data
                require(
                    me.str("accountId") == account.accountId &&
                        me.str("libraryId") == account.libraryId
                ) {
                    "Library identity changed. Sign in again."
                }
                val caps = client.request("capabilities").data
                atomicWrite(file(account.key, "capabilities.json"), caps.toString())
                flushMutations(account.key)
                val old =
                    readJson(file(account.key, "catalog.json"))?.let(Catalog::parse) ?: Catalog()
                val music =
                    me.optBoolean("libraryAvailable", true) && caps.optBoolean("library", true)
                val tracks =
                    if (music) client.pages("library/tracks").map(Track::parse) else old.tracks
                val albums = if (music) client.pages("library/albums") else old.albums
                val artists = if (music) client.pages("library/artists") else old.artists
                val lists =
                    if (music)
                        client.pages("library/playlists").map { p ->
                            val r = client.request("library/playlists/" + Api.path(p.str("id")))
                            r.data.put("_etag", r.etag ?: "\"${r.data.str("version")}\"")
                        }
                    else old.playlists
                var favorites =
                    if (music)
                        client
                            .pages("library/favorites")
                            .map { it.str("kind") + ":" + it.str("id") }
                            .toSet()
                    else old.favorites
                readJson(file(account.key, "mutations.json"))
                    ?.rows("rows")
                    ?.filter { it.str("path").startsWith("library/favorites/") }
                    ?.forEach { row ->
                        val parts =
                            row.str("path").removePrefix("library/favorites/").split('/', limit = 2)
                        if (parts.size == 2) {
                            val ref = parts[0] + ":" + java.net.URLDecoder.decode(parts[1], "UTF-8")
                            favorites =
                                if (row.getJSONObject("body").optBoolean("enabled")) favorites + ref
                                else favorites - ref
                        }
                    }
                val stations =
                    if (caps.optBoolean("radio")) client.request("radio/stations").json.rows("data")
                    else emptyList()
                val fresh =
                    Catalog(
                        tracks,
                        albums,
                        artists,
                        lists,
                        stations,
                        favorites,
                        Instant.now().toString(),
                        if (music)
                            client
                                .request(
                                    "library/albums",
                                    query = mapOf("sort" to "newest", "limit" to "12"),
                                )
                                .json
                                .rows("data")
                        else old.recentAlbums,
                    )
                atomicWrite(file(account.key, "catalog.json"), fresh.json().toString())
                if (active.value?.key == account.key) {
                    catalog.value = fresh
                    capabilities.value = caps
                }
                if (caps.optJSONObject("flightRecorder")?.optBoolean("enabled") == true)
                    refreshFlights(account.key)
            } finally {
                syncing.value = false
            }
        }
    }

    fun saveCatalog(c: Catalog, key: String = active.value!!.key) {
        atomicWrite(file(key, "catalog.json"), c.json().toString())
        if (active.value?.key == key) catalog.value = c
    }

    suspend fun refreshFlights(key: String = active.value!!.key) {
        val client = api(key)
        val s = client.pages("flight-recorder/sessions")
        val plans = client.pages("flight-recorder/schedules")
        atomicWrite(
            file(key, "flights.json"),
            obj("sessions" to JSONArray(s), "schedules" to JSONArray(plans)).toString(),
        )
        if (active.value?.key == key) {
            sessions.value = s
            schedules.value = plans
        }
    }

    @Synchronized
    fun favorite(kind: String, id: String) {
        val key = active.value?.key ?: return
        val ref = "$kind:$id"
        val enabled = ref !in catalog.value.favorites
        saveCatalog(
            catalog.value.copy(
                favorites =
                    if (enabled) catalog.value.favorites + ref else catalog.value.favorites - ref
            ),
            key,
        )
        appendMutation(
            key,
            obj(
                "id" to UUID.randomUUID().toString(),
                "path" to "library/favorites/$kind/${Api.path(id)}",
                "method" to "PUT",
                "body" to obj("enabled" to enabled),
                "state" to "pending",
            ),
        )
        action { flushMutations(key) }
    }

    @Synchronized
    fun appendMutation(key: String, j: JSONObject) {
        val rows = readJson(file(key, "mutations.json"))?.rows("rows").orEmpty() + j
        atomicWrite(file(key, "mutations.json"), obj("rows" to JSONArray(rows)).toString())
        if (active.value?.key == key) mutations.value = rows
    }

    private val mutationFlushLock = java.util.concurrent.locks.ReentrantLock()

    fun flushMutations(key: String) {
        if (accountSetting(key, "offline", "false").toBoolean() || !mutationFlushLock.tryLock())
            return
        try {
            val pending =
                synchronized(this) { readJson(file(key, "mutations.json"))?.rows("rows").orEmpty() }
            for (row in pending) {
                if (row.str("state") != "pending") continue
                var review: String? = null
                try {
                    api(key)
                        .request(
                            row.str("path"),
                            row.str("method"),
                            row.optJSONObject("body"),
                            key = row.str("id"),
                        )
                } catch (e: ApiError) {
                    if (
                        e.code in
                            listOf("outcome_unknown", "version_conflict", "forbidden", "not_found")
                    )
                        review = friendly(e)
                    else break
                } catch (_: java.io.IOException) {
                    break
                }
                synchronized(this) {
                    val latest = readJson(file(key, "mutations.json"))?.rows("rows").orEmpty()
                    val updated =
                        latest.mapNotNull { item ->
                            if (item.str("id") != row.str("id")) item
                            else if (review == null) null
                            else item.put("state", "review").put("error", review)
                        }
                    atomicWrite(
                        file(key, "mutations.json"),
                        obj("rows" to JSONArray(updated)).toString(),
                    )
                    if (active.value?.key == key) mutations.value = updated
                }
            }
        } finally {
            mutationFlushLock.unlock()
        }
    }

    @Synchronized
    fun discardMutation(id: String) {
        val key = active.value!!.key
        val rows = mutations.value.filterNot { it.str("id") == id }
        atomicWrite(file(key, "mutations.json"), obj("rows" to JSONArray(rows)).toString())
        mutations.value = rows
    }

    suspend fun savePlaylist(name: String, tracks: List<Track>, existing: JSONObject? = null) {
        require(name.isNotBlank()) { "Enter a playlist name" }
        require(tracks.size <= capabilities.value.optInt("maxPlaylistTracks", 1000)) {
            "Playlist exceeds the server limit"
        }
        val key = active.value!!.key
        val id = existing?.str("id")
        val client = api(key)
        if (existing != null)
            require(existing.optBoolean("canEdit")) { "This playlist is read-only" }
        val r =
            client.request(
                "library/playlists" + (id?.let { "/${Api.path(it)}" } ?: ""),
                if (id == null) "POST" else "PUT",
                obj("name" to name, "trackIds" to JSONArray(tracks.map { it.id })),
                etag = existing?.str("_etag"),
            )
        val p = r.data.put("_etag", r.etag ?: "\"${r.data.str("version")}\"")
        if (active.value?.key == key)
            saveCatalog(
                catalog.value.copy(
                    playlists =
                        catalog.value.playlists.filterNot { it.str("id") == p.str("id") } + p
                ),
                key,
            )
    }

    suspend fun deletePlaylist(p: JSONObject) {
        val key = active.value!!.key
        require(p.optBoolean("canEdit"))
        api(key)
            .request("library/playlists/${Api.path(p.str("id"))}", "DELETE", etag = p.str("_etag"))
        if (active.value?.key == key)
            saveCatalog(
                catalog.value.copy(
                    playlists = catalog.value.playlists.filterNot { it.str("id") == p.str("id") }
                ),
                key,
            )
    }

    suspend fun refreshStations(key: String = active.value!!.key) {
        val rows = api(key).request("radio/stations").json.rows("data")
        if (active.value?.key == key) saveCatalog(catalog.value.copy(stations = rows), key)
    }

    suspend fun saveStation(name: String, url: String, existing: JSONObject? = null) {
        require(name.isNotBlank())
        val key = active.value!!.key
        val id = existing?.str("id")
        api(key)
            .request(
                "radio/stations" + (id?.let { "/${Api.path(it)}" } ?: ""),
                if (id == null) "POST" else "PATCH",
                obj(
                    "name" to name,
                    "streamURL" to url,
                    "preset" to (existing?.optBoolean("preset") ?: false),
                ),
                etag = existing?.let { "\"v${it.optInt("version")}\"" },
            )
        refreshStations(key)
    }

    suspend fun removeStation(station: JSONObject) {
        val key = active.value!!.key
        api(key)
            .request(
                "radio/stations/${Api.path(station.str("id"))}",
                "DELETE",
                etag = "\"v${station.optInt("version")}\"",
            )
        refreshStations(key)
    }

    suspend fun presets(ids: List<String>) {
        require(ids.size <= 8) { "You can save up to eight presets" }
        val key = active.value!!.key
        val client = api(key)
        val current = client.request("radio/stations")
        client.request(
            "radio/presets",
            "PUT",
            obj("stationIds" to JSONArray(ids)),
            etag = current.etag,
        )
        refreshStations(key)
    }

    fun downloadId(kind: String, id: String, quality: String, revision: String) =
        hash("$kind:$id:$quality:$revision")

    fun downloadFile(key: String, storage: String) = file(key, "audio/$storage.media")

    fun recordFile(key: String, storage: String) = file(key, "downloads/$storage.json")

    fun savedTrack(track: Track, key: String = active.value!!.key): File? {
        val exact =
            downloadId(
                "trackOriginal",
                track.id,
                accountSetting(key, "downloadQuality", "original"),
                track.revision,
            )
        val records =
            if (active.value?.key == key) downloads.value
            else
                File(accountDir(key), "downloads")
                    .listFiles()
                    ?.mapNotNull { runCatching { readJson(it) }.getOrNull() }
                    .orEmpty()
        return records
            .filter {
                it.str("resourceId") == track.id &&
                    it.str("revision") == track.revision &&
                    it.str("status") == "complete"
            }
            .sortedBy { if (it.str("id") == exact) 0 else 1 }
            .firstNotNullOfOrNull { r ->
                downloadFile(key, r.str("id")).takeIf {
                    it.exists() && it.length() == r.optLong("bytes")
                }
            }
    }

    fun enqueueDownload(
        kind: String,
        id: String,
        title: String,
        quality: String = "original",
        revision: String = "",
        bytes: Long = 0,
        sha: String = "",
        key: String = active.value!!.key,
    ): String {
        val storage = downloadId(kind, id, quality, revision)
        val prior = readJson(recordFile(key, storage))
        if (prior?.str("status") in listOf("queued", "downloading")) return storage
        if (
            prior?.str("status") == "complete" &&
                downloadFile(key, storage).length() == prior.optLong("bytes")
        )
            return storage
        val record =
            obj(
                "id" to storage,
                "kind" to kind,
                "resourceId" to id,
                "title" to title,
                "quality" to quality,
                "revision" to revision,
                "expectedBytes" to bytes,
                "sha256" to sha,
                "status" to "queued",
                "bytes" to (prior?.optLong("bytes") ?: 0),
                "entity" to (prior?.str("entity") ?: ""),
            )
        atomicWrite(recordFile(key, storage), record.toString())
        val request =
            OneTimeWorkRequestBuilder<AudioDownloadWorker>()
                .setInputData(workDataOf("account" to key, "storage" to storage))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (accountSetting(key, "wifiOnly", "true").toBoolean())
                                NetworkType.UNMETERED
                            else NetworkType.CONNECTED
                        )
                        .build()
                )
                .addTag(key)
                .addTag("download")
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("$key:$storage", ExistingWorkPolicy.REPLACE, request)
        refreshLocalDownloads(key)
        return storage
    }

    fun saveTracks(tracks: List<Track>) {
        val quality = setting("downloadQuality", "original")
        tracks
            .distinctBy { it.id }
            .forEach { enqueueDownload("trackOriginal", it.id, it.title, quality, it.revision) }
    }

    fun pauseDownload(r: JSONObject) {
        val key = active.value!!.key
        WorkManager.getInstance(context).cancelUniqueWork("$key:${r.str("id")}")
        atomicWrite(recordFile(key, r.str("id")), r.put("status", "paused").toString())
        refreshLocalDownloads(key)
    }

    fun retryDownload(r: JSONObject) {
        enqueueDownload(
            r.str("kind"),
            r.str("resourceId"),
            r.str("title"),
            r.str("quality"),
            r.str("revision"),
            r.optLong("expectedBytes"),
            r.str("sha256"),
        )
    }

    fun removeDownload(r: JSONObject) {
        pauseDownload(r)
        val key = active.value!!.key
        downloadFile(key, r.str("id")).delete()
        File(downloadFile(key, r.str("id")).path + ".part").delete()
        recordFile(key, r.str("id")).delete()
        refreshLocalDownloads(key)
    }

    suspend fun downloadFlight(id: String) {
        val key = active.value!!.key
        val manifest =
            Flight.parse(api(key).request("flight-recorder/sessions/${Api.path(id)}/manifest").data)
        atomicWrite(file(key, "flights/${hash(id)}.json"), manifest.json.toString())
        manifest.stations.forEach { s ->
            s.segments.forEach { segment ->
                enqueueDownload(
                    "recordingSegment",
                    segment.asset,
                    "${manifest.name} · ${s.name}",
                    revision = manifest.revision,
                    bytes = segment.bytes,
                    sha = segment.sha,
                    key = key,
                )
            }
        }
        refreshLocalDownloads(key)
    }

    fun flightAsset(flight: Flight, segment: Segment, key: String = active.value!!.key) =
        downloadFile(
            key,
            downloadId("recordingSegment", segment.asset, "original", flight.revision),
        )

    fun flightReady(flight: Flight, key: String = active.value!!.key) =
        flight.stations
            .flatMap { it.segments }
            .all { s ->
                val f = flightAsset(flight, s, key)
                val r =
                    readJson(
                        recordFile(
                            key,
                            downloadId("recordingSegment", s.asset, "original", flight.revision),
                        )
                    )
                r?.str("status") == "complete" && f.exists() && f.length() == s.bytes
            }

    fun removeFlight(flight: Flight) {
        val ids =
            flight.stations
                .flatMap { it.segments }
                .map { downloadId("recordingSegment", it.asset, "original", flight.revision) }
                .toSet()
        downloads.value.filter { it.str("id") in ids }.forEach(::removeDownload)
        file(active.value!!.key, "flights/${hash(flight.id)}.json").delete()
        refreshLocalDownloads()
    }

    @Synchronized
    fun recordListening(track: Track, occurrence: String, key: String, qualified: Boolean) {
        val saved = readJson(file(key, "history.json")) ?: JSONObject()
        val old = saved.optJSONObject(track.id) ?: JSONObject()
        saved.put(
            track.id,
            obj(
                "count" to (old.optInt("count") + if (qualified) 1 else 0),
                "last" to Instant.now().toString(),
            ),
        )
        atomicWrite(file(key, "history.json"), saved.toString())
        if (active.value?.key == key) history.value = saved
        if (qualified && capabilities.value.optBoolean("scrobble")) {
            appendMutation(
                key,
                obj(
                    "id" to occurrence,
                    "path" to "library/scrobbles",
                    "method" to "POST",
                    "state" to "pending",
                    "body" to
                        obj(
                            "occurrenceId" to occurrence,
                            "trackId" to track.id,
                            "playedAt" to Instant.now().toString(),
                        ),
                ),
            )
            scope.launch { flushMutations(key) }
        }
    }

    suspend fun signOut(account: Account) {
        runCatching { api(account.key).request("auth/logout", "POST") }
        vault.remove(account.key)
        synchronized(apiClients) { apiClients.remove(account.key) }
        if (active.value?.key == account.key) setSetting("offline", "true")
    }

    suspend fun removeAccount(account: Account) {
        if (active.value?.key == account.key) {
            withContext(Dispatchers.Main) { stopPlayback?.invoke() }
            active.value = null
            catalog.value = Catalog()
            prefs.edit().remove("active").apply()
        }
        WorkManager.getInstance(context).cancelAllWorkByTag(account.key).result.get()
        vault.remove(account.key)
        synchronized(apiClients) { apiClients.remove(account.key) }
        accountDir(account.key).deleteRecursively()
        accounts.value = accounts.value.filterNot { it.key == account.key }
        prefs
            .edit()
            .putString("accounts", JSONArray(accounts.value.map { it.json() }).toString())
            .apply()
    }
}
