package com.volna.player.data

import com.volna.player.util.DoH
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkRequest

/**
 * Реализация Downloader, которую NewPipeExtractor использует для всех своих
 * HTTP-запросов: поиск, парсинг страниц и получение ссылок на потоки.
 */
class VolnaDownloader : Downloader() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(DoH.dns)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            method == "GET" || method == "HEAD" -> null
            data != null -> data.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            else -> ByteArray(0).toRequestBody(null)
        }

        val builder = OkRequest.Builder()
            .url(request.url())
            .method(method, body)
        for ((name, values) in request.headers()) {
            for (value in values) builder.addHeader(name, value)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}
