import Foundation
import UIKit

/// Регистрация бесплатного аккаунта Cloudflare WARP — так же, как в Android-версии (по образцу wgcf).
/// Приватный ключ остаётся на телефоне: Cloudflare получает только публичный.
enum Warp {
    private static let api = URL(string: "https://api.cloudflareclient.com/v0a5641/reg")!
    private static let defaultPort = 2408

    /// done(JSON {address4, address6, peerPublicKey, endpoint}, ошибка) — в главном потоке.
    static func register(publicKey: String, done: @escaping (String?, String?) -> Void) {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let body: [String: Any] = [
            "fcm_token": "", "install_id": "", "key": publicKey, "locale": "en_US",
            "model": UIDevice.current.model, "tos": iso.string(from: Date()), "serial_number": "",
            "os_version": "16.0.0", "key_type": "curve25519", "tunnel_type": "wireguard",
        ]
        var req = URLRequest(url: api, timeoutInterval: 20)
        req.httpMethod = "POST"
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        req.setValue("1.1.1.1/6.38.9-5641 (Android 16.0.0)", forHTTPHeaderField: "User-Agent")
        req.setValue("a-6.38.9-5641", forHTTPHeaderField: "CF-Client-Version")
        req.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")

        URLSession.shared.dataTask(with: req) { data, response, error in
            let result: (String?, String?)
            if let error = error {
                result = (nil, human(error))
            } else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                if code == 429 {
                    result = (nil, "Cloudflare просит подождать: слишком много запросов. Попробуйте через несколько минут")
                } else if !(200...299).contains(code) {
                    result = (nil, "Cloudflare ответил ошибкой \(code). Попробуйте позже")
                } else {
                    result = parse(data ?? Data())
                }
            }
            DispatchQueue.main.async { done(result.0, result.1) }
        }.resume()
    }

    private static func parse(_ data: Data) -> (String?, String?) {
        let bad = (nil as String?, "Cloudflare прислал непонятный ответ. Попробуйте позже" as String?)
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let config = root["config"] as? [String: Any],
              let iface = config["interface"] as? [String: Any],
              let addresses = iface["addresses"] as? [String: Any],
              let peer = (config["peers"] as? [[String: Any]])?.first,
              let peerKey = peer["public_key"] as? String,
              let endpoint = peer["endpoint"] as? [String: Any] else { return bad }

        // Берём IP-адрес сервера, а не имя: так не нужен DNS, который могут подменять
        let host = ["v4", "host", "v6"].compactMap { endpoint[$0] as? String }
            .map(stripPort).first { !$0.isEmpty }
        guard let host = host else { return (nil, "В ответе Cloudflare нет адреса сервера") }
        let port = (endpoint["ports"] as? [Int])?.first ?? defaultPort
        let hostPart = host.contains(":") ? "[\(host)]" : host

        let out: [String: Any] = [
            "address4": addresses["v4"] as? String ?? "",
            "address6": addresses["v6"] as? String ?? "",
            "peerPublicKey": peerKey,
            "endpoint": "\(hostPart):\(port)",
        ]
        guard let json = try? JSONSerialization.data(withJSONObject: out),
              let text = String(data: json, encoding: .utf8) else { return bad }
        return (text, nil)
    }

    /// "162.159.192.1:0" → "162.159.192.1", "[2606:4700::1]:0" → "2606:4700::1".
    private static func stripPort(_ value: String) -> String {
        let v = value.trimmingCharacters(in: .whitespaces)
        if v.isEmpty { return "" }
        if v.hasPrefix("["), let end = v.firstIndex(of: "]") {
            return String(v[v.index(after: v.startIndex)..<end])
        }
        return v.filter { $0 == ":" }.count == 1 ? String(v.split(separator: ":")[0]) : v
    }

    private static func human(_ error: Error) -> String {
        guard let e = error as? URLError else {
            return "Не удалось получить WARP: \(error.localizedDescription)"
        }
        switch e.code {
        case .notConnectedToInternet, .cannotFindHost, .dnsLookupFailed:
            return "Нет интернета или сервер Cloudflare недоступен. Выключите VPN и проверьте подключение"
        case .timedOut:
            return "Cloudflare не ответил вовремя. Возможно, его адрес заблокирован в вашей сети"
        case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate:
            return "Не удалось установить защищённое соединение с Cloudflare. Возможно, его блокирует провайдер"
        default:
            return "Не удалось получить WARP: \(e.localizedDescription)"
        }
    }
}
