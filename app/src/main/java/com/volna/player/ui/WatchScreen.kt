package com.volna.player.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.volna.player.R
import com.volna.player.data.OnlineRepository
import com.volna.player.net.ProxyRuntime
import com.volna.player.player.PlayerViewModel
import com.volna.player.util.DoH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val WATCH_PAGE = "https://99vp.github.io/volna/watch/"

/**
 * Отдельный видеоплеер «Просмотр». Он не связан с очередью основного плеера:
 * при открытии музыка ставится на паузу, а видео живёт своей жизнью.
 */
@Composable
fun WatchScreen(
    vm: PlayerViewModel,
    initialUrl: String?,
    initialPositionMs: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf(initialUrl.orEmpty()) }
    var currentUrl by remember { mutableStateOf(initialUrl?.takeIf { it.isNotBlank() }) }
    var playNonce by remember { mutableIntStateOf(0) }
    var pendingStartMs by remember { mutableLongStateOf(initialPositionMs.coerceAtLeast(0L)) }
    var lastInitialUrl by remember { mutableStateOf(initialUrl) }
    var lastInitialPos by remember { mutableLongStateOf(initialPositionMs) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf<OnlineRepository.ResolvedVideo?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seekOnReady by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val player = remember(context) {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(DoH.dns)
            .proxySelector(ProxyRuntime.mediaProxySelector)
            .build()
        val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))
        val factory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(upstream)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(factory)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .build()
    }

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
            }

            override fun onPlayerError(e: PlaybackException) {
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
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(400)
            if (!dragging) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
                isPlaying = player.isPlaying
            }
        }
    }

    LaunchedEffect(initialUrl, initialPositionMs) {
        val link = initialUrl ?: return@LaunchedEffect
        if (link.isBlank()) return@LaunchedEffect
        if (link == lastInitialUrl && initialPositionMs == lastInitialPos) return@LaunchedEffect
        lastInitialUrl = link
        lastInitialPos = initialPositionMs
        urlText = link
        currentUrl = link
        pendingStartMs = initialPositionMs.coerceAtLeast(0L)
        playNonce++
    }

    LaunchedEffect(currentUrl, playNonce) {
        val url = currentUrl?.trim().orEmpty()
        if (url.isEmpty()) return@LaunchedEffect
        vm.pauseForWatch()
        loading = true
        error = null
        resolved = null
        player.stop()
        player.clearMediaItems()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                try {
                    OnlineRepository.resolveVideo(url)
                } catch (e: Exception) {
                    // Прямая ссылка на mp4/m3u8 — тоже допустимый вариант.
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
        result.onSuccess {
            resolved = it
            player.setMediaItem(MediaItem.fromUri(it.url))
            if (pendingStartMs > 0L) {
                seekOnReady = pendingStartMs
                pendingStartMs = 0L
            }
            player.prepare()
            player.play()
        }.onFailure {
            error = it.message ?: "Не удалось открыть ссылку"
        }
        loading = false
    }

    fun share() {
        val url = currentUrl ?: return
        val seconds = (positionMs / 1000L).coerceAtLeast(0L)
        val watch = Uri.parse(WATCH_PAGE).buildUpon()
            .appendQueryParameter("url", url)
            .appendQueryParameter("t", seconds.toString())
            .build()
        val app = Uri.parse("volna://watch").buildUpon()
            .appendQueryParameter("url", url)
            .appendQueryParameter("t", seconds.toString())
            .build()
        val text = "Смотреть вместе в Волне:\n$watch\n\n" +
            "Если приложение не открылось: $app\n" +
            "Скачать Волну: https://github.com/99VP/volna/releases/latest"
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Поделиться просмотром",
                ),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
            }
            Text(
                "Просмотр",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { share() }, enabled = currentUrl != null) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = "Поделиться")
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ссылка на YouTube или видео") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val u = urlText.trim()
                    if (u.isNotEmpty()) {
                        currentUrl = u
                        playNonce++
                    }
                },
                enabled = urlText.isNotBlank() && !loading,
            ) { Text("Смотреть") }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = { view ->
                    view.player = player
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (resolved?.hasVideo == false) {
                AsyncImage(
                    model = resolved?.thumbUrl,
                    contentDescription = null,
                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)),
                )
            }
            if (loading) CircularProgressIndicator()
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            MarqueeTitle(
                text = resolved?.title?.ifBlank { currentUrl.orEmpty() } ?: "Вставь ссылку и нажми «Смотреть»",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
            )
            if (!resolved?.artist.isNullOrBlank()) {
                Text(
                    resolved?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            val duration = durationMs.coerceAtLeast(0L)
            val shown = if (dragging) dragFraction else {
                if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }
            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    dragFraction = it
                },
                onValueChangeFinished = {
                    if (duration > 0) player.seekTo((dragFraction * duration).toLong())
                    dragging = false
                },
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(watchFmt(if (dragging) (dragFraction * duration).toLong() else positionMs), style = MaterialTheme.typography.labelSmall)
                Text(watchFmt(duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)) }) {
                    Icon(painterResource(R.drawable.ic_prev), "Назад на 10 секунд")
                }
                IconButton(onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                }) {
                    Icon(
                        painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = "Играть/пауза",
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = { player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration.coerceAtLeast(0L))) }) {
                    Icon(painterResource(R.drawable.ic_next), "Вперёд на 10 секунд")
                }
            }
            TextButton(onClick = { share() }, enabled = currentUrl != null, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Смотреть вместе")
            }
            Text(
                "Просмотр идёт отдельно от музыки в плеере. Плеер на паузе, пока открыт этот экран.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

private fun watchFmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
