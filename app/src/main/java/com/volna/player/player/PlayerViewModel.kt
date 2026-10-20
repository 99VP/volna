package com.volna.player.player

import android.app.Application
import android.content.ComponentName
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.volna.player.data.LocalLibrary
import com.volna.player.data.LibraryStore
import com.volna.player.data.MusicFilter
import com.volna.player.data.OnlineRepository
import com.volna.player.data.Playlist
import com.volna.player.data.SettingsStore
import com.volna.player.data.Track
import com.volna.player.data.TrackDownloader
import com.volna.player.data.TrackSource
import com.volna.player.playback.PlaybackService
import com.volna.player.playback.TrackMediaItem
import com.volna.player.playback.VolnaResolver
import com.volna.player.update.UpdateChecker
import com.volna.player.update.UpdateDownloadManager
import com.volna.player.update.UpdateInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.ServiceList

data class NowPlaying(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val isLocal: Boolean = false,
)

data class QueueEntry(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val isLocal: Boolean = false,
)

data class TrendFolder(
    val id: String,
    val title: String,
    val subtitle: String,
)

enum class PiratStage { LOCKED, CONFIRM, PLEASE, FINAL, NO_SEARCH }

const val DEFAULT_PIRAT_HINT = "Сиди и слушай"

/** Словечки Сереги Пирата (показываются, когда жмёшь запрещённое). */
private val PIRAT_QUIPS = listOf(
    "Неа, не получится",
    "Даже не пытайся",
    "Куда собрался?",
    "Сиди и слушай",
    "Не-не-не, дружок",
    "Тише-тише",
    "А ты что хотел?",
    "Так ведь и было",
    "Это андеграунд, брат",
    "Мы в андеграунде",
    "Настоящий андеграунд",
    "Серега Пират на связи",
    "Держи краба",
    "Йоу, пацаны",
    "Пират не сдаётся",
    "Уличная музыка",
    "Тут тебе не попса",
    "Брат, слушай дальше",
    "Ну ты и попал",
    "Вот бы меня называли все талант",
    "Ты че, думал я сдался?",
    "Сиди, не рыпайся",
    "Волна тебя не отпустит",
    "Тут андеграунд, детка",
    "Пират всегда в теме",
    "Иди сюда, послушай",
    "Не трогай, это святое",
    "Ууу, закусило?",
    "Держи ритм",
    "Качает? Качает",
)

