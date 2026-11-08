package app.localvpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import org.amnezia.awg.hevtunnel.TProxyService
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Прокси-туннель: весь трафик телефона идёт через SOCKS5-сервер пользователя.
 * Android создаёт VPN-интерфейс, а движок hev-socks5-tunnel пересылает из него
 * соединения на прокси-сервер. DNS-запросы тоже уходят через прокси (режим mapdns):
 * телефон получает временный адрес, а сервер сам находит сайт по имени.
 */
class ProxyTunnelService : VpnService() {

    @Volatile private var fd: ParcelFileDescriptor? = null
    @Volatile private var engine: Thread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onRevoke() {
        // Другое VPN-приложение заняло VPN — выключаемся
        stopTunnel()
        ProxyTunnels.notifyChanged(null, "Прокси-туннель выключен: VPN включило другое приложение")
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun startTunnel(cfg: JSONObject) {
        stopTunnel()
        val id = cfg.getString("id")
        val name = cfg.optString("name", "прокси")
        val builder = Builder()
            .setSession("LocalProxy: $name")
            .setMtu(MTU)
            .addAddress(TUN_V4, 32)
            .addAddress(TUN_V6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(MAP_DNS)
        // Соединения самого движка к прокси-серверу идут мимо туннеля, иначе была бы петля
        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) { }
        val pfd = builder.establish() ?: throw IllegalStateException("Нет разрешения на VPN. Включите туннель ещё раз и нажмите «ОК»")
        fd = pfd

        val conf = File(cacheDir, "proxy-tunnel.yml")
        conf.writeText(yaml(cfg))
        engine = Thread({ TProxyService.TProxyStartService(conf.absolutePath, pfd.fd) }, "proxy-tunnel").apply {
            isDaemon = true
            start()
        }
        runningId = id
    }

    fun stopTunnel() {
        if (engine != null) {
            try { TProxyService.TProxyStopService() } catch (e: Throwable) { }
            try { engine?.join(2000) } catch (e: InterruptedException) { }
            engine = null
        }
        try { fd?.close() } catch (e: Exception) { }
        fd = null
        runningId = null
    }

    private fun yaml(cfg: JSONObject): String {
        fun q(v: String) = "'" + v.replace("'", "''") + "'"
        val user = cfg.optString("user")
        val pass = cfg.optString("pass")
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $MTU")
            appendLine("  ipv4: ${q(TUN_V4)}")
            appendLine("  ipv6: ${q(TUN_V6)}")
            appendLine("socks5:")
            appendLine("  address: ${q(cfg.getString("host"))}")
            appendLine("  port: ${cfg.getString("port").toInt()}")
            appendLine("  udp: 'udp'")
            if (user.isNotEmpty()) appendLine("  username: ${q(user)}")
            if (user.isNotEmpty()) appendLine("  password: ${q(pass)}")
            appendLine("mapdns:")
            appendLine("  address: ${q(MAP_DNS)}")
            appendLine("  port: 53")
            appendLine("  network: '240.0.0.0'")
            appendLine("  netmask: '240.0.0.0'")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  task-stack-size: 24576")
            appendLine("  connect-timeout: 10000")
            appendLine("  log-level: warn")
        }
    }

    companion object {
        private const val MTU = 1500
        private const val TUN_V4 = "198.18.0.1"
        private const val TUN_V6 = "fc00::1"
        private const val MAP_DNS = "198.18.0.2"

        @Volatile var instance: ProxyTunnelService? = null
        /** id включённого прокси-туннеля или null. */
        @Volatile var runningId: String? = null
    }
}

object ProxyTunnels {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var listener: ((String?, String?) -> Unit)? = null

    fun setListener(l: ((String?, String?) -> Unit)?) { listener = l }
    fun notifyChanged(id: String?, error: String?) { listener?.invoke(id, error) }
    fun running(): String? = ProxyTunnelService.runningId

    /** cfg — JSON {id, name, type, host, port, user, pass}. */
    fun apply(context: Context, on: Boolean, cfg: String, done: (String?, String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            var error: String? = null
            try {
                if (on) {
                    if (VpnController.running(app).isNotEmpty())
                        throw IllegalStateException("Сначала выключите VPN: прокси-туннель тоже работает через VPN Android")
                    val json = JSONObject(cfg)
                    if (json.optString("type") != "SOCKS5")
                        throw IllegalStateException("Для всего телефона подходит только SOCKS5. HTTP-прокси укажите вручную в настройках браузера")
                    service(app).startTunnel(json)
                } else {
                    ProxyTunnelService.instance?.let { it.stopTunnel(); it.stopSelf() }
                }
            } catch (e: Throwable) {
                error = VpnController.humanError(e)
                ProxyTunnelService.instance?.let { it.stopTunnel(); it.stopSelf() }
            }
            done(running(), error)
        }
    }

    /** Запускает службу и ждёт, пока Android её создаст. */
    private fun service(app: Context): ProxyTunnelService {
        ProxyTunnelService.instance?.let { return it }
        app.startService(Intent(app, ProxyTunnelService::class.java))
        repeat(50) {
            ProxyTunnelService.instance?.let { return it }
            Thread.sleep(100)
        }
        throw IllegalStateException("Android не запустил прокси-туннель. Попробуйте ещё раз")
    }

    /** Сколько данных прошло через туннель: {tx, rx} в байтах. */
    fun stats(): String {
        val r = JSONObject()
        if (running() == null) return r.toString()
        try {
            val s = TProxyService.TProxyGetStats()
            if (s != null && s.size >= 4) { r.put("tx", s[1]); r.put("rx", s[3]) }
        } catch (e: Throwable) { }
        return r.toString()
    }
}
