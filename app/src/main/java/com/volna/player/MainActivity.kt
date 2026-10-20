package com.volna.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.volna.player.ui.VolnaRoot
import com.volna.player.ui.WatchLink
import com.volna.player.ui.theme.VolnaTheme

class MainActivity : ComponentActivity() {

    private var pendingWatch by mutableStateOf<WatchLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        pendingWatch = parseWatchLink(intent)
        setContent {
            VolnaTheme {
                VolnaRoot(
                    initialWatch = pendingWatch,
                    onWatchConsumed = {
                        pendingWatch = null
                        // Чтобы поворот экрана не открыл тот же просмотр снова.
                        intent?.data = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingWatch = parseWatchLink(intent)
    }

    private fun parseWatchLink(intent: Intent?): WatchLink? {
        val data = intent?.data ?: return null
        if (data.scheme != "volna" || data.host != "watch") return null
        val url = data.getQueryParameter("url")
        val room = data.getQueryParameter("room")
        val server = data.getQueryParameter("server")
        if (url.isNullOrBlank() && room.isNullOrBlank()) return null
        val seconds = data.getQueryParameter("t")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return WatchLink(
            url = url,
            positionMs = seconds * 1000L,
            room = room,
            server = server,
        )
    }
}