/** Слова, которые запускают скрытый выход из режима пасхалки. */
private val PIRAT_FREE_WORDS = listOf(
    "свобода", "спасите", "помогите", "помощь", "напомощь",
    "выключите", "выключи", "выпустите", "остановите", "хватит", "откройте",
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeat: StateFlow<Int> = _repeat.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _loadingLocal = MutableStateFlow(false)
    val loadingLocal: StateFlow<Boolean> = _loadingLocal.asStateFlow()

    val trendFolders: List<TrendFolder> = listOf(
        TrendFolder("new", "Русские хиты", "то, что слушают"),
        TrendFolder("hot", "Зарубежные", "то, что слушают"),
    )

    private val _trendTracks = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val trendTracks: StateFlow<Map<String, List<Track>>> = _trendTracks.asStateFlow()

    private val _trendLoading = MutableStateFlow<Set<String>>(emptySet())
    val trendLoading: StateFlow<Set<String>> = _trendLoading.asStateFlow()

    /** Пасхалка "Серега Пират". */
    val pirat: StateFlow<Boolean> = SettingsStore.pirat

    private val _piratStage = MutableStateFlow(PiratStage.LOCKED)
    val piratStage: StateFlow<PiratStage> = _piratStage.asStateFlow()

    private val _piratMessage = MutableStateFlow(DEFAULT_PIRAT_HINT)
    val piratMessage: StateFlow<String> = _piratMessage.asStateFlow()

    private val _piratQuip = MutableStateFlow<String?>(null)
    val piratQuip: StateFlow<String?> = _piratQuip.asStateFlow()

    private val _favorites = MutableStateFlow<List<Track>>(emptyList())
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _history = MutableStateFlow<List<Track>>(emptyList())
    val history: StateFlow<List<Track>> = _history.asStateFlow()

    private val _wave = MutableStateFlow<List<Track>>(emptyList())
    val wave: StateFlow<List<Track>> = _wave.asStateFlow()

    private val _loadingWave = MutableStateFlow(false)
    val loadingWave: StateFlow<Boolean> = _loadingWave.asStateFlow()

    /** Волна собрана из музыки на телефоне (нет сети или онлайн-подбор пуст). */
    private val _waveLocal = MutableStateFlow(false)
    val waveLocal: StateFlow<Boolean> = _waveLocal.asStateFlow()

    private val _disliked = MutableStateFlow<Set<String>>(emptySet())
    val disliked: StateFlow<Set<String>> = _disliked.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** Текст последней ошибки плеера (показывается на экране, чтобы её было видно). */
    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError.asStateFlow()

    // ---- Обновления ------------------------------------------------------

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    private val _updateReady = MutableStateFlow(false)
    val updateReady: StateFlow<Boolean> = _updateReady.asStateFlow()

    private val _updateDownloading = MutableStateFlow(false)
    val updateDownloading: StateFlow<Boolean> = _updateDownloading.asStateFlow()

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    private val _updateDismissed = MutableStateFlow<String?>(null)
    val updateDismissed: StateFlow<String?> = _updateDismissed.asStateFlow()

    // ---- Просмотр --------------------------------------------------------

    private val _watchUrl = MutableStateFlow<String?>(null)
    val watchUrl: StateFlow<String?> = _watchUrl.asStateFlow()

    private val _watchPosition = MutableStateFlow(0L)
    val watchPosition: StateFlow<Long> = _watchPosition.asStateFlow()

    private val _watchPlaying = MutableStateFlow(false)
    val watchPlaying: StateFlow<Boolean> = _watchPlaying.asStateFlow()

    private val _watchTitle = MutableStateFlow("")
    val watchTitle: StateFlow<String> = _watchTitle.asStateFlow()

    private val _watchArtist = MutableStateFlow("")
    val watchArtist: StateFlow<String> = _watchArtist.asStateFlow()

    private val _watchHasVideo = MutableStateFlow(true)
    val watchHasVideo: StateFlow<Boolean> = _watchHasVideo.asStateFlow()

    private val _videoQueue = MutableStateFlow<List<Track>>(emptyList())
    val videoQueue: StateFlow<List<Track>> = _videoQueue.asStateFlow()

    private val _watchRoom = MutableStateFlow<String?>(null)
    val watchRoom: StateFlow<String?> = _watchRoom.asStateFlow()

    private val _watchRoomServer = MutableStateFlow<String?>(null)
    val watchRoomServer: StateFlow<String?> = _watchRoomServer.asStateFlow()

    private var prevMediaId: String? = null
    private var prevPos = 0L
    private var prevDur = 0L

    private val store = LibraryStore(app)
    private val trackById = HashMap<String, Track>()

    /** Запрос на воспроизведение, пока контроллер ещё поднимается. */
    private var pendingPlay: Pair<List<Track>, Int>? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            evaluateLeft()
            onChanged()
            currentTrack()?.let { recordHistory(it) }
            maybeExtendQueue()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = onChanged()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = onChanged()

        override fun onPlaybackStateChanged(playbackState: Int) {
            onChanged()
            if (playbackState == Player.STATE_ENDED) {
                when {
                    SettingsStore.pirat.value && _piratStage.value == PiratStage.FINAL -> {
                        // реклама доиграла - выходим из режима
                        disablePirat()
                    }
                    SettingsStore.pirat.value && _piratStage.value == PiratStage.NO_SEARCH -> {
                        // "система поиска" закончилась - снова играет Серега
                        _piratStage.value = PiratStage.LOCKED
                        _piratMessage.value = DEFAULT_PIRAT_HINT
                        playPiratPlaylist()
                    }
                    else -> maybeExtendQueue(play = true)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            onChanged()
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            _shuffle.value = enabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeat.value = repeatMode
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val title = _now.value?.title ?: "Трек"
            _toast.value = "$title: ${reasonFor(error)}"
        }
    }

    private fun reasonFor(error: androidx.media3.common.PlaybackException): String = when (error.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "нет соединения"

        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "недоступен или заблокирован"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "не найден"
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "повреждённый поток"

        else -> "не удалось загрузить"
    }

    fun clearToast() {
        _toast.value = null
    }

    init {
        _favorites.value = store.favorites()
        _playlists.value = store.playlists()
        _history.value = store.history()
        _disliked.value = store.disliked()
        _updateDismissed.value = appContext.getSharedPreferences("volna_update", android.content.Context.MODE_PRIVATE)
            .getString("dismissed", null)
        _videoQueue.value = store.videoQueue()
        refreshUpdateDownloadState()

        val token = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java),
        )
        // Сбрасываем журнал сервиса, чтобы видеть события именно этой попытки.
        appContext.getSharedPreferences("volna_diag", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("log", "")
            .putLong("serviceCreated", 0L)
            .putLong("sessionBuilt", 0L)
            .commit()
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            val controller = try {
                future.get()
            } catch (e: Exception) {
                val crash = runCatching {
                    appContext.getSharedPreferences("volna_crash", android.content.Context.MODE_PRIVATE)
                        .getString("last", null)
                }.getOrNull()
                val diag = appContext.getSharedPreferences("volna_diag", android.content.Context.MODE_PRIVATE)
                val created = diag.getLong("serviceCreated", 0L)
                val built = diag.getLong("sessionBuilt", 0L)
                val log = diag.getString("log", null).orEmpty()
                val diagText = "Сервис стартовал: " + (if (created > 0) "да" else "нет") +
                    ", сессия создана: " + (if (built > 0) "да" else "нет") +
                    (if (log.isNotBlank()) "\nЖурнал сервиса:" + log else "")
                val base = "Плеер: ${e.javaClass.simpleName}: ${e.message}\n$diagText"
                _playerError.value = if (crash.isNullOrBlank()) {
                    base
                } else {
                    base + "\n\nПоследний сбой:\n" + crash.take(700)
                }
                _toast.value = "Плеер: ${e.message}"
                return@addListener
            }
            controller.addListener(playerListener)
            _controller.value = controller
            _playerError.value = null
            refresh(controller)
            pendingPlay?.let { (tracks, idx) ->
                pendingPlay = null
                playTracks(tracks, idx)
            }
            viewModelScope.launch {
                while (isActive) {
                    delay(500)
                    _position.value = controller.currentPosition.coerceAtLeast(0L)
                    _duration.value = controller.duration.coerceAtLeast(0L)
                    _isPlaying.value = controller.isPlaying
                    prevMediaId = controller.currentMediaItem?.mediaId
                    prevPos = controller.currentPosition.coerceAtLeast(0L)
                    prevDur = controller.duration.coerceAtLeast(0L)
                }
            }
        }, ContextCompat.getMainExecutor(appContext))

        // Если сессия так и не подключилась - сообщаем об этом на экране.
        viewModelScope.launch {
            delay(8000)
            if (_controller.value == null && _playerError.value == null) {
                val diag = appContext.getSharedPreferences("volna_diag", android.content.Context.MODE_PRIVATE)
                val created = diag.getLong("serviceCreated", 0L)
                val built = diag.getLong("sessionBuilt", 0L)
                val log = diag.getString("log", null).orEmpty()
                _playerError.value = "Плеер не подключился (таймаут). " +
                    "Сервис: " + (if (created > 0) "да" else "нет") +
                    ", сессия: " + (if (built > 0) "да" else "нет") +
                    (if (log.isNotBlank()) "\nЖурнал сервиса:" + log else "")
            }
        }
    }

    private fun onChanged() {
        _controller.value?.let { refresh(it) }
    }

    private fun refresh(c: MediaController) {
        _isPlaying.value = c.isPlaying
        _duration.value = c.duration.coerceAtLeast(0L)
        _position.value = c.currentPosition.coerceAtLeast(0L)
        _currentIndex.value = c.currentMediaItemIndex
        _shuffle.value = c.shuffleModeEnabled
        _repeat.value = c.repeatMode

        val item = c.currentMediaItem
        _now.value = item?.let {
            val md = c.mediaMetadata
            val uri = it.localConfiguration?.uri
            val local = uri != null && (uri.scheme == "content" || uri.scheme == "file")
            NowPlaying(
                id = it.mediaId,
                title = md.title?.toString().orEmpty().ifBlank { "Без названия" },
                artist = md.artist?.toString().orEmpty(),
                album = md.albumTitle?.toString().orEmpty(),
                artworkUri = md.artworkUri?.toString(),
                isLocal = local,
            )
        }

        val list = ArrayList<QueueEntry>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) {
            val mediaItem = c.getMediaItemAt(i)
            val md = mediaItem.mediaMetadata
            val uri = mediaItem.localConfiguration?.uri
            val local = uri != null && (uri.scheme == "content" || uri.scheme == "file")
            list.add(
                QueueEntry(
                    id = mediaItem.mediaId,
                    title = md.title?.toString().orEmpty(),
                    artist = md.artist?.toString().orEmpty(),
                    artworkUri = md.artworkUri?.toString(),
                    isLocal = local,
                ),
            )
        }
        _queue.value = list
    }

    // ---- Управление воспроизведением ------------------------------------

    fun playTracks(tracks: List<Track>, startIndex: Int) {
        val c = _controller.value
        if (c == null) {
            if (tracks.isNotEmpty()) pendingPlay = tracks to startIndex
            _toast.value = "Плеер ещё запускается, попробуйте ещё раз"
            return
        }
        if (tracks.isEmpty()) return
        // выбранная песня - первой в очереди, остальные после неё
        val index = startIndex.coerceIn(0, tracks.size - 1)
        val ordered = tracks.drop(index) + tracks.take(index)
        styleAnchor = ordered[0]
        trackById.clear()
        for (t in ordered) trackById[t.id] = t
        val items = ordered.map { TrackMediaItem.toMediaItem(it) }
        c.setMediaItems(items, 0, 0L)
        c.prepare()
        c.play()
        recordHistory(ordered[0])
        runCatching { store.bump(OnlineRepository.signature(ordered[0]), 0) }
    }

    fun playSingle(track: Track) = playTracks(listOf(track), 0)

    /**
     * Выбор песни из поиска: она начинает играть, а очередь сразу
     * перестраивается на похожие (без ручного нажатия «Похожие»).
     */
    fun playFromSearch(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        val idx = index.coerceIn(0, tracks.lastIndex)
        val anchor = tracks[idx]
        playTracks(tracks, idx)
        rebuildSimilarQueue(anchor)
    }

    fun playQueueIndex(index: Int) {
        val c = _controller.value ?: return
        c.seekTo(index, 0L)
        c.play()
    }

    fun togglePlayPause() {
        if (SettingsStore.pirat.value) {
            quipNow()
            return
        }
        val c = _controller.value ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /** «Просмотр» — отдельный плеер: основная музыка ставится на паузу. */
    fun pauseForWatch() {
        runCatching { _controller.value?.pause() }
    }

    fun setWatch(url: String, positionMs: Long = 0L, playing: Boolean = true) {
        _watchUrl.value = url
        _watchPosition.value = positionMs.coerceAtLeast(0L)
        _watchPlaying.value = playing
        _watchTitle.value = ""
        _watchArtist.value = ""
        _watchHasVideo.value = true
    }

    fun saveWatchState(
        url: String?,
        positionMs: Long,
        playing: Boolean,
        title: String,
        artist: String,
        hasVideo: Boolean,
    ) {
        if (!url.isNullOrBlank()) _watchUrl.value = url
        _watchPosition.value = positionMs.coerceAtLeast(0L)
        _watchPlaying.value = playing
        _watchTitle.value = title
        _watchArtist.value = artist
        _watchHasVideo.value = hasVideo
    }

    fun setWatchRoom(room: String?, server: String?) {
        _watchRoom.value = room?.takeIf { it.isNotBlank() }
        _watchRoomServer.value = server?.takeIf { it.isNotBlank() }
    }

    fun addVideoToQueue(track: Track) {
        val list = _videoQueue.value.toMutableList()
        if (list.none { LibraryStore.sameTrack(it, track) }) {
            list.add(track)
            _videoQueue.value = list
            runCatching { store.saveVideoQueue(list) }
        }
    }

    fun removeVideoFromQueue(track: Track) {
        val list = _videoQueue.value.filterNot { LibraryStore.sameTrack(it, track) }
        _videoQueue.value = list
        runCatching { store.saveVideoQueue(list) }
    }

    fun clearVideoQueue() {
        _videoQueue.value = emptyList()
        runCatching { store.saveVideoQueue(emptyList()) }
    }

    fun next() {
        _controller.value?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        _controller.value?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        _controller.value?.seekTo(ms.coerceAtLeast(0L))
    }

    fun toggleShuffle() {
        val c = _controller.value ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = _controller.value ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---- Поиск и источники ----------------------------------------------

    /** serviceId: -1 - все источники, 0 - YouTube, 1 - SoundCloud. */
    fun search(query: String, serviceId: Int) {
        val q = query.trim()
        if (q.isEmpty()) return

        val norm = q.lowercase().replace("ё", "е").replace(Regex("\\s+"), "")
        // Пасхалка "Серега Пират"
        if (norm.contains("серегапират") || (norm.contains("серега") && norm.contains("пират"))) {
            enablePirat()
            return
        }
        if (SettingsStore.pirat.value) {
            handlePiratInput(norm)
            return
        }

        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            try {
                val results: List<Track> = if (serviceId == -1) {
                    val yt = async {
                        runCatching { OnlineRepository.search(ServiceList.YouTube, q) }
                            .getOrDefault(emptyList())
                    }
                    val sc = async {
                        runCatching { OnlineRepository.search(ServiceList.SoundCloud, q) }
                            .getOrDefault(emptyList())
                    }
                    (yt.await() + sc.await()).let { OnlineRepository.dedupe(it) }
                } else {
                    val service = if (serviceId == 1) ServiceList.SoundCloud else ServiceList.YouTube
                    OnlineRepository.search(service, q)
                }
                _searchResults.value = results
                if (results.isEmpty()) _searchError.value = "Ничего не найдено"
            } catch (e: Exception) {
                _searchResults.value = emptyList()
                _searchError.value = e.message ?: "Ошибка поиска"
            } finally {
                _searching.value = false
            }
        }
    }

    // ---- Избранное и плейлисты ------------------------------------------

    fun currentTrack(): Track? {
        val id = _now.value?.id ?: return null
        trackById[id]?.let { return it }
        // восстановление после перезапуска: строим трек из текущего элемента
        val item = _controller.value?.currentMediaItem ?: return null
        return trackFromMediaItem(item).also { trackById[id] = it }
    }

    fun isFavorite(track: Track): Boolean =
        _favorites.value.any { LibraryStore.sameTrack(it, track) }

    fun toggleFavorite(track: Track) {
        val was = isFavorite(track)
        _favorites.value = store.toggleFavorite(track)
        if (!was) runCatching { store.bump(OnlineRepository.signature(track), 3) }
    }

    fun toggleFavoriteCurrent() {
        currentTrack()?.let { toggleFavorite(it) }
    }

    fun createPlaylist(name: String) {
        _playlists.value = store.createPlaylist(name)
    }

    fun deletePlaylist(id: String) {
        _playlists.value = store.deletePlaylist(id)
    }

    fun addToPlaylist(id: String, track: Track) {
        _playlists.value = store.addToPlaylist(id, track)
        runCatching { store.bump(OnlineRepository.signature(track), 4) }
    }

    fun addCurrentToPlaylist(id: String) {
        currentTrack()?.let { addToPlaylist(id, it) }
    }

    fun removeFromPlaylist(id: String, track: Track) {
        _playlists.value = store.removeFromPlaylist(id, track)
    }

    fun playPlaylist(playlist: Playlist) {
        if (playlist.tracks.isNotEmpty()) playTracks(playlist.tracks, 0)
    }

    fun clearUserData() {
        store.clear()
        _favorites.value = emptyList()
        _playlists.value = emptyList()
        _history.value = emptyList()
        _disliked.value = emptySet()
        _trendTracks.value = emptyMap()
        _wave.value = emptyList()
    }

    /** Очистить только кэши, не трогая избранное/плейлисты/историю. */
    fun clearCaches() {
        _trendTracks.value = emptyMap()
        _wave.value = emptyList()
        runCatching { store.clearTrends() }
    }

    // ---- Обновления ------------------------------------------------------

    fun checkForUpdates(force: Boolean = false) {
        if (_updateChecking.value) return
        viewModelScope.launch {
            _updateChecking.value = true
            _updateError.value = null
            runCatching { UpdateChecker.check(appContext, force) }
                .onSuccess { result ->
                    if (result.success) {
                        _updateInfo.value = result.info
                        refreshUpdateDownloadState()
                    } else if (result.info != null) {
                        _updateInfo.value = result.info
                    }
                }
                .onFailure { _updateError.value = it.message ?: "Не удалось проверить обновления" }
            _updateChecking.value = false
        }
    }

    fun dismissUpdate() {
        val info = _updateInfo.value ?: return
        val id = info.buildId.ifBlank { info.version }
        _updateDismissed.value = id
        appContext.getSharedPreferences("volna_update", android.content.Context.MODE_PRIVATE)
            .edit().putString("dismissed", id).apply()
    }

    fun downloadAndInstallUpdate() {
        val info = _updateInfo.value ?: return
        if (info.apkUrl == null) {
            UpdateChecker.openRelease(appContext, info)
            return
        }
        if (UpdateDownloadManager.isReadyFor(appContext, info)) {
            installUpdate()
            return
        }
        if (UpdateDownloadManager.isDownloading(appContext, info)) {
            _toast.value = "Обновление уже скачивается в уведомлениях"
            return
        }
        _updateReady.value = false
        _updateError.value = null
        dismissUpdate()
        runCatching {
            UpdateDownloadManager.enqueue(appContext, info)
        }.onSuccess {
            _toast.value = "Скачивание идёт в уведомлениях"
            _updateDownloading.value = true
            _updateProgress.value = 0f
            startUpdatePolling(info)
        }.onFailure {
            _updateError.value = it.message ?: "Не удалось начать скачивание"
        }
    }

    fun installUpdate() {
        val info = _updateInfo.value
        if (info == null) {
            _updateError.value = "Нет доступного обновления"
            return
        }
        when (UpdateDownloadManager.installCurrent(appContext, info)) {
            UpdateDownloadManager.InstallResult.OK ->
                _toast.value = "Открываю установщик"
            UpdateDownloadManager.InstallResult.NEED_PERMISSION ->
                _updateError.value = "Разрешите установку из этого приложения и нажмите ещё раз"
            UpdateDownloadManager.InstallResult.STALE -> {
                _updateReady.value = false
                _updateProgress.value = null
                _updateDownloading.value = false
                _updateError.value =
                    "Скачанная сборка устарела или уже установлена. Скачайте обновление заново"
            }
            UpdateDownloadManager.InstallResult.FAILED ->
                _updateError.value = "Не удалось открыть установщик"
        }
    }

    fun refreshUpdateDownloadState() {
        val info = _updateInfo.value
        if (info == null) {
            _updateReady.value = false
            _updateDownloading.value = false
            _updateProgress.value = null
            return
        }
        val state = UpdateDownloadManager.state(appContext, info)
        _updateReady.value = state == UpdateDownloadManager.State.DONE
        _updateDownloading.value = state == UpdateDownloadManager.State.RUNNING
        _updateProgress.value = when (state) {
            UpdateDownloadManager.State.RUNNING ->
                UpdateDownloadManager.progress(appContext, info) ?: 0f
            UpdateDownloadManager.State.DONE -> 1f
            else -> null
        }
        if (state == UpdateDownloadManager.State.FAILED) {
            _updateError.value =
                "Скачанный файл устарел или не подходит. Скачайте обновление заново"
        }
    }

    private fun startUpdatePolling(info: UpdateInfo) {
        viewModelScope.launch {
            var missing = 0
            while (true) {
                delay(1000)
                val current = _updateInfo.value
                if (current?.buildId != info.buildId || current.apkUrl != info.apkUrl) {
                    _updateDownloading.value = false
                    break
                }
                val state = UpdateDownloadManager.state(appContext, info)
                _updateProgress.value = when (state) {
                    UpdateDownloadManager.State.RUNNING ->
                        UpdateDownloadManager.progress(appContext, info) ?: 0f
                    UpdateDownloadManager.State.DONE -> 1f
                    else -> null
                }
                if (state == UpdateDownloadManager.State.DONE) {
                    _updateReady.value = true
                    _updateDownloading.value = false
                    _toast.value = "Обновление скачано — нажмите «Установить»"
                    break
                }
                if (state == UpdateDownloadManager.State.FAILED) {
                    _updateDownloading.value = false
                    _updateError.value =
                        "Скачанный файл устарел или не подходит. Скачайте обновление заново"
                    break
                }
                if (state == UpdateDownloadManager.State.NONE) {
                    missing++
                    if (missing >= 3) {
                        _updateDownloading.value = false
                        _updateError.value = "Скачивание не удалось"
                        break
                    }
                } else {
                    missing = 0
                }
            }
        }
    }

    fun openUpdateRelease() {
        _updateInfo.value?.let { UpdateChecker.openRelease(appContext, it) }
    }

    fun clearUpdateError() {
        _updateError.value = null
    }

    /** Включить режим разработчика (долгое нажатие на кнопку настроек). */
    fun enableDeveloper() {
        SettingsStore.setDeveloper(true)
        _toast.value = "Режим разработчика включён"
    }

    /** Переключить режим разработчика (долгое нажатие ~4 сек). */
    fun toggleDeveloper() {
        val now = !SettingsStore.developer.value
        SettingsStore.setDeveloper(now)
        _toast.value = if (now) "Режим разработчика включён" else "Режим разработчика выключен"
    }

    private fun recordHistory(track: Track) {
        if (SettingsStore.pirat.value) return
        runCatching { _history.value = store.addHistory(track) }
    }

    fun clearHistory() {
        runCatching {
            store.clearHistory()
            _history.value = emptyList()
        }
    }

    /** Перечитать данные из хранилища (например, после лайка из уведомления). */
    fun reloadLibrary() {
        runCatching {
            _favorites.value = store.favorites()
            _playlists.value = store.playlists()
            _history.value = store.history()
            _disliked.value = store.disliked()
        }
    }

    /** «Моя волна» - подбор под вкус (лайки, история, скипы, язык, артисты). */
    fun loadWave() {
        if (_loadingWave.value) return
        viewModelScope.launch {
            _loadingWave.value = true
            try {
                val hits = _trendTracks.value["new"].orEmpty().ifEmpty {
                    val t = runCatching { OnlineRepository.trending("new") }.getOrDefault(emptyList())
                    if (t.isNotEmpty()) _trendTracks.value = _trendTracks.value + ("new" to t)
                    t
                }
                val taste = buildTaste()
                val online = runCatching { OnlineRepository.myWave(taste, hits) }.getOrDefault(emptyList())
                if (online.isNotEmpty()) {
                    _wave.value = online
                    _waveLocal.value = false
                } else {
                    // нет сети / ничего не нашли - собираем из музыки на телефоне
                    val local = _localTracks.value.ifEmpty {
                        runCatching { LocalLibrary.load(appContext) }.getOrDefault(emptyList())
                    }
                    _wave.value = runCatching { OnlineRepository.localWave(taste, local) }
                        .getOrDefault(emptyList())
                    _waveLocal.value = true
                }
            } catch (_: Exception) {
            } finally {
                _loadingWave.value = false
            }
        }
    }

    private fun buildTaste(): OnlineRepository.MyWaveTaste {
        val favs = _favorites.value
        val hist = _history.value
        val stats = store.stats()
        val disliked = _disliked.value

        fun affinity(t: Track): Double {
            val sig = OnlineRepository.signature(t)
            val st = stats[sig]
            var a = 0.0
            if (favs.any { LibraryStore.sameTrack(it, t) }) a += 3.0
            if (st != null) {
                // прослушивания, дослушал, скипнул, лайки, добавления в плейлист
                a += st[0] * 1.0 + st[1] * 2.0 - st[2] * 1.5 + st[3] * 2.0 + st[4] * 1.5
            }
            return a
        }

        val all = (favs + hist).distinctBy { OnlineRepository.signature(it) }
        val seeds = all
            .filter { OnlineRepository.signature(it) !in disliked }
            .sortedByDescending { affinity(it) }
            .take(8)

        val artistCount = HashMap<String, Int>()
        for (t in favs + hist) {
            if (t.artist.isNotBlank() && OnlineRepository.signature(t) !in disliked) {
                artistCount[t.artist] = (artistCount[t.artist] ?: 0) + 1
            }
        }
        val artists = artistCount.entries.sortedByDescending { it.value }.map { it.key }.take(8)

        val heard = (stats.keys + hist.map { OnlineRepository.signature(it) }).toSet()
        val ruCount = (favs + hist).count { MusicFilter.isCyrillic(it.title + " " + it.artist) }
        val preferRu = (favs + hist).isNotEmpty() && ruCount * 2 >= (favs + hist).size

        val moodCount = HashMap<String, Int>()
        val soundCount = HashMap<String, Int>()
        for (t in favs + hist) {
            for (m in MusicFilter.moods(t.title + " " + t.artist)) {
                moodCount[m] = (moodCount[m] ?: 0) + 1
            }
            for (s in MusicFilter.soundTags(t.title + " " + t.artist)) {
                soundCount[s] = (soundCount[s] ?: 0) + 1
            }
        }
        val moods = moodCount.entries.sortedByDescending { it.value }.map { it.key }.take(4).toSet()
        val sounds = soundCount.entries.sortedByDescending { it.value }.map { it.key }.take(3).toSet()
        val seedYears = all.mapNotNull { MusicFilter.yearIn(it.title) }.distinct()

        // контекст: время суток влияет на настроение подборки
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val contextMoods = when (hour) {
            in 0..5 -> setOf("calm", "sad")
            in 6..11 -> setOf("party", "electronic")
            in 12..17 -> setOf("party", "rap")
            else -> setOf("love", "calm")
        }

        return OnlineRepository.MyWaveTaste(
            seeds = seeds,
            artists = artists,
            disliked = disliked,
            heard = heard,
            preferRu = preferRu,
            moods = moods,
            contextMoods = contextMoods,
            soundTags = sounds,
            seedYears = seedYears,
            languageFilter = SettingsStore.languageFilter.value,
            diversity = SettingsStore.diversity.value,
        )
    }

    /** Оценка трека, который только что закончился: дослушал или скипнул. */
    private fun evaluateLeft() {
        val id = prevMediaId ?: return
        val t = trackById[id] ?: return
        if (prevDur <= 0) return
        val sig = OnlineRepository.signature(t)
        val ratio = prevPos.toFloat() / prevDur
        runCatching {
            if (ratio >= 0.85f) {
                store.bump(sig, 1)
            } else if (ratio <= 0.2f) {
                store.bump(sig, 2)
            }
        }
    }

    fun isDisliked(track: Track): Boolean =
        OnlineRepository.signature(track) in _disliked.value

    fun toggleDislike(track: Track) {
        _disliked.value = store.toggleDislike(OnlineRepository.signature(track))
    }

    /** Скачать текущий трек в память телефона (Музыка/Волна). */
    fun downloadCurrent() {
        val track = currentTrack() ?: return
        if (track.isLocal) {
            runCatching { Toast.makeText(appContext, "Этот трек уже на телефоне", Toast.LENGTH_SHORT).show() }
            return
        }
        viewModelScope.launch {
            _downloadProgress.value = 0f
            runCatching { Toast.makeText(appContext, "Скачиваю: ${track.title}", Toast.LENGTH_SHORT).show() }
            val result = runCatching {
                TrackDownloader.download(appContext, track) { p -> _downloadProgress.value = p }
            }
            _downloadProgress.value = null
            val msg = result.fold(
                onSuccess = { "Скачано в Музыка/Волна" },
                onFailure = { "Не удалось скачать: ${it.message ?: it.javaClass.simpleName}" },
            )
            runCatching {
                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Играет трек по прямой ссылке на страницу (YouTube, VK, SoundCloud и т.п.). */
    fun playUrl(url: String) {
        val link = url.trim()
        if (link.isEmpty()) return
        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            try {
                val resolved = OnlineRepository.resolve(link)
                playSingle(
                    Track(
                        id = link,
                        title = resolved.title.ifBlank { link },
                        artist = resolved.artist,
                        durationMs = resolved.durationMs,
                        pageUrl = link,
                        artworkUri = resolved.thumbUrl,
                        source = TrackSource.OTHER,
                    ),
                )
            } catch (e: Exception) {
                _searchError.value = e.message ?: "Не удалось воспроизвести ссылку"
            } finally {
                _searching.value = false
            }
        }
    }

    fun loadLocal() {
        if (_loadingLocal.value) return
        viewModelScope.launch {
            _loadingLocal.value = true
            try {
                _localTracks.value = LocalLibrary.load(appContext)
            } catch (_: Exception) {
                _localTracks.value = emptyList()
            } finally {
                _loadingLocal.value = false
            }
        }
    }

    fun loadTrend(id: String) {
        if (id in _trendLoading.value) return
        // Сначала показываем кэш (мгновенно), потом обновляем в фоне.
        val cached = store.loadTrend(id)
        if (cached.isNotEmpty()) {
            _trendTracks.value = _trendTracks.value + (id to cached)
        }
        viewModelScope.launch {
            _trendLoading.value = _trendLoading.value + id
            try {
                val list = OnlineRepository.trending(id)
                if (list.isNotEmpty()) {
                    _trendTracks.value = _trendTracks.value + (id to list)
                    store.saveTrend(id, list)
                }
            } catch (_: Exception) {
            } finally {
                _trendLoading.value = _trendLoading.value - id
            }
        }
    }

    // ---- Очередь ---------------------------------------------------------

    fun removeFromQueue(index: Int) {
        if (SettingsStore.pirat.value) {
            quipNow()
            return
        }
        val c = _controller.value ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun queueTrackAt(index: Int): Track? {
        val c = _controller.value ?: return null
        if (index !in 0 until c.mediaItemCount) return null
        return trackFromMediaItem(c.getMediaItemAt(index))
    }

    private fun trackFromMediaItem(item: MediaItem): Track {
        val uri = item.localConfiguration?.uri
        val md = item.mediaMetadata
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
        trackById[item.mediaId]?.let { return it }
        return Track(
            id = item.mediaId,
            title = md.title?.toString().orEmpty().ifBlank { "Без названия" },
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            pageUrl = page,
            localUri = local,
            artworkUri = md.artworkUri?.toString(),
            source = if (page != null) TrackSource.YOUTUBE else TrackSource.LOCAL,
        )
    }

    /** Кнопка «Обновить» в очереди - перестроить очередь под текущий трек. */
    fun refreshQueue() = rebuildSimilarQueue()

    /** Подписи недавно прослушанного (последние N из истории) - чтобы не повторять сразу. */
    private fun recentlyHeard(limit: Int = 10): Set<String> =
        _history.value.take(limit)
            .map { OnlineRepository.signature(it) }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Перестраивает очередь: текущий трек - первым, дальше похожие,
     * без повторов и без уже прослушанных. Текущий трек продолжает играть.
     */
    private fun rebuildSimilarQueue(anchor: Track? = null) {
        if (SettingsStore.pirat.value) return
        val c = _controller.value ?: return
        val track = anchor ?: currentTrack() ?: return
        styleAnchor = track

        val generation = ++similarGeneration
        similarJob?.cancel()
        loadingSimilar = true
        similarJob = viewModelScope.launch {
            try {
                val related = OnlineRepository.related(track)
                if (generation != similarGeneration) return@launch
                val recent = recentlyHeard()
                val cc = _controller.value ?: return@launch
                val wasPlaying = cc.isPlaying
                val position = cc.currentPosition.coerceAtLeast(0L)
                val seen = HashSet<String>()
                seen.add(OnlineRepository.signature(track))
                val fresh = related.asSequence()
                    .filter { it.id != track.id }
                    .filter { OnlineRepository.signature(it) !in _disliked.value }
                    .filter {
                        val sig = OnlineRepository.signature(it)
                        sig.isBlank() || (sig !in recent && seen.add(sig))
                    }
                    .take(40)
                    .toList()
                val ordered = ArrayList<Track>(fresh.size + 1)
                ordered.add(track)
                ordered.addAll(fresh)
                trackById.clear()
                for (t in ordered) trackById[t.id] = t
                val items = ordered.map { TrackMediaItem.toMediaItem(it) }
                cc.setMediaItems(items, 0, position)
                cc.prepare()
                if (wasPlaying) cc.play()
            } catch (_: Exception) {
            } finally {
                if (generation == similarGeneration) {
                    loadingSimilar = false
                    similarJob = null
                }
            }
        }
    }

    // ---- Похожие треки («радио») ----------------------------------------

    private var loadingSimilar = false
    private var similarJob: kotlinx.coroutines.Job? = null
    private var similarGeneration = 0

    /** Песня-«якорь» сессии: под неё держим автопродление, чтобы не уезжать в другой жанр. */
    private var styleAnchor: Track? = null

    /** Кнопка «Похожие» - перестроить очередь под текущий трек. */
    fun addSimilarNow() = rebuildSimilarQueue()

    private fun maybeExtendQueue(play: Boolean = false, force: Boolean = false) {
        if (SettingsStore.pirat.value) return // в режиме пасхалки очередь не расширяем
        val c = _controller.value ?: return
        if (c.mediaItemCount == 0) return
        val nearEnd = c.currentMediaItemIndex >= c.mediaItemCount - 2
        if (!force && !play && !nearEnd) return
        if (loadingSimilar) return
        // Ориентир - «якорь» сессии; текущий трек добавляем для лёгкого смещения стиля.
        val seeds = ArrayList<Track>()
        styleAnchor?.let { seeds.add(it) }
        currentTrack()?.let { cur -> if (seeds.none { it.id == cur.id }) seeds.add(cur) }
        if (seeds.isEmpty()) return

        loadingSimilar = true
        viewModelScope.launch {
            try {
                val pool = LinkedHashMap<String, Track>()
                for (s in seeds) {
                    runCatching { OnlineRepository.related(s) }.getOrDefault(emptyList())
                        .forEach { pool.putIfAbsent(it.id, it) }
                    if (pool.size >= 30) break
                }
                val related = pool.values.toList()
                val recent = recentlyHeard()
                val cc = _controller.value ?: return@launch
                val existing = HashSet<String>()
                val existingSigs = HashSet<String>()
                for (i in 0 until cc.mediaItemCount) {
                    val mi = cc.getMediaItemAt(i)
                    existing.add(mi.mediaId)
                    val sig = OnlineRepository.signature(
                        Track(
                            id = mi.mediaId,
                            title = mi.mediaMetadata.title?.toString().orEmpty(),
                            artist = mi.mediaMetadata.artist?.toString().orEmpty(),
                        ),
                    )
                    if (sig.isNotEmpty()) existingSigs.add(sig)
                }
                val items = related
                    .asSequence()
                    .filter { it.id !in existing }
                    .filter { OnlineRepository.signature(it) !in _disliked.value }
                    .filter { OnlineRepository.signature(it) !in recent }
                    .filter {
                        val sig = OnlineRepository.signature(it)
                        sig.isEmpty() || sig !in existingSigs
                    }
                    .map { TrackMediaItem.toMediaItem(it) }
                    .take(20)
                    .toList()
                if (items.isNotEmpty()) {
                    cc.addMediaItems(items)
                    for (t in related) trackById[t.id] = t
                    if (play) {
                        cc.seekToNextMediaItem()
                        cc.prepare()
                        cc.play()
                    }
                }
            } catch (_: Exception) {
            } finally {
                loadingSimilar = false
            }
        }
    }

    // ---- Пасхалка "Серега Пират" ----------------------------------------

    fun enablePirat() {
        SettingsStore.setPirat(true)
        _piratStage.value = PiratStage.LOCKED
        _piratMessage.value = DEFAULT_PIRAT_HINT
        _piratQuip.value = null
        playPiratPlaylist()
    }

    private fun playPiratPlaylist() {
        viewModelScope.launch {
            // только песни Сереги Пирата, повторы допустимы
            val list = runCatching { OnlineRepository.piratSongs() }.getOrDefault(emptyList())
            if (list.isNotEmpty()) playTracks(list, 0)
            _controller.value?.apply {
                volume = 1f
                repeatMode = Player.REPEAT_MODE_ALL
                play()
            }
        }
    }

    fun disablePirat() {
        SettingsStore.setPirat(false)
        _piratStage.value = PiratStage.LOCKED
        _piratMessage.value = DEFAULT_PIRAT_HINT
        _piratQuip.value = null
        _searchError.value = "Свобода!"
        runCatching {
            _controller.value?.apply {
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 1f
                stop()
                clearMediaItems()
            }
        }
    }

    /** Скрытый выход: свобода/спасите/помогите -> да -> пожалуйста -> видео. */
    private fun handlePiratInput(norm: String) {
        when (_piratStage.value) {
            PiratStage.LOCKED -> {
                if (PIRAT_FREE_WORDS.any { norm.contains(it) }) {
                    _piratStage.value = PiratStage.CONFIRM
                    _piratMessage.value = "Ты уверен!!!!"
                }
            }

            PiratStage.CONFIRM -> {
                when {
                    norm == "да" || norm == "yes" || norm.startsWith("да") -> {
                        _piratStage.value = PiratStage.PLEASE
                        _piratMessage.value = "нет)"
                    }
                    norm == "нет" || norm.startsWith("нет") -> {
                        _piratStage.value = PiratStage.NO_SEARCH
                        _piratMessage.value = "Система поиска пидорасов активирована"
                        playSearchSystemVideo()
                    }
                }
            }

            PiratStage.PLEASE -> {
                if (norm.contains("пожалуйста") || norm.contains("пожайлуста") ||
                    norm.contains("пожалуста") || norm.contains("please")
                ) {
                    _piratStage.value = PiratStage.FINAL
                    _piratMessage.value = "Ладно, так уж и быть"
                    viewModelScope.launch {
                        delay(1500)
                        playPiratVideo()
                        // страховка, если ролик не завершится сам
                        delay(6 * 60 * 1000)
                        if (SettingsStore.pirat.value && _piratStage.value == PiratStage.FINAL) {
                            disablePirat()
                        }
                    }
                }
            }

            PiratStage.FINAL -> Unit

            PiratStage.NO_SEARCH -> Unit
        }
    }

    fun quipNow() {
        _piratQuip.value = PIRAT_QUIPS.random()
    }

    private fun playSearchSystemVideo() {
        viewModelScope.launch {
            val video = Track(
                id = "pirat_search_Gkw7Iw7O7ME",
                title = "Система поиска пидорасов активирована",
                artist = "",
                pageUrl = "https://www.youtube.com/watch?v=Gkw7Iw7O7ME",
                source = TrackSource.YOUTUBE,
            )
            playSingle(video)
            _controller.value?.apply {
                volume = 1f
                repeatMode = Player.REPEAT_MODE_OFF
                play()
            }
        }
    }

    private fun playPiratVideo() {
        viewModelScope.launch {
            val ad = Track(
                id = "pirat_ad_laUDFcs3rcI",
                title = "Реклама",
                artist = "",
                pageUrl = "https://www.youtube.com/watch?v=laUDFcs3rcI",
                source = TrackSource.YOUTUBE,
            )
            playSingle(ad)
            _controller.value?.apply {
                volume = 1f
                repeatMode = Player.REPEAT_MODE_OFF
                play()
            }
        }
    }

    override fun onCleared() {
        _controller.value?.release()
        _controller.value = null
        super.onCleared()
    }
}
