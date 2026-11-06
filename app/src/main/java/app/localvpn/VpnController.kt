package app.localvpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Включает и выключает туннели через библиотеку AmneziaWG (GoBackend). Она понимает и обычные
 * WireGuard-конфиги, и конфиги с маскировкой (Jc, Jmin, Jmax, S1, S2, H1–H4, I1–I5).
 * Одновременно может работать только один туннель — так устроен VPN в Android.
 */
object VpnController {

    /** Туннель для библиотеки. Один объект на имя: библиотека сравнивает туннели по ссылке. */
    private class NamedTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {}
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val tunnels = HashMap<String, NamedTunnel>()
    @Volatile private var backend: GoBackend? = null

    private fun backend(context: Context): GoBackend =
        backend ?: synchronized(this) {
            backend ?: GoBackend(context.applicationContext, NoopTunnelActionHandler()).also { backend = it }
        }

    /** Имена туннелей, которые сейчас работают. */
    fun running(context: Context): List<String> =
        try {
            backend(context).runningTunnelNames.toList()
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Меняет состояние туннеля в фоне. По завершении вызывает [done] со списком
     * работающих туннелей и текстом ошибки (или null).
     */
    fun apply(
        context: Context,
        name: String,
        on: Boolean,
        configText: String,
        done: (running: List<String>, error: String?) -> Unit,
    ) {
        val app = context.applicationContext
        worker.execute {
            var error: String? = null
            try {
                if (Tunnel.isNameInvalid(name)) throw IllegalStateException("Название туннеля «$name» не подходит: только латиница, цифры и _ = + . -, до 15 символов")
                val tunnel = synchronized(tunnels) { tunnels.getOrPut(name) { NamedTunnel(name) } }
                if (on) {
                    val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
                    if (config.peers.isEmpty()) throw IllegalStateException("Добавьте пир: без сервера туннелю некуда подключаться")
                    for (pkg in config.getInterface().includedApplications) {
                        if (!isInstalled(app, pkg)) throw IllegalStateException("Приложение $pkg не установлено на телефоне. Выберите другое внизу главного экрана")
                    }
                    backend(app).setState(tunnel, Tunnel.State.UP, config)
                } else {
                    backend(app).setState(tunnel, Tunnel.State.DOWN, null)
                }
            } catch (e: Exception) {
                error = humanError(e)
            }
            done(running(app), error)
        }
    }

    /**
     * Статистика работающего туннеля: сколько получено и отправлено байт и когда сервер
     * последний раз ответил (latestHandshake, мс с 1970 года; 0 — ни разу).
     */
    fun stats(context: Context): String {
        val result = JSONObject()
        try {
            val name = running(context).firstOrNull() ?: return result.toString()
            val tunnel = synchronized(tunnels) { tunnels[name] } ?: return result.toString()
            val stats = backend(context).getStatistics(tunnel)
            var handshake = 0L
            for (key in stats.peers()) {
                val peer = stats.peer(key) ?: continue
                if (peer.latestHandshakeEpochMillis() > handshake) handshake = peer.latestHandshakeEpochMillis()
            }
            result.put("name", name)
            result.put("rx", stats.totalRx())
            result.put("tx", stats.totalTx())
            result.put("handshake", handshake)
            result.put("now", System.currentTimeMillis())
        } catch (e: Exception) {
            result.put("error", e.message ?: e.javaClass.simpleName)
        }
        return result.toString()
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Понятный текст ошибки на русском. */
    private fun humanError(e: Exception): String = when (e) {
        is BackendException -> when (e.reason) {
            BackendException.Reason.VPN_NOT_AUTHORIZED ->
                "Нет разрешения на VPN. Включите туннель ещё раз и нажмите «ОК» в окне Android"
            BackendException.Reason.DNS_RESOLUTION_FAILURE ->
                "Не удалось найти сервер ${e.format.firstOrNull() ?: ""}. Проверьте Endpoint и интернет"
            BackendException.Reason.UNABLE_TO_START_VPN, BackendException.Reason.TUN_CREATION_ERROR ->
                "Android не дал запустить VPN. Проверьте, не включён ли «Постоянный VPN» у другого приложения"
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE ->
                "Туннель не запустился (код ${e.format.firstOrNull() ?: "?"}). Проверьте ключи и адреса"
            else -> "Не удалось включить туннель: ${e.reason}"
        }
        is BadConfigException -> {
            val field = e.location.getName().ifEmpty { e.section.getName() }
            val what = when (e.reason) {
                BadConfigException.Reason.INVALID_KEY -> "неверный ключ"
                BadConfigException.Reason.INVALID_NUMBER -> "неверное число"
                BadConfigException.Reason.INVALID_VALUE -> "неверное значение"
                BadConfigException.Reason.MISSING_ATTRIBUTE -> "не заполнено"
                BadConfigException.Reason.MISSING_SECTION -> "нет раздела"
                else -> "ошибка в записи"
            }
            "Ошибка в настройках: $field — $what. Откройте туннель и нажмите «Изменить»"
        }
        is IllegalStateException -> e.message ?: "Не удалось включить туннель"
        else -> "Не удалось включить туннель: ${e.message ?: e.javaClass.simpleName}"
    }
}
