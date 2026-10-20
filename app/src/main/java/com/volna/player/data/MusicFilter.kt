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
        "(?iu)(" +
            "сборник\\w*|плейлист\\w*|playlist\\w*|compilation\\w*|megamix\\w*|" +
            "non-?stop\\w*|continuous\\s+mix|dj\\s+mix|hours?\\b|час(?:а|ов|ы)?\\b|" +
            "greatest\\b|хит(?:ы|ов)?\\b|" +
            "альбом\\w*|full\\s+(?:album|concert|movie|stream)|" +
            "концерт\\w*|" +
            "подборк\\w*|" +
            "podcast\\w*|подкаст\\w*|audiobook\\w*|аудиокниг\\w*|" +
            "trailer\\w*|трейлер\\w*|обзор\\w*|review\\w*|" +
            "сериал\\w*|дубл\\w*|" +
            "интервью|interview\\w*|реакц\\w*|реценз\\w*|разбор\\w*|новост\\w*|news\\b|" +
            "gameplay\\w*|летсплей\\w*|фрагмент\\w*|отрыв\\w*|эпизод\\w*|episode\\w*|" +
            "clone\\s*hero|leaderboards?\\w*|beat\\s*saber|beatmap\\w*|full\\s*combo|osu!|playthrough\\w*|" +
            "\\bfc\\s*\\d{2,}|" +
            "пранк\\w*|шоу\\b|пресс\\w*|shorts|тикток|tiktok|нарезк\\w*|компиляц\\w*|" +
            "season\\s*\\d+|сезон\\s*\\d+|ep\\.?\\s*\\d+|" +
            "live\\s*stream|прямой\\s*эфир|прямая\\s*трансляция|" +
            "прикол\\w*|смешн\\w*|ytpbr|60fps|hd/60|перезалив|reupload|" +
            // стендапы и прочий разговорный/не-музыкальный контент
            "стендап\\w*|стенд-?ап\\w*|stand-?up\\w*|comedy\\w*|комеди\\w*|комик\\w*|" +
            "импровизац\\w*|монолог\\w*|скетч\\w*|sketch\\w*|ток-?шоу|talk\\s*show|" +
            "передач\\w*|выпуск\\w*|лекц\\w*|вебинар\\w*|озвучк\\w*|репортаж\\w*|эфир\\w*|" +
            "телеканал\\w*|разговор\\w*|вечерн\\w*\\s+шоу|пусть\\s+говорят|" +
            "премьер\\w*|premiere\\w*|клип\\w*|официальн\\w*\\s+видео|official\\s+video|" +
            "music\\s+video|полный\\s+альбом|" +
            "radio\\s+(?:show|station|stream)|радиопередач\\w*|радиоэфир\\w*" +
            ")",
    )

    // "1 час", "1 hour", длительность вида 1:02:33 в заголовке
    private val BAD2 = Regex("(?iu)(\\d{1,2}\\s*(час|hour|h)\\b|1\\s*час|one hour|\\d{1,2}:\\d{2}:\\d{2})")

    private val STOP = setOf(
        "feat", "ft", "official", "video", "music", "audio", "the", "and", "with",
        "prod", "version", "lyrics", "lyric", "remix", "song", "track",
    )

    private val VIDEOISH = Regex("(?iu)(клип|official video|music video|\\bmv\\b|lyric|live)")

    // «Mix» внутри скобок — это версия песни (2013 Mix), а не сборник.
    // Сборником считаем только mix/микс вне скобок.
    private val MIX_WORD = Regex("(?iu)\\b(mix|микс)\\b")
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")

    /** Песня ли это (без сборников/обзоров, нормальной длительности). */
    fun isSong(title: String, durationSec: Long, maxSec: Long = 600, minSec: Long = 45): Boolean {
        if (title.isBlank()) return false
        if (BAD.containsMatchIn(title) || BAD2.containsMatchIn(title)) return false
        if (KIDS.containsMatchIn(title)) return false
        val outsideBrackets = title.replace(BRACKETS, " ")
        if (MIX_WORD.containsMatchIn(outsideBrackets)) return false
        if (durationSec in 1 until minSec) return false
        if (durationSec > maxSec) return false
        return true
    }

    /** Служебный/нежелательный текст (сборники, обзоры, миксы и т.п.). */
    fun isJunkText(text: String): Boolean =
        text.isNotBlank() && (
            BAD.containsMatchIn(text) ||
                BAD2.containsMatchIn(text) ||
                MIX_WORD.containsMatchIn(text.replace(BRACKETS, " "))
            )

    /** Детский контент (мультики, колыбельные, «Маша и Медведь» и т.п.). */
    private val KIDS = Regex(
        "(?iu)(маша\\s*и\\s*медвед|детск|для\\s*детей|детям|мультик|мультфильм|мультсериал|" +
            "колыбельн|сказк\\w*\\s+для|аудиосказк|песенки\\s*для|развивающ|малыш|кроха|" +
            "синий\\s*трактор|кукутики|малышарики|фиксики|смешарики|барбоскины|лунтик|" +
            "kids|children|nursery|cartoon|lullaby|toddler|baby\\s*song)",
    )

    fun isKidText(text: String): Boolean = text.isNotBlank() && KIDS.containsMatchIn(text)

    /** Каналы, которые не берём ни в поиск, ни в очередь. */
    private val BLOCKED_CHANNELS = Regex(
        "(?iu)(internet\\s*jesus|internetjesus|the8bitdrummer|8bitdrummer|8-?bit\\s*drummer)",
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
        s = s.replace(Regex("(?iu)\\s(feat|ft|prod)\\.?\\s.*$"), " ")
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\[[^\\]]*\\]"), " ")
        s = s.replace(
            Regex("(?iu)(official|video|lyrics?|audio|remix|feat\\.?|ft\\.?|\\bhd\\b|\\b4k\\b|\\bmv\\b|visualizer|clip)"),
            " ",
        )
        s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return s
    }

    // ---- Осторожная склейка дублей -------------------------------------
    // Для поиска/очереди: убираем только сервисные пометки, но НЕ трогаем
    // live/remix/cover, чтобы не склеить разные версии одной песни.

    private val SERVICE_PAREN = Regex(
        "(?iu)\\(\\s*(official\\s*(music\\s*)?video|official\\s*audio|official|" +
            "lyrics?|lyric\\s*video|audio|visualizer|hd|4k|mv|m/v|music\\s*video|" +
            "remaster(ed)?(\\s*\\d{4})?|explicit|clean)\\s*\\)",
    )
    private val SERVICE_BRACKET = Regex(
        "(?iu)\\[\\s*(official\\s*(music\\s*)?video|official\\s*audio|official|" +
            "lyrics?|lyric\\s*video|audio|visualizer|hd|4k|mv|m/v|music\\s*video|" +
            "remaster(ed)?(\\s*\\d{4})?|explicit|clean)\\s*\\]",
    )
    private val SERVICE_WORD = Regex(
        "(?iu)\\b(official\\s*(music\\s*)?video|official\\s*audio|" +
            "lyric\\s*video|music\\s*video|visualizer)\\b",
    )
    private val FEAT_TAIL = Regex("(?iu)\\s(feat|ft|prod)\\.?\\s.*$")

    /** Исполнитель без служебных пометок: "Artist - Topic" -> "artist". */
    fun artistKey(artist: String): String {
        var s = artist.lowercase()
        s = s.replace(Regex("(?iu)\\s*[-–—]\\s*topic\\b"), " ")
        s = s.replace(Regex("(?iu)(vevo|topic|official)$"), " ")
        s = s.replace(Regex("(?iu)\\b(vevo|official|topic|music|channel|records|feat|ft|prod)\\b"), " ")
        s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return s
    }

    /**
     * Ключ осторожной склейки: исполнитель + ядро названия. Сервисные
     * пометки (Official Video, Lyrics, HD, Remastered) убираются, а
     * Live/Remix/Cover/Slowed остаются разными версиями.
     */
    fun dedupeKey(title: String, artist: String): String {
        var s = title.lowercase()
        s = s.replace(SERVICE_PAREN, " ")
        s = s.replace(SERVICE_BRACKET, " ")
        s = s.replace(FEAT_TAIL, " ")
        s = s.replace(SERVICE_WORD, " ")
        val a = artistKey(artist)
        if (a.isNotBlank()) {
            s = s.replace(Regex("^\\s*${Regex.escape(a)}\\s*[-–—:]\\s*"), " ")
        }
        s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return (if (a.isBlank()) "" else "$a|") + s
    }

    /** Совпадает ли название/исполнитель настолько, чтобы считать это дублем. */
    fun sameSongCautious(
        aTitle: String,
        aArtist: String,
        bTitle: String,
        bArtist: String,
    ): Boolean {
        val ka = dedupeKey(aTitle, aArtist)
        val kb = dedupeKey(bTitle, bArtist)
        if (ka.isBlank() || kb.isBlank()) return false
        if (ka == kb) return true
        val ta = ka.substringAfter('|')
        val tb = kb.substringAfter('|')
        val aa = ka.substringBefore('|')
        val ab = kb.substringBefore('|')
        if (ta.length >= 3 && ta == tb) {
            // Один и тот же текст названия; исполнители либо совпали,
            // либо один из них пустой/является префиксом другого.
            return aa.isBlank() || ab.isBlank() || aa == ab ||
                (aa.length >= 4 && ab.length >= 4 && (aa.contains(ab) || ab.contains(aa)))
        }
        return false
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
