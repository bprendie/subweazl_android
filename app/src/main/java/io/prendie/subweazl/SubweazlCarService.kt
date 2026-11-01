@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.prendie.subweazl

import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.*
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.*
import androidx.car.app.model.Action
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await

class SubweazlCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.Builder(this)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    override fun onCreateSession(): Session = SubweazlCarSession()
}

class SubweazlCarSession : Session() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var controller: MediaController? = null
        private set
    var ready = false
        private set
    var connectionError: String? = null
        private set
    private var root: CarBrowseScreen? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                scope.launch {
                    try {
                        val token = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
                        val c = MediaController.Builder(carContext, token).buildAsync().await()
                        controller = c
                        val result = c.sendCustomCommand(SessionCommand("car-platform-token", Bundle.EMPTY), Bundle.EMPTY).await()
                        val platform = result.extras.getParcelable<android.media.session.MediaSession.Token>("token")
                            ?: error("Playback session unavailable")
                        carContext.getCarService(MediaPlaybackManager::class.java)
                            .registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(platform)!!)
                        ready = true
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { connectionError = "Unable to connect. Reopen Subweazl on your phone." }
                    root?.invalidate()
                    carContext.getCarService(ScreenManager::class.java).top.invalidate()
                }
            }
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
                controller?.release()
                controller = null
            }
        })
    }
    override fun onCreateScreen(intent: Intent): Screen {
        val screen = CarBrowseScreen(carContext, this, "home", "Subweazl", true)
        root = screen
        if (intent.action == SHOW_PLAYBACK) scope.launch { showPlaying() }
        return screen
    }
    override fun onNewIntent(intent: Intent) {
        if (intent.action == SHOW_PLAYBACK) showPlaying()
    }
    fun push(screen: Screen) {
        val manager = carContext.getCarService(ScreenManager::class.java)
        if (manager.stackSize >= 5) manager.pop()
        manager.push(screen)
    }
    fun showPlaying() {
        val manager = carContext.getCarService(ScreenManager::class.java)
        if (manager.top !is CarPlayingScreen) push(CarPlayingScreen(carContext, this))
    }
    fun play(items: List<MediaItem>, index: Int) {
        val c = controller ?: return
        if (items.isEmpty() || !ready) return
        c.setMediaItems(items, index, 0)
        c.prepare()
        c.play()
        showPlaying()
    }
    companion object { const val SHOW_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK" }
}

private fun carIcon(context: CarContext) = CarIcon.Builder(
    IconCompat.createWithResource(context, R.drawable.weazlmark)).build()

