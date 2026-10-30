package com.aiscalpingsniper.engine

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/**
 * Experimental adapter for biquote.io public read-only endpoints.
 * Requires both closed OHLC candles and a fresh bid/ask tick; no API key required.
 */
class BiquoteMarketDataProvider(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val maxTickAgeMillis: Long = 90_000L
) : MarketDataProvider {
    override fun fetch(symbol: String, timeframe: String, limit: Int): MarketDataBatch {
        require(symbol.matches(Regex("[A-Za-z0-9._-]{3,20}"))) { "Invalid market symbol." }
        require(limit in 60..1000) { "Candle limit must be between 60 and 1000." }
        val interval = when (timeframe.uppercase(Locale.ROOT)) {
            "M1" -> "1m"
            "M5" -> "5m"
            "M15" -> "15m"
            "M30" -> "30m"
            "H1", "1H" -> "1h"
            "H4", "4H" -> "4h"
            "D1", "1D" -> "1d"
            else -> throw IllegalArgumentException("Unsupported timeframe: " + timeframe)
        }

        val ohlc = requestJson("https://biquote.io/api/" + symbol + "/ohlc?interval=" + interval + "&limit=" + limit)
        val returnedSymbol = ohlc.optString("symbol")
        require(returnedSymbol.equals(symbol, ignoreCase = true)) { "Provider symbol mismatch." }
        require(ohlc.optString("interval") == interval) { "Provider timeframe mismatch." }
        val bars = ohlc.optJSONArray("bars") ?: error("Provider response has no bars array.")
        val candles = buildList {
            for (i in 0 until bars.length()) {
                val bar = bars.optJSONObject(i) ?: continue
                if (bar.optBoolean("isOpen", false)) continue
                val timestamp = Instant.parse(bar.getString("openTime")).toEpochMilli()
                add(Candle(
                    timestamp, bar.getDouble("open"), bar.getDouble("high"),
                    bar.getDouble("low"), bar.getDouble("close"),
                    bar.optDouble("tickVolume", bar.optDouble("volume", 0.0))
                ))
            }
        }.sortedBy { it.timestamp }
        require(candles.size >= 60) { "Only " + candles.size + " closed candles returned; need at least 60." }
        require(candles.zipWithNext().all { it.first.timestamp < it.second.timestamp }) {
            "Provider returned duplicate or unordered candle timestamps."
        }

        val tick = requestJson("https://biquote.io/api/" + symbol)
        require(tick.optString("symbol").equals(symbol, ignoreCase = true)) { "Tick symbol mismatch." }
        val bid = tick.getDouble("bid")
        val ask = tick.getDouble("ask")
        require(bid.isFinite() && ask.isFinite() && bid > 0.0 && ask >= bid) {
            "Provider returned invalid bid/ask prices."
        }
        // The API's `time` field is an MT5 display string and is not reliably
        // parseable. Use its documented ISO-8601 `timestamp` field instead.
        val timestampText = tick.optString("timestamp")
        require(timestampText.isNotBlank()) { "Provider tick is missing its ISO timestamp." }
        val tickTime = Instant.parse(timestampText).toEpochMilli()
        val fetchedAt = System.currentTimeMillis()
        require(tick.optString("marketState").equals("open", ignoreCase = true)) {
            "Provider market is closed; last-known prices are not actionable."
        }
        require(!tick.optBoolean("stale", true)) { "Provider marked the bid/ask tick stale." }
        val reportedAgeSeconds = tick.optLong("quoteAgeSeconds", Long.MAX_VALUE)
        require(reportedAgeSeconds in 0..(maxTickAgeMillis / 1_000L)) {
            "Provider reports a stale bid/ask tick."
        }
        require(tickTime <= fetchedAt + 5_000L) { "Provider tick timestamp is in the future." }
        require(fetchedAt - tickTime <= maxTickAgeMillis) { "Provider bid/ask tick is stale." }

        return MarketDataBatch(
            source = "biquote.io experimental public feed",
            symbol = returnedSymbol,
            timeframe = timeframe.uppercase(Locale.ROOT),
            fetchedAtMillis = fetchedAt,
            candles = candles,
            spread = abs(ask - bid),
            bid = bid,
            ask = ask,
            tickTimestampMillis = tickTime
        )
    }

    private fun requestJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AI-Scalping-Sniper-Android/1.0")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Market feed HTTP " + code + ": " + body.take(180) }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
