package app.localvpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Включает и выключает туннели через официальную библиотеку WireGuard (GoBackend).
 * Одновременно может работать только один туннель — так устроен VPN в Android.
 */
object VpnController {

    /** Туннель для библиотеки. Один объект на имя: библиотека сравнивает туннели по ссылке. */
    private class NamedTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {}
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val tunnels = HashMap<String, NamedTunnel>()
    @Volatile private var backend: GoBackend? = null

    private fun backend(context: Context): GoBackend =
        backend ?: synchronized(this) {
            backend ?: GoBackend(context.applicationContext).also { backend = it }
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
