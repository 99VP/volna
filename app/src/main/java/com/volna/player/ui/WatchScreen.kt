package com.volna.player.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.volna.player.R
import com.volna.player.data.OnlineRepository
import com.volna.player.data.Track
import com.volna.player.data.WatchRoomClient
import com.volna.player.data.TrackSource
import com.volna.player.data.SettingsStore
import com.volna.player.net.ProxyRuntime
import com.volna.player.player.PlayerViewModel
import com.volna.player.util.DoH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private const val WATCH_PAGE_URL = "https://99vp.github.io/volna/watch/"

enum class WatchFullscreenMode { NORMAL, LANDSCAPE, PORTRAIT }

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun sourceForUrl(url: String): TrackSource = when {
    url.contains("youtu.be") || url.contains("youtube.com") -> TrackSource.YOUTUBE
    url.contains("soundcloud.com") -> TrackSource.SOUNDCLOUD
    else -> TrackSource.OTHER
}

private fun trackForResolved(
    r: OnlineRepository.ResolvedVideo,
    url: String,
): Track = Track(
    id = url,
    title = r.title.ifBlank { url },
    artist = r.artist,
    durationMs = r.durationMs,
    pageUrl = url,
    artworkUri = r.thumbUrl,
    source = sourceForUrl(url),
)

