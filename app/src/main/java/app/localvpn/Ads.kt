package app.localvpn

import android.app.Activity
import android.content.Context
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader

/**
 * Полноэкранная реклама Рекламной сети Яндекса.
 * Показывается не чаще раза в 7 минут, только пока приложение открыто и только в естественный
 * момент (открыли экран VPN или прокси, включили или выключили соединение) — так требуют правила
 * Яндекса. ID блока задаётся при сборке (переменная YANDEX_AD_UNIT_ID в GitHub); без него —
 * тестовая реклама Яндекса, она денег не приносит.
 * Все методы вызываются из главного потока.
 */
object Ads {
    private const val INTERVAL_MS = 7 * 60 * 1000L
    private const val RETRY_MS = 2 * 60 * 1000L
    private const val PREFS = "ads"

    private var loader: InterstitialAdLoader? = null
    private var ad: InterstitialAd? = null
    private var loading = false
    private var lastFailed = 0L
    private var sessionStart = 0L

    private val adUnitId: String get() = BuildConfig.YANDEX_AD_UNIT_ID

    /** Начало работы приложения: первая реклама — не раньше чем через 7 минут. */
    fun start(activity: Activity) {
        if (sessionStart == 0L) sessionStart = System.currentTimeMillis()
        preload(activity)
    }

    /** Заранее загружает рекламу, чтобы в нужный момент она показалась сразу. */
    fun preload(context: Context) {
        if (ad != null || loading) return
        if (System.currentTimeMillis() - lastFailed < RETRY_MS) return
        loading = true
        val l = loader ?: InterstitialAdLoader(context.applicationContext).also { loader = it }
        l.loadAd(AdRequest.Builder(adUnitId).build(), object : InterstitialAdLoadListener {
            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                loading = false
                ad = interstitialAd
            }

            override fun onAdFailedToLoad(error: AdRequestError) {
                loading = false
                lastFailed = System.currentTimeMillis()
            }
        })
    }

    /** Естественный момент для показа. Показывает рекламу, если прошло 7 минут. */
    fun moment(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = maxOf(prefs.getLong("lastShown", 0L), sessionStart)
        if (now - since < INTERVAL_MS) { preload(activity); return }
        val current = ad ?: run { preload(activity); return }
        current.setAdEventListener(object : InterstitialAdEventListener {
            override fun onAdShown() {
                prefs.edit().putLong("lastShown", System.currentTimeMillis()).apply()
            }

            override fun onAdFailedToShow(adError: AdError) {
                current.setAdEventListener(null)
                ad = null
                preload(activity)
            }

            override fun onAdDismissed() {
                current.setAdEventListener(null)
                ad = null
                preload(activity) // следующая реклама будет готова заранее
            }

            override fun onAdClicked() {}
            override fun onAdImpression(impressionData: ImpressionData?) {}
        })
        current.show(activity)
    }

    fun destroy() {
        ad?.setAdEventListener(null)
        ad = null
        loader = null
        loading = false
    }
}
