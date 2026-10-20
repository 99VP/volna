package com.volna.player.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val WATCH_PAGE_URL = "https://99vp.github.io/volna/watch/"

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun WatchScreen(
    vm: PlayerViewModel,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onFullscreen: (Boolean) -> Unit,
    shareRequest: Int,
) {
    val context = LocalContext.current
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
    var speed by remember { mutableFloatStateOf(1f) }
    var seekOnReady by remember { mutableLongStateOf(0L) }
    var fallbackTried by remember { mutableStateOf(false) }
    var nextIn by remember { mutableIntStateOf(0) }
    var nextUrl by remember { mutableStateOf<String?>(null) }

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
    val player = remember(context) {
        ExoPlayer.Builder(context)
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
    val currentVideoQueue by rememberUpdatedState(videoQueue)
    val currentUrlState by rememberUpdatedState(currentUrl)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
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
        onDispose { roomJob?.cancel() }
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

    fun startRoomLoop(base: String, id: String, host: Boolean) {
        roomJob?.cancel()
        roomJob = scope.launch {
            val interval = if (base.isBlank()) 5000L else 1200L
            while (isActive) {
                delay(interval)
                if (host) {
                    val url = currentUrl ?: continue
                    runCatching {
                        WatchRoomClient.update(base, id, url, player.currentPosition, player.isPlaying)
                    }
                } else {
                    val state = runCatching { WatchRoomClient.get(base, id) }.getOrNull() ?: continue
                    if (state.video.isBlank()) continue
                    if (state.video != currentUrl) {
                        currentUrl = state.video
                        urlText = state.video
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
                }
                .onFailure { error = it.message ?: "Поиск не удался" }
            searching = false
        }
    }

    fun addToQueue(track: Track) {
        vm.addVideoToQueue(track)
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
            val metadata = MediaMetadata.Builder()
                .setTitle(r.title.ifBlank { url })
                .setArtist(r.artist)
                .apply { r.thumbUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            val videoItem = MediaItem.Builder()
                .setUri(r.url)
                .setMediaMetadata(metadata)
                .build()
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(videoItem)
            val source: MediaSource = when {
                r.audioUrl != null -> MergingMediaSource(
                    videoSource,
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(r.audioUrl)),
                )
                else -> videoSource
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
        .pointerInput(player, speed) {
            detectTapGestures(
                onPress = {
                    val released = withTimeoutOrNull(450) { tryAwaitRelease() }
                    if (released == null) {
                        player.setPlaybackSpeed(2f)
                        tryAwaitRelease()
                        player.setPlaybackSpeed(speed)
                    }
                },
                onTap = {
                    if (player.isPlaying) player.pause() else player.play()
                },
            )
        }
        .pointerInput(player) {
            var dx = 0f
            detectHorizontalDragGestures(
                onDragStart = { dx = 0f },
                onHorizontalDrag = { _, drag -> dx += drag },
                onDragEnd = {
                    if (abs(dx) > 40f) {
                        val delta = if (dx > 0) -5000L else 5000L
                        player.seekTo((player.currentPosition + delta).coerceAtLeast(0L))
                    }
                },
            )
        }

    if (roomDialog) {
        AlertDialog(
            onDismissRequest = { roomDialog = false },
            title = { Text("Смотреть вместе") },
            text = {
                Column {
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it },
                        placeholder = { Text("Код комнаты") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    roomId?.let {
                        Text(
                            "Комната: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = { share(it) }) { Text("Поделиться") }
                    }
                    Text(
                        "Сервер: ${roomBase().ifBlank { "ntfy.sh" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    roomError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { createRoom() }, enabled = !roomBusy) { Text("Создать") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { joinRoom() }, enabled = !roomBusy) { Text("Войти") }
                    TextButton(onClick = { roomDialog = false }) { Text("Закрыть") }
                }
            },
        )
    }

    val videoModifier = if (fullscreen) {
        Modifier.fillMaxSize().then(gestureModifier)
    } else {
        Modifier
            .fillMaxWidth()
            .height(if (resolved?.isShort == true) 360.dp else 230.dp)
            .then(gestureModifier)
    }


    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            WatchVideoSurface(player, resolved, loading, currentUrl, videoModifier)
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), "Назад")
                }
                Text(
                    resolved?.title ?: "Просмотр",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { share() }) {
                    Icon(painterResource(R.drawable.ic_people), "Смотреть вместе")
                }
                WatchMenu(
                    qualities = resolved?.qualities.orEmpty(),
                    speed = speed,
                    onQuality = {
                        qualityReload = player.mediaItemCount > 0
                        quality = it
                        playNonce++
                    },
                    onSpeed = {
                        speed = it
                        player.setPlaybackSpeed(it)
                    },
                )
                IconButton(onClick = { onFullscreen(false) }) {
                    Icon(painterResource(R.drawable.ic_fullscreen_exit), "Свернуть")
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
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
                PlayerLine(positionMs, durationMs, dragging, dragFraction,
                    onDrag = { dragging = true; dragFraction = it },
                    onDragEnd = {
                        if (durationMs > 0) player.seekTo((dragFraction * durationMs).toLong())
                        dragging = false
                    },
                )
                WatchControls(player, isPlaying, speed)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        WatchVideoSurface(player, resolved, loading, currentUrl, videoModifier)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                MarqueeTitle(
                    text = resolved?.title ?: "Просмотр",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            WatchMenu(
                qualities = resolved?.qualities.orEmpty(),
                speed = speed,
                onQuality = {
                    qualityReload = player.mediaItemCount > 0
                    quality = it
                    playNonce++
                },
                onSpeed = {
                    speed = it
                    player.setPlaybackSpeed(it)
                },
            )
            IconButton(onClick = { onFullscreen(true) }) {
                Icon(painterResource(R.drawable.ic_fullscreen), "На весь экран")
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
        WatchControls(player, isPlaying, speed)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    selectedResult = null
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Поиск") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedIconButton(
                onClick = {
                    val q = searchQuery.trim()
                    when {
                        q.isEmpty() && selectedResult == null -> Unit
                        q.startsWith("http") -> {
                            addToQueue(
                                Track(
                                    id = q,
                                    title = resolved?.title ?: q,
                                    artist = resolved?.artist.orEmpty(),
                                    pageUrl = q,
                                    artworkUri = resolved?.thumbUrl,
                                    source = TrackSource.YOUTUBE,
                                ),
                            )
                        }
                        else -> {
                            val t = selectedResult ?: searchResults.firstOrNull()
                            if (t != null) addToQueue(t) else runSearch()
                        }
                    }
                },
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add), "В очередь")
            }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.fillMaxSize()) {
            if (searchResults.isNotEmpty()) {
                item {
                    Text(
                        "Найдено",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                items(searchResults, key = { it.id }) { track ->
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
            if (videoQueue.isEmpty()) {
                item {
                    Text(
                        "Добавляйте видео кнопкой +",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            items(videoQueue, key = { it.id }) { track ->
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

@Composable
private fun WatchVideoSurface(
    player: Player,
    resolved: OnlineRepository.ResolvedVideo?,
    loading: Boolean,
    currentUrl: String?,
    modifier: Modifier,
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmtTime(if (dragging) (dragFraction * duration).toLong() else positionMs), style = MaterialTheme.typography.labelSmall)
        Text(fmtTime(duration), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WatchControls(
    player: Player,
    isPlaying: Boolean,
    speed: Float,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { player.seekTo((player.currentPosition - 5000).coerceAtLeast(0L)) }) {
            Icon(painterResource(R.drawable.ic_prev), "−5 секунд")
        }
        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
            Icon(
                painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                "Играть/пауза",
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(onClick = { player.seekTo(player.currentPosition + 5000) }) {
            Icon(painterResource(R.drawable.ic_next), "+5 секунд")
        }
        Text(
            if (speed == 1f) "1×" else "${speed}×",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun WatchMenu(
    qualities: List<Int>,
    speed: Float,
    onQuality: (Int?) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), "Настройки просмотра")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Скорость",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(if (s == 1f) "Обычная" else "$s×") },
                    onClick = { onSpeed(s); open = false },
                )
            }
            HorizontalDivider()
            Text(
                "Качество",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            DropdownMenuItem(
                text = { Text("Авто") },
                onClick = { onQuality(null); open = false },
            )
            qualities.forEach { q ->
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
        IconButton(onClick = onPlay) {
            Icon(painterResource(R.drawable.ic_play), "Смотреть")
        }
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
