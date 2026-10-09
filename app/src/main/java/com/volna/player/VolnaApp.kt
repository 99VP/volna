package com.volna.player

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import com.volna.player.data.GameStore
import com.volna.player.data.GameSave
import com.volna.player.data.SettingsStore
import com.volna.player.data.VolnaDownloader
import java.io.PrintWriter
import java.io.StringWriter

class VolnaApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Ловим падения (в т.ч. сервиса плеера) и сохраняем текст, чтобы показать его в приложении.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                getSharedPreferences("volna_crash", MODE_PRIVATE)
                    .edit().putString("last", sw.toString()).commit()
            }
            previous?.uncaughtException(thread, error)
        }

        SettingsStore.init(this)
        GameStore.init(this)
        GameSave.init(this)
        // Инициализация движка извлечения аудио до первого использования.
        runCatching { NewPipe.init(VolnaDownloader(), Localization("ru", "RU")) }
    }
}
