package app.localvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Автообновление: проверяет последнюю версию в GitHub Releases, скачивает APK и ставит его
 * через PackageInstaller. Android хотя бы один раз попросит подтвердить установку; на Android 12+
 * последующие обновления, которые ставит само приложение, обычно проходят без вопросов.
 *
 * Состояние передаётся странице как JSON:
 * {state: none|available|downloading|ready|installing|error, version, size, progress, error, metered}
 */
object Updater {
    private const val API = "https://api.github.com/repos/shokovri-hue/LocalVPN/releases/latest"
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var state = JSONObject().put("state", "none")
    @Volatile private var apkUrl = ""
    @Volatile private var listener: ((String) -> Unit)? = null
    /** Открыто ли приложение на экране: из фона окно подтверждения показываем уведомлением. */
    @Volatile var appVisible = false

    fun setListener(l: ((String) -> Unit)?) { listener = l }
    fun state(): String = state.toString()

    private fun publish(s: JSONObject) { state = s; listener?.invoke(s.toString()) }

    fun currentVersion(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** force — проверить сейчас, даже если недавно проверяли (кнопка «Проверить обновления»). */
    fun check(context: Context, force: Boolean) {
        val app = context.applicationContext
        worker.execute {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val busy = state.optString("state") in setOf("downloading", "installing")
            if (busy) { publish(state); return@execute }
            if (!force && now - prefs.getLong("lastCheck", 0) < CHECK_EVERY_MS && state.optString("state") != "available") return@execute
            try {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "LocalVPN-updater")
                val text = try {
                    if (conn.responseCode != 200) throw IllegalStateException("GitHub ответил ошибкой ${conn.responseCode}")
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
                prefs.edit().putLong("lastCheck", now).apply()

                val json = JSONObject(text)
                val tag = json.getString("tag_name")                     // v1.0.24
                val latest = tag.substringAfterLast('.').toLongOrNull() ?: 0L // номер сборки = versionCode
                val assets = json.getJSONArray("assets")
                var size = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) { apkUrl = a.getString("browser_download_url"); size = a.optLong("size") }
                }
                if (latest <= currentVersion(app) || apkUrl.isEmpty()) {
                    publish(JSONObject().put("state", "none").put("checked", true)); return@execute
                }
                val metered = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
                publish(JSONObject().put("state", "available").put("version", tag.removePrefix("v")).put("size", size).put("metered", metered))
                // По Wi-Fi качаем сразу, по мобильному интернету — после нажатия «Скачать»
                if (!metered) download(app)
            } catch (e: Throwable) {
                if (force) publish(JSONObject().put("state", "error").put("error", "Не удалось проверить обновления: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    fun download(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val version = state.optString("version")
            val size = state.optLong("size")
            if (apkUrl.isEmpty() || version.isEmpty()) return@execute
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "LocalVPN-$version.apk")
            try {
                if (!(file.exists() && size > 0 && file.length() == size)) {
                    dir.listFiles()?.forEach { it.delete() }
                    val tmp = File(dir, "download.part")
                    val conn = URL(apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "LocalVPN-updater")
                    try {
                        if (conn.responseCode != 200) throw IllegalStateException("ошибка ${conn.responseCode}")
                        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else size
                        var done = 0L
                        var lastShown = -1
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                    if (pct != lastShown) {
                                        lastShown = pct
                                        publish(JSONObject().put("state", "downloading").put("version", version).put("size", size).put("progress", pct))
                                    }
                                }
                            }
                        }
                    } finally { conn.disconnect() }
                    if (size > 0 && tmp.length() != size) throw IllegalStateException("файл скачался не полностью")
                    tmp.renameTo(file)
                }
                publish(JSONObject().put("state", "ready").put("version", version).put("size", size))
            } catch (e: Throwable) {
                publish(JSONObject().put("state", "error").put("version", version).put("size", size)
                    .put("error", "Не удалось скачать обновление: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /** Ставит скачанное обновление. Возвращает false, если нужно разрешение «Установка из этого источника». */
    fun install(context: Context): Boolean {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) return false
        val version = state.optString("version")
        val file = File(File(app.cacheDir, "updates"), "LocalVPN-$version.apk")
        if (!file.exists()) { publish(JSONObject().put("state", "error").put("error", "Файл обновления не найден, скачайте его заново")); return true }
        worker.execute {
            try {
                publish(JSONObject().put("state", "installing").put("version", version))
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                    session.commit(pending.intentSender)
                }
            } catch (e: Throwable) {
                publish(JSONObject().put("state", "error").put("version", version)
                    .put("error", "Не удалось установить обновление: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        return true
    }

    /** Ждёт, пока выполнятся все поставленные задачи (проверка ставит скачивание следом за собой). */
    private fun awaitIdle() {
        repeat(3) { worker.submit(Runnable {}).get() }
    }

    private fun busyNow(context: Context): Boolean =
        VpnController.running(context).isNotEmpty() || ProxyController.isRunning()

    /**
     * Фоновая проверка (раз в 12 часов по Wi-Fi, даже если приложение не открывали):
     * берёт самую новую версию, скачивает и ставит, если VPN и прокси выключены.
     */
    fun backgroundRun(context: Context) {
        val app = context.applicationContext
        check(app, true)
        awaitIdle()
        if (state.optString("state") != "ready") return
        val version = state.optString("version")
        if (busyNow(app)) {
            notify(app, 2, "Обновление LocalVPN $version скачано",
                "Установится, когда выключите VPN или прокси. Можно установить сразу в приложении", null)
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            notify(app, 2, "Доступно обновление LocalVPN $version",
                "Откройте приложение и разрешите установку обновлений — дальше они будут ставиться сами", null)
            return
        }
        install(app)
        awaitIdle()
    }

    internal fun notify(context: Context, id: Int, title: String, text: String, action: Intent?) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("updates", "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
        val target = action ?: Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, id, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_notification_proxy)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try { manager.notify(id, n) } catch (e: SecurityException) { /* уведомления запрещены */ }
    }

    internal fun installFinished(ok: Boolean, message: String?) {
        if (ok) return // приложение сейчас перезапустится в новой версии
        val version = state.optString("version")
        publish(JSONObject().put("state", "ready").put("version", version)
            .put("error", if (message.isNullOrBlank()) "Установка отменена" else "Установка не удалась: $message"))
    }
}

/** Получает результат установки от Android и, если нужно, показывает окно подтверждения. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val shown = Updater.appVisible && try { context.startActivity(confirm); true } catch (e: Throwable) { false }
                    if (!shown) {
                        Updater.notify(context, 3, "Обновление LocalVPN готово",
                            "Нажмите, чтобы установить новую версию", confirm)
                        Updater.installFinished(false, "Подтвердите установку в уведомлении")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installFinished(true, null)
            else -> Updater.installFinished(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/** Приходит после установки новой версии поверх старой. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Updater.notify(context, 4, "LocalVPN обновлён до ${Updater.versionName(context)}",
            "Откройте приложение, чтобы снова включить VPN или прокси", null)
    }
}
