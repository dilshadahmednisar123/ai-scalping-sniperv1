package com.aiscalpingsniper.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class NewsGateStatus { CLEAR, NEWS_RESTRICTED, UNKNOWN }

data class CalendarEvent(
    val currency: String,
    val title: String,
    val impact: String,
    val timeMillis: Long
)

data class NewsGateResult(
    val status: NewsGateStatus,
    val event: CalendarEvent? = null,
    val reason: String
) {
    fun toJson(): JSONObject = JSONObject().put("status", status.name)
        .put("reason", reason).put("event", event?.let {
            JSONObject().put("currency", it.currency).put("title", it.title)
                .put("impact", it.impact).put("timeMillis", it.timeMillis)
        } ?: JSONObject.NULL)
}

/** Conservative calendar gate: unavailable/invalid calendar data is UNKNOWN, never CLEAR. */
class EconomicCalendarFilter(
    private val beforeMinutes: Long = 30,
    private val afterMinutes: Long = 30
) {
    init { require(beforeMinutes >= 0 && afterMinutes >= 0) }

    fun evaluate(symbol: String, events: List<CalendarEvent>, nowMillis: Long): NewsGateResult {
        val currencies = if (symbol == "XAUUSD") setOf("USD") else
            setOf(symbol.take(3).uppercase(), symbol.takeLast(3).uppercase())
        val event = events.filter {
            it.impact.equals("high", true) &&
                it.currency.uppercase() in currencies &&
                nowMillis >= it.timeMillis - TimeUnit.MINUTES.toMillis(beforeMinutes) &&
                nowMillis <= it.timeMillis + TimeUnit.MINUTES.toMillis(afterMinutes)
        }.minByOrNull { abs(it.timeMillis - nowMillis) }
        return if (event != null) NewsGateResult(
            NewsGateStatus.NEWS_RESTRICTED, event,
            "Blocked by high-impact ${event.currency} event: ${event.title}"
        ) else NewsGateResult(NewsGateStatus.CLEAR, reason = "No matching high-impact event in blackout window.")
    }
}

data class CalendarSnapshot(val fetchedAtMillis: Long, val events: List<CalendarEvent>, val valid: Boolean, val detail: String)

/** Public calendar adapter. Unknown schema or network failure stays invalid (fail-closed). */
class BiquoteCalendarFeed(
    private val connectTimeoutMillis: Int = 7_000,
    private val readTimeoutMillis: Int = 8_000
) {
    fun fetch(nowMillis: Long = System.currentTimeMillis()): CalendarSnapshot = try {
        val connection = (URL("https://biquote.io/api/calendar/upcoming?importance=high&limit=200")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AI-Scalping-Sniper-Android/1.0")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Calendar HTTP $code" }
            val root = JSONObject(body)
            val rows = root.optJSONArray("events") ?: root.optJSONArray("data")
                ?: root.optJSONArray("items") ?: JSONArray()
            val events = buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val currency = (row.optString("currency").ifBlank { row.optString("country") }).uppercase()
                    val impact = row.optString("impact").ifBlank { row.optString("importance") }
                    val title = row.optString("title").ifBlank { row.optString("event") }
                    val timeText = row.optString("timestamp").ifBlank {
                        row.optString("dateTime").ifBlank { row.optString("time") }
                    }
                    val time = runCatching { Instant.parse(timeText).toEpochMilli() }.getOrNull()
                    if (currency.length == 3 && title.isNotBlank() && time != null)
                        add(CalendarEvent(currency, title, impact, time))
                }
            }
            val recognized = events.isNotEmpty()
            CalendarSnapshot(nowMillis, events, recognized,
                if (recognized) "Calendar parsed." else "Calendar returned no recognizable event timestamps; verification unavailable.")
        } finally { connection.disconnect() }
    } catch (error: Exception) {
        CalendarSnapshot(nowMillis, emptyList(), false, error.message ?: "Calendar unavailable.")
    }
}

data class FrameResult(
    val symbol: String,
    val timeframe: String,
    val candles: List<Candle>,
    val status: String,
    val detail: String
)

