package com.volna.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

/**
 * Поиск, тренды и извлечение аудио (NewPipeExtractor: YouTube, SoundCloud).
 *
 * Подбор музыки построен на «факторах» (исполнитель, язык, настроение/жанр,
 * характер звука, эпоха, длительность), а не на совпадении слов в названии и
 * не на списках конкретных песен. Тренды собираются из нескольких бесплатных
 * источников без ключей: живые чарты YouTube/SoundCloud (kiosk), Shazam,
 * Apple Music и Deezer.
 */
object OnlineRepository {

    private const val MAX_SONG_SEC = 600L

    // ---- Поиск -----------------------------------------------------------

    suspend fun search(
        service: StreamingService,
        query: String,
        limit: Int = 40,
    ): List<Track> = withContext(Dispatchers.IO) {
        val first = searchRaw(service, query, limit)
        val filtered = first.filter { looksLikeSong(it) }
        val chosen = if (filtered.size >= 5) {
            filtered
        } else {
            val extra = searchRaw(service, "$query трек", limit)
            val merged = dedupe(first + extra).filter { looksLikeSong(it) }
            if (merged.isNotEmpty()) merged else dedupe(first).filter { looksLikeSong(it) }
        }
        spread(
            capPerArtist(
                dedupe(chosen.sortedByDescending { songPriority(it) }),
                2,
            ),
        ).take(limit)
    }

