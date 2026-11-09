package app.localvpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Резервная копия туннелей в папке, которую выбрал пользователь (например, «Документы/LocalVPN»).
 * Папка лежит в общей памяти телефона, поэтому переживает удаление приложения: после повторной
 * установки пользователь выбирает ту же папку, и туннели, прокси и ключи восстанавливаются.
 */
object Backup {
    private const val PREFS = "backup"
    const val FILE = "LocalVPN-backup.json"
    private val worker = Executors.newSingleThreadExecutor()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun folderUri(c: Context): Uri? = prefs(c).getString("tree", null)?.let { Uri.parse(it) }

    /** Есть ли ещё у приложения доступ к выбранной папке. */
    private fun hasAccess(c: Context, uri: Uri): Boolean =
        c.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** Запоминает папку, выбранную в системном окне, и сохраняет постоянный доступ к ней. */
    fun rememberFolder(c: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        c.contentResolver.takePersistableUriPermission(uri, flags)
        // Доступ к прежней папке больше не нужен
        folderUri(c)?.takeIf { it != uri }?.let {
            try { c.contentResolver.releasePersistableUriPermission(it, flags) } catch (e: Exception) { }
        }
        prefs(c).edit().putString("tree", uri.toString()).apply()
    }

    fun folderName(c: Context, uri: Uri? = folderUri(c)): String =
        uri?.let { DocumentFile.fromTreeUri(c, it)?.name } ?: ""

    /** {folder: имя папки или "", savedAt: время последнего сохранения, error}. */
    fun status(c: Context): String {
        val uri = folderUri(c)
        val ok = uri != null && hasAccess(c, uri)
        return JSONObject()
            .put("folder", if (ok) folderName(c, uri) else "")
            .put("savedAt", prefs(c).getLong("savedAt", 0))
            .put("error", prefs(c).getString("error", "") ?: "")
            .toString()
    }

    fun disable(c: Context) {
        folderUri(c)?.let {
            try {
                c.contentResolver.releasePersistableUriPermission(it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { }
        }
        prefs(c).edit().clear().apply()
    }

    /** Записывает копию в фоне. Если доступа к папке нет — ничего не делает. */
    fun write(context: Context, json: String) {
        val c = context.applicationContext
        worker.execute {
            val uri = folderUri(c) ?: return@execute
            try {
                if (!hasAccess(c, uri)) throw IllegalStateException("Нет доступа к папке резервной копии. Выберите её заново")
                val dir = DocumentFile.fromTreeUri(c, uri) ?: throw IllegalStateException("Папка резервной копии недоступна")
                val file = dir.findFile(FILE) ?: dir.createFile("application/json", FILE)
                    ?: throw IllegalStateException("Не удалось создать файл в папке резервной копии")
                c.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Не удалось записать резервную копию")
                prefs(c).edit().putLong("savedAt", System.currentTimeMillis()).putString("error", "").apply()
            } catch (e: Throwable) {
                prefs(c).edit().putString("error", e.message ?: e.javaClass.simpleName).apply()
            }
        }
    }

    /** Читает копию из выбранной папки. Возвращает текст файла или бросает понятную ошибку. */
    fun read(c: Context, uri: Uri): String {
        val dir = DocumentFile.fromTreeUri(c, uri) ?: throw IllegalStateException("Папка недоступна")
        val file = dir.findFile(FILE)
            ?: throw IllegalStateException("В папке «${dir.name ?: ""}» нет файла $FILE. Выберите папку, куда сохранялась копия")
        return c.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Не удалось прочитать резервную копию")
    }
}
