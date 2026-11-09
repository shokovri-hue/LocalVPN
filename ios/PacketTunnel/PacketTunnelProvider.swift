import Foundation
import NetworkExtension
import os.log

/// Сетевое расширение LocalVPN. iOS запускает его отдельно от приложения, когда включается VPN.
/// Два режима (providerConfiguration["mode"]):
///  • "awg"   — туннель AmneziaWG/WireGuard, конфиг в виде текста .conf;
///  • "socks" — прокси-туннель: весь трафик телефона идёт через SOCKS5-сервер.
final class PacketTunnelProvider: NEPacketTunnelProvider {

    private static let log = OSLog(subsystem: "app.localvpn.tunnel", category: "tunnel")

    private lazy var adapter = WireGuardAdapter(with: self) { _, message in
        os_log("%{public}@", log: PacketTunnelProvider.log, type: .info, message)
    }
    private var mode = ""
    private let socks = SocksTunnel()

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        Shared.setLastError(nil)
        let done: (Error?) -> Void = { error in
            if let error = error { Shared.setLastError(error.localizedDescription) }
            completionHandler(error)
        }
        guard let proto = protocolConfiguration as? NETunnelProviderProtocol,
              let cfg = proto.providerConfiguration,
              let mode = cfg["mode"] as? String else {
            done(TunnelError("Настройки туннеля повреждены. Включите его ещё раз из приложения LocalVPN"))
            return
        }
        self.mode = mode

        if mode == "socks" {
            socks.start(provider: self, cfg: cfg, completion: done)
            return
        }

        let text = Self.cleanConfig(cfg["conf"] as? String ?? "")
        let tunnel: TunnelConfiguration
        do {
            tunnel = try TunnelConfiguration(fromWgQuickConfig: text, called: cfg["name"] as? String)
        } catch {
            done(TunnelError(Self.parseMessage(error)))
            return
        }
        adapter.start(tunnelConfiguration: tunnel) { error in
            guard let error = error else { done(nil); return }
            done(TunnelError(Self.adapterMessage(error)))
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        if mode == "socks" {
            socks.stop()
            completionHandler()
            return
        }
        adapter.stop { _ in completionHandler() }
    }

    /// Приложение спрашивает статистику: ответ — JSON {rx, tx, handshake} (байты, миллисекунды).
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)? = nil) {
        guard let reply = completionHandler else { return }
        if mode == "socks" {
            let s = socks.stats()
            reply(Self.json(["rx": s.rx, "tx": s.tx]))
            return
        }
        adapter.getRuntimeConfiguration { settings in
            var rx: Int64 = 0, tx: Int64 = 0, handshake: Int64 = 0
            for line in (settings ?? "").split(separator: "\n") {
                let parts = line.split(separator: "=", maxSplits: 1)
                guard parts.count == 2, let value = Int64(parts[1]) else { continue }
                switch parts[0] {
                case "rx_bytes": rx += value
                case "tx_bytes": tx += value
                case "last_handshake_time_sec": handshake = max(handshake, value * 1000)
                default: break
                }
            }
            reply(Self.json(["rx": rx, "tx": tx, "handshake": handshake]))
        }
    }

    private static func json(_ value: [String: Any]) -> Data? {
        try? JSONSerialization.data(withJSONObject: value)
    }

    /// Убирает строки, которые понимает только Android-версия (выбор приложений и т. п.).
    static func cleanConfig(_ text: String) -> String {
        let androidOnly: Set<String> = ["includedapplications", "excludedapplications"]
        return text.split(whereSeparator: \.isNewline).filter { line in
            let code = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
            guard let eq = code.firstIndex(of: "=") else { return true }
            let key = code[..<eq].trimmingCharacters(in: .whitespaces).lowercased()
            return !androidOnly.contains(key)
        }.joined(separator: "\n")
    }

    static func parseMessage(_ error: Error) -> String {
        guard let e = error as? TunnelConfiguration.ParseError else {
            return "Ошибка в настройках туннеля: \(error.localizedDescription)"
        }
        switch e {
        case .invalidLine(let line): return "Непонятная строка в настройках: \(line)"
        case .noInterface: return "В настройках нет раздела [Interface]"
        case .multipleInterfaces: return "В настройках несколько разделов [Interface]"
        case .interfaceHasNoPrivateKey: return "Не указан приватный ключ"
        case .interfaceHasInvalidPrivateKey: return "Приватный ключ указан неверно"
        case .interfaceHasInvalidListenPort(let v): return "Неверный порт: \(v)"
        case .interfaceHasInvalidAddress(let v): return "Неверный адрес: \(v)"
        case .interfaceHasInvalidDNS(let v): return "Неверный DNS: \(v)"
        case .interfaceHasInvalidMTU(let v): return "Неверный MTU: \(v)"
        case .interfaceHasUnrecognizedKey(let v): return "Неизвестный параметр в [Interface]: \(v)"
        case .interfaceHasInvalidCustomParam(let v): return "Неверное значение параметра маскировки: \(v)"
        case .peerHasNoPublicKey: return "Не указан публичный ключ сервера"
        case .peerHasInvalidPublicKey: return "Публичный ключ сервера указан неверно"
        case .peerHasInvalidPreSharedKey: return "Общий ключ (PresharedKey) указан неверно"
        case .peerHasInvalidAllowedIP(let v): return "Неверное значение AllowedIPs: \(v)"
        case .peerHasInvalidEndpoint(let v): return "Неверный адрес сервера (Endpoint): \(v)"
        case .peerHasInvalidPersistentKeepAlive(let v): return "Неверное значение PersistentKeepalive: \(v)"
        case .peerHasUnrecognizedKey(let v): return "Неизвестный параметр в [Peer]: \(v)"
        case .multiplePeersWithSamePublicKey: return "Два сервера с одинаковым публичным ключом"
        case .multipleEntriesForKey(let v): return "Параметр указан дважды: \(v)"
        default: return "Ошибка в настройках туннеля: \(e)"
        }
    }

    static func adapterMessage(_ error: WireGuardAdapterError) -> String {
        switch error {
        case .cannotLocateTunnelFileDescriptor:
            return "iOS не дала создать VPN-интерфейс. Перезагрузите телефон и попробуйте ещё раз"
        case .dnsResolution(let errors):
            let hosts = errors.map { $0.address }.joined(separator: ", ")
            return "Не удалось найти адрес сервера \(hosts). Проверьте интернет или укажите в Endpoint IP-адрес"
        case .setNetworkSettings(let e):
            return "iOS не приняла настройки сети: \(e.localizedDescription). Проверьте адрес, DNS и MTU"
        case .startWireGuardBackend(let code):
            return "Движок VPN не запустился (код \(code)). Проверьте ключи и параметры маскировки"
        case .invalidState:
            return "VPN уже запускается. Подождите несколько секунд"
        }
    }
}

struct TunnelError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