    /** Поиск видео для отдельного плеера «Просмотр». */
    suspend fun searchVideos(query: String, limit: Int = 24): List<Track> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val service = ServiceList.YouTube
            val extractor = runCatching {
                service.getSearchExtractor(
                    query,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS),
                    "",
                )
            }.getOrElse { service.getSearchExtractor(query) }
            extractor.fetchPage()
            val info = SearchInfo.getInfo(extractor)
            info.relatedItems
                .asSequence()
                .filterIsInstance<StreamInfoItem>()
                .filter { it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.AUDIO_STREAM }
                .take(limit)
                .map { it.toTrack(service) }
                .toList()
        }

    /** Приоритет: официальное аудио (topic) и формат "Исполнитель - Название". */
    private fun songPriority(t: Track): Int {
        var p = 0
        if (t.artist.lowercase().contains("topic")) p += 2
        if (t.title.contains(" - ")) p += 1
        return p
    }

    private fun searchRaw(service: StreamingService, query: String, limit: Int): List<Track> {
        // Для YouTube ищем именно в разделе «Музыка» (music_songs): так в
        // выдачу не попадают подкасты, плейлисты и обычные видео.
        val extractor = if (service.serviceId == ServiceList.YouTube.serviceId) {
            runCatching {
                service.getSearchExtractor(
                    query,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    "",
                )
            }.getOrElse { service.getSearchExtractor(query) }
        } else {
            service.getSearchExtractor(query)
        }
        extractor.fetchPage()
        val info = SearchInfo.getInfo(extractor)
        return info.relatedItems
            .asSequence()
            .filterIsInstance<StreamInfoItem>()
            .filter {
                // Только обычные/аудио-треки, без прямых эфиров, Shorts и
                // постов, которые иногда приходят вместе с выдачей.
                (it.streamType == StreamType.VIDEO_STREAM ||
                    it.streamType == StreamType.AUDIO_STREAM) &&
                    !it.isShortFormContent
            }
            .take(limit)
            .map { it.toTrack(service) }
            .toList()
    }

    // ---- Тренды: несколько живых бесплатных источников -------------------
    //
    //  * kiosk YouTube ("trending_music"/"Trending") и SoundCloud ("New & hot")
    //    — это буквально текущие чарты, без API-ключей;
    //  * Shazam top-200 по региону (что реально ищут люди);
    //  * Apple Music RSS "most-played" по стране;
    //  * Deezer chart (открытый REST, без ключа).
    // Названия/исполнители из чартов затем находятся в YouTube/SoundCloud,
    // поэтому вкладка не «тусклая» и не «прошлый век».

    private fun currentYear(): String =
        Calendar.getInstance().get(Calendar.YEAR).toString()

    /** Треки из живого чарта-«киоска» сервиса. */
    private fun kioskTracks(service: StreamingService, preferred: List<String>): List<Track> {
        val kioskList = service.kioskList
        val available = kioskList.availableKiosks
        val id = preferred.firstOrNull { available.contains(it) } ?: kioskList.defaultKioskId
        if (id.isNullOrBlank()) return emptyList()
        val extractor = kioskList.getExtractorById(id, null)
        extractor.fetchPage()
        val info = KioskInfo.getInfo(extractor)
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { it.toTrack(service) }
    }

    /** Чарт Shazam: region = "russia" или "world" (то, что сейчас в моде). */
    private fun shazamChart(region: String): List<Pair<String, String>> {
        val url = "https://www.shazam.com/services/charts/csv/top-200/$region"
        val body = runCatching { NewPipe.getDownloader().get(url).responseBody() }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val re = Regex("^\\d+,\"([^\"]*)\",\"([^\"]*)\"")
        for (line in body.lineSequence()) {
            val m = re.find(line.trim()) ?: continue
            val artist = m.groupValues[1].trim()
            val title = m.groupValues[2].trim()
            if (title.isNotBlank()) out.add(artist to title)
        }
        return out
    }

    /** Чарт самых популярных песен страны (Apple Music RSS). */
    private fun appleChart(country: String, limit: Int): List<Pair<String, String>> {
        val url = "https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/$limit/songs.json"
        val body = runCatching { NewPipe.getDownloader().get(url).responseBody() }.getOrNull()
            ?: return emptyList()
        val results = runCatching {
            JSONObject(body).getJSONObject("feed").getJSONArray("results")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>(results.length())
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val artist = item.optString("artistName")
            val title = item.optString("name")
            if (title.isNotBlank()) out.add(artist to title)
        }
        return out
    }

    /** Чарт Deezer (открытый REST, без ключа). */
    private fun deezerChart(limit: Int): List<Pair<String, String>> {
        val url = "https://api.deezer.com/chart/0/tracks?limit=$limit"
        val body = runCatching { NewPipe.getDownloader().get(url).responseBody() }.getOrNull()
            ?: return emptyList()
        val data = runCatching { JSONObject(body).getJSONArray("data") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Pair<String, String>>(data.length())
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val title = o.optString("title")
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            if (title.isNotBlank()) out.add(artist to title)
        }
        return out
    }

    /**
     * Тренды: "new" - русские, "hot" - зарубежные.
     * Собираем живые чарты и находим песни в YouTube/SoundCloud. Никаких
     * зашитых названий.
     */
    suspend fun trending(kind: String): List<Track> = withContext(Dispatchers.IO) {
        val wantRu = kind != "hot"
        val out = LinkedHashMap<String, Track>()

        fun putAll(list: List<Track>) {
            for (t in list) if (looksLikeSong(t)) out.putIfAbsent(t.id, t)
        }

        // 1) живые чарты сервисов
        val kiosks = coroutineScope {
            listOf(
                async { runCatching { kioskTracks(ServiceList.YouTube, listOf("trending_music", "Trending")) }.getOrDefault(emptyList()) },
                async { runCatching { kioskTracks(ServiceList.SoundCloud, listOf("New & hot", "Charts")) }.getOrDefault(emptyList()) },
            ).awaitAll()
        }
        kiosks.forEach { putAll(it) }

        // 2) чарты «что ищут люди» -> находим песни в YouTube/SoundCloud
        val pairs = ArrayList<Pair<String, String>>()
        val region = if (wantRu) "russia" else "world"
        runCatching { shazamChart(region) }.getOrDefault(emptyList()).let { pairs += it }
        runCatching { appleChart(if (wantRu) "ru" else "us", 25) }.getOrDefault(emptyList()).let { pairs += it }
        runCatching { deezerChart(50) }.getOrDefault(emptyList()).let { pairs += it }
        val chart = pairs
            .filter { MusicFilter.isCyrillic(it.first + " " + it.second) == wantRu }
            .ifEmpty { pairs }
            .take(24)
        if (chart.isNotEmpty()) {
            val resolved = coroutineScope {
                chart.map { (artist, title) ->
                    async { runCatching { findSong(artist, title) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
            resolved.forEach { if (looksLikeSong(it)) out.putIfAbsent(it.id, it) }
        }

        if (out.isNotEmpty()) {
            // один стиль/язык, без «солянки»: фильтруем по языку вкладки
            val list = out.values.toList()
            val langFiltered = list.filter {
                MusicFilter.isCyrillic(it.title + " " + it.artist) == wantRu
            }
            val basis = langFiltered.ifEmpty { list }
            return@withContext spread(
                capPerArtist(
                    dedupe(basis.sortedByDescending { songPriority(it) }),
                    2,
                ),
            ).take(60)
        }

        // 3) запасной вариант: общие запросы (год берём текущий, без зашитых песен)
        val year = currentYear()
        val queries = if (wantRu) {
            listOf("новинки музыки $year", "новые русские песни $year", "популярные песни $year")
        } else {
            listOf("new songs $year", "top hits $year", "popular music $year")
        }
        val parts = coroutineScope {
            queries.flatMap { q ->
                listOf(
                    async {
                        runCatching { searchRaw(ServiceList.YouTube, q, 12) }
                            .getOrDefault(emptyList())
                    },
                    async {
                        runCatching { searchRaw(ServiceList.SoundCloud, q, 12) }
                            .getOrDefault(emptyList())
                    },
                )
            }.awaitAll()
        }
        dedupe(parts.flatten())
            .filter { looksLikeSong(it, maxSec = 300) }
            .let { spread(capPerArtist(it, 2)) }
            .take(60)
    }

    /** Находим конкретную песню (YouTube, при неудаче - SoundCloud). */
    private fun findSong(artist: String, title: String): Track? {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        val yt = searchRaw(ServiceList.YouTube, query, 8)
            .filter { looksLikeSong(it, maxSec = 300) }
        if (yt.isNotEmpty()) {
            return yt.sortedByDescending { songScore(it, artist, title) }.first()
        }
        val sc = searchRaw(ServiceList.SoundCloud, query, 8)
            .filter { looksLikeSong(it, maxSec = 300) }
        if (sc.isNotEmpty()) {
            return sc.sortedByDescending { songScore(it, artist, title) }.first()
        }
        return null
    }

    private fun songScore(track: Track, artist: String, title: String): Double {
        var score = 0.0
        val t = track.title.lowercase()
        val a = artist.lowercase()
        for (word in a.split(Regex("[\\s,&]+"))) {
            if (word.length >= 3 && t.contains(word)) score += 0.6
        }
        val titleKey = MusicFilter.titleKey(title)
        if (titleKey.isNotEmpty() && MusicFilter.titleKey(track.title).contains(titleKey)) score += 1.2
        if (track.artist.lowercase().contains("topic")) score += 1.2
        if (track.title.contains(" - ")) score += 0.4
        if (MusicFilter.videoish(track.title)) score -= 0.8
        // ближе к 3-4 минутам - вероятнее песня
        val sec = track.durationMs / 1000
        if (sec in 90..420) score += 0.3
        return score
    }

    // ---- Факторы стиля ---------------------------------------------------

    private val VIDEO_WORDS = listOf(
        "vlog", "влог", "unboxing", "распаковка", "челлендж", "challenge", "prank",
        "обзор", "review", "reaction", "реакция", "interview", "интервью",
        "podcast", "подкаст", "episode", "эпизод", "trailer", "трейлер",
        "shorts", "тикток", "tiktok", "gameplay", "let's play", "летсплей",
        "recap", "новости", "news",
        "clone hero", "leaderboard", "leaderboards", "beat saber", "beatmap",
        "full combo", "osu!", "playthrough",
        "клип", "премьера", "premiere", "official video", "music video",
        "video", "видео", "футаж", "врек",
        // стендапы и разговорные форматы
        "стендап", "стенд-ап", "стенд ап", "stand-up", "standup", "comedy",
        "комеди", "комик", "импровизац", "монолог", "скетч", "sketch",
        "ток-шоу", "talk show", "ток шоу", "передач", "выпуск", "лекц", "вебинар",
        "озвучк", "репортаж", "эфир", "телеканал", "разговор",
        "аудиокниг", "audiobook", "радиопередач", "радиоэфир",
    )

    private fun isVideoLike(text: String): Boolean {
        val t = text.lowercase()
        return VIDEO_WORDS.any { t.contains(it) }
    }

    /** Годится ли как песня: и название, и канал должны быть «музыкальными». */    private fun looksLikeSong(
        t: Track,
        maxSec: Long = MAX_SONG_SEC,
        requireSongSignal: Boolean = false,
    ): Boolean {
        if (!MusicFilter.isSong(t.title, t.durationMs / 1000, maxSec)) return false
        if (isVideoLike(t.title) || isVideoLike(t.artist)) return false
        if (MusicFilter.isJunkText(t.artist)) return false
        if (MusicFilter.isKidText(t.artist)) return false
        if (MusicFilter.isBlockedChannel(t.artist)) return false
        // Для «сырых» рекомендаций YouTube требуем явный музыкальный признак.
        // Для поиска music_songs и чартов это не нужно: там уже только музыка.
        if (requireSongSignal && t.source == TrackSource.YOUTUBE && !isSongSignal(t)) {
            return false
        }
        return true
    }

    /** Совпадают ли имена исполнителей (грубо, по вхождению). */
    private fun matchArtist(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a.length < 3 || b.length < 3) return a == b
        return a.contains(b) || b.contains(a)
    }

    /** Признак «это песня», а не видео: официальный topic-канал или формат «Исполнитель - Название». */
    private fun isSongSignal(t: Track): Boolean =
        t.artist.lowercase().contains("topic") || MusicFilter.songLike(t.title)

    private fun isLikelySong(t: Track): Boolean = looksLikeSong(t)

    /**
     * Оценка трека по факторам стиля. Совпадения слов в названии НЕ используются:
     * только исполнитель, язык, настроение/жанр, характер звука, эпоха, длина.
     * Если с профилем нет ни одного общего фактора — это «солянка», сильный минус.
     */
    private fun factorScore(
        track: Track,
        artistKeys: Set<String>,
        moods: Set<String>,
        contextMoods: Set<String>,
        sounds: Set<String>,
        years: List<Int>,
        preferRu: Boolean,
        languageFilter: Int,
        heard: Set<String>,
    ): Double {
        var s = 0.0
        val artist = track.artist.lowercase()
        if (artistKeys.any { matchArtist(it, artist) }) s += 2.4
        if (artist.contains("topic")) s += 0.5

        val ru = MusicFilter.isCyrillic(track.title + " " + track.artist)
        when (languageFilter) {
            0 -> if (ru) s += 1.6 else s -= 2.0
            1 -> if (!ru) s += 1.6 else s -= 2.0
            else -> if (ru == preferRu) s += 1.2 else s -= 0.8
        }

        val text = track.title + " " + track.artist
        val candMoods = MusicFilter.moods(text)
        val moodOverlap = candMoods.intersect(moods)
        s += moodOverlap.size.coerceAtMost(3) * 1.5
        s += candMoods.intersect(contextMoods).size.coerceAtMost(2) * 0.7

        val candSounds = MusicFilter.soundTags(text)
        val soundOverlap = candSounds.intersect(sounds)
        s += soundOverlap.size.coerceAtMost(2) * 0.9

        val y = MusicFilter.yearIn(track.title)
        if (y != null && years.isNotEmpty() && years.any { abs(it - y) <= 3 }) s += 0.8

        // связность стиля: хотя бы один общий фактор, иначе резко вниз
        val shared = moodOverlap.isNotEmpty() || soundOverlap.isNotEmpty() ||
            artistKeys.any { matchArtist(it, artist) }
        if (!shared && (moods.isNotEmpty() || sounds.isNotEmpty())) s -= 3.0

        if (signature(track) in heard) s -= 0.8
        val sec = track.durationMs / 1000
        if (sec in 100..420) s += 0.4
        if (sec > 480) s -= 1.0
        s += Random.nextDouble() * 0.35
        return s
    }

    // ---- Похожие (один стиль, разные песни) ------------------------------

    suspend fun related(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val url = track.pageUrl
        if (url == null) {
            // Локальный трек: ищем его в сети и берём похожие на найденный.
            val q = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
            if (q.isBlank()) return@withContext emptyList()
            val yt = runCatching { searchRaw(ServiceList.YouTube, q, 12) }.getOrDefault(emptyList())
            val seed = yt.filter { isLikelySong(it) }
                .maxByOrNull { songScore(it, track.artist, track.title) }
                ?: runCatching { searchRaw(ServiceList.SoundCloud, q, 12) }.getOrDefault(emptyList())
                    .firstOrNull { looksLikeSong(it) }
                ?: return@withContext emptyList()
            return@withContext related(seed)
        }
        val service = runCatching { NewPipe.getServiceByUrl(url) }.getOrNull()
            ?: return@withContext emptyList()
        val info = StreamInfo.getInfo(service, url)

        val artist = (info.uploaderName ?: track.artist).orEmpty()
        val tags = runCatching { info.tags }.getOrDefault(emptyList()).map { it.lowercase() }
        val category = runCatching { info.category }.getOrDefault("").orEmpty()
        val text = track.title + " " + artist + " " + tags.joinToString(" ") + " " + category
        val moods = MusicFilter.moods(text)
        val sounds = MusicFilter.soundTags(text)
        val ru = MusicFilter.isCyrillic(track.title + " " + artist)
        val years = listOfNotNull(MusicFilter.yearIn(track.title))
        val artistKeys = HashSet<String>()
        artistKeys.add(artist.lowercase())
        for (w in Regex("[\\p{L}\\p{N}]+").findAll(artist.lowercase())) {
            if (w.value.length >= 3) artistKeys.add(w.value)
        }
        val seedSig = signature(track)

        val pool = LinkedHashMap<String, Track>()
        fun addAll(items: List<Track>, requireSignal: Boolean = false) {
            for (t in items) {
                if (t.id == track.id) continue
                if (signature(t) == seedSig) continue
                if (!looksLikeSong(t, requireSongSignal = requireSignal)) continue
                pool.putIfAbsent(t.id, t)
            }
        }

        // 1) рекомендации YouTube (совместные прослушивания) - здесь могут
        //    попадаться обычные видео, поэтому нужен музыкальный признак.
        addAll(
            info.relatedStreams.filterIsInstance<StreamInfoItem>().map { it.toTrack(service) },
            requireSignal = true,
        )
        // 2) другие песни этого же исполнителя (поиск music_songs уже музыкальный)
        if (artist.isNotBlank()) addAll(searchRaw(service, artist, 25))

        val scored = pool.values
            .mapNotNull { t ->
                val sc = factorScore(t, artistKeys, moods, emptySet(), sounds, years, ru, -1, emptySet())
                if (sc.isFinite() && sc >= 0.0) t to sc else null
            }
            .sortedByDescending { it.second }
            .map { it.first }

        // разнообразие: по одному треку на исполнителя
        val out = ArrayList<Track>()
        val perArtist = HashMap<String, Int>()
        for (t in scored) {
            val key = MusicFilter.artistKey(t.artist).ifBlank { t.artist.lowercase() }
            if ((perArtist[key] ?: 0) < 1) {
                out.add(t)
                perArtist[key] = (perArtist[key] ?: 0) + 1
            }
            if (out.size >= 30) break
        }
        spread(out)
    }

    // ---- «Моя волна» -----------------------------------------------------

    data class MyWaveTaste(
        val seeds: List<Track>,
        val artists: List<String>,
        val disliked: Set<String>,
        val heard: Set<String>,
        val preferRu: Boolean,
        val moods: Set<String>,
        val contextMoods: Set<String>,
        /** Характер звука (slowed/lofi/acoustic/live/...). */
        val soundTags: Set<String> = emptySet(),
        /** Годы любимых песен (для фактора эпохи). */
        val seedYears: List<Int> = emptyList(),
        /** -1 любой, 0 русский, 1 зарубежный. */
        val languageFilter: Int = -1,
        /** 0 - до 3 песен артиста, 1 - до 2, 2 - строго разные. */
        val diversity: Int = 1,
    )

    private fun maxPerArtist(diversity: Int): Int = when (diversity) {
        0 -> 3
        2 -> 1
        else -> 2
    }

    /**
     * «Моя волна»: подбор по факторам вкуса. Один стиль, но песни разные:
     * без повторов, без совпадений по названию, хиты подмешиваются только
     * если попадают в стиль.
     */
    suspend fun myWave(taste: MyWaveTaste, hits: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        if (taste.seeds.isEmpty() && taste.artists.isEmpty()) {
            return@withContext runCatching { trending("new") }.getOrDefault(emptyList())
        }

        val pool = LinkedHashMap<String, Track>()
        fun add(items: List<Track>) {
            for (t in items) {
                if (!looksLikeSong(t)) continue
                if (signature(t) in taste.disliked) continue
                pool.putIfAbsent(t.id, t)
            }
        }

        val parts = coroutineScope {
            val seedJobs = taste.seeds.take(6).map { s ->
                async { runCatching { related(s) }.getOrDefault(emptyList()) }
            }
            val artistJobs = taste.artists.take(6).map { a ->
                async { runCatching { searchRaw(ServiceList.YouTube, a, 12) }.getOrDefault(emptyList()) }
            }
            (seedJobs + artistJobs).awaitAll()
        }
        parts.forEach { add(it) }

        val artistKeys = taste.artists.map { it.lowercase() }.toHashSet()
        val scored = pool.values
            .mapNotNull { t ->
                val sc = factorScore(
                    t,
                    artistKeys,
                    taste.moods,
                    taste.contextMoods,
                    taste.soundTags,
                    taste.seedYears,
                    taste.preferRu,
                    taste.languageFilter,
                    taste.heard,
                )
                if (sc.isFinite() && sc >= 0.0) t to sc else null
            }
            .sortedByDescending { it.second }
            .map { it.first }

        // ~60% нового / 40% знакомого (знакомое чуть ниже в рейтинге, не в топе)
        val fresh = scored.filter { signature(it) !in taste.heard }
        val familiar = scored.filter { signature(it) in taste.heard }
        val mixed = ArrayList<Track>()
        var fi = 0
        var hi = 0
        while (mixed.size < 80 && (fi < fresh.size || hi < familiar.size)) {
            repeat(4) { if (fi < fresh.size) mixed.add(fresh[fi++]) }
            repeat(1) { if (hi < familiar.size) mixed.add(familiar[hi++]) }
        }

        // хиты - только если попадают в стиль (иначе это и есть «солянка»)
        val coherentHits = hits.filter { h ->
            if (signature(h) in taste.disliked) return@filter false
            val sc = factorScore(
                h, artistKeys, taste.moods, taste.contextMoods, taste.soundTags,
                taste.seedYears, taste.preferRu, taste.languageFilter, taste.heard,
            )
            sc.isFinite() && sc >= 0.6
        }.take(10)

        val withHits = ArrayList<Track>()
        var wi = 0
        var hh = 0
        while (wi < mixed.size || hh < coherentHits.size) {
            repeat(4) { if (wi < mixed.size) withHits.add(mixed[wi++]) }
            if (hh < coherentHits.size) withHits.add(coherentHits[hh++])
        }

        spread(capPerArtist(dedupe(withHits), maxPerArtist(taste.diversity))).take(60)
    }

    /**
     * Офлайн-«Моя волна»: та же факторная модель, но по музыке на телефоне.
     * Работает без сети, если онлайн-подбор ничего не вернул.
     */
    fun localWave(taste: MyWaveTaste, local: List<Track>): List<Track> {
        if (local.isEmpty()) return emptyList()
        val artistKeys = taste.artists.map { it.lowercase() }.toHashSet()
        val scored = local
            .filter { signature(it) !in taste.disliked }
            .mapNotNull { t ->
                val sc = factorScore(
                    t, artistKeys, taste.moods, taste.contextMoods, taste.soundTags,
                    taste.seedYears, taste.preferRu, taste.languageFilter, taste.heard,
                )
                if (sc.isFinite()) t to sc else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
        // сначала то, что ещё не слышал; знакомое — в конец
        val fresh = scored.filter { signature(it) !in taste.heard }
        val familiar = scored.filter { signature(it) in taste.heard }
        return spread(capPerArtist(dedupe(fresh + familiar), maxPerArtist(taste.diversity))).take(60)
    }

    /** Песни Сереги Пирата (для пасхалки): только он, только песни, огромная очередь. */
    suspend fun piratSongs(): List<Track> = withContext(Dispatchers.IO) {
        val queries = listOf(
            "серега пират",
            "серега пират песни",
            "серега пират лучшие",
            "серега пират новый",
            "серега пират альбом",
            "серега пират ремикс",
        )
        val all = ArrayList<Track>()
        for (q in queries) {
            all += runCatching { searchRaw(ServiceList.YouTube, q, 30) }.getOrDefault(emptyList())
            all += runCatching { searchRaw(ServiceList.SoundCloud, q, 30) }.getOrDefault(emptyList())
        }
        fun good(t: Track) = looksLikeSong(t)

        val his = dedupe(
            all.filter {
                val s = (it.title + " " + it.artist).lowercase().replace("ё", "е")
                (s.contains("пират") || s.contains("серега")) && good(it)
            },
        )
        val base = his.ifEmpty { dedupe(all.filter { good(it) }) }
        if (base.isEmpty()) return@withContext emptyList()

        // огромная очередь: повторяем список (повторы допустимы)
        val out = ArrayList<Track>()
        while (out.size < 300) out.addAll(base)
        out
    }

    /** Короткий русский рекламный ролик для пасхалки. */
    suspend fun findAd(): Track? = withContext(Dispatchers.IO) {
        val queries = listOf("реклама сникерс", "русская реклама", "реклама еда прикол")
        for (q in queries) {
            val res = runCatching { searchRaw(ServiceList.YouTube, q, 15) }.getOrDefault(emptyList())
            res.firstOrNull {
                it.durationMs in 5_000..90_000 &&
                    MusicFilter.isCyrillic(it.title + " " + it.artist)
            }?.let { return@withContext it }
        }
        null
    }

    fun isSupportedUrl(text: String): Boolean {
        if (!text.startsWith("http://") && !text.startsWith("https://")) return false
        return runCatching { NewPipe.getServiceByUrl(text) }.isSuccess
    }

    // ---- Потоки ----------------------------------------------------------

    suspend fun resolve(pageUrl: String): Resolved = withContext(Dispatchers.IO) {
        val service = NewPipe.getServiceByUrl(pageUrl)
        val info = StreamInfo.getInfo(service, pageUrl)

        val audio = pickAudio(info.audioStreams)
            ?: throw IllegalStateException("Не удалось получить аудиопоток")

        val url = (audio.url ?: audio.content).orEmpty()
        if (url.isBlank()) throw IllegalStateException("Пустой адрес аудиопотока")

        Resolved(
            url = url,
            mimeType = audio.format?.mimeType,
            title = info.name.orEmpty(),
            artist = info.uploaderName.orEmpty(),
            durationMs = info.duration.coerceAtLeast(0L) * 1000L,
            thumbUrl = info.thumbnails?.lastOrNull()?.url,
        )
    }

    data class Resolved(
        val url: String,
        val mimeType: String?,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val thumbUrl: String?,
    )

    /**
     * Поток для отдельного видеоплеера «Просмотр». Сначала пытаемся взять
     * progressive-видео (в нём уже есть звук), иначе отдаём аудио и рисуем
     * обложку — так «Просмотр» работает даже для треков без видео.
     */
    suspend fun resolveVideo(pageUrl: String, maxHeight: Int? = null): ResolvedVideo =
        withContext(Dispatchers.IO) {
            val service = NewPipe.getServiceByUrl(pageUrl)
            val info = StreamInfo.getInfo(service, pageUrl)
            val all = info.videoStreams
                .filter { it.isUrl && !it.url.isNullOrBlank() }
            val progressive = all.filter {
                !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }
            val videoOnly = all.filter { it.isVideoOnly }
            val qualities = (progressive + videoOnly)
                .map { it.height }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
                .filter { it <= 1080 }
                .ifEmpty { listOf(360) }
            val target = (maxHeight ?: qualities.first()).coerceIn(
                qualities.last(),
                qualities.first(),
            )
            val progressivePick = progressive
                .filter { it.height in 1..target }
                .maxByOrNull { it.height }
            val videoOnlyPick = videoOnly
                .filter { it.height in 1..target }
                .maxByOrNull { it.height }

            val video = when {
                progressivePick == null -> videoOnlyPick
                videoOnlyPick == null -> progressivePick
                videoOnlyPick.height > progressivePick.height -> videoOnlyPick
                else -> progressivePick
            }
            if (video != null) {
                val url = (video.url ?: video.content).orEmpty()
                if (url.isNotBlank()) {
                    val isVideoOnly = video.isVideoOnly
                    val audioUrl = if (isVideoOnly) {
                        pickAudio(info.audioStreams)?.let { (it.url ?: it.content).orEmpty() }
                            ?.takeIf { it.isNotBlank() }
                    } else null
                    return@withContext ResolvedVideo(
                        url = url,
                        mimeType = video.format?.mimeType,
                        title = info.name.orEmpty(),
                        artist = info.uploaderName.orEmpty(),
                        durationMs = info.duration.coerceAtLeast(0L) * 1000L,
                        thumbUrl = info.thumbnails?.lastOrNull()?.url,
                        hasVideo = true,
                        audioUrl = audioUrl,
                        videoOnly = isVideoOnly,
                        qualities = qualities,
                        height = video.height,
                        isShort = info.duration in 1..90,
                    )
                }
            }

            val audio = pickAudio(info.audioStreams)
                ?: throw IllegalStateException("Не удалось получить видеопоток")
            val url = (audio.url ?: audio.content).orEmpty()
            if (url.isBlank()) throw IllegalStateException("Пустой адрес потока")
            ResolvedVideo(
                url = url,
                mimeType = audio.format?.mimeType,
                title = info.name.orEmpty(),
                artist = info.uploaderName.orEmpty(),
                durationMs = info.duration.coerceAtLeast(0L) * 1000L,
                thumbUrl = info.thumbnails?.lastOrNull()?.url,
                hasVideo = false,
                qualities = emptyList(),
                height = 0,
                isShort = info.duration in 1..90,
            )
        }

    data class ResolvedVideo(
        val url: String,
        val mimeType: String?,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val thumbUrl: String?,
        val hasVideo: Boolean,
        val audioUrl: String? = null,
        val videoOnly: Boolean = false,
        val qualities: List<Int> = emptyList(),
        val height: Int = 0,
        val isShort: Boolean = false,
    )

    /** Запасной источник: ищем тот же трек на SoundCloud и берём его поток. */
    suspend fun resolveFallback(title: String): String? = withContext(Dispatchers.IO) {
        val q = title.substringBefore("(").substringBefore("[").trim().ifBlank { title }
        val results = runCatching { search(ServiceList.SoundCloud, q, 12) }.getOrDefault(emptyList())
        for (t in results) {
            val page = t.pageUrl ?: continue
            val direct = runCatching { resolve(page).url }.getOrNull()
            if (!direct.isNullOrBlank()) return@withContext direct
        }
        null
    }

    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter { it.isUrl && !it.url.isNullOrBlank() }
        if (usable.isEmpty()) return null
        val progressive = usable.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pool = (progressive.ifEmpty { usable }).sortedWith(
            compareByDescending<AudioStream> {
                it.averageBitrate.coerceAtLeast(0) + it.bitrate.coerceAtLeast(0)
            },
        )
        return when (SettingsStore.quality.value) {
            0 -> pool.last()
            1 -> pool[pool.size / 2]
            else -> pool.first()
        }
    }

    // ---- Утилиты ---------------------------------------------------------

    private fun StreamInfoItem.toTrack(service: StreamingService): Track = Track(
        id = url ?: name.orEmpty(),
        title = name.orEmpty(),
        artist = uploaderName.orEmpty(),
        durationMs = duration.coerceAtLeast(0L) * 1000L,
        pageUrl = url,
        artworkUri = thumbnails?.lastOrNull()?.url,
        source = if (service.serviceId == ServiceList.SoundCloud.serviceId) {
            TrackSource.SOUNDCLOUD
        } else {
            TrackSource.YOUTUBE
        },
    )

    /**
     * Дедупликация: точное совпадение «подписи» песни. Подпись уже отбрасывает
     * варианты (slowed/remix/feat/кавер), поэтому дубликаты склеиваются, а
     * разные песни с общим началом названия — нет.
     */
    fun dedupeFuzzy(tracks: List<Track>): List<Track> = dedupe(tracks)

    /** То же самое: склейка вариантов одной песни по подписи. */
    fun dedupeByCore(tracks: List<Track>): List<Track> = dedupe(tracks)

    fun dedupe(tracks: List<Track>): List<Track> {
        val out = ArrayList<Track>(tracks.size)
        for (t in tracks) {
            val duplicate = out.any { existing ->
                if (existing.id == t.id) {
                    true
                } else if (existing.pageUrl != null && existing.pageUrl == t.pageUrl) {
                    true
                } else {
                    MusicFilter.sameSongCautious(
                        existing.title, existing.artist, t.title, t.artist,
                    )
                }
            }
            if (!duplicate) out.add(t)
        }
        return out
    }

    /** Подпись песни (перезаливы/ремиксы/каверы = одна песня). */
    fun signature(t: Track): String = MusicFilter.signature(t.title, t.artist)

    /** Не более max треков одного исполнителя. */
    fun capPerArtist(tracks: List<Track>, max: Int): List<Track> {
        val counts = HashMap<String, Int>()
        return tracks.filter { t ->
            val k = MusicFilter.artistKey(t.artist).ifBlank { t.artist.lowercase() }
            val c = counts.getOrDefault(k, 0)
            if (c >= max) {
                false
            } else {
                counts[k] = c + 1
                true
            }
        }
    }

    /** Раскладка: один артист/одна песня не чаще, чем раз в cooldown треков. */
    fun spread(tracks: List<Track>): List<Track> {
        if (tracks.size < 3) return tracks
        val cooldown = 4
        val remaining = tracks.toMutableList()
        val out = ArrayList<Track>(tracks.size)
        val recentArtists = ArrayDeque<String>()
        val recentSigs = ArrayDeque<String>()
        while (remaining.isNotEmpty()) {
            var idx = remaining.indexOfFirst {
                MusicFilter.artistKey(it.artist) !in recentArtists &&
                    signature(it) !in recentSigs
            }
            if (idx < 0) idx = 0
            val t = remaining.removeAt(idx)
            out.add(t)
            recentArtists.addLast(MusicFilter.artistKey(t.artist))
            recentSigs.addLast(signature(t))
            while (recentArtists.size > cooldown) recentArtists.removeFirst()
            while (recentSigs.size > cooldown) recentSigs.removeFirst()
        }
        return out
    }

    fun key(track: Track): String = track.localUri ?: track.pageUrl ?: track.id
}
