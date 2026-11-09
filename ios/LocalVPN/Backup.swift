import Foundation

/// Резервная копия туннелей в папке, которую выбрал пользователь (например, iCloud Drive/LocalVPN).
/// Папка в «Файлах» переживает удаление приложения: после повторной установки пользователь
/// выбирает ту же папку, и туннели, прокси и ключи восстанавливаются.
enum Backup {
    static let fileName = "LocalVPN-backup.json"
    private static let defaults = UserDefaults.standard
    private static let queue = DispatchQueue(label: "app.localvpn.backup")

    // MARK: - Папка

    /// Запоминает папку из окна выбора (доступ к ней сохраняется закладкой).
    static func rememberFolder(_ url: URL) throws {
        let ok = url.startAccessingSecurityScopedResource()
        defer { if ok { url.stopAccessingSecurityScopedResource() } }
        let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        defaults.set(bookmark, forKey: "backup.bookmark")
        defaults.set(url.lastPathComponent, forKey: "backup.folder")
        defaults.removeObject(forKey: "backup.error")
    }

    private static func folderURL() -> URL? {
        guard let data = defaults.data(forKey: "backup.bookmark") else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil,
                                 bookmarkDataIsStale: &stale) else { return nil }
        if stale, let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            defaults.set(fresh, forKey: "backup.bookmark")
        }
        return url
    }

    static func disable() {
        ["backup.bookmark", "backup.folder", "backup.savedAt", "backup.error"].forEach { defaults.removeObject(forKey: $0) }
    }

    /// {folder, savedAt (мс), error} — как в Android-версии.
    static func status() -> String {
        let hasFolder = defaults.data(forKey: "backup.bookmark") != nil
        let obj: [String: Any] = [
            "folder": hasFolder ? (defaults.string(forKey: "backup.folder") ?? "") : "",
            "savedAt": defaults.double(forKey: "backup.savedAt"),
            "error": defaults.string(forKey: "backup.error") ?? "",
        ]
        let data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{}".utf8)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    // MARK: - Запись и чтение

    /// Записывает копию в фоне; done вызывается в главном потоке.
    static func write(_ json: String, done: @escaping () -> Void) {
        queue.async {
            if let url = folderURL() {
                let ok = url.startAccessingSecurityScopedResource()
                defer { if ok { url.stopAccessingSecurityScopedResource() } }
                var coordError: NSError?
                var writeError: Error?
                NSFileCoordinator().coordinate(writingItemAt: url.appendingPathComponent(fileName),
                                               options: .forReplacing, error: &coordError) { target in
                    do { try Data(json.utf8).write(to: target, options: .atomic) } catch { writeError = error }
                }
                if let e = writeError ?? coordError {
                    defaults.set("Не удалось записать резервную копию: \(e.localizedDescription). Выберите папку заново",
                                 forKey: "backup.error")
                } else {
                    defaults.set(Date().timeIntervalSince1970 * 1000, forKey: "backup.savedAt")
                    defaults.removeObject(forKey: "backup.error")
                }
            }
            DispatchQueue.main.async(execute: done)
        }
    }

    /// Читает копию из выбранной папки или бросает ошибку с понятным текстом.
    static func read(from folder: URL) throws -> String {
        let ok = folder.startAccessingSecurityScopedResource()
        defer { if ok { folder.stopAccessingSecurityScopedResource() } }
        let file = folder.appendingPathComponent(fileName)
        var coordError: NSError?
        var result: Result<String, Error> = .failure(BackupError("Не удалось прочитать резервную копию"))
        NSFileCoordinator().coordinate(readingItemAt: file, options: [], error: &coordError) { target in
            if !FileManager.default.fileExists(atPath: target.path) {
                result = .failure(BackupError("В папке «\(folder.lastPathComponent)» нет файла \(fileName). Выберите папку, куда сохранялась копия"))
                return
            }
            do {
                let data = try Data(contentsOf: target)
                result = .success(String(decoding: data, as: UTF8.self))
            } catch {
                result = .failure(BackupError("Не удалось прочитать резервную копию: \(error.localizedDescription)"))
            }
        }
        if let e = coordError { throw BackupError("Не удалось открыть папку: \(e.localizedDescription)") }
        return try result.get()
    }
}

struct BackupError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
