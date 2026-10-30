package com.aiscalpingsniper.engine

data class MarketDataBatch(
    val source: String,
    val symbol: String,
    val timeframe: String,
    val fetchedAtMillis: Long,
    val candles: List<Candle>,
    val spread: Double? = null,
    val bid: Double? = null,
    val ask: Double? = null,
    val tickTimestampMillis: Long? = null
)
enum class FeedHealth { HEALTHY, STALE, INVALID, INSUFFICIENT }
data class FeedCheck(val health: FeedHealth, val detail: String)

class MarketDataQuality(
    private val maxFetchAgeMillis: Long = 90_000L,
    private val closedBarGraceMillis: Long = 120_000L,
    private val maxGapMultiplier: Double = 3.0
) {
    init {
        require(maxFetchAgeMillis > 0 && closedBarGraceMillis > 0)
        require(maxGapMultiplier.isFinite() && maxGapMultiplier > 1.0)
    }

    fun validate(batch: MarketDataBatch, nowMillis: Long, expectedIntervalMillis: Long): FeedCheck {
        if (batch.source.isBlank() || batch.symbol.isBlank() || batch.timeframe.isBlank())
            return FeedCheck(FeedHealth.INVALID, "Missing provider, symbol or timeframe identity.")
        if (expectedIntervalMillis <= 0 || batch.fetchedAtMillis <= 0 || nowMillis < batch.fetchedAtMillis)
            return FeedCheck(FeedHealth.INVALID, "Invalid feed timestamps or timeframe interval.")
        if (batch.spread != null && (!batch.spread.isFinite() || batch.spread < 0.0))
            return FeedCheck(FeedHealth.INVALID, "Invalid bid/ask spread.")
        if (batch.bid != null || batch.ask != null || batch.tickTimestampMillis != null) {
            if (batch.bid == null || batch.ask == null || batch.tickTimestampMillis == null ||
                !batch.bid.isFinite() || !batch.ask.isFinite() || batch.bid <= 0.0 || batch.ask < batch.bid)
                return FeedCheck(FeedHealth.INVALID, "Incomplete or invalid bid/ask tick.")
            if (batch.tickTimestampMillis > nowMillis + 5_000L)
                return FeedCheck(FeedHealth.INVALID, "Bid/ask tick timestamp is in the future.")
            if (nowMillis - batch.tickTimestampMillis > maxFetchAgeMillis)
                return FeedCheck(FeedHealth.STALE, "Bid/ask tick is stale.")
        }
        if (batch.candles.size < 60)
            return FeedCheck(FeedHealth.INSUFFICIENT, "At least 60 closed candles are required.")
        if (batch.candles.zipWithNext().any { it.first.timestamp >= it.second.timestamp })
            return FeedCheck(FeedHealth.INVALID, "Candle timestamps are not strictly chronological.")
        val maxGap = (expectedIntervalMillis * maxGapMultiplier).toLong()
        if (batch.candles.zipWithNext().any { it.second.timestamp - it.first.timestamp > maxGap })
            return FeedCheck(FeedHealth.INVALID, "Feed contains a candle gap larger than the configured tolerance.")
        if (nowMillis - batch.fetchedAtMillis > maxFetchAgeMillis)
            return FeedCheck(FeedHealth.STALE, "Last successful fetch is outside the freshness limit.")
        val latestBarClose = batch.candles.last().timestamp + expectedIntervalMillis
        if (nowMillis - latestBarClose > expectedIntervalMillis + closedBarGraceMillis)
            return FeedCheck(FeedHealth.STALE, "Latest closed candle is too old; no signal will be generated.")
        if (latestBarClose > nowMillis + closedBarGraceMillis)
            return FeedCheck(FeedHealth.INVALID, "Provider candle timestamp is in the future.")
        return FeedCheck(FeedHealth.HEALTHY, "Feed identity, ordering, gaps and candle freshness checks passed.")
    }
}
