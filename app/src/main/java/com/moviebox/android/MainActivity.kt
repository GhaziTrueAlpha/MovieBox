package com.moviebox.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moviebox.android.data.M3uParser
import com.moviebox.android.data.NativeMovieBox
import com.moviebox.android.data.RangeDownloadManager
import com.moviebox.android.data.SettingsStore
import com.moviebox.android.data.StremioClient
import com.moviebox.android.model.*
import com.moviebox.android.player.ExternalPlayerLauncher
import com.moviebox.android.player.InAppPlayerOverlay
import com.moviebox.android.ui.components.StreamPickerSheet
import com.moviebox.android.ui.screens.DownloadsSettingsScreen
import com.moviebox.android.ui.screens.HomeScreen
import com.moviebox.android.ui.screens.LiveTvScreen
import com.moviebox.android.ui.screens.SearchScreen
import com.moviebox.android.ui.theme.MovieBoxTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeMovieBox.init(applicationContext, filesDir.absolutePath)
        setContent { MovieBoxApp() }
    }
}

@Composable
fun MovieBoxApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    val stremioClient = remember { StremioClient() }
    val downloadManager = remember { RangeDownloadManager(context) }

    var currentTab by remember { mutableIntStateOf(0) }
    var selectedTheme by remember { mutableStateOf(store.selectedTheme) }
    var selectedProvider by remember { mutableStateOf(ProviderOption.MOVIEBOX) }
    var streamAddonUrl by remember { mutableStateOf(store.streamAddonUrl.ifBlank { DEFAULT_ADDON_URLS }) }
    var useExternalPlayer by remember { mutableStateOf(store.useExternalPlayer) }

    var trendingItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isLoadingTrending by remember { mutableStateOf(true) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var recentItems by remember { mutableStateOf(store.loadRecents()) }
    var favoriteItems by remember { mutableStateOf(store.loadFavorites()) }

    var playlistUrl by remember { mutableStateOf(store.playlistUrl.ifBlank { DEFAULT_PLAYLIST }) }
    var liveChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var isLoadingTv by remember { mutableStateOf(false) }

    var selectedMedia by remember { mutableStateOf<MediaItem?>(null) }
    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var selectedEpisode by remember { mutableStateOf<EpisodeItem?>(null) }
    var availableStreams by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var isLoadingStreams by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }

    var activePlayerStream by remember { mutableStateOf<Pair<String, StreamSource>?>(null) }
    val downloads by downloadManager.tasks.collectAsState()

    fun addRecent(item: MediaItem) {
        recentItems = (listOf(item) + recentItems.filterNot { it.id == item.id }).take(15)
        store.saveRecents(recentItems)
    }

    fun toggleFavorite(item: MediaItem) {
        favoriteItems = if (favoriteItems.any { it.id == item.id }) {
            favoriteItems.filterNot { it.id == item.id }
        } else {
            favoriteItems + item
        }
        store.saveFavorites(favoriteItems)
    }

    LaunchedEffect(Unit) {
        isLoadingTrending = true
        NativeMovieBox.fetchHomepage(1).onSuccess { trendingItems = it.take(30) }
        isLoadingTrending = false
    }

    fun loadStreams(item: MediaItem, season: Int, episode: Int, stremioEpId: String? = null) {
        isLoadingStreams = true
        availableStreams = emptyList()
        streamError = null
        scope.launch {
            if (item.provider == ProviderOption.STREMIO) {
                val addons = streamAddonUrl.split(",").map { it.trim() }.filter { it.isNotBlank() }
                stremioClient.fetchStreams(addons, stremioEpId ?: item.id, item.type)
                    .onSuccess { availableStreams = it }
                    .onFailure { streamError = it.message }
            } else {
                NativeMovieBox.fetchStreams(item.provider, item.id, season, episode)
                    .onSuccess { availableStreams = it }
                    .onFailure { streamError = it.message }
            }
            isLoadingStreams = false
        }
    }

    fun openMedia(item: MediaItem) {
        selectedMedia = item
        episodes = emptyList()
        selectedEpisode = null
        availableStreams = emptyList()
        streamError = null
        isLoadingStreams = true

        scope.launch {
            if (item.provider == ProviderOption.STREMIO) {
                if (item.type == MediaType.SERIES) {
                    val eps = stremioClient.fetchEpisodes(DEFAULT_CINEMETA, item.id).getOrDefault(emptyList())
                    episodes = eps
                    val first = eps.firstOrNull()
                    selectedEpisode = first
                    if (first != null) loadStreams(item, first.season, first.episode, first.id)
                    else isLoadingStreams = false
                } else {
                    loadStreams(item, 0, 0)
                }
            } else {
                val detailsRes = NativeMovieBox.fetchDetails(item.provider, item.id)
                val (eps, inlineStreams) = detailsRes.getOrDefault(emptyList<EpisodeItem>() to emptyList())
                episodes = eps
                if (eps.isNotEmpty()) {
                    val first = eps.first()
                    selectedEpisode = first
                    loadStreams(item, first.season, first.episode)
                } else if (inlineStreams.isNotEmpty()) {
                    availableStreams = inlineStreams
                    isLoadingStreams = false
                } else {
                    loadStreams(item, 0, 0)
                }
            }
        }
    }

    fun playStream(title: String, rawStream: StreamSource, item: MediaItem? = null, forceExternal: Boolean = false) {
        scope.launch {
            var stream = NativeMovieBox.resolveStreamIfNeeded(rawStream, item?.id.orEmpty())
            if (item != null && item.provider == ProviderOption.MOVIEBOX &&
                stream.subtitles.isEmpty() && stream.resourceId.isNotBlank()) {
                val subs = NativeMovieBox.fetchCaptions(item.id, stream.resourceId)
                if (subs.isNotEmpty()) stream = stream.copy(subtitles = subs)
            }
            if (item != null) addRecent(item)
            selectedMedia = null
            if (forceExternal || useExternalPlayer) {
                ExternalPlayerLauncher.launch(context, title, stream)
            } else {
                activePlayerStream = title to stream
            }
        }
    }

    fun runSearch(provider: ProviderOption) {
        if (searchQuery.isBlank()) return
        isSearching = true
        searchError = null
        scope.launch {
            val res = if (provider == ProviderOption.STREMIO) {
                stremioClient.searchCatalog(DEFAULT_CINEMETA, searchQuery, MediaType.MOVIE)
            } else {
                NativeMovieBox.search(provider, searchQuery, 1)
            }
            res.onSuccess { searchResults = it }
                .onFailure { searchError = it.message }
            isSearching = false
        }
    }
    MovieBoxTheme(appTheme = selectedTheme) {
        Box {
            if (!NativeMovieBox.isAvailable()) {
                val err = NativeMovieBox.availabilityError() ?: ""
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Native engine unavailable: $err. Live providers disabled; Stremio addons and Live TV still work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            icon = { Icon(Icons.Default.Search, contentDescription = null) },
                            label = { Text("Search") }
                        )
                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            icon = { Icon(Icons.Default.LiveTv, contentDescription = null) },
                            label = { Text("Live TV") }
                        )
                        NavigationBarItem(
                            selected = currentTab == 3,
                            onClick = { currentTab = 3 },
                            icon = { Icon(Icons.Default.Download, contentDescription = null) },
                            label = { Text("Library") }
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    when (currentTab) {
                        0 -> HomeScreen(
                            trendingItems = trendingItems,
                            recentItems = recentItems,
                            favorites = favoriteItems,
                            isLoadingTrending = isLoadingTrending,
                            onSelectItem = ::openMedia,
                            onGoToSearch = { currentTab = 1 }
                        )
                        1 -> SearchScreen(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            selectedProvider = selectedProvider,
                            onProviderChange = { prov ->
                                selectedProvider = prov
                                if (searchQuery.isNotBlank()) runSearch(prov)
                            },
                            results = searchResults,
                            favorites = favoriteItems.map { it.id }.toSet(),
                            isLoading = isSearching,
                            errorMessage = searchError,
                            onSearch = { runSearch(selectedProvider) },
                            onSelectItem = ::openMedia,
                            onToggleFavorite = ::toggleFavorite
                        )
                        2 -> LiveTvScreen(
                            playlistUrl = playlistUrl,
                            onPlaylistUrlChange = { playlistUrl = it; store.playlistUrl = it },
                            channels = liveChannels,
                            isLoading = isLoadingTv,
                            onLoadPlaylist = {
                                isLoadingTv = true
                                scope.launch {
                                    M3uParser.loadPlaylist(playlistUrl).onSuccess { liveChannels = it }
                                    isLoadingTv = false
                                }
                            },
                            onPlayChannel = { ch ->
                                playStream(ch.name, StreamSource(title = ch.name, url = ch.url))
                            }
                        )
                        3 -> DownloadsSettingsScreen(
                            tasks = downloads,
                            onPauseDownload = downloadManager::pause,
                            onResumeDownload = downloadManager::startOrResume,
                            streamAddonUrl = streamAddonUrl,
                            onStreamAddonUrlChange = { streamAddonUrl = it; store.streamAddonUrl = it },
                            useExternalPlayerByDefault = useExternalPlayer,
                            onExternalPlayerToggle = { useExternalPlayer = it; store.useExternalPlayer = it },
                            selectedTheme = selectedTheme,
                            onThemeSelect = { selectedTheme = it; store.selectedTheme = it }
                        )
                    }
                }
            }

            selectedMedia?.let { media ->
                StreamPickerSheet(
                    title = media.title,
                    providerLabel = media.provider.label,
                    episodes = episodes,
                    selectedEpisode = selectedEpisode,
                    onSelectEpisode = { ep ->
                        selectedEpisode = ep
                        loadStreams(media, ep.season, ep.episode, ep.id)
                    },
                    streams = availableStreams,
                    isLoading = isLoadingStreams,
                    errorMessage = streamError,
                    onDismiss = { selectedMedia = null },
                    onPlayInApp = { stream -> playStream(media.title, stream, media, forceExternal = false) },
                    onPlayExternal = { stream -> playStream(media.title, stream, media, forceExternal = true) },
                    onDownload = { stream ->
                        scope.launch {
                            val resolved = NativeMovieBox.resolveStreamIfNeeded(stream, media.id)
                            selectedMedia = null
                            downloadManager.enqueue(media.title, resolved.url, resolved.headers)
                            currentTab = 3
                        }
                    }
                )
            }

            activePlayerStream?.let { (title, stream) ->
                InAppPlayerOverlay(
                    title = title,
                    stream = stream,
                    onClose = { activePlayerStream = null }
                )
            }
        }
    }
}

private const val DEFAULT_ADDON_URLS =
    "https://webstreamr.hayd.uk/manifest.json, https://nodebrid.fly.dev/manifest.json"
private const val DEFAULT_PLAYLIST = "https://iptv-org.github.io/iptv/index.m3u"
private const val DEFAULT_CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
