package app.localvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Держит приложение активным, пока работает LocalProxy: без такого уведомления Android
 * останавливает фоновую работу, и прокси отключился бы при сворачивании приложения.
 */
class ProxyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Прокси работает"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "LocalProxy", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывается, пока работает прокси"
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_proxy)
            .setContentTitle("LocalProxy работает")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "localproxy"
        private const val ID = 1080
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            context.startForegroundService(Intent(context, ProxyService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
