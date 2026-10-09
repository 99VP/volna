package com.volna.player.data

/**
 * Фильтр музыки (порт логики сайта "Волна" из server.py):
 * отсекает сборники, миксы, обзоры, длинные ролики; оставляет песни.
 *
 * Здесь же — «факторы» для подбора: настроение/жанр, характер звука, язык,
 * эпоха. Подбор опирается на эти факторы, а не на конкретные песни.
 */
object MusicFilter {

    private val BAD = Regex(
        "(?i)(" +
            "mix\\b|микс\\w*|сборник\\w*|плейлист\\w*|playlist\\w*|compilation\\w*|megamix\\w*|" +
            "non-?stop\\w*|hours?\\b|час(?:а|ов|ы)?\\b|best\\b|хит(?:ы|ов)?\\b|greatest\\b|" +
            "album\\b|альбом\\w*|full\\b|полн\\w*|концерт\\w*|stream\\w*|radio\\b|" +
            "подборк\\w*|top\\b|топ\\b|podcast\\w*|подкаст\\w*|audiobook\\w*|аудиокниг\\w*|" +
            "trailer\\w*|трейлер\\w*|обзор\\w*|review\\w*|фильм\\w*|сериал\\w*|сцен\\w*|дубл\\w*|" +
            "интервью|interview\\w*|реакц\\w*|реценз\\w*|разбор\\w*|новост\\w*|news\\b|" +
            "gameplay\\w*|летсплей\\w*|фрагмент\\w*|отрыв\\w*|кадр\\w*|момент\\w*|эпизод\\w*|" +
            "episode\\w*|пранк\\w*|шоу\\b|пресс\\w*|shorts|тикток|tiktok|нарезк\\w*|компиляц\\w*|" +
            "прикол\\w*|смешн\\w*|ytpbr|60fps|hd/60|перезалив|reupload|" +
            // стендапы и прочий разговорный/не-музыкальный контент
            "стендап\\w*|стенд-?ап\\w*|stand-?up\\w*|comedy\\w*|комеди\\w*|комик\\w*|" +
            "импровизац\\w*|монолог\\w*|скетч\\w*|sketch\\w*|ток-?шоу|talk\\s*show|" +
            "передач\\w*|выпуск\\w*|лекц\\w*|вебинар\\w*|озвучк\\w*|репортаж\\w*|эфир\\w*|" +
            "телеканал\\w*|разговор\\w*|вечерн\\w*\\s+шоу|пусть\\s+говорят" +
            ")"
    )

    // "1 час", "1 hour", длительность вида 1:02:33 в заголовке
    private val BAD2 = Regex("(?i)(\\d{1,2}\\s*(час|hour|h)\\b|1\\s*час|one hour|\\d{1,2}:\\d{2}:\\d{2})")

    private val STOP = setOf(
        "feat", "ft", "official", "video", "music", "audio", "the", "and", "with",
        "prod", "version", "lyrics", "lyric", "remix", "song", "track",
    )

    private val VIDEOISH = Regex("(?i)(клип|official video|music video|\\bmv\\b|lyric|live)")

    /** Песня ли это (без сборников/обзоров, нормальной длительности). */
    fun isSong(title: String, durationSec: Long, maxSec: Long = 600, minSec: Long = 45): Boolean {
        if (title.isBlank()) return false
        if (BAD.containsMatchIn(title) || BAD2.containsMatchIn(title)) return false
        if (KIDS.containsMatchIn(title)) return false
        if (durationSec in 1 until minSec) return false
        if (durationSec > maxSec) return false
        return true
    }

    /** Служебный/нежелательный текст (сборники, обзоры, миксы и т.п.). */
    fun isJunkText(text: String): Boolean =
        text.isNotBlank() && (BAD.containsMatchIn(text) || BAD2.containsMatchIn(text))

    /** Детский контент (мультики, колыбельные, «Маша и Медведь» и т.п.). */
    private val KIDS = Regex(
        "(?i)(маша\\s*и\\s*медвед|детск|для\\s*детей|детям|мультик|мультфильм|мультсериал|" +
            "колыбельн|сказк|песенки\\s*для|развивающ|малыш|кроха|" +
            "синий\\s*трактор|кукутики|малышарики|фиксики|смешарики|барбоскины|лунтик|" +
            "kids|children|nursery|cartoon|lullaby|toddler|baby\\s*song)",
    )

    fun isKidText(text: String): Boolean = text.isNotBlank() && KIDS.containsMatchIn(text)

    /** Каналы, которые не берём ни в поиск, ни в очередь. */
    private val BLOCKED_CHANNELS = Regex(
        "(?i)(internet\\s*jesus|internetjesus|the8bitdrummer|8bitdrummer|8-?bit\\s*drummer)",
    )

    fun isBlockedChannel(text: String): Boolean =
        text.isNotBlank() && BLOCKED_CHANNELS.containsMatchIn(text)

