package com.volna.player.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Рекорды и «плюшки» мини-игр: лучший результат, серия побед и накопленные очки.
 * Хранится локально, на телефоне.
 */
object GameStore {

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext
            .getSharedPreferences("volna_games", Context.MODE_PRIVATE)
    }

    /** Лучший результат для конкретного режима. */
    fun best(key: String): Int = prefs?.getInt("best_$key", 0) ?: 0

    /** Лучшее время (в секундах) для режима; 0 - ещё нет. */
    fun bestTime(key: String): Int = prefs?.getInt("besttime_$key", 0) ?: 0

    /** true, если это новое лучшее время. */
    fun submitTime(key: String, seconds: Int): Boolean {
        val p = prefs ?: return false
        val prev = bestTime(key)
        if (prev == 0 || seconds < prev) {
            p.edit().putInt("besttime_$key", seconds).apply()
            return true
        }
        return false
    }

    /** Серия побед подряд для режима. */
    fun streak(key: String): Int = prefs?.getInt("streak_$key", 0) ?: 0

    /** Всего накоплено очков. */
    fun totalScore(): Int = prefs?.getInt("totalScore", 0) ?: 0

    /** Сколько раз сыграно. */
    fun gamesPlayed(): Int = prefs?.getInt("gamesPlayed", 0) ?: 0

    /** Сколько раз выиграно. */
    fun gamesWon(): Int = prefs?.getInt("gamesWon", 0) ?: 0

    /** Записать попытку (независимо от исхода): +1 к счётчику игр. */
    fun registerStart() {
        val p = prefs ?: return
        p.edit().putInt("gamesPlayed", gamesPlayed() + 1).apply()
    }

    /**
     * Результат победы. Возвращает true, если это новый рекорд для режима.
     * Очки добавляются к общему счёту, серия побед растёт.
     */
    fun submit(key: String, score: Int): Boolean {
        val p = prefs ?: return false
        val isRecord = score > best(key)
        val editor = p.edit()
            .putInt("gamesWon", gamesWon() + 1)
            .putInt("streak_$key", streak(key) + 1)
            .putInt("totalScore", totalScore() + score)
        if (isRecord) editor.putInt("best_$key", score)
        editor.apply()
        return isRecord
    }

    /** Проигрыш: серия побед обнуляется. */
    fun registerLoss(key: String) {
        prefs?.edit()?.putInt("streak_$key", 0)?.apply()
    }
}
