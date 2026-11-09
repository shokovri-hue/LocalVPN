import UIKit
import YandexMobileAds

/// Полноэкранная (межстраничная) реклама Рекламной сети Яндекса — как в Android-версии.
/// Показывается не чаще раза в 7 минут, только пока приложение открыто и только в естественный
/// момент: открыли экран VPN или прокси, включили или выключили соединение.
/// ID блока задаётся при сборке (переменная YANDEX_IOS_AD_UNIT_ID в GitHub); без него —
/// тестовая реклама Яндекса, она денег не приносит.
final class Ads: NSObject, InterstitialAdDelegate {
    static let shared = Ads()

    private static let interval: TimeInterval = 7 * 60
    private static let retry: TimeInterval = 2 * 60
    private static let demoUnit = "demo-interstitial-yandex"

    private let loader = InterstitialAdLoader()
    private var ad: InterstitialAd?
    private var loading = false
    private var lastFailed: Date?
    private var sessionStart: Date?

    private var adUnitID: String {
        let id = (Bundle.main.object(forInfoDictionaryKey: "YandexAdUnitID") as? String ?? "")
            .trimmingCharacters(in: .whitespaces)
        return id.isEmpty || id.hasPrefix("$(") ? Self.demoUnit : id
    }

    private var lastShown: Date? {
        get { UserDefaults.standard.object(forKey: "ads.lastShown") as? Date }
        set { UserDefaults.standard.set(newValue, forKey: "ads.lastShown") }
    }

    /// Запуск приложения: первая реклама — не раньше чем через 7 минут.
    func start() {
        if sessionStart == nil { sessionStart = Date() }
        YandexAds.initializeSDK(completionHandler: nil)
        preload()
    }

    /// Заранее загружает рекламу, чтобы в нужный момент она показалась сразу.
    func preload() {
        guard ad == nil, !loading else { return }
        if let failed = lastFailed, Date().timeIntervalSince(failed) < Self.retry { return }
        loading = true
        loader.loadAd(with: AdRequest(adUnitID: adUnitID)) { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.loading = false
                switch result {
                case .success(let ad):
                    ad.delegate = self
                    self.ad = ad
                case .failure:
                    self.lastFailed = Date()
                }
            }
        }
    }

    /// Естественный момент для показа. Показывает рекламу, если прошло 7 минут.
    func moment(from controller: UIViewController) {
        let since = [lastShown, sessionStart].compactMap { $0 }.max() ?? Date()
        guard Date().timeIntervalSince(since) >= Self.interval,
              controller.presentedViewController == nil,
              let ad = ad else { preload(); return }
        ad.show(from: controller)
    }

    // MARK: - InterstitialAdDelegate

    func interstitialAdDidShow(_ interstitialAd: InterstitialAd) {
        lastShown = Date()
    }

    func interstitialAdDidDismiss(_ interstitialAd: InterstitialAd) {
        finished(interstitialAd)
    }

    func interstitialAd(_ interstitialAd: InterstitialAd, didFailToShow error: Error) {
        finished(interstitialAd)
    }

    func interstitialAdDidClick(_ interstitialAd: InterstitialAd) {}

    func interstitialAd(_ interstitialAd: InterstitialAd, didTrackImpression impressionData: ImpressionData?) {}

    private func finished(_ shown: InterstitialAd) {
        shown.delegate = nil
        if ad === shown { ad = nil }
        preload() // следующая реклама будет готова заранее
    }
}