    /**
     * Настроение/жанр по словам названия/тегов/категории.
     * Это факторы стиля, а не привязка к конкретным песням.
     */
    private val MOODS: Map<String, List<String>> = mapOf(
        "sad" to listOf(
            "sad", "груст", "плач", "слез", "лирик", "прощ", "одинок", "печаль",
            "tears", "lonely", "broken", "pain", "скучаю", "потерял", "боль", "разлук",
        ),
        "funny" to listOf(
            "мем", "прикол", "смешн", "мемн", "funny", "meme", "пароди", "joke",
            "шутк", "баян", "rofl", "lol", "ржач",
        ),
        "love" to listOf(
            "love", "любов", "люблю", "сердце", "heart", "романт", "romantic",
            "ты моя", "любимая", "любимый", "поцел", "нежность",
        ),
        "party" to listOf(
            "party", "dance", "танц", "клуб", "энерг", "bang", "кач", "пляс",
            "громко", "фиест", "celebrat",
        ),
        "calm" to listOf(
            "lofi", "лофи", "chill", "чилл", "calm", "спокой", "relax", "sleep",
            "сон", "акустик", "acoustic", "piano", "пиан", "ambient", "эмбиент",
            "meditation", "медитац", "природа", "rain", "дожд",
        ),
        "rap" to listOf(
            "rap", "рэп", "hip", "hop", "хип", "trap", "трэп", "баттл", "бары", "flow",
            "drill", "дрилл", "underground", "андеграунд", "phonk", "фонк",
        ),
        "rock" to listOf(
            "rock", "рок", "metal", "метал", "punk", "панк", "guitar", "гитар",
            "nirvana", "indie", "инди", "альтернатив", "alternative",
        ),
        "electronic" to listOf(
            "electronic", "electro", "house", "techno", "edm", "synth", "синт",
            "dj", "remix", "dance", "dubstep", "hardstyle", "hyperpop", "хиперпоп",
            "trance", "транс",
        ),
        "epic" to listOf(
            "epic", "эпик", "саундтрек", "soundtrack", "ost", "orchestr", "оркестр",
            "choir", "хор", "cinematic", "кино",
        ),
        "nostalgia" to listOf(
            "retro", "ретро", "nostalgi", "ностальг", "80s", "90s", "2000s", "old",
            "классик", "classic", "хиты прошлых",
        ),
    )

    fun moods(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val t = text.lowercase()
        val out = HashSet<String>()
        for ((mood, words) in MOODS) {
            if (words.any { t.contains(it) }) out.add(mood)
        }
        return out
    }

    /** «Звуковой профиль»: темп/формат по словам (slowed, remix, 8d, acoustic, live, bass...). */
    private val SOUND_TAGS: Map<String, List<String>> = mapOf(
        "slowed" to listOf("slowed", "sped up", "spedup", "speed up", "speed-up", "замедл", "ускорен"),
        "remix" to listOf("remix", "ремикс", "edit", "flip", "bootleg", "mashup"),
        "8d" to listOf("8d", "8-d"),
        "bass" to listOf("bass boosted", "bassboost", " bass ", "phonk", "фонк", "808"),
        "lofi" to listOf("lofi", "лофи", "chill"),
        "acoustic" to listOf("acoustic", "акустик", "piano", "unplugged"),
        "live" to listOf(" live ", "concert", "концерт"),
        "instrumental" to listOf("instrumental", "минус", "минусовк", "karaoke", "караоке"),
    )

    fun soundTags(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val t = text.lowercase()
        val out = HashSet<String>()
        for ((tag, words) in SOUND_TAGS) {
            if (words.any { t.contains(it) }) out.add(tag)
        }
        return out
    }

    /** Формат "Исполнитель - Название". */
    fun songLike(title: String): Boolean =
        title.contains(" - ") || title.contains(" – ") || title.contains(" — ")

    fun videoish(title: String): Boolean = VIDEOISH.containsMatchIn(title)

    fun tokens(text: String): Set<String> {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }
        return words.filter { it.length >= 4 && it !in STOP }.toSet()
    }

    fun isCyrillic(text: String): Boolean =
        Regex("[а-яё]").findAll(text.lowercase()).count() >= 3

    fun languageRu(text: String): Boolean = isCyrillic(text)

    fun sharesTokens(a: String, b: String): Boolean =
        tokens(a).any { it in tokens(b) }

    fun isStopWord(word: String): Boolean = word in STOP

    /** Год из названия: "(2019)", "[2004]", "2019 -". Нужен для фактора «эпоха». */
    fun yearIn(text: String): Int? {
        val m = Regex("(?<![\\d])(19\\d{2}|20[0-2]\\d)(?![\\d])").find(text) ?: return null
        val y = m.value.toIntOrNull() ?: return null
        return if (y in 1950..2035) y else null
    }

    /** Нормализованное название для поиска дублей ("Numb (Official Video)" -> "numb"). */
    fun titleKey(title: String): String {
        var s = title.lowercase()
        s = s.replace(Regex("(?i)\\s(feat|ft|prod)\\.?\\s.*$"), " ")
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\[[^\\]]*\\]"), " ")
        s = s.replace(
            Regex("(?i)(official|video|lyrics?|audio|remix|feat\\.?|ft\\.?|\\bhd\\b|\\b4k\\b|\\bmv\\b|visualizer|clip)"),
            " ",
        )
        s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return s
    }

    /** «Ядро» песни: первые значимые слова подписи (для склейки вариантов одной песни). */
    fun coreKey(title: String, artist: String): String =
        signature(title, artist).split(" ").filter { it.isNotEmpty() }.take(3).joinToString(" ")

    private val VARIANT_WORDS = setOf(
        "slowed", "reverb", "sped", "nightcore", "speed", "remix", "mashup",
        "version", "extended", "edit", "cover", "instrumental", "karaoke", "acoustic",
        "live", "ремикс", "кавер", "версия", "минус", "минусовка", "реп",
        "tik", "spedup", "8d",
    )

    /**
     * «Подпись» песни: ядро названия без исполнителя, служебных и вариантных слов.
     * Помогает считать перезаливы/каверы/ремиксы одной песней и не повторять её.
     */
    fun signature(title: String, artist: String): String {
        val base = titleKey(title)
        val artistTokens = tokens(artist)
        val words = base.split(" ").filter {
            it.isNotEmpty() && it !in artistTokens && !isStopWord(it) && it !in VARIANT_WORDS
        }
        val core = words.take(6).joinToString(" ")
        return core.ifBlank { base }
    }
}
