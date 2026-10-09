package com.volna.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.volna.player.MainActivity
import com.volna.player.R
import com.volna.player.data.LibraryStore
import com.volna.player.data.Track
import com.volna.player.data.TrackSource
import com.volna.player.util.DoH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Фоновое воспроизведение: MediaSessionService + ExoPlayer.
 *
 * НЕ МЕНЯТЬ способ создания сессии: кастомный Callback/onConnect и кастомная
 * раскладка кнопок на части устройств ломают подключение контроллера, и плеер
 * вообще не запускается. Здесь сессия создаётся по умолчанию (как в рабочей 3.4).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var mediaCache: SimpleCache? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bufferingSince = 0L

    private fun diag(msg: String) {
        runCatching {
            val p = getSharedPreferences("volna_diag", MODE_PRIVATE)
            val cur = p.getString("log", "").orEmpty()
            p.edit().putString("log", (cur + "\n" + msg).takeLast(3000)).commit()
        }
    }

    override fun onCreate() {
        super.onCreate()
        diag("onCreate")
        // Кнопка «лайк» в уведомлении — через провайдер уведомления, без кастомной сессии.
        getSharedPreferences("volna_diag", MODE_PRIVATE)
            .edit().putLong("serviceCreated", System.currentTimeMillis()).commit()

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DoH.dns)
            .build()

        val httpFactory = OkHttpDataSource.Factory(httpClient)
        // DefaultDataSource умеет открывать локальные content:// и file:// файлы,
        // а для http(s) делегирует в OkHttp (с нашим DoH).
        val upstream = DefaultDataSource.Factory(this, httpFactory)
        // Ограниченный кэш аудио: прослушанное кэшируется, старое само вытесняется (LRU),
        // поэтому память не забивается бесконечно.
        val cache = runCatching {
            SimpleCache(
                File(cacheDir, "volna_media"),
                LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024),
            )
        }.getOrNull()
        mediaCache = cache
        val cachedUpstream = if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstream
        }
        val resolving = ResolvingDataSource.Factory(cachedUpstream, VolnaResolver())

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(resolving)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Если трек не открылся (ссылка устарела/недоступна) - просто идём дальше.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                bufferingSince = 0L
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_BUFFERING) bufferingSince = 0L
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                refreshButtons()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                refreshButtons()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshButtons()
            }
        })

        // Сторож зависаний: если трек долго не открывается - перелистываем дальше.
        scope.launch {
            while (isActive) {
                delay(3000)
                val playing = player.playWhenReady
                if (playing && player.playbackState == Player.STATE_BUFFERING) {
                    val now = System.currentTimeMillis()
                    if (bufferingSince == 0L) {
                        bufferingSince = now
                    } else if (now - bufferingSince > 25000) {
                        bufferingSince = 0L
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                        }
                        player.prepare()
                        player.play()
                    }
                } else {
                    bufferingSince = 0L
                }
            }
        }

        mediaSession = MediaSession.Builder(this, PiratGuardPlayer(player))
            .setSessionActivity(openAppIntent())
            .setMediaButtonPreferences(buttonsFor(player))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
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
                    when (customCommand.customAction) {
                        ACTION_LIKE -> {
                            toggleFavorite()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        ACTION_SHUFFLE -> {
                            exoPlayer?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                            refreshButtons()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        ACTION_REPEAT -> {
                            exoPlayer?.let {
                                it.repeatMode = when (it.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                            refreshButtons()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
        exoPlayer = player
        refreshButtons()
        getSharedPreferences("volna_diag", MODE_PRIVATE)
            .edit().putLong("sessionBuilt", System.currentTimeMillis()).commit()
        diag("session built")
    }

    /** Лайк текущего трека прямо из уведомления/экрана блокировки. */
    private fun trackFromItem(item: MediaItem): Track {
        val md = item.mediaMetadata
        val uri = item.localConfiguration?.uri
        val page = if (uri != null && uri.scheme == VolnaResolver.SCHEME) {
            uri.getQueryParameter("url")
        } else {
            null
        }
        val local = if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
            uri.toString()
        } else {
            null
        }
        return Track(
            id = item.mediaId,
            title = md.title?.toString().orEmpty().ifBlank { "Без названия" },
            artist = md.artist?.toString().orEmpty(),
            pageUrl = page,
            localUri = local,
            artworkUri = md.artworkUri?.toString(),
            source = if (page != null) TrackSource.OTHER else TrackSource.LOCAL,
        )
    }

    private fun isFavoriteFor(p: Player?): Boolean {
        val item = p?.currentMediaItem ?: return false
        return runCatching { LibraryStore(this).isFavorite(trackFromItem(item)) }.getOrDefault(false)
    }

    private fun likeButton(filled: Boolean): CommandButton = CommandButton.Builder(
        if (filled) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
    )
        .setSessionCommand(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
        .setDisplayName("В избранное")
        .setSlots(CommandButton.SLOT_BACK_SECONDARY)
        .build()

    private fun buttonsFor(p: Player?): List<CommandButton> {
        val repeatIcon = when (p?.repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }
        return listOf(
            likeButton(isFavoriteFor(p)),
            CommandButton.Builder(repeatIcon)
                .setSessionCommand(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
                .setDisplayName("Повтор")
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
                .build(),
        )
    }

    private fun refreshButtons() {
        runCatching { mediaSession?.setMediaButtonPreferences(buttonsFor(exoPlayer)) }
    }

    private fun toggleFavorite() {
        val item = exoPlayer?.currentMediaItem ?: return
        val track = trackFromItem(item)
        val store = LibraryStore(this)
        val wasFav = runCatching { store.isFavorite(track) }.getOrDefault(false)
        runCatching {
            store.toggleFavorite(track)
            android.widget.Toast.makeText(
                this,
                if (wasFav) "Убрано из избранного" else "Добавлено в избранное",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        refreshButtons()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        diag("onGetSession -> ${mediaSession != null}")
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diag("onTaskRemoved")
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        diag("onDestroy")
        scope.cancel()
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
        runCatching { mediaCache?.release() }
        mediaCache = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_LIKE = "com.volna.player.LIKE"
        const val ACTION_SHUFFLE = "com.volna.player.SHUFFLE"
        const val ACTION_REPEAT = "com.volna.player.REPEAT"
    }
}
