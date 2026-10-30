package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataQualityTest {
    private val quality = MarketDataQuality()
    private val interval = 60_000L
    private val now = 4_630_000L
    private fun candles(count: Int = 60, step: Long = interval) =
        (0 until count).map { i ->
            val price = 1.0 + i * 0.001
            Candle(i * step + 1_000L, price, price + 0.002, price - 0.002, price + 0.001, 10.0)
        }
    private fun batch(candles: List<Candle> = candles(), fetchedAt: Long = now - 1_000L, spread: Double? = 0.0001) =
        MarketDataBatch("CSV_IMPORT", "EURUSD", "M1", fetchedAt, candles, spread)

    @Test fun acceptsFreshChronologicalFeed() {
        val data = candles()
        val currentTime = data.last().timestamp + interval + 30_000L
        assertEquals(FeedHealth.HEALTHY, quality.validate(batch(data, currentTime - 1_000L), currentTime, interval).health)
    }
    @Test fun marksOldFetchStale() {
        val data = candles()
        val currentTime = data.last().timestamp + interval + 30_000L
        assertEquals(FeedHealth.STALE, quality.validate(batch(data, currentTime - 200_000L), currentTime, interval).health)
    }
    @Test fun marksOldLatestCandleStaleEvenAfterFreshFetch() {
        val data = candles().map { it.copy(timestamp = it.timestamp - 600_000L) }
        val currentTime = candles().last().timestamp + interval + 30_000L
        val result = quality.validate(batch(data, currentTime - 1_000L), currentTime, interval)
        assertEquals(FeedHealth.STALE, result.health)
        assertTrue(result.detail.contains("candle"))
    }
    @Test fun rejectsOutOfOrderCandles() {
        val ordered = candles()
        val invalid = listOf(ordered[0], ordered[2], ordered[1]) + ordered.drop(3)
        val currentTime = ordered.last().timestamp + interval + 30_000L
        assertEquals(FeedHealth.INVALID, quality.validate(batch(invalid, currentTime - 1_000L), currentTime, interval).health)
    }
    @Test fun rejectsLargeCandleGap() {
        val invalid = candles(step = 300_000L)
        val currentTime = invalid.last().timestamp + interval + 30_000L
        val result = quality.validate(batch(invalid, currentTime - 1_000L), currentTime, interval)
        assertEquals(FeedHealth.INVALID, result.health)
        assertTrue(result.detail.contains("gap"))
    }
    @Test fun rejectsNegativeSpread() {
        val data = candles()
        val currentTime = data.last().timestamp + interval + 30_000L
        assertEquals(FeedHealth.INVALID, quality.validate(batch(data, currentTime - 1_000L, -0.1), currentTime, interval).health)
    }
    @Test fun rejectsMissingFeedIdentity() {
        val data = candles()
        val currentTime = data.last().timestamp + interval + 30_000L
        assertEquals(FeedHealth.INVALID, quality.validate(MarketDataBatch("", "EURUSD", "M1", currentTime - 1_000L, data), currentTime, interval).health)
    }
    @Test fun reportsInsufficientCandles() {
        val data = candles(count = 2)
        val currentTime = data.last().timestamp + interval + 30_000L
        assertEquals(FeedHealth.INSUFFICIENT, quality.validate(batch(data, currentTime - 1_000L), currentTime, interval).health)
    }
}
