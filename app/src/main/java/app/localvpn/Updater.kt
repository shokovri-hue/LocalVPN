package app.localvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Проверка обновлений: смотрит последнюю версию в GitHub Releases и сообщает о ней.
 * Само приложение ничего не скачивает и не устанавливает — по кнопке «Скачать» открывается
 * браузер, и человек ставит APK сам. Так у приложения нет разрешений на установку других
 * приложений, из-за которых Google Play Protect считал его вредоносным «загрузчиком».
 *
 * Состояние для страницы — JSON: {state: none|available|error, version, size, checked, error}
 */
object Updater {
    private const val API = "https://api.github.com/repos/shokovri-hue/LocalVPN/releases/latest"
    private const val LATEST_PAGE = "https://github.com/shokovri-hue/LocalVPN/releases/latest"
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private class Release(val code: Long, val name: String, val apkUrl: String, val size: Long)

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var state = JSONObject().put("state", "none")
    @Volatile private var apkUrl = ""
    @Volatile private var listener: ((String) -> Unit)? = null

    fun setListener(l: ((String) -> Unit)?) { listener = l }
    fun state(): String = state.toString()

    private fun publish(s: JSONObject) { state = s; listener?.invoke(s.toString()) }

    fun currentVersion(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** Последний релиз на GitHub — всегда самая новая версия, сколько бы их ни вышло. */
    private fun fetchLatest(): Release {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "LocalVPN-updater")
        val text = try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub ответил ошибкой ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        val json = JSONObject(text)
        val tag = json.getString("tag_name")                         // v1.0.29
        val code = tag.substringAfterLast('.').toLongOrNull() ?: 0L  // номер сборки = versionCode
        var url = ""
        var size = 0L
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); size = a.optLong("size") }
        }
        return Release(code, tag.removePrefix("v"), url, size)
    }

    /** force — проверить сейчас, даже если недавно проверяли (кнопка «Проверить обновления»). */
    fun check(context: Context, force: Boolean) {
        val app = context.applicationContext
        worker.execute {
            // Файлы, скачанные старыми версиями с автоустановкой, больше не нужны
            File(app.cacheDir, "updates").deleteRecursively()
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong("lastCheck", 0) < CHECK_EVERY_MS) { publish(state); return@execute }
            try {
                val r = fetchLatest()
                prefs.edit().putLong("lastCheck", now).apply()
                if (r.code <= currentVersion(app)) {
                    publish(JSONObject().put("state", "none").put("checked", true)); return@execute
                }
                apkUrl = r.apkUrl
                publish(JSONObject().put("state", "available").put("version", r.name).put("size", r.size))
            } catch (e: Throwable) {
                if (force) publish(JSONObject().put("state", "error").put("error", "Не удалось проверить обновления: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /** Открывает скачивание новой версии в браузере. Устанавливает её человек сам. */
    fun open(context: Context) {
        val url = apkUrl.ifEmpty { LATEST_PAGE }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            publish(JSONObject().put("state", "error").put("error", "Не удалось открыть браузер. Скачайте обновление по ссылке $LATEST_PAGE"))
        }
    }

    /** Фоновая проверка: если вышла новая версия, один раз показывает уведомление со ссылкой. */
    fun backgroundRun(context: Context) {
        val app = context.applicationContext
        val r = fetchLatest()
        if (r.code <= currentVersion(app) || r.apkUrl.isEmpty()) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("notified", 0) >= r.code) return
        prefs.edit().putLong("notified", r.code).apply()
        notify(app, 2, "Вышла LocalVPN ${r.name}",
            "Нажмите, чтобы скачать обновление, затем откройте файл и нажмите «Обновить»",
            Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)))
    }

    private fun notify(context: Context, id: Int, title: String, text: String, action: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("updates", "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, id, action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
}
