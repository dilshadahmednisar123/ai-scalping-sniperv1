package com.aiscalpingsniper.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale

enum class QuoteState { LIVE, STALE, CLOSED, UNAVAILABLE, INVALID }

data class LiveQuote(
    val symbol: String,
    val bid: Double?,
    val ask: Double?,
    val mid: Double?,
    val spread: Double?,
    val timestampMillis: Long?,
    val receivedAtMillis: Long,
    val source: String?,
    val state: QuoteState,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("bid", bid ?: JSONObject.NULL)
        put("ask", ask ?: JSONObject.NULL); put("mid", mid ?: JSONObject.NULL)
        put("spread", spread ?: JSONObject.NULL)
        put("timestampMillis", timestampMillis ?: JSONObject.NULL)
        put("receivedAtMillis", receivedAtMillis)
        put("source", source ?: JSONObject.NULL); put("state", state.name)
        put("detail", detail)
    }

    companion object {
        fun fromJson(value: JSONObject): LiveQuote = LiveQuote(
            value.getString("symbol"),
            value.optDoubleOrNull("bid"), value.optDoubleOrNull("ask"),
            value.optDoubleOrNull("mid"), value.optDoubleOrNull("spread"),
            if (value.isNull("timestampMillis")) null else value.optLong("timestampMillis"),
            value.optLong("receivedAtMillis"), value.optString("source").takeIf { it.isNotBlank() && it != "null" },
            runCatching { QuoteState.valueOf(value.optString("state")) }.getOrDefault(QuoteState.INVALID),
            value.optString("detail")
        )
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }

/**
 * Free public Biquote REST adapter. Batch snapshots are preferred; missing
 * symbols are retried individually. No price is called LIVE unless the
 * provider reports an open, non-stale quote with a recent ISO timestamp.
 */
class LiveQuoteFeed(
    private val maxAgeMillis: Long = 90_000L,
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 10_000
) {
    fun fetchUniverse(symbols: List<String>, nowMillis: Long = System.currentTimeMillis()): List<LiveQuote> {
        require(symbols.isNotEmpty() && symbols.distinct().size == symbols.size)
        require(symbols.all { it.matches(Regex("[A-Z]{6}|XAUUSD")) })
        val bySymbol = linkedMapOf<String, LiveQuote>()
        val batchUrl = buildString {
            append("https://biquote.io/api/latest?")
            append(symbols.joinToString("&") { "symbols=" + it })
        }
        val batchResult = runCatching { parseBatch(request(batchUrl), nowMillis) }.getOrDefault(emptyMap())
        bySymbol.putAll(batchResult)
        symbols.filterNot { bySymbol[it]?.state == QuoteState.LIVE }.forEach { symbol ->
            val fallback = runCatching {
                parseQuote(request("https://biquote.io/api/$symbol?allowStale=false"), symbol, nowMillis)
            }.getOrElse { error ->
                LiveQuote(symbol, null, null, null, null, null, nowMillis, null,
                    QuoteState.UNAVAILABLE, (error.message ?: "Quote unavailable").take(140))
            }
            // A valid batch quote wins over a failed fallback; otherwise preserve
            // the fallback's explicit stale/closed/unavailable reason.
            val current = bySymbol[symbol]
            if (fallback.state == QuoteState.LIVE || current == null ||
                current.state == QuoteState.INVALID || current.state == QuoteState.UNAVAILABLE) {
                bySymbol[symbol] = fallback
            }
        }
        return symbols.map { bySymbol[it] ?: LiveQuote(
            it, null, null, null, null, null, nowMillis, null,
            QuoteState.UNAVAILABLE, "Provider returned no quote for this instrument."
        ) }
    }

    private fun parseBatch(json: JSONObject, now: Long): Map<String, LiveQuote> {
        val array = when {
            json.optJSONArray("items") != null -> json.getJSONArray("items")
            json.optJSONArray("data") != null -> json.getJSONArray("data")
            json.optJSONArray("quotes") != null -> json.getJSONArray("quotes")
            else -> null
        }
        val result = linkedMapOf<String, LiveQuote>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val symbol = item.optString("symbol").uppercase(Locale.ROOT)
                if (symbol.isNotBlank()) result[symbol] = parseQuote(item, symbol, now)
            }
        } else {
            // /api/latest returns an object keyed by symbol.
            val keys = json.keys()
            while (keys.hasNext()) {
                val symbol = keys.next().uppercase(Locale.ROOT)
                val item = json.optJSONObject(symbol) ?: continue
                result[symbol] = parseQuote(item, symbol, now)
            }
        }
        return result
    }

    private fun parseQuote(json: JSONObject, expected: String, now: Long): LiveQuote {
        val symbol = json.optString("symbol").uppercase(Locale.ROOT)
        require(symbol == expected) { "Provider symbol mismatch: expected $expected, received $symbol." }
        val bid = json.optDoubleOrNull("bid")
        val ask = json.optDoubleOrNull("ask")
        require(bid != null && ask != null && bid > 0.0 && ask >= bid) {
            "Provider returned invalid bid/ask for $expected."
        }
        val timestampText = json.optString("timestamp")
        require(timestampText.isNotBlank()) { "Provider omitted ISO quote timestamp for $expected." }
        val timestamp = Instant.parse(timestampText).toEpochMilli()
        require(timestamp <= now + 5_000L) { "Provider quote timestamp is in the future." }
        val age = now - timestamp
        val reportedAge = json.optLong("quoteAgeSeconds", Long.MAX_VALUE)
        val providerStale = json.optBoolean("stale", true)
        val marketState = json.optString("marketState", "unknown").lowercase(Locale.ROOT)
        val state = when {
            marketState == "closed" -> QuoteState.CLOSED
            providerStale || reportedAge < 0 || reportedAge > maxAgeMillis / 1_000L || age > maxAgeMillis -> QuoteState.STALE
            marketState != "open" -> QuoteState.INVALID
            else -> QuoteState.LIVE
        }
        val mid = json.optDoubleOrNull("mid") ?: ((bid + ask) / 2.0)
        return LiveQuote(
            expected, bid, ask, mid, ask - bid, timestamp, now,
            json.optString("source").takeIf { it.isNotBlank() },
            state,
            when (state) {
                QuoteState.LIVE -> "Fresh provider bid/ask; market open."
                QuoteState.STALE -> "Provider reports a stale quote or timestamp exceeds freshness limit."
                QuoteState.CLOSED -> "Market closed; last-known quote is display-only."
                else -> "Provider market state is not verified."
            }
        )
    }

    private fun request(address: String): JSONObject {
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
            check(code in 200..299) {
                val retry = connection.getHeaderField("Retry-After")
                "Market feed HTTP $code" + (retry?.let { "; retry after $it seconds" } ?: "") +
                    ": " + body.take(120)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
