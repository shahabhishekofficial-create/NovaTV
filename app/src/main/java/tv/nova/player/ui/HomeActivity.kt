package tv.nova.player.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nova.player.R
import tv.nova.player.data.Channel
import tv.nova.player.data.Playlist
import tv.nova.player.data.PlaylistRepository
import tv.nova.player.data.Search
import tv.nova.player.data.Store
import java.text.DateFormat
import java.util.Date

class HomeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: Store
    private lateinit var repository: PlaylistRepository
    private lateinit var logos: LogoLoader
    private lateinit var channels: ChannelAdapter
    private lateinit var rail: RailAdapter
    private lateinit var player: PlayerController
    private lateinit var setup: PlaylistSetup

    private lateinit var homeContent: View
    private lateinit var grid: RecyclerView
    private lateinit var categoryRail: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var hdButton: Button

    private var allChannels: List<Channel> = emptyList()
    private var documents: List<Search.Document> = emptyList()
    private var activePlaylist: Playlist? = null
    private var catalogueGeneration = 0
    private var filterGeneration = 0
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var statusOverride: String? = null
    private var pendingFocusUrl: String? = null
    private var lastWatchedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        store = Store(this)
        repository = PlaylistRepository(store)
        logos = LogoLoader(this) { isOnline() }
        bindViews()
        setupLists()
        setupActions()
        player = PlayerController(
            activity = this,
            onPosition = { channel, position -> store.saveResume(channel.url, position) },
            onLeave = { channel, _ -> returnFromPlayer(channel) },
        )
        setup = PlaylistSetup(
            activity = this,
            store = store,
            onActivate = { playlist -> loadPlaylist(playlist, showCache = true) },
            onEmpty = { emptyCatalogue() },
            onRefresh = { playlist ->
                if (store.activePlaylistId() != playlist.id) store.setActive(playlist.id)
                loadPlaylist(playlist, showCache = false)
            },
        )
        updateHdButton()
        store.activePlaylist()?.let { loadPlaylist(it, showCache = true) } ?: emptyCatalogue()
        handleIntent(intent)
    }

    private fun bindViews() {
        homeContent = findViewById(R.id.homeContent)
        grid = findViewById(R.id.channelGrid)
        categoryRail = findViewById(R.id.categoryRail)
        searchInput = findViewById(R.id.searchInput)
        emptyText = findViewById(R.id.emptyText)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.loadingProgress)
        hdButton = findViewById(R.id.hdButton)
    }

    private fun setupLists() {
        channels = ChannelAdapter(
            logos = logos,
            isFavourite = { store.isFavourite(it) },
            onPlay = { playChannel(it) },
            onFavourite = { toggleFavourite(it) },
        )
        rail = RailAdapter { applyFilter(immediate = true) }
        grid.layoutManager = GridLayoutManager(this, gridSpans())
        grid.adapter = channels
        grid.itemAnimator = null
        grid.setItemViewCacheSize(12)
        categoryRail.layoutManager = LinearLayoutManager(this)
        categoryRail.adapter = rail
        categoryRail.itemAnimator = null
    }

    private fun setupActions() {
        searchInput.doAfterTextChanged { applyFilter(immediate = false) }
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                applyFilter(immediate = true, focusGrid = true)
                true
            } else false
        }
        findViewById<Button>(R.id.voiceButton).setOnClickListener { startVoiceSearch() }
        hdButton.setOnClickListener {
            store.setHdOnly(!store.hdOnly())
            updateHdButton()
            applyFilter(immediate = true)
        }
        findViewById<Button>(R.id.playlistsButton).setOnClickListener { showPlaylistSetup() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            activePlaylist?.let { loadPlaylist(it, showCache = false) } ?: showPlaylistSetup()
        }
    }

    private fun loadPlaylist(playlist: Playlist, showCache: Boolean) {
        loadJob?.cancel()
        activePlaylist = playlist
        if (store.activePlaylistId() != playlist.id) store.setActive(playlist.id)
        val generation = ++catalogueGeneration
        if (showCache) clearCatalogue(keepStatus = true)
        statusOverride = if (showCache) "Opening ${playlist.name}…" else "Refreshing ${playlist.name}…"
        statusText.text = statusOverride
        progress.visibility = if (allChannels.isEmpty()) View.VISIBLE else View.GONE
        emptyText.visibility = View.GONE
        loadJob = scope.launch {
            var showedCached = false
            if (showCache) {
                val cached = repository.cached(playlist)
                if (cached != null && isCurrent(playlist, generation)) {
                    val indexed = withContext(Dispatchers.Default) { Search.documents(cached) }
                    if (isCurrent(playlist, generation)) {
                        setCatalogue(cached, indexed)
                        showedCached = true
                        statusOverride = "Cached catalogue • Refreshing in the background…"
                        statusText.text = statusOverride
                    }
                }
            }
            if (!isCurrent(playlist, generation)) return@launch
            if (!isOnline()) {
                progress.visibility = View.GONE
                if (!showedCached && allChannels.isEmpty()) {
                    statusOverride = "Offline • No cached catalogue is available"
                    statusText.text = statusOverride
                    showEmpty("You are offline. Connect the TV, then choose Reload.")
                } else {
                    statusOverride = "Offline • Showing the cached catalogue"
                    statusText.text = statusOverride
                }
                return@launch
            }
            try {
                val loaded = repository.refresh(playlist)
                val indexed = withContext(Dispatchers.Default) { Search.documents(loaded) }
                if (isCurrent(playlist, generation)) {
                    activePlaylist = store.playlists().firstOrNull { it.id == playlist.id } ?: playlist
                    setCatalogue(loaded, indexed)
                    setup.refreshList()
                }
            } catch (error: Exception) {
                if (isCurrent(playlist, generation)) {
                    progress.visibility = View.GONE
                    val message = error.message ?: "Playlist could not be loaded."
                    statusOverride = if (showedCached || allChannels.isNotEmpty()) "$message Showing cached channels." else message
                    statusText.text = statusOverride
                    if (!showedCached && allChannels.isEmpty()) showEmpty("$message\nOpen Playlists to edit the URL or try Reload.")
                }
            }
        }
    }

    private fun isCurrent(playlist: Playlist, generation: Int): Boolean =
        generation == catalogueGeneration && store.activePlaylistId() == playlist.id

    private fun setCatalogue(values: List<Channel>, indexed: List<Search.Document>) {
        allChannels = values
        documents = indexed
        progress.visibility = View.GONE
        statusOverride = null
        rebuildRail()
        applyFilter(immediate = true)
        updateStatus()
    }

    private fun clearCatalogue(keepStatus: Boolean = false) {
        filterJob?.cancel()
        filterGeneration++
        allChannels = emptyList()
        documents = emptyList()
        channels.submit(emptyList())
        rebuildRail()
        if (!keepStatus) statusOverride = null
    }

    private fun emptyCatalogue() {
        loadJob?.cancel()
        catalogueGeneration++
        activePlaylist = null
        clearCatalogue()
        progress.visibility = View.GONE
        statusOverride = "No playlist configured"
        statusText.text = statusOverride
        showEmpty(getString(R.string.welcome))
    }

    private fun rebuildRail() {
        val favourites = store.favourites()
        val recents = store.recents().toSet()
        val entries = mutableListOf(
            RailEntry("all", "All", allChannels.size, RailKind.ALL),
            RailEntry("favourites", "Favourites", allChannels.count { it.url in favourites }, RailKind.FAVOURITES),
            RailEntry("recent", "Recently watched", allChannels.count { it.url in recents }, RailKind.RECENTS),
        )
        allChannels.groupingBy { it.group }.eachCount().toList()
            .sortedBy { it.first.lowercase() }
            .forEach { (group, count) -> entries += RailEntry("group:$group", group, count, RailKind.GROUP) }
        rail.submit(entries)
    }

    private fun applyFilter(immediate: Boolean, focusGrid: Boolean = false) {
        val generation = ++filterGeneration
        filterJob?.cancel()
        val query = searchInput.text.toString()
        val selected = rail.selected()
        val snapshot = documents
        val favourites = store.favourites()
        val recentOrder = store.recents().withIndex().associate { it.value to it.index }
        val hdOnly = store.hdOnly()
        filterJob = scope.launch {
            if (!immediate) delay(200)
            val result = withContext(Dispatchers.Default) {
                val base = when (selected?.kind) {
                    RailKind.FAVOURITES -> snapshot.filter { it.channel.url in favourites }
                    RailKind.RECENTS -> snapshot.filter { it.channel.url in recentOrder }.sortedBy { recentOrder[it.channel.url] ?: Int.MAX_VALUE }
                    RailKind.GROUP -> snapshot.filter { it.channel.group == selected?.title }
                    else -> snapshot
                }
                Search.search(base, query, hdOnly)
            }
            if (generation == filterGeneration) showResults(result, query, focusGrid)
        }
    }

    private fun showResults(result: List<Channel>, query: String, focusGrid: Boolean) {
        val hadGridFocus = grid.hasFocus()
        val focusedUrl = focusedChannel()?.url ?: pendingFocusUrl ?: lastWatchedUrl
        channels.submit(result)
        if (result.isEmpty()) {
            val message = when {
                query.isNotBlank() -> "No match for “$query”. Try fewer words or turn off the HD filter."
                rail.selected()?.kind == RailKind.FAVOURITES -> "No favourites here yet. Long-press OK on a channel to add one."
                rail.selected()?.kind == RailKind.RECENTS -> "No recently watched channels yet."
                store.hdOnly() -> "No HD+ channels in this category. Turn off the HD filter to see more."
                allChannels.isEmpty() -> getString(R.string.welcome)
                else -> "No channels in this category."
            }
            showEmpty(message)
            if (hadGridFocus || focusGrid) categoryRail.requestFocus()
        } else {
            emptyText.visibility = View.GONE
            if (hadGridFocus || focusGrid || pendingFocusUrl != null) {
                val target = focusedUrl?.let(channels::indexOf)?.coerceAtLeast(0) ?: 0
                focusGridAt(target)
            }
        }
        pendingFocusUrl = null
        updateStatus()
    }

    private fun showEmpty(message: String) {
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
    }

    private fun focusedChannel(): Channel? {
        val child = grid.focusedChild ?: return null
        val holder = grid.getChildViewHolder(child) as? ChannelAdapter.Holder ?: return null
        return channels.at(holder.bindingAdapterPosition)
    }

    private fun focusGridAt(position: Int) {
        if (channels.itemCount == 0) return
        val target = position.coerceIn(0, channels.itemCount - 1)
        grid.post {
            (grid.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(target, 0)
            grid.post {
                val holder = grid.findViewHolderForAdapterPosition(target)
                if (holder != null) holder.itemView.requestFocus() else grid.requestFocus()
            }
        }
    }

    private fun updateStatus() {
        if (statusOverride != null) return
        val playlist = activePlaylist
        val last = playlist?.lastLoaded?.takeIf { it > 0 }?.let {
            " • Updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}"
        }.orEmpty()
        statusText.text = "${channels.itemCount} of ${allChannels.size} channels • ${playlist?.name ?: "No playlist"}$last"
    }

    private fun toggleFavourite(channel: Channel) {
        val added = store.toggleFavourite(channel.url)
        Toast.makeText(this, if (added) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
        rebuildRail()
        if (rail.selected()?.kind == RailKind.FAVOURITES) {
            applyFilter(immediate = true)
        } else {
            val index = channels.indexOf(channel.url)
            if (index >= 0) channels.notifyItemChanged(index)
        }
    }

    private fun playChannel(channel: Channel) {
        lastWatchedUrl = channel.url
        store.addRecent(channel.url)
        rebuildRail()
        homeContent.visibility = View.GONE
        player.play(channel, channels.snapshot, store.resume(channel.url))
    }

    private fun returnFromPlayer(channel: Channel) {
        lastWatchedUrl = channel.url
        pendingFocusUrl = channel.url
        store.addRecent(channel.url)
        homeContent.visibility = View.VISIBLE
        rebuildRail()
        applyFilter(immediate = true, focusGrid = true)
    }

    private fun updateHdButton() {
        hdButton.text = if (store.hdOnly()) "HD+ ✓" else getString(R.string.hd_plus)
        hdButton.contentDescription = "HD and better filter ${if (store.hdOnly()) "on" else "off"}"
    }

    private fun showPlaylistSetup(prefill: String? = null) {
        if (player.isShowing) player.leave()
        setup.show(prefill)
    }

    private fun showChannelPicker() {
        if (channels.itemCount == 0) {
            Toast.makeText(this, "No channels to pick", Toast.LENGTH_SHORT).show()
            return
        }
        val values = channels.snapshot
        AlertDialog.Builder(this)
            .setTitle("Channel picker")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, values.map { "${it.name}  ${it.quality.label}" })) { _, which ->
                playChannel(values[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel name or category")
        }
        try {
            startActivityForResult(intent, VOICE_REQUEST)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Voice typing is not available on this device. Use the search field instead.", Toast.LENGTH_LONG).show()
            openSearch()
        }
    }

    private fun openSearch(prefill: String? = null, focusResults: Boolean = false) {
        if (player.isShowing) player.leave()
        pendingFocusUrl = null
        if (prefill != null) {
            searchInput.setText(prefill)
            searchInput.setSelection(prefill.length)
        }
        searchInput.requestFocus()
        applyFilter(immediate = true, focusGrid = focusResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) openSearch(text, focusResults = true)
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEARCH -> openSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty(), focusResults = true)
            Intent.ACTION_VIEW -> if (intent.data?.scheme == "novatv" && intent.data?.host == "playlist") {
                showPlaylistSetup(intent.data?.getQueryParameter("url"))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.action == KeyEvent.ACTION_UP
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (up) openSearch()
            return true
        }
        if (player.isShowing) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> if (up) showChannelPicker() else Unit
                KeyEvent.KEYCODE_INFO -> if (up) player.toggleStats() else Unit
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> if (up) player.next() else Unit
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (up) player.previous() else Unit
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (up) player.pauseResume() else Unit
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (up) player.toggleOverlay() else Unit
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (up) showChannelPicker()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            player.isShowing -> player.leave()
            searchInput.text.isNotEmpty() -> {
                searchInput.setText("")
                applyFilter(immediate = true, focusGrid = true)
            }
            searchInput.hasFocus() -> focusGridAt(0)
            else -> super.onBackPressed()
        }
    }

    override fun onStop() {
        setup.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        filterJob?.cancel()
        scope.cancel()
        setup.dismiss()
        logos.close()
        player.close()
        super.onDestroy()
    }

    private fun gridSpans(): Int {
        val metrics = resources.displayMetrics
        val availableDp = metrics.widthPixels / metrics.density - 190
        return (availableDp / 164).toInt().coerceIn(1, 8)
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.activeNetworkInfo?.isConnected == true
    }

    companion object {
        private const val VOICE_REQUEST = 4102
    }
}
