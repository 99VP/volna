package com.volna.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Настройки приложения (тема, размер, качество звука, подбор, игры). */
object SettingsStore {

    private var prefs: SharedPreferences? = null

    private val _theme = MutableStateFlow("dark")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _artworkScale = MutableStateFlow(1.0f)
    val artworkScale: StateFlow<Float> = _artworkScale.asStateFlow()

    private val _fontScale = MutableStateFlow(1.0f)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    /** 0 - низкое, 1 - среднее, 2 - высокое. */
    private val _quality = MutableStateFlow(2)
    val quality: StateFlow<Int> = _quality.asStateFlow()

    /** Язык подбора: -1 - любой, 0 - русский, 1 - зарубежный. */
    private val _languageFilter = MutableStateFlow(-1)
    val languageFilter: StateFlow<Int> = _languageFilter.asStateFlow()

    /** Разнообразие: 0 - спокойно (до 3 песен одного артиста), 1 - средне, 2 - максимально разные. */
    private val _diversity = MutableStateFlow(1)
    val diversity: StateFlow<Int> = _diversity.asStateFlow()

    // ---- Звук мини-игр: отдельно для каждой игры, не влияет на музыку ----

    private val _minesSound = MutableStateFlow(true)
    val minesSound: StateFlow<Boolean> = _minesSound.asStateFlow()

    private val _minesVolume = MutableStateFlow(0.7f)
    val minesVolume: StateFlow<Float> = _minesVolume.asStateFlow()

    private val _sudokuSound = MutableStateFlow(true)
    val sudokuSound: StateFlow<Boolean> = _sudokuSound.asStateFlow()

    private val _sudokuVolume = MutableStateFlow(0.7f)
    val sudokuVolume: StateFlow<Float> = _sudokuVolume.asStateFlow()

    /** Режим-пасхалка "Серега Пират". */
    private val _pirat = MutableStateFlow(false)
    val pirat: StateFlow<Boolean> = _pirat.asStateFlow()

    /** Режим разработчика (включается долгим нажатием на кнопку настроек). */
    private val _developer = MutableStateFlow(false)
    val developer: StateFlow<Boolean> = _developer.asStateFlow()

    /** God mode: нельзя проиграть (бомбы не взрываются, подсказки бесконечны). */
    private val _godMode = MutableStateFlow(false)
    val godMode: StateFlow<Boolean> = _godMode.asStateFlow()

    /** Локальный обход DPI (ByeDPI) для YouTube/SoundCloud. */
    private val _bypass = MutableStateFlow(true)
    val bypass: StateFlow<Boolean> = _bypass.asStateFlow()

    /** HTTPS-адрес watch-server для совместного просмотра. */
    private val _watchServer = MutableStateFlow("")
    val watchServer: StateFlow<String> = _watchServer.asStateFlow()
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences("volna_settings", Context.MODE_PRIVATE)
        prefs = p
        _theme.value = p.getString("theme", "dark") ?: "dark"
        _artworkScale.value = p.getFloat("artworkScale", 1.0f)
        _fontScale.value = p.getFloat("fontScale", 1.0f)
        _quality.value = p.getInt("quality", 2)
        _languageFilter.value = p.getInt("languageFilter", -1)
        _diversity.value = p.getInt("diversity", 1)
        _minesSound.value = p.getBoolean("minesSound", true)
        _minesVolume.value = p.getFloat("minesVolume", 0.7f)
        _sudokuSound.value = p.getBoolean("sudokuSound", true)
        _sudokuVolume.value = p.getFloat("sudokuVolume", 0.7f)
        // режим "Серега Пират" всегда начинается выключенным (не залипает)
        _pirat.value = false
        _developer.value = p.getBoolean("developer", false)
        _godMode.value = p.getBoolean("godMode", false)
        _bypass.value = p.getBoolean("bypass", true)
        _watchServer.value = p.getString("watchServer", "").orEmpty()
    }

    fun setTheme(id: String) {
        _theme.value = id
        prefs?.edit()?.putString("theme", id)?.apply()
    }

    fun setArtworkScale(value: Float) {
        _artworkScale.value = value
        prefs?.edit()?.putFloat("artworkScale", value)?.apply()
    }

    fun setFontScale(value: Float) {
        _fontScale.value = value
        prefs?.edit()?.putFloat("fontScale", value)?.apply()
    }

    fun setQuality(value: Int) {
        _quality.value = value
        prefs?.edit()?.putInt("quality", value)?.apply()
    }

    fun setLanguageFilter(value: Int) {
        _languageFilter.value = value
        prefs?.edit()?.putInt("languageFilter", value)?.apply()
    }

    fun setDiversity(value: Int) {
        _diversity.value = value
        prefs?.edit()?.putInt("diversity", value)?.apply()
    }

    fun setMinesSound(value: Boolean) {
        _minesSound.value = value
        prefs?.edit()?.putBoolean("minesSound", value)?.apply()
    }

    fun setMinesVolume(value: Float) {
        _minesVolume.value = value
        prefs?.edit()?.putFloat("minesVolume", value)?.apply()
    }

    fun setSudokuSound(value: Boolean) {
        _sudokuSound.value = value
        prefs?.edit()?.putBoolean("sudokuSound", value)?.apply()
    }

    fun setSudokuVolume(value: Float) {
        _sudokuVolume.value = value
        prefs?.edit()?.putFloat("sudokuVolume", value)?.apply()
    }

    fun setPirat(value: Boolean) {
        _pirat.value = value
    }

    fun setDeveloper(value: Boolean) {
        _developer.value = value
        prefs?.edit()?.putBoolean("developer", value)?.apply()
    }

    fun setGodMode(value: Boolean) {
        _godMode.value = value
        prefs?.edit()?.putBoolean("godMode", value)?.apply()
    }

    fun setBypass(value: Boolean) {
        _bypass.value = value
        prefs?.edit()?.putBoolean("bypass", value)?.apply()
    }

    fun setWatchServer(value: String) {
        _watchServer.value = value
        prefs?.edit()?.putString("watchServer", value)?.apply()
    }
}
