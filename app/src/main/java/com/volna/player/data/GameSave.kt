package com.volna.player.data

import android.content.Context
import android.content.SharedPreferences

/** Хранилище незавершённых партий мини-игр (Сапёр, Судоку). */
object GameSave {

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("volna_gamesave", Context.MODE_PRIVATE)
        }
    }

    fun get(key: String): String? = prefs?.getString(key, null)

    fun put(key: String, value: String?) {
        val p = prefs ?: return
        if (value.isNullOrBlank()) p.edit().remove(key).apply()
        else p.edit().putString(key, value).apply()
    }
}