class CarBrowseScreen(
    context: CarContext,
    private val session: SubweazlCarSession,
    private var parent: String,
    private var title: String,
    private val tabs: Boolean = false,
) : Screen(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loading = true
    private var items = emptyList<MediaItem>()
    private var offset = 0
    private var homeSections = emptyList<Pair<String, List<MediaItem>>>()
    private var artJob: Job? = null
    private var job: Job? = null
    private var error: String? = null
    private val artwork = mutableMapOf<String, CarIcon>()
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { load() }
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
    }
    private fun load() {
        job?.cancel()
        artJob?.cancel()
        loading = true
        invalidate()
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val repo = carContext.repo
                    val catalog = MediaCatalog(repo)
                    if (parent == "home" && repo.active.value != null) {
                        val c = repo.catalog.value
                        val history = repo.history.value
                        val recent = c.tracks.filter { history.has(it.id) }
                            .sortedByDescending { history.optJSONObject(it.id)?.str("last") }
                            .take(8).map(catalog::track)
                        fun albums(values: List<org.json.JSONObject>) = values.take(8).map {
                            folder("album:${it.str("id")}", it.str("name"), it.str("artist"))
                        }
                        val newest = albums(c.recentAlbums.ifEmpty { c.albums.sortedByDescending { it.str("addedAt") } })
                        val random = albums(c.albums.shuffled())
                        val groups = listOf("Recently played" to recent, "Newest albums" to newest, "Random albums" to random)
                        groups.flatMap { it.second } to groups
                    } else {
                        val children = if (parent == "library") listOf(folder("car-search", "Search")) + catalog.children(parent)
                            else catalog.children(parent)
                        children to emptyList()
                    }
                }
                items = result.first
                homeSections = result.second
                error = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Unable to load. Check your account on the phone." }
            loading = false
            invalidate()
            loadArt()

        }
    }
    private fun loadArt() {
        artJob?.cancel()
        artJob = scope.launch {
            val repo = carContext.repo
            val key = repo.active.value?.key ?: return@launch
            val albums = repo.catalog.value.albums.associateBy { it.str("id") }
            val tracks = repo.catalog.value.tracks.associateBy { it.id }
            items.drop(offset).take(30).forEach { item ->
                if (artwork.containsKey(item.mediaId)) return@forEach
                val id = item.mediaId.removePrefix("album:")
                val cover = if (item.mediaId.startsWith("track:")) tracks[item.mediaId.removePrefix("track:")]?.coverId
                    else albums[id]?.str("coverId")
                if (cover.isNullOrBlank()) return@forEach
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = if (repo.offline.value) repo.file(key, "art/${hash(cover)}").takeIf { it.exists() }
                            else repo.artwork(key, cover)
                        if (file == null) return@runCatching null
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, options)
                        options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 180)
                        options.inJustDecodeBounds = false
                        BitmapFactory.decodeFile(file.path, options)
                    }.getOrNull()
                }
                if (bitmap != null) {
                    artwork[item.mediaId] = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
                    invalidate()
                }
            }
        }
    }
    private fun open(item: MediaItem) {
        if (item.mediaId == "car-search") { session.push(CarSearchScreen(carContext, session)); return }

        if (item.mediaMetadata.isBrowsable == true) {
            if (item.mediaId == "login") return
            session.push(CarBrowseScreen(carContext, session, item.mediaId, item.mediaMetadata.title.toString()))
        } else {
            val queue = items.filter { it.mediaMetadata.isPlayable == true }
            session.play(queue, items.take(items.indexOf(item)).count { it.mediaMetadata.isPlayable == true })
        }
    }
    override fun onGetTemplate(): Template {
        val rows = RowSection.Builder().setNoItemsMessage(error ?: "Nothing here yet. Add content on your phone.")
        items.drop(offset).take(30).forEach { item ->
            val metadata = item.mediaMetadata
            val row = Row.Builder().setTitle(metadata.title ?: "Untitled")
                .setOnClickListener { open(item) }
                .setBrowsable(metadata.isBrowsable == true)
            val subtitle = metadata.subtitle ?: metadata.artist
            if (!subtitle.isNullOrBlank()) row.addText(subtitle)
            rows.addItem(row.build())
        }
        if (offset > 0) rows.addItem(Row.Builder().setTitle("Previous page").setOnClickListener {
            offset = (offset - 30).coerceAtLeast(0); invalidate()
        }.build())
        if (offset + 30 < items.size) rows.addItem(Row.Builder().setTitle("More").setOnClickListener {
            offset += 30; invalidate()
        }.build())
        val header = Header.Builder().setTitle(title).setStartHeaderAction(if (tabs) Action.APP_ICON else Action.BACK)
            .addEndHeaderAction(Action.Builder().setTitle("Search").setOnClickListener {
                session.push(CarSearchScreen(carContext, session))
            }.build())
            .apply { if (parent == "home") addEndHeaderAction(Action.Builder().setTitle("Refresh")
                .setOnClickListener { artwork.clear(); load() }.build()) }.build()
        val content = SectionedItemTemplate.Builder().setHeader(header).setLoading(loading)
            .addAction(Action.MEDIA_PLAYBACK)
        if (!loading) {
            val page = items.drop(offset).take(30)
            if (parent == "home" && homeSections.isNotEmpty()) {
                homeSections.forEach { (heading, media) ->
                    val grid = GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_MEDIUM).setTitle(heading)
                        .setNoItemsMessage(if (heading == "Recently played") "Play music to see your listening history here" else "No albums yet")
                    media.forEach { item -> grid.addItem(GridItem.Builder()
                        .setTitle(item.mediaMetadata.title ?: "Untitled")
                        .setText(item.mediaMetadata.subtitle ?: item.mediaMetadata.artist ?: "")
                        .setImage(artwork[item.mediaId] ?: carIcon(carContext))
                        .setOnClickListener { open(item) }.build()) }
                    content.addSection(grid.build())
                }
                content.addSection(RowSection.Builder().setTitle("Discover")
                    .addItem(Row.Builder().setTitle("Search music & stations").setBrowsable(true)
                        .setOnClickListener { session.push(CarSearchScreen(carContext, session)) }.build())
                    .addItem(Row.Builder().setTitle("Refresh random albums")
                        .setOnClickListener { load() }.build()).build())
            } else if (page.isNotEmpty() && page.all { it.mediaId.startsWith("album:") }) {
                val grid = GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_MEDIUM)
                page.forEach { item -> grid.addItem(GridItem.Builder()
                    .setTitle(item.mediaMetadata.title ?: "Album")
                    .setText(item.mediaMetadata.subtitle ?: "")
                    .setImage(artwork[item.mediaId] ?: carIcon(carContext))
                    .setOnClickListener { open(item) }.build()) }
                content.addSection(grid.build())
                val paging = RowSection.Builder()
                if (offset > 0) paging.addItem(Row.Builder().setTitle("Previous page").setOnClickListener {
                    offset = (offset - 30).coerceAtLeast(0); loadArt(); invalidate()
                }.build())
                if (offset + 30 < items.size) paging.addItem(Row.Builder().setTitle("More").setOnClickListener {
                    offset += 30; loadArt(); invalidate()
                }.build())
                if (offset > 0 || offset + 30 < items.size) content.addSection(paging.build())
            } else content.addSection(rows.build())
        }
        if (!tabs) return content.build()
        val root = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(id: String) { parent = id; offset = 0; load() }
        })
            .setHeaderAction(Action.APP_ICON).setActiveTabContentId(parent)
            .setTabContents(TabContents.Builder(content.build()).build())
        listOf("home" to "Home", "library" to "Library", "radio" to "Radio", "saved" to "Saved").filter { carContext.repo.active.value?.serverType != "navidrome" || it.first != "radio" }.forEach { (id, label) ->
            root.addTab(Tab.Builder().setContentId(id).setTitle(label).setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
                when (id) { "home" -> R.drawable.car_home; "library" -> R.drawable.car_library; "radio" -> R.drawable.car_radio; else -> R.drawable.car_saved })).build()).build())
        }
        return root.build()
    }
}

