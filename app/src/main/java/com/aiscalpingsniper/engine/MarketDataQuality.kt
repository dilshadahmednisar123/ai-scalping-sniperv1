package com.aiscalpingsniper.engine

/**
 * Provider-neutral contract for market data. Adapters must identify the
 * actual source and timestamp; the scanner must never label cached data live.
 */
data class MarketDataBatch(
    val source: String,
    val symbol: String,
    val timeframe: String,
    val fetchedAtMillis: Long,
    val candles: List<Candle>,
    val spread: Double? = null
)

enum class FeedHealth { HEALTHY, STALE, INVALID, INSUFFICIENT }

data class FeedCheck(
    val health: FeedHealth,
    val detail: String
)

class MarketDataQuality(
    private val maxAgeMillis: Long = 90_000L,
    private val maxGapMultiplier: Double = 3.0
) {
    init {
        require(maxAgeMillis > 0)
        require(maxGapMultiplier.isFinite() && maxGapMultiplier > 1.0)
    }

    fun validate(batch: MarketDataBatch, nowMillis: Long, expectedIntervalMillis: Long): FeedCheck {
        if (batch.source.isBlank() || batch.symbol.isBlank() || batch.timeframe.isBlank()) {
            return FeedCheck(FeedHealth.INVALID, "Missing provider, symbol or timeframe identity.")
        }
        if (expectedIntervalMillis <= 0 || batch.fetchedAtMillis <= 0 || nowMillis < batch.fetchedAtMillis) {
            return FeedCheck(FeedHealth.INVALID, "Invalid feed timestamps or timeframe interval.")
        }
        if (batch.spread != null && (!batch.spread.isFinite() || batch.spread < 0.0)) {
            return FeedCheck(FeedHealth.INVALID, "Invalid bid/ask spread.")
        }
        if (batch.candles.size < 3) {
            return FeedCheck(FeedHealth.INSUFFICIENT, "At least three candles are required for feed validation.")
        }
        if (batch.candles.zipWithNext().any { it.first.timestamp >= it.second.timestamp }) {
            return FeedCheck(FeedHealth.INVALID, "Candle timestamps are not strictly chronological.")
        }
        val maxGap = (expectedIntervalMillis * maxGapMultiplier).toLong()
        if (batch.candles.zipWithNext().any { it.second.timestamp - it.first.timestamp > maxGap }) {
            return FeedCheck(FeedHealth.INVALID, "Feed contains a candle gap larger than the configured tolerance.")
        }
        if (nowMillis - batch.fetchedAtMillis > maxAgeMillis) {
            return FeedCheck(FeedHealth.STALE, "Last successful fetch is outside the freshness limit.")
        }
        return FeedCheck(FeedHealth.HEALTHY, "Feed identity, ordering, gaps and freshness checks passed.")
    }
}
