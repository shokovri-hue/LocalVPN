package app.localvpn

import android.content.Context
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.ProxyGoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.config.proxy.HttpProxy
import org.amnezia.awg.config.proxy.Proxy
import org.amnezia.awg.config.proxy.Socks5Proxy
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * LocalProxy: запускает WARP (AmneziaWG) без VPN, а на телефоне поднимает прокси-серверы
 * SOCKS5 и HTTP. Через них ходят приложения, в которых прокси указан вручную.
 */
object ProxyController {
    const val NAME = "localproxy"

    private class NamedTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {}
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val tunnel = NamedTunnel(NAME)
    @Volatile private var backend: ProxyGoBackend? = null

    private fun backend(context: Context): ProxyGoBackend =
        backend ?: synchronized(this) {
            backend ?: ProxyGoBackend(context.applicationContext, NoopTunnelActionHandler()).also { backend = it }
        }

    fun isRunning(): Boolean =
        try {
            backend?.runningTunnelNames?.contains(NAME) == true
        } catch (e: Throwable) {
            false
        }

    /**
     * Запускает или останавливает прокси. options — JSON {socks, http, user, pass}:
     * адреса, на которых слушать (127.0.0.1:1080 или 0.0.0.0:1080 для раздачи по Wi-Fi), и вход.
     */
    fun apply(context: Context, on: Boolean, configText: String, options: String, done: (up: Boolean, error: String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            var error: String? = null
            try {
                if (on) {
                    if (VpnController.running(app).isNotEmpty())
                        throw IllegalStateException("Сначала выключите VPN: прокси и VPN не работают одновременно")
                    val parsed = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
                    if (parsed.peers.isEmpty()) throw IllegalStateException("В настройках прокси нет сервера")
                    val o = JSONObject(options)
                    val user = o.optString("user").ifEmpty { null }
                    val pass = o.optString("pass").ifEmpty { null }
                    val socks = o.optString("socks", "127.0.0.1:1080")
                    val http = o.optString("http", "127.0.0.1:8080")
                    val proxies = listOf<Proxy>(Socks5Proxy(socks, user, pass), HttpProxy(http, user, pass))
                    val config = Config.Builder()
                        .setInterface(parsed.getInterface())
                        .addPeers(parsed.peers)
                        .setDnsSettings(parsed.dnsSettings)
                        .addProxies(proxies)
                        .build()
                    // Новые настройки (например, раздача по Wi-Fi) — перезапускаем
                    if (isRunning()) backend(app).setState(tunnel, Tunnel.State.DOWN, null)
                    val shown = if (socks.startsWith("0.0.0.0")) "${lanIp().ifEmpty { "IP телефона" }}:${socks.substringAfter(':')}" else socks
                    ProxyService.start(app, "SOCKS5 $shown · HTTP ${http.substringAfter(':')}")
                    backend(app).setState(tunnel, Tunnel.State.UP, config)
                } else {
                    backend(app).setState(tunnel, Tunnel.State.DOWN, null)
                }
            } catch (e: Throwable) {
                error = VpnController.humanError(e)
            }
            val up = isRunning()
            if (!up) ProxyService.stop(app)
            done(up, error)
        }
    }

    /** Статистика прокси: получено и отправлено байт, когда WARP последний раз ответил. */
    fun stats(context: Context): String {
        val result = JSONObject()
        try {
            if (!isRunning()) return result.toString()
            val stats = backend(context).getStatistics(tunnel)
            var handshake = 0L
            for (key in stats.peers()) {
                val peer = stats.peer(key) ?: continue
                if (peer.latestHandshakeEpochMillis() > handshake) handshake = peer.latestHandshakeEpochMillis()
            }
            result.put("name", NAME)
            result.put("rx", stats.totalRx())
            result.put("tx", stats.totalTx())
            result.put("handshake", handshake)
            result.put("now", System.currentTimeMillis())
        } catch (e: Throwable) {
            result.put("error", e.message ?: e.javaClass.simpleName)
        }
        return result.toString()
    }

    /** IPv4-адрес телефона в Wi-Fi сети (или в точке доступа), например 192.168.1.5. */
    fun lanIp(): String {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
                .filter { (_, addr) -> addr.isSiteLocalAddress }
            val preferred = candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") }
                ?: candidates.firstOrNull { (name, _) -> !name.startsWith("tun") && !name.startsWith("rmnet") }
            preferred?.second?.hostAddress ?: ""
        } catch (e: Throwable) {
            ""
        }
    }
}