@Composable
fun WatchScreen(
    vm: PlayerViewModel,
    fullscreenMode: WatchFullscreenMode,
    onBack: () -> Unit,
    onFullscreenMode: (WatchFullscreenMode) -> Unit,
    shareRequest: Int,
) {
    val context = LocalContext.current
    val fullscreen = fullscreenMode != WatchFullscreenMode.NORMAL
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val savedUrl by vm.watchUrl.collectAsState()
    val videoQueue by vm.videoQueue.collectAsState()
    val savedRoom by vm.watchRoom.collectAsState()
    val savedRoomServer by vm.watchRoomServer.collectAsState()
    val configuredServer by SettingsStore.watchServer.collectAsState()

    var roomDialog by remember { mutableStateOf(false) }
    var roomCode by remember { mutableStateOf("") }
    var roomId by remember { mutableStateOf<String?>(null) }
    var isHost by remember { mutableStateOf(false) }
    var roomBusy by remember { mutableStateOf(false) }
    var roomError by remember { mutableStateOf<String?>(null) }
    var roomJob by remember { mutableStateOf<Job?>(null) }

    var urlText by remember { mutableStateOf(savedUrl.orEmpty()) }
    var currentUrl by remember { mutableStateOf(savedUrl) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var selectedResult by remember { mutableStateOf<Track?>(null) }
    var searching by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf<OnlineRepository.ResolvedVideo?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var playNonce by remember { mutableIntStateOf(0) }
    var pendingStartMs by remember { mutableLongStateOf(vm.watchPosition.value) }
    var resumePlaying by remember { mutableStateOf(vm.watchPlaying.value) }
    var qualityReload by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf<Int?>(null) }
    val qualityOptions = OnlineRepository.WATCH_QUALITIES
    var videoAspect by remember { mutableFloatStateOf(0f) }
    var resultsExpanded by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var seekFeedback by remember { mutableIntStateOf(0) }
    var seekFeedbackLeft by remember { mutableStateOf(true) }
    var seekFeedbackKey by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seekOnReady by remember { mutableLongStateOf(0L) }
    var fallbackTried by remember { mutableStateOf(false) }
    var nextIn by remember { mutableIntStateOf(0) }
    var nextUrl by remember { mutableStateOf<String?>(null) }

    fun currentAspect(): Float {
        if (videoAspect > 0f) return videoAspect
        val r = resolved
        if (r != null && r.videoWidth > 0 && r.videoHeight > 0) {
            return r.videoWidth.toFloat() / r.videoHeight
        }
        return 16f / 9f
    }

    fun isVerticalVideo(): Boolean = currentAspect() < 1f

    fun nextFullscreenMode(): WatchFullscreenMode =
        if (fullscreenMode == WatchFullscreenMode.NORMAL) {
            if (isVerticalVideo()) WatchFullscreenMode.PORTRAIT else WatchFullscreenMode.LANDSCAPE
        } else {
            WatchFullscreenMode.NORMAL
        }

    fun changeFullscreen(target: WatchFullscreenMode) {
        if (target == fullscreenMode) return
        scope.launch {
            // Сначала прячем шторки в текущей ориентации, затем поворачиваем
            // экран и выпускаем их уже с нужной стороны.
            controlsVisible = false
            delay(240)
            onFullscreenMode(target)
            delay(360)
            controlsVisible = true
        }
    }

    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(DoH.dns)
            .proxySelector(ProxyRuntime.mediaProxySelector)
            .build()
    }
    val dataSourceFactory = remember(context) {
        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(httpClient))
    }
    val trackSelector = remember(context) { DefaultTrackSelector(context) }
    val player = remember(context) {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .build()
    }
    fun applyQuality(q: Int?) {
        // «1080p» для вертикального ролика — это 1080 по ширине и 1920 по
        // высоте, поэтому ограничение по одной стороне задаём с учётом формата.
        val target = q ?: 1080
        val aspect = currentAspect().coerceAtLeast(0.1f)
        val params = trackSelector.buildUponParameters()
        if (aspect < 1f) {
            params.setMaxVideoSize(target, (target / aspect).roundToInt().coerceAtLeast(target))
        } else {
            params.setMaxVideoSize((target * aspect).roundToInt().coerceAtLeast(target), target)
        }
        trackSelector.setParameters(params)
    }

    val currentVideoQueue by rememberUpdatedState(videoQueue)
    val currentUrlState by rememberUpdatedState(currentUrl)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    if (aspect > 0f) videoAspect = aspect
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                    if (seekOnReady > 0L) {
                        player.seekTo(seekOnReady)
                        seekOnReady = 0L
                    }
                }
                if (playbackState == Player.STATE_ENDED) {
                    val url = currentUrlState
                    val index = currentVideoQueue.indexOfFirst { it.pageUrl == url || it.id == url }
                    val next = currentVideoQueue.getOrNull(index + 1)
                    if (next?.pageUrl != null) {
                        nextUrl = next.pageUrl
                        nextIn = 5
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val h = resolved?.height ?: 0
                if (h > 720 && !fallbackTried) {
                    fallbackTried = true
                    quality = 720
                    qualityReload = player.mediaItemCount > 0
                    playNonce++
                    error = "1080p не запустился, включил 720p"
                    return
                }
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    -> "Нет соединения"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Поток недоступен"
                    else -> "Не удалось открыть видео"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            vm.saveWatchState(
                url = vm.watchUrl.value,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                playing = player.isPlaying,
                title = vm.watchTitle.value,
                artist = vm.watchArtist.value,
                hasVideo = vm.watchHasVideo.value,
            )
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(Unit) {
        onDispose { roomJob?.cancel(); WatchRoomClient.stopStream() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                vm.saveWatchState(
                    url = vm.watchUrl.value,
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    playing = false,
                    title = vm.watchTitle.value,
                    artist = vm.watchArtist.value,
                    hasVideo = vm.watchHasVideo.value,
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(350)
            if (!dragging) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
                isPlaying = player.isPlaying
            }
        }
    }

    LaunchedEffect(savedUrl) {
        val link = savedUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (link == currentUrl && playNonce > 0) return@LaunchedEffect
        if (link != currentUrl) {
            urlText = link
            currentUrl = link
            videoAspect = 0f
            pendingStartMs = vm.watchPosition.value
            resumePlaying = vm.watchPlaying.value
            playNonce++
        }
    }

    fun startUrl(url: String) {
        nextIn = 0
        nextUrl = null
        fallbackTried = false
        urlText = url
        currentUrl = url
        videoAspect = 0f
        pendingStartMs = 0L
        qualityReload = false
        resumePlaying = true
        playNonce++
    }

    LaunchedEffect(nextIn) {
        if (nextIn <= 0) return@LaunchedEffect
        delay(1000)
        nextIn--
        if (nextIn <= 0) {
            val target = nextUrl
            nextUrl = null
            if (target != null) startUrl(target)
        }
    }

    fun playQueue(delta: Int) {
        val url = currentUrl ?: return
        val index = videoQueue.indexOfFirst { it.pageUrl == url || it.id == url }
        val next = if (index < 0) videoQueue.getOrNull(0) else videoQueue.getOrNull(index + delta)
        next?.pageUrl?.let { startUrl(it) }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun roomBase(): String = (savedRoomServer ?: configuredServer).trim().trimEnd('/')

    fun share(room: String? = null) {
        val url = currentUrl ?: return
        val seconds = (player.currentPosition / 1000L).coerceAtLeast(0L)
        val watch = Uri.parse(WATCH_PAGE_URL).buildUpon()
            .appendQueryParameter("url", url)
            .apply {
                if (room.isNullOrBlank()) {
                    appendQueryParameter("t", seconds.toString())
                } else {
                    appendQueryParameter("room", room)
                    if (roomBase().isNotBlank()) appendQueryParameter("server", roomBase())
                }
            }
            .build()
        val app = Uri.parse("volna://watch").buildUpon()
            .appendQueryParameter("url", url)
            .apply {
                if (room.isNullOrBlank()) {
                    appendQueryParameter("t", seconds.toString())
                } else {
                    appendQueryParameter("room", room)
                    if (roomBase().isNotBlank()) appendQueryParameter("server", roomBase())
                }
            }
            .build()
        val text = "Смотреть вместе:\n$watch\n\n$app"
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Смотреть вместе",
                ),
            )
        }
    }

    fun applyRoomState(state: com.volna.player.data.WatchRoomState) {
        if (state.video.isBlank()) return
        if (state.video != currentUrl) {
            currentUrl = state.video
            urlText = state.video
            videoAspect = 0f
            pendingStartMs = state.positionMs
            resumePlaying = state.playing
            qualityReload = false
            playNonce++
        } else {
            if (state.playing != player.isPlaying) {
                if (state.playing) player.play() else player.pause()
            }
            if (abs(player.currentPosition - state.positionMs) > 2500L) {
                player.seekTo(state.positionMs)
            }
        }
    }

    fun startRoomLoop(base: String, id: String, host: Boolean) {
        roomJob?.cancel()
        roomJob = scope.launch {
            if (host) {
                var lastVideo = ""
                var lastPlaying = player.isPlaying
                var lastPos = player.currentPosition
                var lastAt = System.currentTimeMillis()
                while (isActive) {
                    delay(2000)
                    val url = currentUrl ?: continue
                    val now = System.currentTimeMillis()
                    val expected = lastPos + if (lastPlaying) now - lastAt else 0L
                    val changed = url != lastVideo ||
                        player.isPlaying != lastPlaying ||
                        abs(player.currentPosition - expected) > 3000L
                    if (changed) {
                        runCatching {
                            WatchRoomClient.update(base, id, url, player.currentPosition, player.isPlaying)
                        }
                        lastVideo = url
                        lastPlaying = player.isPlaying
                        lastPos = player.currentPosition
                        lastAt = now
                    }
                }
            } else if (base.isBlank()) {
                WatchRoomClient.listenNtfy(id) { state ->
                    scope.launch { applyRoomState(state) }
                }
            } else {
                while (isActive) {
                    delay(1200)
                    val state = runCatching { WatchRoomClient.get(base, id) }.getOrNull() ?: continue
                    applyRoomState(state)
                }
            }
        }
    }

    fun createRoom() {
        val base = roomBase()
        val url = currentUrl
        if (url.isNullOrBlank()) {
            roomError = "Сначала включите видео"
            return
        }
        scope.launch {
            roomBusy = true
            roomError = null
            runCatching {
                WatchRoomClient.create(base, url, player.currentPosition, player.isPlaying)
            }.onSuccess { id ->
                roomId = id
                isHost = true
                startRoomLoop(base, id, true)
                share(id)
                roomDialog = false
            }.onFailure {
                roomError = it.message ?: "Не удалось создать комнату"
                roomDialog = true
            }
            roomBusy = false
        }
    }

    fun joinRoom() {
        val base = roomBase()
        val code = roomCode.trim()
        if (code.isBlank()) {
            roomError = "Введите код комнаты"
            return
        }
        scope.launch {
            roomBusy = true
            roomError = null
            runCatching { WatchRoomClient.get(base, code) }
                .onSuccess { state ->
                    roomId = code
                    isHost = false
                    currentUrl = state.video
                    urlText = state.video
                    videoAspect = 0f
                    pendingStartMs = state.positionMs
                    resumePlaying = state.playing
                    qualityReload = false
                    playNonce++
                    startRoomLoop(base, code, false)
                    roomDialog = false
                }
                .onFailure { roomError = it.message ?: "Комната не найдена" }
            roomBusy = false
        }
    }

    LaunchedEffect(shareRequest) {
        if (shareRequest > 0) {
            if (currentUrl == null) {
                roomDialog = true
            } else {
                createRoom()
            }
        }
    }

    LaunchedEffect(savedRoom, savedRoomServer) {
        val code = savedRoom?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val base = savedRoomServer?.takeIf { it.isNotBlank() } ?: configuredServer
        if (roomId == code) return@LaunchedEffect
        SettingsStore.setWatchServer(base)
        roomCode = code
        joinRoom()
    }

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        if (q.startsWith("http")) {
            startUrl(q)
            return
        }
        scope.launch {
            searching = true
            error = null
            runCatching { OnlineRepository.searchVideos(q) }
                .onSuccess {
                    searchResults = it
                    selectedResult = it.firstOrNull()
                    resultsExpanded = false
                }
                .onFailure { error = it.message ?: "Поиск не удался" }
            searching = false
        }
    }

    fun addToQueue(track: Track) {
        vm.addVideoToQueue(track)
    }

    fun addLinkToQueue(link: String) {
        scope.launch {
            searching = true
            error = null
            val track = runCatching {
                trackForResolved(OnlineRepository.resolveVideo(link), link)
            }.getOrElse {
                Track(
                    id = link,
                    title = link,
                    pageUrl = link,
                    source = sourceForUrl(link),
                )
            }
            addToQueue(track)
            searching = false
        }
    }

    LaunchedEffect(currentUrl, playNonce, quality) {
        val url = currentUrl?.trim().orEmpty()
        if (url.isEmpty()) return@LaunchedEffect
        val startAt = if (qualityReload) player.currentPosition else pendingStartMs
        qualityReload = false
        pendingStartMs = 0L
        vm.pauseForWatch()
        loading = true
        error = null
        resolved = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                try {
                    OnlineRepository.resolveVideo(url, quality)
                } catch (e: Exception) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        OnlineRepository.ResolvedVideo(
                            url = url,
                            mimeType = null,
                            title = url,
                            artist = "",
                            durationMs = 0L,
                            thumbUrl = null,
                            hasVideo = true,
                        )
                    } else {
                        throw e
                    }
                }
            }
        }
        result.onSuccess { r ->
            resolved = r
            if (r.videoWidth > 0 && r.videoHeight > 0) {
                videoAspect = r.videoWidth.toFloat() / r.videoHeight
            }
            if (r.title.isNotBlank() && r.title != url) {
                vm.updateVideoInQueue(trackForResolved(r, url))
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(r.title.ifBlank { url })
                .setArtist(r.artist)
                .apply { r.thumbUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            applyQuality(quality)
            val videoItem = MediaItem.Builder()
                .setUri(r.url)
                .setMediaMetadata(metadata)
                .build()
            val source: MediaSource = if (r.dashUrl != null) {
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.Builder().setUri(r.dashUrl).setMediaMetadata(metadata).build())
            } else {
                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(videoItem)
                if (r.audioUrl != null) {
                    MergingMediaSource(
                        videoSource,
                        ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(r.audioUrl)),
                    )
                } else {
                    videoSource
                }
            }
            player.setMediaSource(source, startAt.coerceAtLeast(0L))
            player.prepare()
            player.setPlaybackSpeed(speed)
            if (resumePlaying) player.play() else player.pause()
            durationMs = r.durationMs.coerceAtLeast(0L)
            vm.saveWatchState(
                url = url,
                positionMs = startAt.coerceAtLeast(0L),
                playing = resumePlaying,
                title = r.title,
                artist = r.artist,
                hasVideo = r.hasVideo,
            )
        }.onFailure {
            error = it.message ?: "Не удалось открыть ссылку"
        }
        loading = false
    }

    val gestureModifier = Modifier
        .pointerInput(player, fullscreenMode, controlsVisible) {
            detectTapGestures(
                onTap = {
                    if (fullscreen) {
                        controlsVisible = !controlsVisible
                    } else {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                },
                onDoubleTap = { offset ->
                    when {
                        offset.x < size.width / 3f -> {
                            player.seekTo((player.currentPosition - 5000L).coerceAtLeast(0L))
                            seekFeedback -= 5
                            seekFeedbackLeft = true
                            seekFeedbackKey++
                        }
                        offset.x > size.width * 2f / 3f -> {
                            player.seekTo(player.currentPosition + 5000L)
                            seekFeedback += 5
                            seekFeedbackLeft = false
                            seekFeedbackKey++
                        }
                        else -> {
                            changeFullscreen(
                                if (fullscreenMode == WatchFullscreenMode.NORMAL) {
                                    nextFullscreenMode()
                                } else {
                                    WatchFullscreenMode.NORMAL
                                },
                            )
                        }
                    }
                },
            )
        }

    LaunchedEffect(seekFeedbackKey) {
        if (seekFeedback != 0) {
            delay(900)
            seekFeedback = 0
        }
    }

    val videoModifier = Modifier.fillMaxSize().then(gestureModifier)

    if (fullscreen) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                WatchVideoSurface(player, resolved, loading, currentUrl, videoModifier, seekFeedback, seekFeedbackLeft)
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically(
                        initialOffsetY = { -it },
                        animationSpec = tween(260),
                    ),
                    exit = slideOutVertically(
                        targetOffsetY = { -it },
                        animationSpec = tween(220),
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.60f))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), "Назад")
                        }
                        Box(Modifier.weight(1f)) {
                            MarqueeTitle(
                                text = resolved?.title.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        IconButton(onClick = { share() }) {
                            Icon(painterResource(R.drawable.ic_people), "Смотреть вместе")
                        }
                    }
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(260),
                    ),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(220),
                    ),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.68f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (nextIn > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                Text("Следующее через ${nextIn} с", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    val target = nextUrl
                                    nextIn = 0
                                    nextUrl = null
                                    if (target != null) startUrl(target)
                                }) { Text("Сейчас") }
                            }
                        }
                        PlayerLine(
                            positionMs, durationMs, dragging, dragFraction,
                            onDrag = { dragging = true; dragFraction = it },
                            onDragEnd = {
                                if (durationMs > 0) player.seekTo((dragFraction * durationMs).toLong())
                                dragging = false
                            },
                        )
                        WatchControls(
                            player = player,
                            isPlaying = isPlaying,
                            speed = speed,
                            quality = quality,
                            qualities = qualityOptions,
                            onSpeed = { speed = it; player.setPlaybackSpeed(it) },
                            onQuality = {
                                fallbackTried = false
                                qualityReload = player.mediaItemCount > 0
                                quality = it
                                playNonce++
                            },
                            onPrev = { playQueue(-1) },
                            onNext = { playQueue(1) },
                            fullscreenMode = fullscreenMode,
                            nextFullscreenMode = nextFullscreenMode(),
                            onFullscreen = { changeFullscreen(nextFullscreenMode()) },
                        )
                    }
                }
            }
        }
        return
    }


    BoxWithConstraints(Modifier.fillMaxSize()) {
        val aspect = currentAspect().coerceAtLeast(0.1f)
        val maxPreview = if (aspect < 1f) {
            minOf(maxHeight * 0.40f, 280.dp)
        } else {
            minOf(maxHeight * 0.46f, 280.dp)
        }
        val previewHeight = (maxWidth / aspect).coerceAtMost(maxPreview).coerceAtLeast(120.dp)
        val previewWidth = (previewHeight * aspect).coerceAtMost(maxWidth)

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(previewHeight),
                contentAlignment = Alignment.Center,
            ) {
                WatchVideoSurface(
                    player = player,
                    resolved = resolved,
                    loading = loading,
                    currentUrl = currentUrl,
                    modifier = Modifier.width(previewWidth).height(previewHeight).then(gestureModifier),
                    seekFeedback = seekFeedback,
                    seekFeedbackLeft = seekFeedbackLeft,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    MarqueeTitle(
                        text = resolved?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
            if (nextIn > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text("Следующее через ${nextIn} с", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val target = nextUrl
                        nextIn = 0
                        nextUrl = null
                        if (target != null) startUrl(target)
                    }) { Text("Сейчас") }
                }
            }
            PlayerLine(
                positionMs = positionMs,
                durationMs = durationMs,
                dragging = dragging,
                dragFraction = dragFraction,
                onDrag = { dragging = true; dragFraction = it },
                onDragEnd = {
                    if (durationMs > 0) player.seekTo((dragFraction * durationMs).toLong())
                    dragging = false
                },
            )
            WatchControls(
                player = player,
                isPlaying = isPlaying,
                speed = speed,
                quality = quality,
                qualities = qualityOptions,
                onSpeed = { speed = it; player.setPlaybackSpeed(it) },
                onQuality = {
                    fallbackTried = false
                    qualityReload = player.mediaItemCount > 0
                    quality = it
                    playNonce++
                },
                onPrev = { playQueue(-1) },
                onNext = { playQueue(1) },
                fullscreenMode = fullscreenMode,
                nextFullscreenMode = nextFullscreenMode(),
                onFullscreen = {
                    changeFullscreen(nextFullscreenMode())
                },
            )
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        selectedResult = null
                        searchResults = emptyList()
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Поиск") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedIconButton(
                    onClick = {
                        val q = searchQuery.trim()
                        when {
                            q.isEmpty() && selectedResult == null -> Unit
                            q.startsWith("http") -> addLinkToQueue(q)
                            else -> {
                                val t = selectedResult ?: searchResults.firstOrNull()
                                if (t != null) addToQueue(t) else runSearch()
                            }
                        }
                    },
                    shape = CircleShape,
                    enabled = searchQuery.isNotBlank(),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), "В очередь")
                }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(Modifier.fillMaxSize()) {
                if (searchResults.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Найдено · ${searchResults.size}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (searchResults.size > 2) {
                                TextButton(onClick = { resultsExpanded = !resultsExpanded }) {
                                    Text(if (resultsExpanded) "Свернуть" else "Ещё")
                                }
                            }
                        }
                    }
                    val shown = if (resultsExpanded) searchResults else searchResults.take(2)
                    items(shown, key = { "s-${it.id}" }) { track ->
                        VideoRow(
                            track = track,
                            onPlay = {
                                selectedResult = track
                                track.pageUrl?.let { startUrl(it) }
                            },
                            onAdd = { addToQueue(track) },
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Очередь видео", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { roomDialog = true }) { Text("Войти по коду") }
                        if (videoQueue.isNotEmpty()) {
                            TextButton(onClick = { vm.clearVideoQueue() }) { Text("Очистить") }
                        }
                    }
                }
                items(videoQueue, key = { "q-${it.id}" }) { track ->
                    VideoRow(
                        track = track,
                        onPlay = {
                            track.pageUrl?.let { startUrl(it) }
                        },
                        onAdd = null,
                        onRemove = { vm.removeVideoFromQueue(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchVideoSurface(
    player: Player,
    resolved: OnlineRepository.ResolvedVideo?,
    loading: Boolean,
    currentUrl: String?,
    modifier: Modifier,
    seekFeedback: Int,
    seekFeedbackLeft: Boolean,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        if (resolved == null && !loading && currentUrl == null) {
            MarqueeTitle(
                text = "Вставьте ссылку с YouTube",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
        if (seekFeedback != 0) {
            Box(
                Modifier
                    .align(if (seekFeedbackLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(
                    if (seekFeedback > 0) "+$seekFeedback с" else "$seekFeedback с",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun PlayerLine(
    positionMs: Long,
    durationMs: Long,
    dragging: Boolean,
    dragFraction: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val shown = if (dragging) dragFraction else {
        if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }
    LineSlider(
        value = shown,
        valueRange = 0f..1f,
        onValueChange = onDrag,
        onValueChangeFinished = onDragEnd,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(fmtTime(if (dragging) (dragFraction * duration).toLong() else positionMs), style = MaterialTheme.typography.labelSmall)
        Text(fmtTime(duration), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WatchControls(
    player: Player,
    isPlaying: Boolean,
    speed: Float,
    quality: Int?,
    qualities: List<Int>,
    onSpeed: (Float) -> Unit,
    onQuality: (Int?) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    fullscreenMode: WatchFullscreenMode,
    nextFullscreenMode: WatchFullscreenMode,
    onFullscreen: () -> Unit,
) {
    val speedControl: @Composable () -> Unit = { SpeedControl(speed, onSpeed) }
    val qualityControl: @Composable () -> Unit = {
        QualityControl(quality, qualities, onQuality)
    }
    val fullscreenButton: @Composable () -> Unit = {
        val icon = when {
            fullscreenMode != WatchFullscreenMode.NORMAL -> R.drawable.ic_fullscreen_exit
            nextFullscreenMode == WatchFullscreenMode.PORTRAIT -> R.drawable.ic_fullscreen_portrait
            else -> R.drawable.ic_fullscreen
        }
        val label = when {
            fullscreenMode != WatchFullscreenMode.NORMAL -> "Выйти из полного экрана"
            nextFullscreenMode == WatchFullscreenMode.PORTRAIT -> "Вертикальный полный экран"
            else -> "Горизонтальный полный экран"
        }
        OutlinedIconButton(onClick = onFullscreen, modifier = Modifier.size(40.dp)) {
            Icon(
                painterResource(icon),
                label,
                modifier = Modifier.size(22.dp),
            )
        }
    }
    val transport: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = onPrev, modifier = Modifier.size(42.dp)) {
                Icon(painterResource(R.drawable.ic_prev), "Предыдущее видео", modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(5.dp))
            FilledIconButton(
                onClick = { if (player.isPlaying) player.pause() else player.play() },
                modifier = Modifier.size(58.dp),
            ) {
                Icon(
                    painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    "Играть/пауза",
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(5.dp))
            OutlinedIconButton(onClick = onNext, modifier = Modifier.size(42.dp)) {
                Icon(painterResource(R.drawable.ic_next), "Следующее видео", modifier = Modifier.size(22.dp))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (maxWidth < 330.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    speedControl()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        qualityControl()
                        fullscreenButton()
                    }
                }
                Spacer(Modifier.height(6.dp))
                transport()
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.CenterStart)) { speedControl() }
                Box(Modifier.align(Alignment.Center)) { transport() }
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    qualityControl()
                    fullscreenButton()
                }
            }
        }
    }
}

@Composable
private fun SpeedControl(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedIconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Text(if (speed == 1f) "1×" else "${speed}×", style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(if (s == 1f) "Обычная" else "$s×") },
                    onClick = { onSpeed(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun QualityControl(quality: Int?, qualities: List<Int>, onQuality: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val all = (qualities + listOfNotNull(quality))
        .filter { it in OnlineRepository.WATCH_QUALITIES }
        .distinct()
        .sortedDescending()
    Box {
        OutlinedIconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Text(
                quality?.let { "${it}p" } ?: "Авто",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Авто") },
                onClick = { onQuality(null); open = false },
            )
            all.forEach { q ->
                DropdownMenuItem(
                    text = { Text("${q}p") },
                    onClick = { onQuality(q); open = false },
                )
            }
        }
    }
}

@Composable
private fun VideoRow(
    track: Track,
    onPlay: () -> Unit,
    onAdd: (() -> Unit)?,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) {
            VolnaArtwork(
                model = track.artworkUri,
                isLocal = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                track.artist.ifBlank { fmtTime(track.durationMs) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_add), "В очередь")
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close), "Убрать")
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