class CarPlayingScreen(context: CarContext, private val session: SubweazlCarSession) : Screen(context) {
    override fun onGetTemplate(): Template {
        if (!session.ready) return MessageTemplate.Builder(session.connectionError ?: "Connecting to playback…")
            .setHeader(Header.Builder().setTitle("Subweazl").setStartHeaderAction(Action.BACK).build()).build()
        return MediaPlaybackTemplate.Builder().setHeader(Header.Builder().setTitle("Now Playing")
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(Action.Builder().setTitle("Queue").setOnClickListener {
                session.push(CarQueueScreen(carContext, session))
            }.build()).build()).build()
    }
}

class CarQueueScreen(context: CarContext, private val session: SubweazlCarSession) : Screen(context) {
    private var offset = 0
    override fun onGetTemplate(): Template {
        val rows = RowSection.Builder().setNoItemsMessage("Your queue is empty")
        val c = session.controller
        if (c != null) {
            (offset until minOf(offset + 30, c.mediaItemCount)).forEach { index ->
                val m = c.getMediaItemAt(index).mediaMetadata
                rows.addItem(Row.Builder().setTitle(m.title ?: "Untitled").addText(m.artist ?: "")
                    .setOnClickListener { c.seekToDefaultPosition(index); c.prepare(); c.play(); session.showPlaying() }.build())
            }
            if (offset > 0) rows.addItem(Row.Builder().setTitle("Previous page").setOnClickListener {
                offset = (offset - 30).coerceAtLeast(0); invalidate()
            }.build())
            if (offset + 30 < c.mediaItemCount) rows.addItem(Row.Builder().setTitle("More").setOnClickListener {
                offset += 30; invalidate()
            }.build())
        }
        return SectionedItemTemplate.Builder().setHeader(Header.Builder().setTitle("Queue")
            .setStartHeaderAction(Action.BACK).build()).addSection(rows.build()).addAction(Action.MEDIA_PLAYBACK).build()
    }
}

class CarSearchScreen(context: CarContext, private val session: SubweazlCarSession) : Screen(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var results = emptyList<MediaItem>()
    private var loading = false
    init { lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
    }) }
    private fun search(query: String) {
        job?.cancel()
        job = scope.launch {
            loading = true; invalidate()
            delay(250)
            results = withContext(Dispatchers.Default) {
                if (query.isBlank()) emptyList() else MediaCatalog(carContext.repo).search(query).take(30)
            }
            loading = false; invalidate()
        }
    }
    override fun onGetTemplate(): Template {
        val rows = ItemList.Builder().setNoItemsMessage("Search songs, albums, artists or stations")
        results.forEachIndexed { index, item -> rows.addItem(Row.Builder()
            .setTitle(item.mediaMetadata.title ?: "Untitled").addText(item.mediaMetadata.artist ?: "")
            .setOnClickListener { session.play(results, index) }.build()) }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) { search(searchText) }
            override fun onSearchSubmitted(searchText: String) { search(searchText) }
        }).setHeaderAction(Action.BACK).setSearchHint("Search Subweazl").setLoading(loading)
            .apply { if (!loading) setItemList(rows.build()) }.setActionStrip(ActionStrip.Builder().addAction(Action.Builder()
                .setTitle("Playing").setOnClickListener { session.showPlaying() }.build()).build()).build()
    }
}
