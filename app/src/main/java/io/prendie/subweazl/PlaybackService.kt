@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media.utils.MediaConstants
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.future
import org.json.JSONArray
import org.json.JSONObject

fun folder(id: String, title: String, subtitle: String = "") =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

class MediaCatalog(private val repo: Repository) {
    private fun album(a: JSONObject): MediaItem {
        val base = folder("album:${a.str("id")}", a.str("name"), a.str("artist"))
        return base.buildUpon().setMediaMetadata(base.mediaMetadata.buildUpon()
            .setArtworkUri(repo.active.value?.key?.let { artworkUri(it, a.str("coverId")) })
            .setExtras(Bundle().apply {
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }).build()).build()
    }
    private fun group(item: MediaItem, title: String): MediaItem = item.buildUpon()
        .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title)
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        }).build()).build()

    fun home(): List<MediaItem> {
        val c = repo.catalog.value
        val h = repo.history.value
        val recent = c.tracks.filter { h.has(it.id) }.sortedByDescending { h.optJSONObject(it.id)?.str("last") }
            .take(8).map { group(track(it), "Recently played") }
        val newest = c.recentAlbums.ifEmpty { c.albums.sortedByDescending { it.str("addedAt") } }
            .take(8).map { group(album(it), "Newest albums") }
        // Stable for a browsing session, rotated daily, so paging cannot duplicate/skip items.
        val random = c.albums.shuffled(kotlin.random.Random((System.currentTimeMillis() / 86400000).toInt()))
            .take(8).map { group(album(it), "Random albums") }
        return recent + newest + random
    }

    private var voiceSnapshot: Catalog? = null
    private var voiceIndex: VoiceSearchIndex? = null
    @Synchronized private fun index(): VoiceSearchIndex {
        val snapshot = repo.catalog.value
        if (voiceSnapshot !== snapshot) {
            voiceIndex = VoiceSearchIndex(snapshot)
            voiceSnapshot = snapshot
        }
        return requireNotNull(voiceIndex)
    }
    fun voice(request: VoiceRequest): List<MediaItem> {
        if (repo.active.value == null) throw VoiceSearchFailure("Sign in to Subweazl on your phone first.")
        val selection = index().resolve(request)
        android.util.Log.i("SubweazlVoice", "Resolved ${selection.kind}; ${selection.tracks.size} tracks")
        if (selection.kind == VoiceKind.RADIO) {
            if (repo.offline.value) throw VoiceSearchFailure("Live radio needs a connection.")
            return repo.catalog.value.stations.firstOrNull { it.str("id") == selection.id }?.let { listOf(station(it)) }.orEmpty()
        }
        val tracks = selection.tracks.filter { !repo.offline.value || repo.savedTrack(it) != null }
        if (tracks.isEmpty()) throw VoiceSearchFailure("This music is not saved offline.")
        return (if (selection.kind == VoiceKind.SONG && tracks.size == 1) continuation(tracks.single()) else tracks).map(::track)
    }

    private fun continuation(selected: Track): List<Track> = relatedQueue(selected,
        repo.catalog.value.tracks.filter { !repo.offline.value || repo.savedTrack(it) != null })

    fun singleTrackQueue(item: MediaItem): List<MediaItem>? {
        if (!item.mediaId.startsWith("track:")) return null
        val selected = repo.catalog.value.tracks.firstOrNull { "track:${it.id}" == item.mediaId } ?: return null
        if (repo.offline.value && repo.savedTrack(selected) == null)
            throw VoiceSearchFailure("This music is not saved offline.")
        return continuation(selected).map(::track)
    }

    fun track(t: Track): MediaItem {
        val network = repo.context.getSystemService(android.net.ConnectivityManager::class.java)
        val quality =
            repo.setting(
                if (network.isActiveNetworkMetered) "cellularQuality" else "wifiQuality",
                "original",
            )
        return MediaItem.Builder()
            .setMediaId("track:${t.id}")
            .setUri(
                Uri.Builder()
                    .scheme("weazl")
                    .authority("track")
                    .appendPath(t.id)
                    .appendQueryParameter("quality", quality)
                    .build()
            )
            .setCustomCacheKey("${repo.active.value?.key}:${t.id}:${t.revision}:${quality}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setDurationMs(t.durationMs)
                    .setArtworkUri(repo.active.value?.key?.let { artworkUri(it, t.coverId) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    fun station(s: JSONObject) =
        MediaItem.Builder()
            .setMediaId("radio:${s.str("id")}")
            .setUri(
                Uri.Builder().scheme("weazl").authority("radio").appendPath(s.str("id")).build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.str("name"))
                    .setArtist("Internet Radio")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()

    fun flight(f: Flight, s: FlightStation) =
        MediaItem.Builder()
            .setMediaId("flight:${f.id}:${s.id}")
            .setUri(
                Uri.Builder()
                    .scheme("weazl")
                    .authority("flight")
                    .appendPath(f.id)
                    .appendPath(s.id)
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.name)
                    .setArtist(f.name)
                    .setDurationMs(f.duration)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    fun children(id: String): List<MediaItem> {
        val c = repo.catalog.value
        if (repo.active.value == null) return listOf(folder("login", "Sign in on your phone"))
        return when {
            id == "root" ->
                listOf(
                    folder("home", "Home"),
                    folder("library", "Library"),
                    folder("radio", "Radio"),
                    folder("saved", "Saved"),
                ).filter { repo.active.value?.serverType != "navidrome" || it.mediaId != "radio" }
            id == "home" -> home()
            id == "library" ->
                listOf(
                    folder("albums", "Albums"),
                    folder("artists", "Artists"),
                    folder("tracks", "Songs"),
                    folder("favorites", "Favorites"),
                    folder("playlists", "Playlists"),
                )
            id == "albums" ->
                c.albums.map(::album)
            id == "artists" -> c.artists.map { folder("artist:${it.str("id")}", it.str("name")) }
            id == "tracks" -> c.tracks.map(::track)
            id == "favorites" -> c.tracks.filter { "track:${it.id}" in c.favorites }.map(::track)
            id == "playlists" ->
                c.playlists.map { folder("playlist:${it.str("id")}", it.str("name")) }
            id == "saved" ->
                listOf(folder("saved-music", "Saved music")) +
                    repo.flights.value
                        .filter { repo.flightReady(it) }
                        .map { folder("recording:${it.id}", it.name) }
            id == "saved-music" -> c.tracks.filter { repo.savedTrack(it) != null }.map(::track)
            id == "radio" ->
                c.stations
                    .sortedWith(
                        compareByDescending<JSONObject> { it.optBoolean("preset") }
                            .thenBy { it.optInt("order") }
                    )
                    .map(::station)
            id.startsWith("album:") -> c.albumTracks(id.substringAfter(':')).map(::track)
            id.startsWith("artist:") ->
                c.albums
                    .filter {
                        it.str("artistId") == id.substringAfter(':') ||
                            it.str("artist") ==
                                c.artists
                                    .firstOrNull { artist ->
                                        artist.str("id") == id.substringAfter(':')
                                    }
                                    ?.str("name")
                    }
                    .map(::album)
            id.startsWith("playlist:") -> c.playlistTracks(id.substringAfter(':')).map(::track)
            id.startsWith("recording:") ->
                repo.flights.value
                    .firstOrNull { it.id == id.substringAfter(':') }
                    ?.let { f -> f.stations.map { flight(f, it) } }
                    .orEmpty()
            else -> emptyList()
        }
    }

    fun item(id: String): MediaItem? {
        val c = repo.catalog.value
        val roots = mapOf("root" to "Subweazl", "home" to "Home", "library" to "Library", "saved" to "Saved",
            "radio" to "Radio", "playlists" to "Playlists", "albums" to "Albums",
            "artists" to "Artists", "tracks" to "Songs", "favorites" to "Favorites",
            "saved-music" to "Saved music", "login" to "Sign in on your phone")
        roots[id]?.let { return folder(id, it) }
        val key = id.substringAfter(':')
        when {
            id.startsWith("album:") -> c.albums.firstOrNull { it.str("id") == key }
                ?.let { return folder(id, it.str("name"), it.str("artist")) }
            id.startsWith("artist:") -> c.artists.firstOrNull { it.str("id") == key }
                ?.let { return folder(id, it.str("name")) }
            id.startsWith("playlist:") -> c.playlists.firstOrNull { it.str("id") == key }
                ?.let { return folder(id, it.str("name")) }
            id.startsWith("recording:") -> repo.flights.value.firstOrNull { it.id == key }
                ?.let { return folder(id, it.name) }
            id.startsWith("track:") || id.startsWith("radio:") || id.startsWith("flight:") ->
                return resolve(MediaItem.Builder().setMediaId(id).build()).firstOrNull()
        }
        return null
    }

    fun resolve(item: MediaItem): List<MediaItem> {
        val id = item.mediaId
        if (item.requestMetadata.searchQuery != null)
            return voice(voiceRequest(item.requestMetadata.searchQuery!!, item.requestMetadata.extras))
        return when {
            id.startsWith("track:") ->
                repo.catalog.value.tracks
                    .firstOrNull { it.id == id.substringAfter(':') }
                    ?.let { listOf(track(it)) }
                    .orEmpty()
            id.startsWith("radio:") ->
                repo.catalog.value.stations
                    .firstOrNull { it.str("id") == id.substringAfter(':') }
                    ?.let { listOf(station(it)) }
                    .orEmpty()
            id.startsWith("flight:") -> {
                val f = repo.flights.value.firstOrNull { id.startsWith("flight:${it.id}:") }
                f?.stations
                    ?.firstOrNull { id == "flight:${f.id}:${it.id}" }
                    ?.let { listOf(flight(f, it)) }
                    .orEmpty()
            }
            else ->
                children(id).flatMap {
                    if (it.mediaMetadata.isPlayable == true) listOf(it)
                    else
                        children(it.mediaId).filter { child ->
                            child.mediaMetadata.isPlayable == true
                        }
                }
        }
    }

    fun search(query: String): List<MediaItem> {
        val q = normalize(query.removeSuffix(" on Subweazl"))
        val c = repo.catalog.value
        Regex("(?:radio )?preset (\\d+)").matchEntire(q)?.let { m ->
            return c.stations
                .filter { it.optBoolean("preset") }
                .sortedBy { it.optInt("order") }
                .getOrNull(m.groupValues[1].toInt() - 1)
                ?.let { listOf(station(it)) }
                .orEmpty()
        }
        c.stations
            .firstOrNull { normalize(it.str("name")) == q }
            ?.let {
                return listOf(station(it))
            }
        c.playlists
            .firstOrNull { normalize(it.str("name")) == q }
            ?.let {
                return c.playlistTracks(it.str("id")).map(::track)
            }
        c.albums
            .firstOrNull { normalize(it.str("name")) == q }
            ?.let {
                return c.albumTracks(it.str("id")).map(::track)
            }
        val found =
            c.tracks
                .filter { matches(q, it.title, it.artist, it.album) }
                .filter { !repo.offline.value || repo.savedTrack(it) != null }
        return found.take(1000).map(::track)
    }
}

class PlaybackService : MediaLibraryService() {
    private lateinit var engine: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var cache: SimpleCache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository
        get() = repo

    private val catalog by lazy { MediaCatalog(repository) }
    private val promoting = mutableSetOf<String>()
    private var radioJob: Job? = null
    private var restoreJob: Job? = null
    private val persistence =
        kotlinx.coroutines.channels.Channel<Pair<String, String>>(
            kotlinx.coroutines.channels.Channel.UNLIMITED
        )
    private var held: JSONObject? = null
    private var occurrence = UUID.randomUUID().toString()
    private var listened = 0L
    private var submitted = false
    private var recentlyReported = false
    private var lastPosition = 0L
    private var restored = false
    private var playbackAccount: String? = null

    override fun onCreate() {
        super.onCreate()
        cache =
            SimpleCache(
                File(cacheDir, "stream-audio"),
                LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
                StandaloneDatabaseProvider(this),
            )
        val http = OkHttpDataSource.Factory(Api.http)
        val upstream =
            ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
                if (spec.uri.scheme != "weazl") spec
                else {
                    val key = playbackAccount ?: throw java.io.IOException("Select an account")
                    val id = spec.uri.pathSegments.first()
                    val client = repository.api(key)
                    if (spec.uri.host == "track") {
                        val track =
                            repository.catalog.value.tracks.firstOrNull { it.id == id }
                                ?: throw java.io.IOException("Track is unavailable")
                        val saved = repository.savedTrack(track, key)
                        if (saved != null) spec.withUri(Uri.fromFile(saved))
                        else {
                            val network =
                                getSystemService(android.net.ConnectivityManager::class.java)
                            val quality =
                                spec.uri.getQueryParameter("quality")
                                    ?: repository.accountSetting(
                                        key,
                                        if (network.isActiveNetworkMetered) "cellularQuality"
                                        else "wifiQuality",
                                        "original",
                                    )
                            val lease = client.lease("trackStream", id, quality)
                            spec
                                .withUri(Uri.parse(lease.getString("url")))
                                .withRequestHeaders(client.mediaHeaders())
                        }
                    } else {
                        val lease = client.lease("radioLive", id)
                        val playbackId = lease.str("playbackId")
                        scope.launch {
                            radioJob?.cancel()
                            repository.radioMetadata.value = JSONObject()
                            radioJob =
                                scope.launch(Dispatchers.IO) {
                                    while (isActive) {
                                        try {
                                            client.radioEvents(playbackId) { metadata ->
                                                repository.radioMetadata.value = metadata
                                                scope.launch {
                                                    val current = engine.currentMediaItem
                                                    if (
                                                        current?.mediaId == "radio:$id" &&
                                                            metadata.str("title").isNotBlank()
                                                    ) {
                                                        val updated =
                                                            current
                                                                .buildUpon()
                                                                .setMediaMetadata(
                                                                    current.mediaMetadata
                                                                        .buildUpon()
                                                                        .setSubtitle(
                                                                            metadata.str("title")
                                                                        )
                                                                        .setArtist(
                                                                            listOf(
                                                                                    metadata.str(
                                                                                        "artist"
                                                                                    ),
                                                                                    metadata.str(
                                                                                        "title"
                                                                                    ),
                                                                                )
                                                                                .filter {
                                                                                    it.isNotBlank()
                                                                                }
                                                                                .joinToString(" · ")
                                                                        )
                                                                        .build()
                                                                )
                                                                .build()
                                                        engine.replaceMediaItem(
                                                            engine.currentMediaItemIndex,
                                                            updated,
                                                        )
                                                    }
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {}
                                        delay(3000)
                                    }
                                }
                        }
                        spec
                            .withUri(Uri.parse(lease.getString("url")))
                            .withRequestHeaders(client.mediaHeaders())
                    }
                }
            }
        val cached =
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val normal = DefaultMediaSourceFactory(this).setDataSourceFactory(cached)
        val directRadio = OkHttpDataSource.Factory(Api.http)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        val live = DefaultMediaSourceFactory(this).setDataSourceFactory {
            RadioGatewayDataSource(upstream.createDataSource(), directRadio.createDataSource()) { spec ->
                val uri = spec.uri
                val key = playbackAccount
                val stationId = uri.pathSegments.firstOrNull()
                val station = repository.catalog.value.stations.firstOrNull { it.str("id") == stationId }
                val url = station?.str("streamURL")?.let(::directRadioUrl)
                if (uri.scheme != "weazl" || uri.host != "radio" || url == null || key == null ||
                    repository.active.value?.key != key || repository.offline.value) null
                else {
                    android.util.Log.i("SubweazlRadio", "Relay gateway failure; trying configured HTTPS stream")
                    scope.launch {
                        if (engine.currentMediaItem?.mediaId == "radio:$stationId") {
                            radioJob?.cancel()
                            repository.radioMetadata.value = JSONObject()
                        }
                    }
                    url
                }
            }
        }
        val factory =
            object : MediaSource.Factory {
                override fun setDrmSessionManagerProvider(
                    provider: DrmSessionManagerProvider
                ): MediaSource.Factory {
                    normal.setDrmSessionManagerProvider(provider)
                    return this
                }

                override fun setLoadErrorHandlingPolicy(
                    policy: LoadErrorHandlingPolicy
                ): MediaSource.Factory {
                    normal.setLoadErrorHandlingPolicy(policy)
                    return this
                }

                override fun getSupportedTypes() = normal.supportedTypes

                override fun createMediaSource(item: MediaItem): MediaSource {
                    val uri = item.localConfiguration?.uri
                    if (uri?.host == "flight" && uri.scheme == "weazl") {
                        val f = repository.flights.value.first { it.id == uri.pathSegments[0] }
                        val station = f.stations.first { it.id == uri.pathSegments[1] }
                        check(repository.flightReady(f)) { "Download the complete recording first" }
                        val builder = ConcatenatingMediaSource2.Builder().setMediaItem(item)
                        var at = 0L
                        station.segments.forEach { segment ->
                            if (segment.start > at)
                                builder.add(
                                    SilenceMediaSource.Factory()
                                        .setDurationUs((segment.start - at) * 1000)
                                        .createMediaSource()
                                )
                            val piece =
                                MediaItem.Builder()
                                    .setUri(Uri.fromFile(repository.flightAsset(f, segment)))
                                    .setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder()
                                            .setStartPositionMs(segment.mediaStart)
                                            .setEndPositionMs(segment.mediaStart + segment.duration)
                                            .build()
                                    )
                                    .build()
                            builder.add(normal.createMediaSource(piece), segment.duration)
                            at = segment.end
                        }
                        if (at < f.duration)
                            builder.add(
                                SilenceMediaSource.Factory()
                                    .setDurationUs((f.duration - at) * 1000)
                                    .createMediaSource()
                            )
                        return builder.build()
                    }
                    return if (uri?.host == "radio" && uri.scheme == "weazl")
                        live.createMediaSource(item)
                    else normal.createMediaSource(item)
                }
            }
        engine =
            ExoPlayer.Builder(this).setMediaSourceFactory(factory).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
        val controls =
            object : ForwardingPlayer(engine) {
                private fun recorded() =
                    engine.currentMediaItem?.mediaId?.startsWith("flight:") == true

                override fun getAvailableCommands(): Player.Commands {
                    val commands = super.getAvailableCommands()
                    return if (recorded())
                        commands
                            .buildUpon()
                            .add(Player.COMMAND_SEEK_TO_NEXT)
                            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                            .build()
                    else commands
                }

                override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

                override fun hasNextMediaItem() = recorded() || super.hasNextMediaItem()

                override fun hasPreviousMediaItem() = recorded() || super.hasPreviousMediaItem()

                override fun seekToNext() {
                    if (!switchFlight(1)) super.seekToNext()
                }

                override fun seekToNextMediaItem() {
                    if (!switchFlight(1)) super.seekToNextMediaItem()
                }

                override fun seekToPrevious() {
                    if (!switchFlight(-1)) super.seekToPrevious()
                }

                override fun seekToPreviousMediaItem() {
                    if (!switchFlight(-1)) super.seekToPreviousMediaItem()
                }
            }
        val activity =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session =
            MediaLibrarySession.Builder(this, controls, Callback())
                .setSessionActivity(activity)
                .build()
        repository.stopPlayback = {
            persist()
            radioJob?.cancel()
            restoreJob?.cancel()
            engine.stop()
            playbackAccount = null
            engine.clearMediaItems()
            held = null
            playbackAccount = null
            restored = false
        }
        repository.restorePlayback = { restoreQueue() }
        repository.offlineChanged = {
            if (repository.offline.value) {
                val id = engine.currentMediaItem?.mediaId.orEmpty()
                val t = repository.catalog.value.tracks.firstOrNull { id == "track:${it.id}" }
                if (id.startsWith("radio:") || (t != null && repository.savedTrack(t) == null)) {
                    engine.pause()
                    radioJob?.cancel()
                }
            }
        }
        repository.scope.launch {
            for ((key, value) in persistence) atomicWrite(repository.file(key, "queue.json"), value)
        }
        engine.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem?.mediaId?.startsWith("radio:") != true) {
                        radioJob?.cancel()
                        repository.radioMetadata.value = JSONObject()
                    }
                    val key = playbackAccount
                    val track =
                        repository.catalog.value.tracks.firstOrNull {
                            mediaItem?.mediaId == "track:${it.id}"
                        }
                    if (
                        key != null && track != null && mediaItem?.mediaMetadata?.artworkUri == null
                    ) {
                        scope.launch {
                            val artwork =
                                withContext(Dispatchers.IO) {
                                    runCatching { repository.artwork(key, track.coverId) }
                                        .getOrNull()
                                }
                            val current = engine.currentMediaItem
                            if (
                                artwork != null &&
                                    key == playbackAccount &&
                                    current?.mediaId == mediaItem?.mediaId
                            ) {
                                engine.replaceMediaItem(
                                    engine.currentMediaItemIndex,
                                    current!!
                                        .buildUpon()
                                        .setMediaMetadata(
                                            current.mediaMetadata
                                                .buildUpon()
                                                .setArtworkUri(artworkUri(key, track.coverId))
                                                .build()
                                        )
                                        .build(),
                                )
                            }
                        }
                    }
                    occurrence = UUID.randomUUID().toString()
                    listened = 0
                    submitted = false
                    recentlyReported = false
                    lastPosition = 0
                    persist()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    android.util.Log.i("SubweazlVoice", "Player isPlaying=$isPlaying")
                }

                override fun onPlayerError(error: PlaybackException) {
                    repository.message.value = playbackFailureMessage(error, error.errorCodeName)
                    persist()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    lastPosition = engine.currentPosition
                    persist()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    lastPosition = engine.currentPosition
                    persist()
                }
            }
        )
        restoreQueue()
        scope.launch {
            while (isActive) {
                delay(1000)
                if (engine.isPlaying) {
                    val delta = engine.currentPosition - lastPosition
                    if (delta in 0..2000) listened += delta
                    val key = playbackAccount
                    val id = engine.currentMediaItem?.mediaId
                    val track =
                        if (id?.startsWith("track:") == true)
                            repository.catalog.value.tracks.firstOrNull {
                                it.id == id.substringAfter(':')
                            }
                        else null
                    if (key != null && track != null) {
                        val occurred = occurrence
                        if (
                            !recentlyReported &&
                                listened >= minOf(5000L, maxOf(1000L, track.durationMs / 2))
                        ) {
                            recentlyReported = true
                            repository.scope.launch {
                                repository.recordListening(track, occurred, key, false)
                            }
                        }
                        if (
                            !submitted &&
                                track.durationMs >= 30000 &&
                                listened >= minOf(track.durationMs / 2, 240_000)
                        ) {
                            submitted = true
                            repository.scope.launch {
                                repository.recordListening(track, occurred, key, true)
                            }
                        }
                    }
                }
                lastPosition = engine.currentPosition
                promoteCached()
                persist()
            }
        }
    }

    private fun promoteCached() {
        val item = engine.currentMediaItem ?: return
        val key = playbackAccount ?: return
        val cacheKey = item.localConfiguration?.customCacheKey ?: return
        val track =
            repository.catalog.value.tracks.firstOrNull { item.mediaId == "track:${it.id}" }
                ?: return
        val quality = item.localConfiguration?.uri?.getQueryParameter("quality") ?: "original"
        val storage = repository.downloadId("trackOriginal", track.id, quality, track.revision)
        if (storage in promoting || repository.downloadFile(key, storage).exists()) return
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        if (length <= 0 || !cache.isCached(cacheKey, 0, length)) return
        promoting.add(storage)
        repository.scope.launch {
            try {
                val existing = readJson(repository.recordFile(key, storage))
                if (existing?.str("status") in listOf("queued", "downloading")) return@launch
                val target = repository.downloadFile(key, storage)
                target.parentFile?.mkdirs()
                val budget =
                    repository.accountSetting(key, "budgetGiB", "8").toLong() * 1024 * 1024 * 1024
                if (
                    target.parentFile!!.listFiles().orEmpty().sumOf { it.length() } + length >
                        budget
                )
                    return@launch
                val partial = File(target.path + ".promote")
                val source = CacheDataSource.Factory().setCache(cache).createDataSource()
                try {
                    source.open(
                        DataSpec.Builder()
                            .setUri(item.localConfiguration!!.uri)
                            .setKey(cacheKey)
                            .setLength(length)
                            .build()
                    )
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = source.read(buffer, 0, buffer.size)
                            if (n == C.RESULT_END_OF_INPUT) break
                            output.write(buffer, 0, n)
                        }
                    }
                } finally {
                    source.close()
                }
                if (partial.length() != length || !partial.renameTo(target)) {
                    partial.delete()
                    return@launch
                }
                atomicWrite(
                    repository.recordFile(key, storage),
                    obj(
                            "id" to storage,
                            "kind" to "trackOriginal",
                            "resourceId" to track.id,
                            "title" to track.title,
                            "quality" to quality,
                            "revision" to track.revision,
                            "expectedBytes" to length,
                            "bytes" to length,
                            "status" to "complete",
                            "cached" to true,
                        )
                        .toString(),
                )
                repository.refreshLocalDownloads(key)
            } catch (_: Exception) {
                /* A cache miss must never interrupt the active player. */
            } finally {
                withContext(Dispatchers.Main) { promoting.remove(storage) }
            }
        }
    }

    private fun snapshot() =
        obj(
            "items" to
                JSONArray(
                    (0 until engine.mediaItemCount).map { engine.getMediaItemAt(it).mediaId }
                ),
            "index" to engine.currentMediaItemIndex.coerceAtLeast(0),
            "position" to engine.currentPosition,
            "shuffle" to engine.shuffleModeEnabled,
            "repeat" to engine.repeatMode,
        )

    private fun persist() {
        val key = playbackAccount ?: return
        val j = snapshot().put("held", held ?: JSONObject.NULL)
        persistence.trySend(key to j.toString())
    }

    private fun restoreQueue() {
        restoreJob?.cancel()
        val key = repository.active.value?.key ?: return
        restoreJob =
            scope.launch {
                val j =
                    withContext(Dispatchers.IO) { readJson(repository.file(key, "queue.json")) }
                        ?: return@launch
                if (repository.active.value?.key != key || engine.mediaItemCount != 0) return@launch
                val state = resumeItems(j)
                if (state.mediaItems.isEmpty()) return@launch
                playbackAccount = key
                held = j.optJSONObject("held")
                engine.setMediaItems(state.mediaItems, state.startIndex, state.startPositionMs)
                engine.shuffleModeEnabled = j.optBoolean("shuffle")
                engine.repeatMode = j.optInt("repeat")
                restored = true
            }
    }

    private fun resumeItems(j: JSONObject?): MediaSession.MediaItemsWithStartPosition {
        val a = j?.optJSONArray("items")
        val items =
            if (a == null) emptyList()
            else
                (0 until a.length()).flatMap {
                    catalog.resolve(MediaItem.Builder().setMediaId(a.getString(it)).build())
                }
        return MediaSession.MediaItemsWithStartPosition(
            items,
            (j?.optInt("index") ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            j?.optLong("position") ?: 0,
        )
    }

    private fun switchFlight(direction: Int): Boolean {
        val uri = engine.currentMediaItem?.localConfiguration?.uri ?: return false
        if (uri.host != "flight") return false
        val f =
            repository.flights.value.firstOrNull { it.id == uri.pathSegments[0] } ?: return false
        val index = f.stations.indexOfFirst { it.id == uri.pathSegments[1] }
        val item = catalog.flight(f, f.stations[Math.floorMod(index + direction, f.stations.size)])
        val position = engine.currentPosition
        val play = engine.playWhenReady
        engine.setMediaItem(item, position)
        engine.prepare()
        engine.playWhenReady = play
        return true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        persist()
        repository.stopPlayback = null
        repository.restorePlayback = null
        repository.offlineChanged = null
        persistence.close()
        scope.cancel()
        engine.release()
        session.release()
        cache.release()
        super.onDestroy()
    }

    private fun applyCollectionState(items: List<MediaItem>) {
            if (
                items.firstOrNull()?.mediaId?.startsWith("track:") != true &&
                    engine.currentMediaItem?.mediaId?.startsWith("track:") == true
            ) {
                held = snapshot()
                engine.shuffleModeEnabled = false
                engine.repeatMode = Player.REPEAT_MODE_OFF
            }
            if (items.firstOrNull()?.mediaId?.startsWith("track:") == true && held != null) {
                engine.shuffleModeEnabled = held?.optBoolean("shuffle") ?: false
                engine.repeatMode = held?.optInt("repeat") ?: Player.REPEAT_MODE_OFF
                held = null
            }
    }
    private fun isGenericVoice(request: VoiceRequest): Boolean =
        request.kind in listOf(VoiceKind.ANY, VoiceKind.MUSIC) && request.artist.isBlank() && request.title.isBlank() && request.album.isBlank() && request.playlist.isBlank() &&
            request.query.trim().lowercase().replace(Regex("^(?:play )?(?:some )?music(?: on subweazl)?$"), "").isBlank()

    private suspend fun resolveVoiceItems(request: VoiceRequest): MediaSession.MediaItemsWithStartPosition {
        val started = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("SubweazlVoice", "Session voice request received")
        if (repository.active.value == null) throw VoiceSearchFailure("Sign in to Subweazl on your phone first.")
        val account = repository.active.value?.key
        playbackAccount = account
        val generic = isGenericVoice(request) && !voiceWantsShuffle(request)
        if (generic && engine.mediaItemCount > 0)
            return MediaSession.MediaItemsWithStartPosition((0 until engine.mediaItemCount).map(engine::getMediaItemAt),
                engine.currentMediaItemIndex, engine.currentPosition)
        val items = withContext(Dispatchers.Default) { catalog.voice(request) }
        if (account != repository.active.value?.key) throw VoiceSearchFailure("Your active account changed. Try again.")
        android.util.Log.i("SubweazlVoice", "Matching completed in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        return MediaSession.MediaItemsWithStartPosition(items, if (voiceWantsShuffle(request) && items.size > 1) kotlin.random.Random.nextInt(items.size) else 0, 0)
    }
    private fun reportVoiceFailure(session: MediaSession, controller: MediaSession.ControllerInfo, failure: VoiceSearchFailure) {
        repository.message.value = failure.message
        android.util.Log.i("SubweazlVoice", "Request could not be resolved")
        session.sendError(controller, SessionError(SessionError.ERROR_BAD_VALUE, failure.message ?: "No matching music"))
    }

    inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted)
                return MediaSession.ConnectionResult.reject()
            val commands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand("voice-play", Bundle.EMPTY))
                    .add(SessionCommand("car-platform-token", Bundle.EMPTY))
                    .add(SessionCommand("resume-library", Bundle.EMPTY))
                    .add(SessionCommand("clear-cache", Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == "voice-play") {
                if (controller.packageName != packageName)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                return scope.future {
                    try {
                        val request = voiceRequest(args.getString(android.app.SearchManager.QUERY).orEmpty(), args)
                        val items = resolveVoiceItems(request)
                        applyCollectionState(items.mediaItems)
                        if (!isGenericVoice(request)) engine.shuffleModeEnabled = voiceWantsShuffle(request)
                        engine.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
                        engine.prepare()
                        engine.play()
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } catch (e: CancellationException) { throw e }
                    catch (e: VoiceSearchFailure) {
                        reportVoiceFailure(session, controller, e)
                        SessionResult(SessionError.ERROR_BAD_VALUE, Bundle().apply { putString("message", e.message) })
                    }
                }
            }
            if (customCommand.customAction == "car-platform-token") {
                if (controller.packageName != packageName)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS,
                    Bundle().apply { putParcelable("token", session.platformToken) }))
            }
            when (customCommand.customAction) {
                "resume-library" -> {
                    val state = resumeItems(held)
                    if (state.mediaItems.isNotEmpty()) {
                        engine.shuffleModeEnabled = held?.optBoolean("shuffle") ?: false
                        engine.repeatMode = held?.optInt("repeat") ?: Player.REPEAT_MODE_OFF
                        engine.setMediaItems(
                            state.mediaItems,
                            state.startIndex,
                            state.startPositionMs,
                        )
                        engine.prepare()
                        engine.play()
                        held = null
                    }
                }
                "clear-cache" -> {
                    if (!engine.isPlaying) cache.keys.forEach(cache::removeResource)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Subweazl"), LibraryParams.Builder()
                .setExtras(Bundle().apply {
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                    putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                }).build()))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return scope.future {
                withContext(Dispatchers.IO) {
                    val items = catalog.children(parentId)
                    val start = (page.toLong() * pageSize).coerceIn(0, items.size.toLong()).toInt()
                    LibraryResult.ofItemList(items.drop(start).take(pageSize.coerceIn(0, 1000)), params)
                }
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            withContext(Dispatchers.IO) {
                val item = catalog.item(mediaId)
                if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                else LibraryResult.ofItem(item, null)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            return scope.future {
                val count = withContext(Dispatchers.Default) { catalog.search(query).size }
                session.notifySearchResultChanged(browser, query, count, params)
                LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            withContext(Dispatchers.Default) {
                val found = catalog.search(query)
                val start = (page.toLong() * pageSize).coerceIn(0, found.size.toLong()).toInt()
                LibraryResult.ofItemList(found.drop(start).take(pageSize.coerceIn(0, 1000)), params)
            }
        }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            playbackAccount = repository.active.value?.key
            return scope.future { withContext(Dispatchers.Default) { mediaItems.flatMap(catalog::resolve) } }
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return scope.future {
            val searchItem = mediaItems.singleOrNull()?.takeIf { it.requestMetadata.searchQuery != null }
            val resolved = if (searchItem != null) {
                try {
                    resolveVoiceItems(voiceRequest(searchItem.requestMetadata.searchQuery!!, searchItem.requestMetadata.extras))
                } catch (e: VoiceSearchFailure) {
                    reportVoiceFailure(session, controller, e)
                    throw e
                }
            } else null
            playbackAccount = repository.active.value?.key
            val singleTrack = searchItem == null && mediaItems.singleOrNull()?.mediaId?.startsWith("track:") == true
            val items = resolved?.mediaItems ?: withContext(Dispatchers.Default) {
                if (singleTrack) catalog.singleTrackQueue(mediaItems.single()).orEmpty()
                else mediaItems.flatMap(catalog::resolve)
            }
            if (singleTrack) engine.shuffleModeEnabled = false
            applyCollectionState(items)
            if (searchItem != null && !isGenericVoice(voiceRequest(searchItem.requestMetadata.searchQuery!!, searchItem.requestMetadata.extras)))
                engine.shuffleModeEnabled = voiceWantsShuffle(voiceRequest(searchItem.requestMetadata.searchQuery!!, searchItem.requestMetadata.extras))
            val next = items.firstOrNull()?.localConfiguration?.uri
            val current = engine.currentMediaItem?.localConfiguration?.uri
            val sameFlight =
                next?.host == "flight" &&
                    current?.host == "flight" &&
                    next.pathSegments.firstOrNull() == current.pathSegments.firstOrNull()
            MediaSession.MediaItemsWithStartPosition(
                items,
                resolved?.startIndex ?: if (startIndex == C.INDEX_UNSET) 0 else startIndex,
                resolved?.startPositionMs ?: if (sameFlight) engine.currentPosition
                else if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs,
            )
            }
        }

        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future {
                val key =
                    repository.active.value?.key
                        ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                playbackAccount = key
                val j = withContext(Dispatchers.IO) { readJson(repository.file(key, "queue.json")) }
                held = j?.optJSONObject("held")
                engine.shuffleModeEnabled = j?.optBoolean("shuffle") ?: false
                engine.repeatMode = j?.optInt("repeat") ?: Player.REPEAT_MODE_OFF
                restored = true
                resumeItems(j)
            }
    }
}
