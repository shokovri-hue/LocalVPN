import Foundation
import NetworkExtension

/// Управляет VPN через одну конфигурацию iOS «LocalVPN». Что именно запускать — туннель AmneziaWG
/// или прокси-туннель — передаётся сетевому расширению в providerConfiguration.
final class TunnelController {
    static let shared = TunnelController()

    /// Вызывается, когда VPN включили или выключили не из приложения (например, в Настройках).
    var onExternalChange: (() -> Void)?

    private var manager: NETunnelProviderManager?
    private var busy = false
    private var watchers: [UUID: () -> Void] = [:]

    private init() {
        NotificationCenter.default.addObserver(self, selector: #selector(statusChanged),
                                               name: .NEVPNStatusDidChange, object: nil)
    }

    func load(_ done: @escaping () -> Void) {
        NETunnelProviderManager.loadAllFromPreferences { managers, _ in
            DispatchQueue.main.async {
                self.manager = managers?.first
                done()
            }
        }
    }

    // MARK: - Состояние

    private var status: NEVPNStatus { manager?.connection.status ?? .invalid }
    private var isUp: Bool { status == .connected || status == .reasserting }
    private var config: [String: Any] {
        (manager?.protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration ?? [:]
    }
    private var mode: String { config["mode"] as? String ?? "" }

    /// Имена работающих туннелей (как getRunning на Android).
    var running: [String] { isUp && mode == "awg" ? [config["name"] as? String ?? ""] : [] }
    /// id работающего прокси-туннеля или "".
    var proxyTunnelId: String { isUp && mode == "socks" ? (config["id"] as? String ?? "") : "" }
    var runningMode: String { isUp ? mode : "" }

    @objc private func statusChanged() {
        DispatchQueue.main.async {
            self.watchers.values.forEach { $0() }
            if !self.busy { self.onExternalChange?() }
        }
    }

    // MARK: - Включение и выключение

    /// Включает VPN с настройками `conf`. done(nil) — подключился, иначе текст ошибки.
    func start(_ conf: [String: Any], done: @escaping (String?) -> Void) {
        busy = true
        let finish: (String?) -> Void = { error in self.busy = false; done(error) }
        stop {
            let m = self.manager ?? NETunnelProviderManager()
            let proto = NETunnelProviderProtocol()
            proto.providerBundleIdentifier = (Bundle.main.bundleIdentifier ?? "app.localvpn") + Shared.tunnelBundleSuffix
            proto.serverAddress = conf["server"] as? String ?? "LocalVPN"
            proto.providerConfiguration = conf
            m.protocolConfiguration = proto
            m.localizedDescription = "LocalVPN"
            m.isEnabled = true
            m.saveToPreferences { error in
                DispatchQueue.main.async {
                    if let error = error { finish(Self.saveError(error)); return }
                    // После сохранения iOS требует перечитать конфигурацию, иначе запуск не сработает
                    m.loadFromPreferences { _ in
                        DispatchQueue.main.async {
                            self.manager = m
                            let started = Date()
                            do {
                                try m.connection.startVPNTunnel()
                            } catch {
                                finish("iOS не дала включить VPN: \(error.localizedDescription)")
                                return
                            }
                            var seenConnecting = false
                            self.wait(timeout: 40, until: {
                                switch self.status {
                                case .connected: return true
                                case .connecting, .reasserting: seenConnecting = true; return nil
                                case .disconnected, .invalid: return seenConnecting ? false : nil
                                default: return nil
                                }
                            }) { ok in
                                if ok { finish(nil); return }
                                self.lastError(since: started) { message in
                                    m.connection.stopVPNTunnel()
                                    finish(message)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /// Выключает VPN и ждёт, пока iOS его остановит.
    func stop(_ done: @escaping () -> Void) {
        guard let m = manager, status != .disconnected, status != .invalid else { done(); return }
        let wasBusy = busy
        busy = true
        m.connection.stopVPNTunnel()
        wait(timeout: 10, until: { (self.status == .disconnected || self.status == .invalid) ? true : nil }) { _ in
            self.busy = wasBusy
            done()
        }
    }

    func stopFromPage(_ done: @escaping () -> Void) {
        busy = true
        stop { self.busy = false; done() }
    }

    // MARK: - Статистика

    /// Спрашивает у расширения {rx, tx, handshake}. Ответ приходит в главном потоке.
    func stats(_ done: @escaping ([String: Any]?) -> Void) {
        guard isUp, let session = manager?.connection as? NETunnelProviderSession else { done(nil); return }
        do {
            try session.sendProviderMessage(Data("stats".utf8)) { data in
                let json = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
                DispatchQueue.main.async { done(json) }
            }
        } catch {
            done(nil)
        }
    }

    // MARK: - Вспомогательное

    private func wait(timeout: TimeInterval, until check: @escaping () -> Bool?, done: @escaping (Bool) -> Void) {
        if let result = check() { done(result); return }
        let id = UUID()
        var finished = false
        let complete: (Bool) -> Void = { result in
            guard !finished else { return }
            finished = true
            self.watchers[id] = nil
            done(result)
        }
        watchers[id] = { if let result = check() { complete(result) } }
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { complete(check() ?? false) }
    }

    private func lastError(since: Date, _ done: @escaping (String) -> Void) {
        // Расширение записывает понятный текст ошибки в общую группу — ждём его чуть-чуть
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            if let text = Shared.lastError(since: since) { done(text); return }
            if #available(iOS 16.0, *), let connection = self.manager?.connection {
                connection.fetchLastDisconnectError { error in
                    DispatchQueue.main.async {
                        done(error.map { "VPN не подключился: \($0.localizedDescription)" }
                             ?? "VPN не подключился. Проверьте настройки туннеля и интернет")
                    }
                }
                return
            }
            done("VPN не подключился. Проверьте настройки туннеля и интернет")
        }
    }

    private static func saveError(_ error: Error) -> String {
        let e = error as NSError
        if e.domain == NEVPNErrorDomain, e.code == NEVPNError.configurationReadWriteFailed.rawValue
            || e.code == 5 /* permission denied */ {
            return "Без разрешения iOS не сможет включить VPN. Включите ещё раз и нажмите «Разрешить»"
        }
        return "Не удалось сохранить настройки VPN: \(e.localizedDescription)"
    }
}
