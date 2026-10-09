package com.volna.player.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.volna.player.R

/**
 * Свой провайдер уведомления: строим его сами и добавляем кнопки
 * «лайк / перемешать / повтор».
 *
 * ВАЖНО: MediaSession НЕ трогаем — никаких onConnect/setCustomLayout/setCallback.
 * Поэтому запуск плеера не ломается. Кнопки живут в уведомлении приложения.
 */
class VolnaNotificationProvider(
    private val context: Context,
    private val onLike: () -> Unit,
) : MediaNotification.Provider {

    private val iconLike = IconCompat.createWithResource(context, R.drawable.ic_favorite_border)
    private val iconShuffle = IconCompat.createWithResource(context, R.drawable.ic_shuffle)
    private val iconRepeat = IconCompat.createWithResource(context, R.drawable.ic_repeat)
    private val iconPrev = IconCompat.createWithResource(context, R.drawable.ic_prev)
    private val iconNext = IconCompat.createWithResource(context, R.drawable.ic_next)
    private val iconPlay = IconCompat.createWithResource(context, R.drawable.ic_play)
    private val iconPause = IconCompat.createWithResource(context, R.drawable.ic_pause)

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        ensureChannel()
        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString().orEmpty().ifBlank { "Волна" }
        val artist = metadata.artist?.toString().orEmpty()

        val isPlaying = player.isPlaying
        val prev = actionFactory.createMediaAction(
            mediaSession, iconPrev, "Назад", Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        val playPause = actionFactory.createMediaAction(
            mediaSession,
            if (isPlaying) iconPause else iconPlay,
            if (isPlaying) "Пауза" else "Играть",
            Player.COMMAND_PLAY_PAUSE,
        )
        val next = actionFactory.createMediaAction(
            mediaSession, iconNext, "Вперёд", Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )
        val like = actionFactory.createCustomAction(
            mediaSession, iconLike, "В избранное", ACTION_LIKE, Bundle.EMPTY,
        )
        val shuffle = actionFactory.createMediaAction(
            mediaSession, iconShuffle, "Перемешать", Player.COMMAND_SET_SHUFFLE_MODE,
        )
        val repeat = actionFactory.createMediaAction(
            mediaSession, iconRepeat, "Повтор", Player.COMMAND_SET_REPEAT_MODE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(mediaSession.sessionActivity)
            .setDeleteIntent(
                actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_STOP.toLong()),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prev)
            .addAction(playPause)
            .addAction(next)
            .addAction(like)
            .addAction(shuffle)
            .addAction(repeat)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )

        return MediaNotification(NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
        if (action == ACTION_LIKE) {
            onLike()
            return true
        }
        return false
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Воспроизведение",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "volna_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_LIKE = "com.volna.player.LIKE"
    }
}
