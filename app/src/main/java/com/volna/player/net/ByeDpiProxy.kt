package com.volna.player.net

/**
 * Локальный SOCKS5-обход DPI (ByeDPI, https://github.com/hufrea/byedpi).
 *
 * Это не VPN и не удалённый прокси: процесс слушает 127.0.0.1 и меняет
 * способ отправки первых пакетов TLS-соединения, как zapret на компьютере.
 * Трафик приложения при этом не покидает устройство.
 *
 * Нативная часть собрана из исходников ByeDPI (MIT) и обёртки ByeDPIAndroid
 * (GPL-3.0); проект «Волна» распространяется под GPL-3.0.
 */
class ByeDpiProxy {

    companion object {
        /** Библиотека может отсутствовать на экзотической ABI — тогда просто
         *  работаем напрямую, без обхода. */
        private val nativeAvailable: Boolean by lazy {
            runCatching { System.loadLibrary("volna_byedpi") }.isSuccess
        }

        /** Порт по умолчанию; при занятости пробуем следующие. */
        private val PORTS = intArrayOf(18080, 18081, 18082, 18083)

        /** Стратегии по очереди: сначала мягкая, потом запасная. */
        private val STRATEGIES = listOf(
            arrayOf("--disorder", "1"),
            arrayOf("--split", "1"),
            arrayOf("--fake", "1", "--ttl", "8"),
        )
    }

    private var fd = -1
    private var worker: Thread? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var strategyIndex: Int = -1
        private set

    val isRunning: Boolean get() = fd >= 0

    /**
     * Запускает локальный SOCKS5. Возвращает true, если сокет открылся.
     * Сама проверка доступности YouTube делается снаружи (ProxyRuntime).
     */
    @Synchronized
    fun start(strategy: Int = 0): Boolean {
        if (!nativeAvailable) return false
        if (fd >= 0) return true
        val args = STRATEGIES.getOrNull(strategy) ?: STRATEGIES[0]
        for (p in PORTS) {
            val argv = ArrayList<String>(args.size + 6)
            argv += "ciadpi"
            argv += "-i"
            argv += "127.0.0.1"
            argv += "-p"
            argv += p.toString()
            argv += args
            val socket = runCatching {
                jniCreateSocketWithCommandLine(argv.toTypedArray())
            }.getOrDefault(-1)
            if (socket >= 0) {
                fd = socket
                port = p
                strategyIndex = strategy
                // jniStartProxy блокируется до shutdown; поэтому отдельный поток.
                val t = Thread {
                    runCatching { jniStartProxy(socket) }
                    if (fd == socket) fd = -1
                }
                t.name = "volna-byedpi"
                t.isDaemon = true
                worker = t
                t.start()
                return true
            }
        }
        return false
    }

    @Synchronized
    fun stop() {
        val socket = fd
        fd = -1
        if (socket >= 0) {
            runCatching { jniStopProxy(socket) }
        }
        worker?.let { runCatching { it.join(1200) } }
        worker = null
        port = 0
        strategyIndex = -1
    }

    // JNI-функции из native-lib.c.
    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    private external fun jniStopProxy(fd: Int): Int
}
