package com.volna.player.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.volna.player.R
import com.volna.player.data.SettingsStore

/** Какая игра просит звук - у каждой свои громкость и вкл/выкл. */
enum class GameSfx { MINES, SUDOKU }

/**
 * Звуки мини-игр (Сапёр/Судоку) из res/raw.
 *
 * Играет по отдельному системному потоку игр (USAGE_GAME), поэтому ползунок
 * «Музыка» и общая громкость музыки не меняются: игрок убавляет/выключает звук
 * прямо внутри игры.
 */
object Sfx {

    private var pool: SoundPool? = null
    private val soundIds = HashMap<Int, Int>()

    private fun ensure(context: Context): SoundPool? {
        pool?.let { return it }
        val p = runCatching {
            SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        }.getOrNull() ?: return null
        pool = p
        val appContext = context.applicationContext
        for (res in intArrayOf(
            R.raw.sfx_click,
            R.raw.sfx_flag,
            R.raw.sfx_select,
            R.raw.sfx_boom,
            R.raw.sfx_win,
        )) {
            runCatching { soundIds[res] = p.load(appContext, res, 1) }
        }
        return p
    }

    /** (включено, громкость 0..1) для нужной игры. */
    private fun settings(game: GameSfx): Pair<Boolean, Float> = when (game) {
        GameSfx.MINES -> SettingsStore.minesSound.value to SettingsStore.minesVolume.value
        GameSfx.SUDOKU -> SettingsStore.sudokuSound.value to SettingsStore.sudokuVolume.value
    }

    private fun play(context: Context, res: Int, game: GameSfx, base: Float) {
        val (on, volume) = settings(game)
        if (!on || volume <= 0f) return
        val p = ensure(context) ?: return
        val id = soundIds[res] ?: return
        val v = (volume * base).coerceIn(0f, 1f)
        runCatching { p.play(id, v, v, 1, 0, 1f) }
    }

    /** Прогреть звуки заранее, чтобы первый звук не был тихим. */
    fun warm(context: Context) {
        ensure(context)
    }

    fun click(context: Context, game: GameSfx) = play(context, R.raw.sfx_click, game, 0.9f)

    fun select(context: Context, game: GameSfx) = play(context, R.raw.sfx_select, game, 0.8f)

    fun flag(context: Context, game: GameSfx) = play(context, R.raw.sfx_flag, game, 0.85f)

    fun boom(context: Context, game: GameSfx) = play(context, R.raw.sfx_boom, game, 0.9f)

    fun win(context: Context, game: GameSfx) = play(context, R.raw.sfx_win, game, 0.9f)

    /** Короткий сигнал «новый рекорд» (используем победный звук чуть громче). */
    fun record(context: Context, game: GameSfx) = play(context, R.raw.sfx_win, game, 1f)
}
