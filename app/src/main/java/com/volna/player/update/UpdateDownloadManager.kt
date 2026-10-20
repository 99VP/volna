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

    enum class State { NONE, RUNNING, DONE, FAILED }

    enum class InstallResult { OK, NEED_PERMISSION, STALE, FAILED }

    private fun prefs(context: Context) =
        context.getSharedPreferences("volna_update", Context.MODE_PRIVATE)

    private fun expectedName(info: UpdateInfo): String =
        "Volna-${info.version}-${info.buildId.ifBlank { "new" }}.apk"

    private fun expectedFile(context: Context, info: UpdateInfo): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "volna-update",
        )
        return File(dir, expectedName(info))
    }

    private fun matchesCurrent(context: Context, info: UpdateInfo): Boolean {
        val path = prefs(context).getString("downloadPath", null) ?: return false
        return File(path).absolutePath == expectedFile(context, info).absolutePath
    }

    private fun rawState(context: Context, id: Long): State {
        if (id <= 0L) return State.NONE
        val dm = context.getSystemService(DownloadManager::class.java) ?: return State.NONE
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return State.NONE
        return cursor.use {
            if (!it.moveToFirst()) return@use State.NONE
            when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> State.RUNNING
                DownloadManager.STATUS_SUCCESSFUL -> State.DONE
                DownloadManager.STATUS_FAILED -> State.FAILED
                else -> State.NONE
            }
        }
    }

    private fun installedVersionCode(context: Context): Long =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

    /** Проверяем сам APK, а не только имя файла: старый файл с тем же именем
     *  или уже установленная сборка не должны открывать установщик. */
    private fun isNewerApk(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return false
        if (archive.packageName != context.packageName) return false
        return archive.longVersionCode > installedVersionCode(context)
    }

    fun enqueue(context: Context, info: UpdateInfo): Long {
        val url = info.apkUrl ?: throw IllegalStateException("В релизе нет APK")
        val dm = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("DownloadManager недоступен")

        // Старую загрузку (например, предыдущей сборки) убираем, чтобы она
        // не висела в уведомлениях и не путалась с новой.
        val oldId = currentDownloadId(context)
        if (oldId > 0L) runCatching { dm.remove(oldId) }

        val file = expectedFile(context, info)
        file.parentFile?.mkdirs()
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
                "volna-update/${file.name}",
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(request)
        prefs(context).edit()
            .putLong("downloadId", id)
            .putString("downloadPath", file.absolutePath)
            .apply()
        return id
    }

    fun currentDownloadId(context: Context): Long =
        prefs(context).getLong("downloadId", -1L)

    /**
     * Состояние только для конкретного обновления. Если в prefs остался
     * файл/загрузка другой сборки, возвращается NONE, а не DONE.
     */
    fun state(context: Context, info: UpdateInfo): State {
        val id = currentDownloadId(context)
        if (id <= 0L || !matchesCurrent(context, info)) return State.NONE
        val raw = rawState(context, id)
        if (raw != State.DONE) return raw
        return if (isNewerApk(context, expectedFile(context, info))) State.DONE else State.FAILED
    }

    fun isDownloading(context: Context, info: UpdateInfo): Boolean =
        state(context, info) == State.RUNNING

    fun isReadyFor(context: Context, info: UpdateInfo): Boolean =
        state(context, info) == State.DONE

    fun progress(context: Context, info: UpdateInfo): Float? {
        if (state(context, info) != State.RUNNING) return null
        val id = currentDownloadId(context)
        val dm = context.getSystemService(DownloadManager::class.java) ?: return null
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val done = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        }
    }

    fun installCurrent(context: Context, info: UpdateInfo): InstallResult {
        if (!isReadyFor(context, info)) return InstallResult.STALE
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return InstallResult.NEED_PERMISSION
        }
        val file = expectedFile(context, info)
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
        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            InstallResult.OK
        } else {
            InstallResult.FAILED
        }
    }

    fun versionLabel(info: UpdateInfo): String =
        "${info.version} · ${info.buildId.ifBlank { BuildConfig.BUILD_ID }}"
}
