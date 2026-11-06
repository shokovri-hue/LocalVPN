package app.localvpn

import android.os.Build
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

/**
 * Регистрация бесплатного аккаунта Cloudflare WARP — так же, как это делает приложение 1.1.1.1
 * (по образцу открытого проекта wgcf). Приватный ключ остаётся на телефоне: Cloudflare получает
 * только публичный ключ и в ответ сообщает адреса туннеля и данные своего сервера.
 */
object Warp {
    private const val API = "https://api.cloudflareclient.com/v0a5641/reg"
    private const val DEFAULT_PORT = 2408

    /** Возвращает JSON {address4, address6, peerPublicKey, endpoint} или бросает исключение с понятным текстом. */
    fun register(publicKey: String): String {
        val body = JSONObject()
            .put("fcm_token", "")
            .put("install_id", "")
            .put("key", publicKey)
            .put("locale", "en_US")
            .put("model", Build.MODEL ?: "Android")
            .put("tos", Instant.now().toString())
            .put("serial_number", "")
            .put("os_version", "16.0.0")
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            .toString()

        val conn = URL(API).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "1.1.1.1/6.38.9-5641 (Android 16.0.0)")
            conn.setRequestProperty("CF-Client-Version", "a-6.38.9-5641")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Connection", "Keep-Alive")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 429) throw IllegalStateException("Cloudflare просит подождать: слишком много запросов. Попробуйте через несколько минут")
            if (code !in 200..299) throw IllegalStateException("Cloudflare ответил ошибкой $code. Попробуйте позже")

            val config = JSONObject(text).getJSONObject("config")
            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            val peer = config.getJSONArray("peers").getJSONObject(0)
            val endpoint = peer.getJSONObject("endpoint")

            // Берём IP-адрес сервера, а не имя: так не нужен DNS, который могут подменять
            val host = sequenceOf(endpoint.optString("v4"), endpoint.optString("host"), endpoint.optString("v6"))
                .map { stripPort(it) }
                .firstOrNull { it.isNotEmpty() }
                ?: throw IllegalStateException("В ответе Cloudflare нет адреса сервера")
            val ports = endpoint.optJSONArray("ports")
            val port = if (ports != null && ports.length() > 0) ports.optInt(0, DEFAULT_PORT) else DEFAULT_PORT
            val hostPart = if (host.contains(':')) "[$host]" else host

            return JSONObject()
                .put("address4", addresses.optString("v4"))
                .put("address6", addresses.optString("v6"))
                .put("peerPublicKey", peer.getString("public_key"))
                .put("endpoint", "$hostPart:$port")
                .toString()
        } finally {
            conn.disconnect()
        }
    }

    /** "162.159.192.1:0" → "162.159.192.1", "[2606:4700::1]:0" → "2606:4700::1". */
    private fun stripPort(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("[")) return v.substringAfter("[").substringBefore("]")
        return if (v.count { it == ':' } == 1) v.substringBefore(":") else v
    }

    fun humanError(e: Exception): String = when (e) {
        is IllegalStateException -> e.message ?: "Не удалось получить WARP"
        is java.net.UnknownHostException -> "Нет интернета или сервер Cloudflare недоступен. Выключите VPN и проверьте подключение"
        is java.net.SocketTimeoutException -> "Cloudflare не ответил вовремя. Возможно, его адрес заблокирован в вашей сети"
        is javax.net.ssl.SSLException -> "Не удалось установить защищённое соединение с Cloudflare. Возможно, его блокирует провайдер"
        is org.json.JSONException -> "Cloudflare прислал непонятный ответ. Попробуйте позже"
        else -> "Не удалось получить WARP: ${e.message ?: e.javaClass.simpleName}"
    }
}
