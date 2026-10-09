package com.volna.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.volna.player.data.SettingsStore

/**
 * Плеер-обёртка для пасхалки "Серега Пират".
 * Пока режим включён, запрещает паузу и остановку (даже из уведомления),
 * но перемотка и переключение треков работают.
 */
class PiratGuardPlayer(player: Player) : ForwardingPlayer(player) {

    private val locked: Boolean get() = SettingsStore.pirat.value

    override fun pause() {
        if (!locked) super.pause()
    }

    override fun stop() {
        if (!locked) super.stop()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady || !locked) super.setPlayWhenReady(playWhenReady)
    }
}
