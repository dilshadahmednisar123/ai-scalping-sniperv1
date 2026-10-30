package com.aiscalpingsniper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aiscalpingsniper.engine.BiquoteMarketDataProvider
import com.aiscalpingsniper.engine.FeedHealth
import com.aiscalpingsniper.engine.SetupStatus
import com.aiscalpingsniper.engine.WatchlistInstrument
import com.aiscalpingsniper.engine.WatchlistScanner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** User-started foreground scanner; Android may stop it under OS background limits. */
class LiveScanService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val provider by lazy { BiquoteMarketDataProvider() }
    private val scanner by lazy { WatchlistScanner(provider) }
    private val symbols = listOf("EURUSD", "GBPUSD", "USDJPY", "XAUUSD")

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(ONGOING_ID, buildOngoing("Connecting to experimental market feed…"))
        executor.scheduleWithFixedDelay({ scanOnce() }, 0, 60, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun scanOnce() {
        val watchlist = symbols.map { WatchlistInstrument(it, "M5", 300_000L) }
        val scan = scanner.scan(watchlist, System.currentTimeMillis(), candleLimit = 200)
        val healthy = scan.items.count { it.feedCheck.health == FeedHealth.HEALTHY }
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_ID, buildOngoing("Experimental feed • " + healthy + "/" + scan.items.size + " healthy • " + clock))

        scan.items.forEach { item ->
            val setup = item.scanResult?.setup
            if (item.feedCheck.health != FeedHealth.HEALTHY ||
                item.scanResult?.status != SetupStatus.CONFIRMED || setup == null) return@forEach
            val key = setup.symbol + "|" + setup.timeframe + "|" + setup.direction + "|" + setup.signalCandleTime
            val prefs = getSharedPreferences("live_scan", MODE_PRIVATE)
            if (prefs.getString("last_signal_" + key, null) == "sent") return@forEach
            val title = setup.symbol + " " + setup.direction + " setup"
            val body = "M5 • Entry " + price(setup.symbol, setup.entry) +
                " • SL " + price(setup.symbol, setup.stopLoss) +
                " • TP " + price(setup.symbol, setup.takeProfit) +
                " • Rule score " + setup.score + "/100"
            val notification = NotificationCompat.Builder(this, SIGNAL_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    body + "\nExperimental third-party feed. Verify spread and broker price before trading."
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)
                .notify((key.hashCode() and 0x7fffffff).coerceAtLeast(1), notification)
            prefs.edit().putString("last_signal_" + key, "sent").apply()
        }
    }

    private fun price(symbol: String, value: Double): String {
        val digits = when {
            symbol.endsWith("JPY") -> 3
            symbol.startsWith("XAU") -> 2
            else -> 5
        }
        return String.format(java.util.Locale.US, "%." + digits + "f", value)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                STATUS_CHANNEL, "Scanner status", NotificationManager.IMPORTANCE_LOW
            ))
            manager.createNotificationChannel(NotificationChannel(
                SIGNAL_CHANNEL, "Trade setup alerts", NotificationManager.IMPORTANCE_HIGH
            ))
        }
    }

    private fun buildOngoing(message: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("AI Scalping Sniper is scanning")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                message + "\nPolling M5 candles every 60 seconds. Experimental public data source."
            ))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.aiscalpingsniper.START_SCAN"
        const val ACTION_STOP = "com.aiscalpingsniper.STOP_SCAN"
        private const val ONGOING_ID = 7301
        private const val STATUS_CHANNEL = "scanner_status"
        private const val SIGNAL_CHANNEL = "scanner_signals"
    }
}
