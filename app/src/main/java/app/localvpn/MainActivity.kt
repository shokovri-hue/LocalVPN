package app.localvpn

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Окно приложения: интерфейс — это та же страница, что и в браузере (assets/index.html),
 * а включение VPN, список программ и буфер обмена делает Kotlin через объект window.Android.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val background = Executors.newSingleThreadExecutor()
    private var pendingName: String? = null
    private var pendingConfig: String? = null
    private var pendingProxyTunnel: String? = null

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val proxyCfg = pendingProxyTunnel
            pendingProxyTunnel = null
            if (proxyCfg != null) {
                if (result.resultCode == RESULT_OK) ProxyTunnels.apply(this, true, proxyCfg, this@MainActivity::deliverProxyTunnel)
                else deliverProxyTunnel(ProxyTunnels.running(), "Без разрешения Android не сможет включить прокси-туннель")
                return@registerForActivityResult
            }
            val name = pendingName
            val config = pendingConfig
            pendingName = null
            pendingConfig = null
            if (name == null || config == null) return@registerForActivityResult
            if (result.resultCode == RESULT_OK) {
                VpnController.apply(this, name, true, config, this@MainActivity::deliver)
            } else {
                deliver(VpnController.running(this), "Без разрешения Android не сможет включить VPN")
            }
        }

    // Резервная копия: системное окно выбора папки. restore — восстановить из неё, иначе — сохранять в неё.
    private var backupRestore = false
    private val pickBackupFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val restore = backupRestore
            if (uri == null) {
                jsCall(if (restore) "onBackupRestore" else "onBackupFolder", null, null)
                return@registerForActivityResult
            }
            background.execute {
                try {
                    if (restore) {
                        val text = Backup.read(this, uri)
                        Backup.rememberFolder(this, uri) // дальше копия сохраняется в ту же папку
                        jsCall("onBackupRestore", text, null)
                    } else {
                        Backup.rememberFolder(this, uri)
                        jsCall("onBackupFolder", Backup.folderName(this, uri), null)
                    }
                } catch (e: Throwable) {
                    jsCall(if (restore) "onBackupRestore" else "onBackupFolder", null, e.message ?: "Не удалось открыть папку")
                }
            }
        }

    /** Вызывает window.<fn>(value, error) на странице; строки передаются как JSON-строки. */
    private fun jsCall(fn: String, value: String?, error: String?) {
        val js = "window.$fn && window.$fn(" +
            (if (value == null) "null" else JSONObject.quote(value)) + ", " +
            (if (error == null) "null" else JSONObject.quote(error)) + ")"
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
    }

    // Android 13+: разрешение показывать уведомление «LocalProxy работает». Прокси запускается в любом случае.
    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Страница открывается с адреса https://appassets.androidplatform.net — так у неё
        // постоянное хранилище (туннели не пропадают) и нет доступа к файлам телефона.
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(getColor(R.color.screen_bg))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ASSET_HOST) return false
                // Внешние ссылки открываем в браузере телефона
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        // С целевой версией Android 15+ окно рисуется во весь экран, под системными панелями.
        // Сама страница отступы не учитывает, поэтому кладём её в контейнер и отступаем
        // контейнером на высоту строки состояния, панели навигации, выреза камеры и клавиатуры.
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.screen_bg))
        root.addView(web, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout() or
                        android.view.WindowInsets.Type.ime()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                android.view.WindowInsets.CONSUMED
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) web.loadUrl("https://$ASSET_HOST/assets/index.html")

        // Кнопка «Назад»: сначала её обрабатывает страница (закрыть окно, вернуться к списку)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.appBack ? window.appBack() : false)") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        // Автообновление: состояние сразу передаём странице
        Updater.setListener { json ->
            runOnUiThread { if (!isDestroyed) web.evaluateJavascript("window.onUpdate && window.onUpdate($json)", null) }
        }

        // Прокси-туннель могли выключить извне (другое VPN-приложение) — сообщаем странице
        ProxyTunnels.setListener { id, error -> deliverProxyTunnel(id, error) }

        // Реклама Яндекса: загружаем заранее, показываем в естественные моменты не чаще раза в 7 минут
        Ads.start(this)

        // Фоновая проверка обновлений раз в 12 часов (по Wi-Fi)
        UpdateWorker.schedule(applicationContext)

        // Список приложений собираем заранее, чтобы окно выбора открывалось сразу
        Executors.newSingleThreadExecutor().execute { installedAppsJson(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        Updater.check(this, false)
        web.evaluateJavascript("window.onAppResume && window.onAppResume()", null)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        Updater.setListener(null)
        ProxyTunnels.setListener(null)
        Ads.destroy()
        web.destroy()
        super.onDestroy()
    }

    /** Передаёт странице, какой прокси-туннель включён (id или null). */
    private fun deliverProxyTunnel(id: String?, error: String?) {
        val js = "window.onProxyTunnelState && window.onProxyTunnelState(" +
            (if (id == null) "null" else JSONObject.quote(id)) + ", " +
            (if (error == null) "null" else JSONObject.quote(error)) + ")"
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
    }

    /** Передаёт странице состояние туннелей. */
    private fun deliver(running: List<String>, error: String?) {
        val js = "window.onTunnelState && window.onTunnelState(" +
            JSONArray(running).toString() + ", " +
            (if (error == null) "null" else JSONObject.quote(error)) + ")"
        runOnUiThread {
            if (!isDestroyed) web.evaluateJavascript(js, null)
        }
    }

    /** Методы, которые страница вызывает как window.Android.имя(...). */
    inner class Bridge {

        @JavascriptInterface
        fun getRunning(): String = JSONArray(VpnController.running(this@MainActivity)).toString()

        @JavascriptInterface
        fun setTunnel(name: String, on: Boolean, config: String) {
            runOnUiThread {
                if (on) {
                    val ask = VpnService.prepare(this@MainActivity)
                    if (ask != null) {
                        // Android один раз спрашивает разрешение на VPN
                        pendingName = name
                        pendingConfig = config
                        vpnPermission.launch(ask)
                        return@runOnUiThread
                    }
                }
                VpnController.apply(this@MainActivity, name, on, config, this@MainActivity::deliver)
            }
        }

        /** LocalProxy: запуск и остановка. Ответ — window.onProxyState(up, error). */
        @JavascriptInterface
        fun setProxy(on: Boolean, config: String, options: String) {
            runOnUiThread {
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                ProxyController.apply(this@MainActivity, on, config, options) { up, error ->
                    val js = "window.onProxyState && window.onProxyState(" + up + ", " +
                        (if (error == null) "null" else JSONObject.quote(error)) + ")"
                    runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
                }
            }
        }

        @JavascriptInterface
        fun isProxyRunning(): Boolean = ProxyController.isRunning()

        /** Прокси-туннель на весь телефон: cfg — JSON {id, name, type, host, port, user, pass}. */
        @JavascriptInterface
        fun setProxyTunnel(on: Boolean, cfg: String) {
            runOnUiThread {
                if (on) {
                    val ask = VpnService.prepare(this@MainActivity)
                    if (ask != null) {
                        pendingProxyTunnel = cfg
                        vpnPermission.launch(ask)
                        return@runOnUiThread
                    }
                }
                ProxyTunnels.apply(this@MainActivity, on, cfg, this@MainActivity::deliverProxyTunnel)
            }
        }

        @JavascriptInterface
        fun getProxyTunnel(): String = ProxyTunnels.running() ?: ""

        @JavascriptInterface
        fun getProxyTunnelStats(): String = ProxyTunnels.stats()

        /** Статистика прокси для строки «связь с сервером». */
        @JavascriptInterface
        fun getProxyStats(): String = ProxyController.stats(this@MainActivity)

        /** Адрес телефона в Wi-Fi сети — для раздачи прокси другим устройствам. */
        @JavascriptInterface
        fun getLanIp(): String = ProxyController.lanIp()

        /** Естественный момент для рекламы (страница вызывает при смене экрана и включении/выключении). */
        @JavascriptInterface
        fun adMoment() {
            runOnUiThread { Ads.moment(this@MainActivity) }
        }

        // ===== Резервная копия =====
        @JavascriptInterface
        fun backupStatus(): String = Backup.status(this@MainActivity)

        /** restore=false — выбрать папку для копий; true — выбрать папку и восстановить из неё. */
        @JavascriptInterface
        fun chooseBackupFolder(restore: Boolean) {
            runOnUiThread {
                backupRestore = restore
                try { pickBackupFolder.launch(null) } catch (e: Exception) {
                    jsCall(if (restore) "onBackupRestore" else "onBackupFolder", null, "Не удалось открыть выбор папки")
                }
            }
        }

        @JavascriptInterface
        fun writeBackup(json: String) = Backup.write(this@MainActivity, json)

        @JavascriptInterface
        fun disableBackup() = Backup.disable(this@MainActivity)

        // ===== Обновления =====
        @JavascriptInterface
        fun getVersion(): String = Updater.versionName(this@MainActivity)

        @JavascriptInterface
        fun getUpdateState(): String = Updater.state()

        @JavascriptInterface
        fun checkUpdate(force: Boolean) = Updater.check(this@MainActivity, force)

        /** Открывает скачивание новой версии в браузере — устанавливает человек сам. */
        @JavascriptInterface
        fun openUpdate() = Updater.open(this@MainActivity)

        /** Статистика работающего туннеля для индикатора «связь с сервером». */
        @JavascriptInterface
        fun getStats(): String = VpnController.stats(this@MainActivity)

        /** Получает бесплатный конфиг Cloudflare WARP. Ответ — window.onWarpResult(data, error). */
        @JavascriptInterface
        fun warpRegister(publicKey: String) {
            background.execute {
                var data: String? = null
                var error: String? = null
                try {
                    data = Warp.register(publicKey)
                } catch (e: Exception) {
                    error = Warp.humanError(e)
                }
                val js = "window.onWarpResult && window.onWarpResult(" +
                    (data ?: "null") + ", " + (if (error == null) "null" else JSONObject.quote(error)) + ")"
                runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
            }
        }

        @JavascriptInterface
        fun getInstalledApps(): String = installedAppsJson(applicationContext)

        @JavascriptInterface
        fun isInstalled(pkg: String): Boolean = VpnController.isInstalled(this@MainActivity, pkg)

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("LocalVPN", text))
            }
        }
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val ICON_SIZE = 72

        @Volatile private var appsCache: String? = null

        /** Приложения с иконкой на рабочем столе: название, имя пакета и иконка в PNG. */
        @Synchronized
        fun installedAppsJson(context: Context): String {
            appsCache?.let { return it }
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcher, 0)
            }
            val result = JSONArray()
            activities
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map { it to it.loadLabel(pm).toString() }
                .sortedBy { it.second.lowercase() }
                .forEach { (info, label) ->
                    val item = JSONObject()
                    item.put("n", label)
                    item.put("p", info.activityInfo.packageName)
                    try {
                        val drawable = info.loadIcon(pm)
                        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
                        drawable.draw(canvas)
                        val png = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                        item.put("icon", "data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP))
                    } catch (e: Exception) {
                    }
                    result.put(item)
                }
            return result.toString().also { appsCache = it }
        }
    }
}
