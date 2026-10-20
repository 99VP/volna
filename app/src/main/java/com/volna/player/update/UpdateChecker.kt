package com.volna.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.volna.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val notes: String = "",
)

/**
 * Проверка обновлений через публичный GitHub Releases.
 * Токен в приложение не встраивается: репозиторий 99VP/volna публичный.
 */
object UpdateChecker {

    private const val REPO = "99VP/volna"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("volna_update", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong("checkedAt", 0L) < CHECK_INTERVAL_MS) {
                return@withContext cached(prefs)
            }
            val result = runCatching { fetch() }.getOrNull()
            if (result == null) {
                prefs.edit().putLong("checkedAt", now).apply()
                return@withContext cached(prefs)
            }
            prefs.edit()
                .putLong("checkedAt", now)
                .putString("version", result.version)
                .putString("releaseUrl", result.releaseUrl)
                .putString("apkUrl", result.apkUrl ?: "")
                .putString("notes", result.notes)
                .apply()
            result
        }

    private fun cached(prefs: android.content.SharedPreferences): UpdateInfo? {
        val version = prefs.getString("version", null) ?: return null
        val releaseUrl = prefs.getString("releaseUrl", null) ?: return null
        val apk = prefs.getString("apkUrl", null).orEmpty().ifBlank { null }
        val notes = prefs.getString("notes", "").orEmpty()
        val info = UpdateInfo(version, releaseUrl, apk, notes)
        return if (isNewer(info.version)) info else null
    }

    private fun fetch(): UpdateInfo? {
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Volna-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
            if (tag.isBlank() || !isNewer(tag)) return null
            val html = json.optString("html_url")
            val notes = json.optString("body").take(1200)
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url").ifBlank { null }
                        if (apk != null) break
                    }
                }
            }
            return UpdateInfo(
                version = tag,
                releaseUrl = html,
                apkUrl = apk,
                notes = notes,
            )
        }
    }

    /** Простое сравнение версий вида 1.1 / 1.2.3. */
    fun isNewer(candidate: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(BuildConfig.VERSION_NAME)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }

    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: throw IllegalStateException("В релизе нет APK")
        val dir = File(context.cacheDir, "volna-update").apply { mkdirs() }
        val target = File(dir, "volna-${info.version}.apk")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Volna-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Пустой ответ")
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
        }
        target
    }

    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
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
        context.startActivity(intent)
    }

    fun openRelease(context: Context, info: UpdateInfo) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
