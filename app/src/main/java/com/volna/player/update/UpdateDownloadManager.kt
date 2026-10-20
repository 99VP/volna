package com.volna.player.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.volna.player.BuildConfig
import java.io.File

/**
 * Системный DownloadManager: скачивание продолжается, даже если приложение
 * свернули или закрыли, а прогресс виден в уведомлении.
 */
object UpdateDownloadManager {

    fun enqueue(context: Context, info: UpdateInfo): Long {
        val url = info.apkUrl ?: throw IllegalStateException("В релизе нет APK")
        val dm = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("DownloadManager недоступен")
        val name = "Volna-${info.version}-${info.buildId.ifBlank { "new" }}.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Обновление Волны ${info.version}")
            .setDescription("Сборка ${info.buildId.ifBlank { info.version }}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "volna-update/$name",
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(request)
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "volna-update",
        )
        context.getSharedPreferences("volna_update", Context.MODE_PRIVATE)
            .edit()
            .putLong("downloadId", id)
            .putString("downloadPath", File(dir, name).absolutePath)
            .apply()
        return id
    }

    fun installDownloaded(context: Context, downloadId: Long): Boolean {
        val prefs = context.getSharedPreferences("volna_update", Context.MODE_PRIVATE)
        if (prefs.getLong("downloadId", -1L) != downloadId) return false
        val path = prefs.getString("downloadPath", null) ?: return false
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun currentDownloadId(context: Context): Long =
        context.getSharedPreferences("volna_update", Context.MODE_PRIVATE)
            .getLong("downloadId", -1L)

    fun isDownloading(context: Context): Boolean {
        val id = currentDownloadId(context)
        if (id <= 0L) return false
        val dm = context.getSystemService(DownloadManager::class.java) ?: return false
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return false
        return cursor.use {
            if (!it.moveToFirst()) return@use false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
        }
    }

    fun versionLabel(info: UpdateInfo): String =
        "${info.version} · ${info.buildId.ifBlank { BuildConfig.BUILD_ID }}"
}
