package com.volna.player.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.volna.player.data.SettingsStore
import com.volna.player.util.DoH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

enum class BypassState {
    /** Пользователь выключил обход в настройках. */
    DISABLED,
    /** Идёт запуск и проверка. */
    STARTING,
    /** Локальный обход работает. */
    READY,
    /** В системе включён другой VPN: обход приостановлен, чтобы не мешать. */
    VPN_ACTIVE,
    /** Запустить/проверить не удалось. */
    FAILED,
}

/**
 * Управляет локальным обходом DPI и сообщает OkHttp, когда его использовать.
 *
 * Проверка VPN делается при каждом возврате в приложение (ON_RESUME), а не
 * только при первом запуске: пользователь может свернуть приложение, включить
 * VPN и вернуться. Чужой VPN Android не разрешает выключать программно,
 * поэтому при активном VPN обход просто приостанавливается.
 */
object ProxyRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val proxy = ByeDpiProxy()
    private var httpProxy: LocalHttpProxy? = null

    private val _state = MutableStateFlow(BypassState.DISABLED)
    val state: StateFlow<BypassState> = _state.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    @Volatile
    private var ready = false

    @Volatile
    private var useRelay = false

    @Volatile
    private var lastCheckAt = 0L

    /** Домены, которые имеет смысл пускать через локальный обход. */
    private val mediaHosts = listOf(
        "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com",
        "sndcdn.com", "bcbits.com",
    )

    /** API/страницы, которые пробуем напрямую, а через обход — только если
     *  прямой запрос не удался. */
    private val apiHosts = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "yt.be",
        "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com",
        "googleapis.com", "gstatic.com", "google.com",
        "soundcloud.com", "sndcdn.com", "bandcamp.com", "bcbits.com",
    )

    private fun matches(host: String, domains: List<String>): Boolean {
        val h = host.lowercase()
        return domains.any { h == it || h.endsWith(".$it") }
    }

    private fun selectorFor(domains: List<String>): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val host = uri.host.orEmpty()
            if (!ready || !matches(host, domains)) return listOf(Proxy.NO_PROXY)
            return if (useRelay) {
                val relay = httpProxy?.port ?: 0
                if (relay <= 0) listOf(Proxy.NO_PROXY)
                else listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relay)))
            } else {
                val socks = proxy.port
                if (socks <= 0) listOf(Proxy.NO_PROXY)
                else listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks)))
            }
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {
            ready = false
        }
    }

    /** Только медиа-CDAT: используется ExoPlayer и загрузчиком файлов. */
    val mediaProxySelector: ProxySelector = selectorFor(mediaHosts)

    /** API/поиск: используется как запасной путь, если прямой запрос упал. */
    val apiProxySelector: ProxySelector = selectorFor(apiHosts)

    val isReady: Boolean get() = ready

    fun init(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }

    /** Вызывать при каждом возврате в приложение (ON_RESUME). */
    fun refresh(context: Context, force: Boolean = false) {
        scope.launch { refreshNow(context.applicationContext, force) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        SettingsStore.setBypass(enabled)
        refresh(context, force = true)
    }

    fun retry(context: Context) {
        refresh(context, force = true)
    }

    private val mutex = Mutex()

    private suspend fun refreshNow(context: Context, force: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            refreshLocked(context, force)
        }
    }

    private suspend fun refreshLocked(context: Context, force: Boolean) {
        if (!SettingsStore.bypass.value) {
            stop()
            _state.value = BypassState.DISABLED
            _detail.value = ""
            return
        }

        // Повторные ON_RESUME/инициализация не должны запускать проверку
        // заново, пока одна уже идёт, и не должны долбить сеть после ошибки.
        if (!force) {
            if (_state.value == BypassState.STARTING) return
            if (_state.value == BypassState.FAILED &&
                System.currentTimeMillis() - lastCheckAt < 120_000L
            ) return
        }

        if (isVpnActive(context)) {
            stop()
            _state.value = BypassState.VPN_ACTIVE
            _detail.value = "Включён VPN — обход приостановлен"
            return
        }

        if (ready && proxy.isRunning && !force) {
            _state.value = BypassState.READY
            _detail.value = "Локальный обход работает"
            return
        }
        if (ready && proxy.isRunning && force &&
            System.currentTimeMillis() - lastCheckAt < 60_000L
        ) {
            _state.value = BypassState.READY
            _detail.value = "Локальный обход работает"
            return
        }

        _state.value = BypassState.STARTING
        _detail.value = "Запускаю локальный обход…"
        ready = false
        stop()

        var ok = false
        for (strategy in 0..2) {
            _detail.value = "Проверяю обход (${strategy + 1}/3)…"
            if (!proxy.start(strategy)) continue
            useRelay = false
            if (healthCheckSocks(proxy.port)) {
                ok = true
                break
            }
            // Прямой SOCKS не прошёл (например, из-за DNS) — пробуем тот же
            // обход через локальный HTTP-редирект с DoH-резолвом.
            val relay = LocalHttpProxy(proxy.port)
            httpProxy?.stop()
            httpProxy = relay
            relay.start()
            if (healthCheckRelay(relay.port)) {
                useRelay = true
                ok = true
                break
            }
            relay.stop()
            httpProxy = null
            proxy.stop()
        }
        lastCheckAt = System.currentTimeMillis()
        if (ok) {
            ready = true
            _state.value = BypassState.READY
            _detail.value = "Локальный обход работает (стратегия ${proxy.strategyIndex + 1})"
        } else {
            stop()
            _state.value = BypassState.FAILED
            _detail.value = "YouTube не ответил через обход"
        }
    }

    private fun stop() {
        ready = false
        useRelay = false
        httpProxy?.stop()
        httpProxy = null
        proxy.stop()
    }

    fun isVpnActive(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.getOrDefault(false)
    }

    private fun healthCheckSocks(port: Int): Boolean = healthCheck(port, Proxy.Type.SOCKS)

    private fun healthCheckRelay(port: Int): Boolean = healthCheck(port, Proxy.Type.HTTP)

    private fun healthCheck(port: Int, type: Proxy.Type): Boolean {
        if (port <= 0) return false
        val p = Proxy(type, InetSocketAddress("127.0.0.1", port))
        val client = OkHttpClient.Builder()
            .proxy(p)
            .dns(DoH.dns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        val urls = listOf("https://www.youtube.com/generate_204")
        return urls.all { url ->
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) VolnaPlayer")
                        .build(),
                ).execute().use { it.code in 200..499 }
            }.getOrDefault(false)
        }
    }

    fun statusText(): String = when (_state.value) {
        BypassState.DISABLED -> "Выключен"
        BypassState.STARTING -> "Запуск…"
        BypassState.READY -> "Работает"
        BypassState.VPN_ACTIVE -> "Пауза: включён VPN"
        BypassState.FAILED -> "Не удалось запустить"
    }
}
