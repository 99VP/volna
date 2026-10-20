package com.volna.player.data

import com.volna.player.net.ProxyRuntime
import com.volna.player.util.DoH
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkRequest

/**
 * Реализация Downloader, которую NewPipeExtractor использует для всех своих
 * HTTP-запросов: поиск, парсинг страниц и получение ссылок на потоки.
 *
 * Сначала идём напрямую (так поиск работает даже если локальный обход ещё
 * проверяется). Если прямой запрос упал или сервер ответил 403/429, повторяем
 * через локальный ByeDPI — на сетях с DPI это спасает извлечение ссылок.
 */
class VolnaDownloader : Downloader() {

    private val directClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(DoH.dns)
        .build()

    private val proxyClient: OkHttpClient = directClient.newBuilder()
        .proxySelector(ProxyRuntime.apiProxySelector)
        .build()

    override fun execute(request: Request): Response {
        val first = runCatching { executeWith(request, directClient) }
        val directResponse = first.getOrNull()
        if (directResponse != null && directResponse.responseCode() != 429 &&
            directResponse.responseCode() !in intArrayOf(403, 500, 502, 503, 504)
        ) {
            return directResponse
        }
        if (ProxyRuntime.isReady) {
            val second = runCatching { executeWith(request, proxyClient) }
            second.getOrNull()?.let {
                if (it.responseCode() == 429) throw ReCaptchaException("reCaptcha", request.url())
                return it
            }
            second.exceptionOrNull()?.let { throw it }
        }
        first.exceptionOrNull()?.let { throw it }
        if (directResponse?.responseCode() == 429) {
            throw ReCaptchaException("reCaptcha", request.url())
        }
        return directResponse ?: throw IOException("Пустой ответ")
    }

    private fun executeWith(request: Request, client: OkHttpClient): Response {
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
