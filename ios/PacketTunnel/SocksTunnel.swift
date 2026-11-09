import Foundation
import NetworkExtension
import Tun2SocksKit

/// Прокси-туннель: iOS отдаёт весь трафик телефона в VPN-интерфейс, а движок hev-socks5-tunnel
/// пересылает соединения на SOCKS5-сервер пользователя. DNS тоже идёт через прокси (mapdns):
/// телефон получает временный адрес, а сервер сам находит сайт по имени.
/// Соединения самого расширения идут мимо туннеля, поэтому петли нет.
final class SocksTunnel {
    private static let mtu = 1500
    private static let tunV4 = "198.18.0.1"
    private static let tunV6 = "fc00::1"
    private static let mapDNS = "198.18.0.2"

    private var running = false

    func start(provider: NEPacketTunnelProvider, cfg: [String: Any], completion: @escaping (Error?) -> Void) {
        let host = (cfg["host"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        let portText = (cfg["port"] as? String) ?? (cfg["port"] as? NSNumber)?.stringValue ?? ""
        guard !host.isEmpty, let port = Int(portText), port > 0, port < 65536 else {
            completion(TunnelError("Не указан адрес или порт прокси-сервера"))
            return
        }
        if let type = cfg["type"] as? String, type != "SOCKS5" {
            completion(TunnelError("Для всего телефона подходит только SOCKS5. HTTP-прокси укажите в настройках Wi-Fi"))
            return
        }

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: host)
        settings.mtu = NSNumber(value: Self.mtu)
        let v4 = NEIPv4Settings(addresses: [Self.tunV4], subnetMasks: ["255.255.255.255"])
        v4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = v4
        let v6 = NEIPv6Settings(addresses: [Self.tunV6], networkPrefixLengths: [128])
        v6.includedRoutes = [NEIPv6Route.default()]
        settings.ipv6Settings = v6
        let dns = NEDNSSettings(servers: [Self.mapDNS])
        dns.matchDomains = [""]
        settings.dnsSettings = dns

        let config = Self.yaml(host: host, port: port,
                               user: cfg["user"] as? String ?? "", pass: cfg["pass"] as? String ?? "")
        provider.setTunnelNetworkSettings(settings) { [weak self, weak provider] error in
            if let error = error {
                completion(TunnelError("iOS не приняла настройки сети: \(error.localizedDescription)"))
                return
            }
            self?.running = true
            Socks5Tunnel.run(withConfig: .string(content: config)) { code in
                guard let self = self, self.running else { return }
                // Движок остановился сам — выключаем VPN, чтобы интернет не пропал молча
                self.running = false
                let message = code == -1
                    ? "iOS не дала доступ к VPN-интерфейсу"
                    : "Прокси-туннель остановился (код \(code)). Проверьте адрес прокси"
                Shared.setLastError(message)
                provider?.cancelTunnelWithError(TunnelError(message))
            }
            completion(nil)
        }
    }

    func stop() {
        guard running else { return }
        running = false
        Socks5Tunnel.quit()
    }

    func stats() -> (rx: Int, tx: Int) {
        guard running else { return (0, 0) }
        let s = Socks5Tunnel.stats
        return (s.down.bytes, s.up.bytes)
    }

    private static func yaml(host: String, port: Int, user: String, pass: String) -> String {
        func q(_ v: String) -> String { "'" + v.replacingOccurrences(of: "'", with: "''") + "'" }
        var s = """
        tunnel:
          mtu: \(mtu)
        socks5:
          address: \(q(host))
          port: \(port)
          udp: 'udp'

        """
        if !user.isEmpty {
            s += "  username: \(q(user))\n  password: \(q(pass))\n"
        }
        s += """
        mapdns:
          address: \(q(mapDNS))
          port: 53
          network: '100.64.0.0'
          netmask: '255.192.0.0'
          cache-size: 10000
        misc:
          task-stack-size: 24576
          connect-timeout: 10000
          log-level: warn

        """
        return s
    }
}