/**
 * Full 29 x 7 OHLC collector. Three workers, a shared request throttle and
 * per-frame exception isolation prevent a delinquent symbol from blocking peers.
 * Run on a background executor, not the Android main thread.
 */
class SevenTimeframeCollector(
    private val provider: BiquoteMarketDataProvider,
    private val workers: Int = 3,
    private val minimumRequestGapMillis: Long = 350L,
    private val candleLimit: Int = 200
) {
    private val pool = Executors.newFixedThreadPool(workers)
    private val throttleLock = Any()
    private var lastRequestAt = 0L

    init {
        require(workers in 1..4)
        require(minimumRequestGapMillis >= 0)
        require(candleLimit in 60..1000)
    }

    fun collect(symbols: List<String> = MarketUniverse.forexPairs): List<FrameResult> {
        val tasks = symbols.flatMap { symbol ->
            MarketUniverse.timeframes.map { frame -> Callable {
                try {
                    synchronized(throttleLock) {
                        val wait = minimumRequestGapMillis - (System.currentTimeMillis() - lastRequestAt)
                        if (wait > 0) Thread.sleep(wait)
                        lastRequestAt = System.currentTimeMillis()
                    }
                    val candles = provider.fetchCandles(symbol, frame, candleLimit)
                    FrameResult(symbol, frame, candles, "LIVE", "Closed candles validated.")
                } catch (error: Exception) {
                    FrameResult(symbol, frame, emptyList(), "UNAVAILABLE",
                        (error.message ?: error.javaClass.simpleName).take(150))
                }
            } }
        }
        return try { pool.invokeAll(tasks).mapNotNull { runCatching { it.get() }.getOrNull() } }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); emptyList() }
    }

    fun close() { pool.shutdownNow() }
}

data class TargetPlan(
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val risk: Double
) {
    companion object {
        fun from(setup: TradeSetup): TargetPlan {
            val risk = abs(setup.entry - setup.stopLoss)
            require(risk.isFinite() && risk > 0.0)
            val sign = if (setup.direction == Direction.LONG) 1.0 else -1.0
            return TargetPlan(setup.entry, setup.stopLoss,
                setup.entry + sign * risk, setup.entry + sign * risk * 2.0,
                setup.entry + sign * risk * 3.0, risk)
        }
    }
}

data class PositionProgress(
    val remainingFraction: Double = 1.0,
    val tp1Hit: Boolean = false,
    val tp2Hit: Boolean = false,
    val tp3Hit: Boolean = false,
    val stopAtBreakeven: Boolean = false,
    val realizedR: Double = 0.0
) {
    fun advance(plan: TargetPlan, direction: Direction, high: Double, low: Double): PositionProgress {
        var next = this
        if (!tp1Hit && (if (direction == Direction.LONG) high >= plan.tp1 else low <= plan.tp1))
            next = next.copy(tp1Hit = true, remainingFraction = 0.70, stopAtBreakeven = true, realizedR = realizedR + 0.30)
        if (!next.tp2Hit && (if (direction == Direction.LONG) high >= plan.tp2 else low <= plan.tp2))
            next = next.copy(tp2Hit = true, remainingFraction = 0.40, realizedR = next.realizedR + 0.60)
        if (!next.tp3Hit && (if (direction == Direction.LONG) high >= plan.tp3 else low <= plan.tp3))
            next = next.copy(tp3Hit = true, remainingFraction = 0.0, realizedR = next.realizedR + 1.20)
        return next
    }
}

/** Explicit friction model. Commission is account-currency per standard lot round trip. */
data class ExecutionCostModel(
    val spreadPrice: Double,
    val slippagePricePerSide: Double,
    val commissionPerLotRoundTrip: Double,
    val priceValuePerLot: Double = 100_000.0
) {
    init {
        require(listOf(spreadPrice, slippagePricePerSide, commissionPerLotRoundTrip, priceValuePerLot)
            .all { it.isFinite() && it >= 0.0 })
        require(priceValuePerLot > 0.0)
    }
    fun roundTripPriceCost(lots: Double = 1.0): Double {
        require(lots.isFinite() && lots > 0.0)
        return spreadPrice + 2.0 * slippagePricePerSide +
            (commissionPerLotRoundTrip * lots / priceValuePerLot)
    }
}
