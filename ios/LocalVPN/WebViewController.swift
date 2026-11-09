import UIKit
import WebKit
import UniformTypeIdentifiers

/// Весь интерфейс LocalVPN — та же страница, что и в Android-версии (assets/index.html).
/// Страница обращается к телефону через window.Android; на iPhone этот объект создаёт скрипт ниже.
/// Методы, которые на Android возвращают значение сразу, здесь читают последнее состояние,
/// которое приложение заранее передало странице (window.__lvSet).
final class WebViewController: UIViewController, WKNavigationDelegate, WKUIDelegate,
                               WKScriptMessageHandler, UIDocumentPickerDelegate {

    private var web: WKWebView!
    private let tunnels = TunnelController.shared
    private var statsTimer: Timer?
    private var pickerRestore = false

    // MARK: - Создание страницы

    override func loadView() {
        let content = WKUserContentController()
        content.add(WeakMessageHandler(self), name: "lv")
        content.addUserScript(WKUserScript(source: Self.shim(initial: initialState()),
                                           injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let config = WKWebViewConfiguration()
        config.userContentController = content
        config.websiteDataStore = .default()

        web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.isOpaque = false
        web.backgroundColor = UIColor(named: "LaunchBackground")
        web.scrollView.backgroundColor = UIColor(named: "LaunchBackground")
        // Отступы под вырез и полоску «домой» страница делает сама через env(safe-area-inset-*)
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.allowsLinkPreview = false
        if #available(iOS 16.4, *) { web.isInspectable = true }
        view = web
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        if let dir = Bundle.main.url(forResource: "assets", withExtension: nil) {
            web.loadFileURL(dir.appendingPathComponent("index.html"), allowingReadAccessTo: dir)
        }
        tunnels.onExternalChange = { [weak self] in self?.pushState(resume: true) }
        tunnels.load { [weak self] in self?.pushState(resume: true) }

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(appResumed), name: UIApplication.willEnterForegroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(appPaused), name: UIApplication.didEnterBackgroundNotification, object: nil)
        startStats()
    }

    @objc private func appResumed() {
        startStats()
        tunnels.load { [weak self] in self?.pushState(resume: true) }
    }

    @objc private func appPaused() {
        statsTimer?.invalidate()
        statsTimer = nil
    }

    private func initialState() -> [String: Any] {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = "\(info["CFBundleShortVersionString"] as? String ?? "1.0").\(info["CFBundleVersion"] as? String ?? "0")"
        return ["version": version, "backup": Backup.status()]
    }

    /// Объект window.Android для страницы.
    private static func shim(initial: [String: Any]) -> String {
        let json = String(data: (try? JSONSerialization.data(withJSONObject: initial)) ?? Data("{}".utf8),
                          encoding: .utf8) ?? "{}"
        return """
        (function(){
          var S = {running:[], stats:'{}', proxyTunnel:'', ptStats:'{}', backup:'{}', version:''};
          var init = \(json); for (var k in init) S[k] = init[k];
          window.__lvSet = function(o){ for (var k in o) S[k] = o[k]; };
          function post(m){ window.webkit.messageHandlers.lv.postMessage({m:m, a:[].slice.call(arguments, 1)}); }
          window.Android = {
            isIOS: true,
            getRunning: function(){ return JSON.stringify(S.running); },
            setTunnel: function(n, on, c){ post('setTunnel', n, !!on, c || ''); },
            getStats: function(){ return S.stats; },
            warpRegister: function(pub){ post('warpRegister', pub); },
            setProxy: function(on){ post('setProxy', !!on); },
            isProxyRunning: function(){ return false; },
            getProxyStats: function(){ return '{}'; },
            getLanIp: function(){ return ''; },
            setProxyTunnel: function(on, cfg){ post('setProxyTunnel', !!on, cfg || ''); },
            getProxyTunnel: function(){ return S.proxyTunnel; },
            getProxyTunnelStats: function(){ return S.ptStats; },
            backupStatus: function(){ return S.backup; },
            chooseBackupFolder: function(restore){ post('chooseBackupFolder', !!restore); },
            writeBackup: function(json){ post('writeBackup', json); },
            disableBackup: function(){ post('disableBackup'); S.backup = JSON.stringify({folder:'', savedAt:0, error:''}); },
            getVersion: function(){ return S.version; },
            getInstalledApps: function(){ return '[]'; },
            isInstalled: function(){ return false; },
            copy: function(t){ post('copy', t); },
            adMoment: function(){ post('adMoment'); }
          };
        })();
        """
    }

    // MARK: - Состояние для страницы

    private func stateObject() -> [String: Any] {
        ["running": tunnels.running, "proxyTunnel": tunnels.proxyTunnelId, "backup": Backup.status()]
    }

    /// Передаёт странице состояние; resume — заодно перерисовать её (как при возвращении в приложение).
    private func pushState(resume: Bool = false) {
        var js = "window.__lvSet && window.__lvSet(\(Self.encode(stateObject())));"
        if resume { js += "window.onAppResume && window.onAppResume();" }
        web.evaluateJavaScript(js, completionHandler: nil)
    }

    /// Вызывает window.fn(args...) на странице.
    private func call(_ fn: String, _ args: [Any?], before: String = "") {
        let list = args.map { $0.map(Self.encode) ?? "null" }.joined(separator: ", ")
        web.evaluateJavaScript("\(before)window.\(fn) && window.\(fn)(\(list));", completionHandler: nil)
    }

    private static func encode(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]),
              let text = String(data: data, encoding: .utf8) else { return "null" }
        return text
    }

    private func startStats() {
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.pollStats() }
    }

    private func pollStats() {
        let mode = tunnels.runningMode
        guard !mode.isEmpty else { return }
        let name = tunnels.running.first ?? ""
        tunnels.stats { [weak self] s in
            guard let self = self, let s = s else { return }
            let rx = (s["rx"] as? NSNumber)?.int64Value ?? 0
            let tx = (s["tx"] as? NSNumber)?.int64Value ?? 0
            var update: [String: Any] = [:]
            if mode == "awg" {
                let stats: [String: Any] = ["name": name, "rx": rx, "tx": tx,
                                            "handshake": (s["handshake"] as? NSNumber)?.int64Value ?? 0,
                                            "now": Int64(Date().timeIntervalSince1970 * 1000)]
                update["stats"] = Self.encode(stats)
            } else {
                update["ptStats"] = Self.encode(["rx": rx, "tx": tx])
            }
            self.web.evaluateJavaScript("window.__lvSet && window.__lvSet(\(Self.encode(update)));", completionHandler: nil)
        }
    }

    // MARK: - Вызовы со страницы

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let method = body["m"] as? String else { return }
        let a = body["a"] as? [Any] ?? []
        func str(_ i: Int) -> String { i < a.count ? (a[i] as? String ?? "") : "" }
        func bool(_ i: Int) -> Bool { i < a.count ? ((a[i] as? NSNumber)?.boolValue ?? false) : false }

        switch method {
        case "setTunnel": setTunnel(name: str(0), on: bool(1), conf: str(2))
        case "setProxyTunnel": setProxyTunnel(on: bool(0), cfg: str(1))
        case "setProxy":
            let message: String? = bool(0)
                ? "На iPhone LocalProxy не может работать в фоне: iOS усыпляет свёрнутые приложения. Включите большую кнопку LocalVPN или прокси-туннель"
                : nil
            call("onProxyState", [false, message])
        case "warpRegister":
            Warp.register(publicKey: str(0)) { [weak self] data, error in
                guard let self = self else { return }
                // data — готовый JSON-объект, его передаём как есть
                let js = "window.onWarpResult && window.onWarpResult(\(data ?? "null"), \(error.map(Self.encode) ?? "null"));"
                self.web.evaluateJavaScript(js, completionHandler: nil)
            }
        case "chooseBackupFolder": chooseBackupFolder(restore: bool(0))
        case "writeBackup": Backup.write(str(0)) { [weak self] in self?.pushState() }
        case "disableBackup": Backup.disable(); pushState()
        case "copy": UIPasteboard.general.string = str(0)
        case "adMoment": Ads.shared.moment(from: self)
        default: break
        }
    }

    private func setTunnel(name: String, on: Bool, conf: String) {
        let report: (String?) -> Void = { [weak self] error in
            guard let self = self else { return }
            let state = "window.__lvSet && window.__lvSet(\(Self.encode(self.stateObject())));"
            self.call("onTunnelState", [self.tunnels.running, error], before: state)
        }
        if on {
            tunnels.start(["mode": "awg", "name": name, "conf": conf, "server": Self.endpoint(of: conf)], done: report)
        } else if tunnels.running.contains(name) || tunnels.runningMode.isEmpty {
            tunnels.stopFromPage { report(nil) }
        } else {
            report(nil) // этот туннель и так выключен
        }
    }

    private func setProxyTunnel(on: Bool, cfg: String) {
        let report: (String?) -> Void = { [weak self] error in
            guard let self = self else { return }
            let id = self.tunnels.proxyTunnelId
            let state = "window.__lvSet && window.__lvSet(\(Self.encode(self.stateObject())));"
            self.call("onProxyTunnelState", [id.isEmpty ? nil : id, error], before: state)
        }
        if on {
            guard let data = cfg.data(using: .utf8),
                  var obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                report("Настройки прокси повреждены"); return
            }
            if let port = obj["port"] as? NSNumber { obj["port"] = port.stringValue }
            obj["mode"] = "socks"
            obj["server"] = "\(obj["host"] as? String ?? ""):\(obj["port"] as? String ?? "")"
            tunnels.start(obj, done: report)
        } else if tunnels.runningMode == "socks" {
            tunnels.stopFromPage { report(nil) }
        } else {
            report(nil)
        }
    }

    /// Адрес сервера из конфига — его iOS показывает в Настройках → VPN.
    private static func endpoint(of conf: String) -> String {
        for line in conf.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: "=", maxSplits: 1)
            if parts.count == 2, parts[0].trimmingCharacters(in: .whitespaces).lowercased() == "endpoint" {
                return parts[1].trimmingCharacters(in: .whitespaces)
            }
        }
        return "LocalVPN"
    }

    // MARK: - Резервная копия: выбор папки

    private func chooseBackupFolder(restore: Bool) {
        pickerRestore = restore
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.folder])
        picker.delegate = self
        picker.allowsMultipleSelection = false
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { documentPickerWasCancelled(controller); return }
        let restore = pickerRestore
        do {
            if restore {
                let text = try Backup.read(from: url)
                try Backup.rememberFolder(url) // дальше копия сохраняется в ту же папку
                pushState()
                call("onBackupRestore", [text, nil])
            } else {
                try Backup.rememberFolder(url)
                pushState()
                call("onBackupFolder", [url.lastPathComponent, nil])
            }
        } catch {
            call(restore ? "onBackupRestore" : "onBackupFolder", [nil, error.localizedDescription])
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        call(pickerRestore ? "onBackupRestore" : "onBackupFolder", [nil, nil])
    }

    // MARK: - Ссылки открываются в Safari

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = action.request.url, !url.isFileURL, url.scheme != "about" {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url { UIApplication.shared.open(url) }
        return nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pushState(resume: true) // состояние VPN могло загрузиться раньше страницы
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }
}

/// WKUserContentController держит обработчик сильной ссылкой — прокладка, чтобы не было утечки.
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(c, didReceive: message)
    }
}
