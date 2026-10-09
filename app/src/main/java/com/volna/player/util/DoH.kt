package com.volna.player.util

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-резолвер с поддержкой DNS-over-HTTPS (Cloudflare).
 *
 * Зачем: в некоторых сетях DNS-ответы для YouTube и CDN подменяются провайдером
 * (как и у сервера «Волна», который делает резолв через 1.1.1.1). Здесь резолвим
 * проблемные домены через DoH, а всё остальное - обычным системным DNS.
 */
object DoH {

    private val dohHosts = listOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com",
        "ggpht.com", "googleusercontent.com", "googleapis.com",
        "soundcloud.com", "sndcdn.com", "bandcamp.com",
    )

    private data class Entry(val addrs: List<InetAddress>, val ts: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val dohClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.let { if (now - it.ts < 10 * 60_000L) return it.addrs }

            val useDoh = dohHosts.any { hostname == it || hostname.endsWith(".$it") }
            if (useDoh) {
                val addrs = runCatching { resolveDoh(hostname) }.getOrDefault(emptyList())
                if (addrs.isNotEmpty()) {
                    cache[hostname] = Entry(addrs, now)
                    return addrs
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun resolveDoh(host: String): List<InetAddress> {
        val request = Request.Builder()
            .url("https://1.1.1.1/dns-query?name=$host&type=A")
            .header("accept", "application/dns-json")
            .build()
        dohClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return emptyList()
            val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<InetAddress>(answers.length())
            for (i in 0 until answers.length()) {
                val answer = answers.optJSONObject(i) ?: continue
                if (answer.optInt("type") == 1) {
                    runCatching { out.add(InetAddress.getByName(answer.optString("data"))) }
                }
            }
            return out
        }
    }
}
