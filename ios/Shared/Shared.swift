import Foundation

/// Общее для приложения и сетевого расширения: через группу приложений расширение
/// сообщает приложению текст последней ошибки (iOS сама его не передаёт).
enum Shared {
    static let appGroup = "group.app.localvpn"
    static let tunnelBundleSuffix = ".tunnel"

    private static var defaults: UserDefaults? { UserDefaults(suiteName: appGroup) }

    static func setLastError(_ text: String?) {
        guard let d = defaults else { return }
        if let text = text {
            d.set(text, forKey: "lastError")
            d.set(Date().timeIntervalSince1970, forKey: "lastErrorAt")
        } else {
            d.removeObject(forKey: "lastError")
            d.removeObject(forKey: "lastErrorAt")
        }
    }

    /// Ошибка, если она случилась не раньше чем `since`.
    static func lastError(since: Date) -> String? {
        guard let d = defaults, let text = d.string(forKey: "lastError") else { return nil }
        let at = d.double(forKey: "lastErrorAt")
        return at >= since.timeIntervalSince1970 - 1 ? text : nil
    }
}
