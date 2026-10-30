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
import com.aiscalpingsniper.engine.Candle
import com.aiscalpingsniper.engine.Direction
import com.aiscalpingsniper.engine.MarketDataQuality
import com.aiscalpingsniper.engine.FeedHealth
import com.aiscalpingsniper.engine.SetupStatus
import com.aiscalpingsniper.engine.WatchlistInstrument
import com.aiscalpingsniper.engine.WatchlistScanner
import com.aiscalpingsniper.engine.LiveQuoteFeed
import com.aiscalpingsniper.engine.MarketUniverse
import com.aiscalpingsniper.engine.SevenTimeframeCollector
import com.aiscalpingsniper.engine.BiquoteCalendarFeed
import com.aiscalpingsniper.engine.CalendarEvent
import com.aiscalpingsniper.engine.EconomicCalendarFilter
import com.aiscalpingsniper.engine.NewsGateStatus
import com.aiscalpingsniper.engine.MarketOpportunityEngine
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.abs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** User-started foreground scanner; Android may stop it under OS background limits. */
class LiveScanService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val matrixScheduler = Executors.newSingleThreadScheduledExecutor()
    private val provider by lazy { BiquoteMarketDataProvider() }
    private val scanner by lazy { WatchlistScanner(provider) }
    private val quoteFeed by lazy { LiveQuoteFeed() }
    private val symbols = MarketUniverse.forexPairs

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(ONGOING_ID, buildOngoing("Connecting to experimental market feed…"))
        executor.scheduleWithFixedDelay({
            try {
                scanOnce()
            } catch (error: Exception) {
                // Keep the periodic task alive after an unexpected cycle failure.
                val detail = (error.message ?: error::class.java.simpleName).take(140)
                getSystemService(NotificationManager::class.java)
                    .notify(ONGOING_ID, buildOngoing("Scan cycle failed • retrying next cycle • " + detail))
            }
        }, 0, 60, TimeUnit.SECONDS)
        // 203 OHLC frames are refreshed independently; never overlap matrix runs.
        matrixScheduler.scheduleWithFixedDelay({
            try { collectMatrixAndRank() } catch (error: Exception) {
                getSharedPreferences("live_scan", MODE_PRIVATE).edit()
                    .putString("matrix_status", "UNAVAILABLE: " + (error.message ?: "matrix scan failed").take(120)).apply()
            }
        }, 0, 15, TimeUnit.MINUTES)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun collectMatrixAndRank() {
        val prefs = getSharedPreferences("live_scan", MODE_PRIVATE)
        val collector = SevenTimeframeCollector(provider)
        val frames = try { collector.collect(symbols) } finally { collector.close() }
        val byPair = frames.filter { it.status == "LIVE" }
            .groupBy { it.symbol }
            .mapValues { (_, rows) -> rows.associate { it.timeframe to it.candles } }
        val report = MarketOpportunityEngine().analyze(symbols, byPair)
        val calendar = BiquoteCalendarFeed().fetch()
        val gate = EconomicCalendarFilter()
        val eventsJson = JSONArray()
        calendar.events.forEach { e -> eventsJson.put(JSONObject()
            .put("currency", e.currency).put("title", e.title).put("impact", e.impact)
            .put("timeMillis", e.timeMillis)) }
        val rankedJson = JSONArray()
        report.ranked.forEach { opportunity ->
            val news = if (calendar.valid) gate.evaluate(opportunity.symbol, calendar.events, System.currentTimeMillis())
                else com.aiscalpingsniper.engine.NewsGateResult(NewsGateStatus.UNKNOWN, reason = calendar.detail)
            val item = JSONObject().put("symbol", opportunity.symbol)
                .put("rating", opportunity.rating ?: JSONObject.NULL)
                .put("direction", opportunity.direction?.name ?: JSONObject.NULL)
                .put("status", when {
                    news.status == NewsGateStatus.NEWS_RESTRICTED -> "NEWS_RESTRICTED"
                    opportunity.setup != null -> "CANDIDATE"
                    else -> "NO_TRADE"
                })
                .put("news", news.toJson())
                .put("calendarFlag", if (news.status == NewsGateStatus.UNKNOWN) "CALENDAR_UNVERIFIED" else "VERIFIED")
                .put("blockers", JSONArray(opportunity.blockers))
                .put("reasons", JSONArray(opportunity.reasons))
            opportunity.setup?.let { setup ->
                val targets = com.aiscalpingsniper.engine.TargetPlan.from(setup)
                item.put("entry", targets.entry).put("sl", targets.stopLoss)
                    .put("tp1", targets.tp1).put("tp2", targets.tp2).put("tp3", targets.tp3)
                    .put("rr", setup.riskReward)
            }
            rankedJson.put(item)
        }
        val liveFrames = frames.count { it.status == "LIVE" }
        prefs.edit().putString("matrix_opportunities_json", rankedJson.toString())
            .putString("calendar_events_json", eventsJson.toString())
            .putBoolean("calendar_valid", calendar.valid)
            .putLong("matrix_updated_at", System.currentTimeMillis())
            .putInt("matrix_healthy_frames", liveFrames)
            .putInt("matrix_total_frames", symbols.size * MarketUniverse.timeframes.size)
            .putString("matrix_status", report.decision + " • " + liveFrames + "/" +
                (symbols.size * MarketUniverse.timeframes.size) + " frames • Calendar " +
                if (calendar.valid) "VALID" else "UNKNOWN")
            .apply()
    }

    private fun cachedNewsGate(symbol: String): com.aiscalpingsniper.engine.NewsGateResult {
        val prefs = getSharedPreferences("live_scan", MODE_PRIVATE)
        if (!prefs.getBoolean("calendar_valid", false))
            return com.aiscalpingsniper.engine.NewsGateResult(NewsGateStatus.UNKNOWN, reason = "Economic calendar unavailable; informational only unless a matching high-impact event is confirmed.")
        val rows = runCatching { JSONArray(prefs.getString("calendar_events_json", "[]")) }.getOrDefault(JSONArray())
        val events = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                add(CalendarEvent(row.optString("currency"), row.optString("title"),
                    row.optString("impact"), row.optLong("timeMillis")))
            }
        }
        return EconomicCalendarFilter().evaluate(symbol, events, System.currentTimeMillis())
    }

    private fun scanOnce() {
        val now = System.currentTimeMillis()
        val quotes = quoteFeed.fetchUniverse(symbols, now)
        val prefs = getSharedPreferences("live_scan", MODE_PRIVATE)
        val quoteJson = JSONArray()
        quotes.forEach { quoteJson.put(it.toJson()) }
        prefs.edit().putString("universe_quotes_json", quoteJson.toString())
            .putLong("universe_quotes_updated_at", now)
            .putInt("universe_live_count", quotes.count { it.state.name == "LIVE" })
            .putBoolean("market_data_verified", quotes.size == symbols.size && quotes.all { it.state.name == "LIVE" })
            .putString("scanner_status", if (quotes.size == symbols.size && quotes.all { it.state.name == "LIVE" }) "VERIFIED • SCANNING ACTIVE" else "PARTIAL • SCANNING ACTIVE").apply()

        // Scan all requested instruments on M5. Quote snapshots are batched;
        // candle and entry validation remains per-symbol and strict.
        val watchlist = symbols.map { WatchlistInstrument(it, "M5", 300_000L) }
        val scan = scanner.scan(watchlist, System.currentTimeMillis(), candleLimit = 200)
        val healthy = scan.items.count { it.feedCheck.health == FeedHealth.HEALTHY }
        val failures = scan.items.filter { it.feedCheck.health != FeedHealth.HEALTHY }
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val failedSymbols = failures.joinToString { it.instrument.symbol + ": " + it.feedCheck.detail.take(55) }
        val liveQuotes = quotes.count { it.state.name == "LIVE" }
        val status = "Quotes " + liveQuotes + "/" + symbols.size + " live • M5 candles " +
            healthy + "/" + scan.items.size + " healthy • " + clock +
            if (failedSymbols.isNotBlank()) " • " + failedSymbols.take(180) else ""
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_ID, buildOngoing(status))

        scan.items.forEach { item ->
            val setup = item.scanResult?.setup
            if (item.feedCheck.health != FeedHealth.HEALTHY ||
                item.scanResult?.status != SetupStatus.CONFIRMED || setup == null) return@forEach
            val bid = item.currentBid ?: return@forEach
            val ask = item.currentAsk ?: return@forEach
            val liveMid = (bid + ask) / 2.0
            val maxEntryDrift = abs(setup.entry - setup.stopLoss) * 0.25
            if (abs(liveMid - setup.entry) > maxEntryDrift) return@forEach
            if (!higherTimeframesAgree(setup.direction, setup.symbol)) return@forEach
            val news = cachedNewsGate(setup.symbol)
            if (news.status == NewsGateStatus.NEWS_RESTRICTED) {
                prefs.edit().putString("signal_gate_" + setup.symbol, news.status.name + ": " + news.reason).apply()
                return@forEach
            }
            val key = setup.symbol + "|" + setup.timeframe + "|" + setup.direction + "|" + setup.signalCandleTime
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
                    body + "\nLive mid: " + price(setup.symbol, liveMid) + "\nM15/H1 EMA50 aligned; live price remains within 0.25R of signal entry. Verify your broker spread and price."
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)
                .notify((key.hashCode() and 0x7fffffff).coerceAtLeast(1), notification)
            prefs.edit().putString("last_signal_" + key, "sent").apply()
        }
    }


    /** M5 entries are notified only when both closed M15 and H1 candles agree with direction. */
    private fun higherTimeframesAgree(direction: Direction, symbol: String): Boolean {
        val quality = MarketDataQuality()
        val now = System.currentTimeMillis()
        val checks = listOf("M15" to 900_000L, "H1" to 3_600_000L)
        for ((timeframe, interval) in checks) {
            val batch = try {
                provider.fetch(symbol, timeframe, 200)
            } catch (_: Exception) {
                return false
            }
            val health = quality.validate(batch, now, interval)
            if (health.health != FeedHealth.HEALTHY) return false
            val bias = emaBias(batch.candles, 50) ?: return false
            if (bias != direction) return false
        }
        return true
    }

    private fun emaBias(candles: List<Candle>, period: Int): Direction? {
        if (candles.size < period) return null
        val closes = candles.map { it.close }
        val alpha = 2.0 / (period + 1.0)
        var ema = closes.take(period).average()
        for (close in closes.drop(period)) ema = close * alpha + ema * (1.0 - alpha)
        val last = closes.last()
        return when {
            last > ema -> Direction.LONG
            last < ema -> Direction.SHORT
            else -> null
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
        matrixScheduler.shutdownNow()
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
