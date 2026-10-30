package com.aiscalpingsniper.engine

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale

/** Experimental adapter for biquote.io's public OHLC endpoint; no API key required. */
class BiquoteMarketDataProvider(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000
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
            else -> throw IllegalArgumentException("Unsupported timeframe: $timeframe")
        }
        val endpoint = URL("https://biquote.io/api/$symbol/ohlc?interval=$interval&limit=$limit")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
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
            check(code in 200..299) { "Market feed HTTP $code: " + body.take(180) }
            val root = JSONObject(body)
            val returnedSymbol = root.optString("symbol")
            val returnedInterval = root.optString("interval")
            check(returnedSymbol.equals(symbol, ignoreCase = true)) { "Provider symbol mismatch." }
            check(returnedInterval == interval) { "Provider timeframe mismatch." }
            val bars = root.optJSONArray("bars") ?: error("Provider response has no bars array.")
            val candles = buildList {
                for (i in 0 until bars.length()) {
                    val bar = bars.optJSONObject(i) ?: continue
                    if (bar.optBoolean("isOpen", false)) continue
                    val timestamp = Instant.parse(bar.getString("openTime")).toEpochMilli()
                    add(Candle(timestamp, bar.getDouble("open"), bar.getDouble("high"),
                        bar.getDouble("low"), bar.getDouble("close"),
                        bar.optDouble("tickVolume", bar.optDouble("volume", 0.0))))
                }
            }.sortedBy { it.timestamp }
            check(candles.size >= 60) { "Only " + candles.size + " closed candles returned; need at least 60." }
            check(candles.zipWithNext().all { it.first.timestamp < it.second.timestamp }) {
                "Provider returned duplicate or unordered candle timestamps."
            }
            return MarketDataBatch(
                source = "biquote.io experimental public feed",
                symbol = returnedSymbol,
                timeframe = timeframe.uppercase(Locale.ROOT),
                fetchedAtMillis = System.currentTimeMillis(),
                candles = candles
            )
        } finally {
            connection.disconnect()
        }
    }
}
