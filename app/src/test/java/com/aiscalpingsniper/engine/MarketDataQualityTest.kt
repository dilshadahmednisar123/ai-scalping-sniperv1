package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataQualityTest {
    private val quality = MarketDataQuality()
    private fun candles(count: Int = 4, step: Long = 60_000L) =
        (0 until count).map { i ->
            val price = 1.0 + i * 0.001
            Candle(i * step + 1L, price, price + 0.002, price - 0.002, price + 0.001, 10.0)
        }

    private fun batch(
        candles: List<Candle> = candles(),
        fetchedAt: Long = 1_000_000L,
        spread: Double? = 0.0001
    ) = MarketDataBatch("CSV_IMPORT", "EURUSD", "M1", fetchedAt, candles, spread)

    @Test
    fun acceptsFreshChronologicalFeed() {
        val result = quality.validate(batch(), nowMillis = 1_030_000L, expectedIntervalMillis = 60_000L)
        assertEquals(FeedHealth.HEALTHY, result.health)
    }

    @Test
    fun marksOldFeedStale() {
        val result = quality.validate(batch(), nowMillis = 1_200_001L, expectedIntervalMillis = 60_000L)
        assertEquals(FeedHealth.STALE, result.health)
    }

    @Test
    fun rejectsOutOfOrderCandles() {
        val ordered = candles()
        val invalid = listOf(ordered[0], ordered[2], ordered[1], ordered[3])
        val result = quality.validate(batch(candles = invalid), 1_030_000L, 60_000L)
        assertEquals(FeedHealth.INVALID, result.health)
    }

    @Test
    fun rejectsLargeCandleGap() {
        val invalid = candles(step = 300_000L)
        val result = quality.validate(batch(candles = invalid), 1_030_000L, 60_000L)
        assertEquals(FeedHealth.INVALID, result.health)
        assertTrue(result.detail.contains("gap"))
    }

    @Test
    fun rejectsNegativeSpread() {
        val result = quality.validate(batch(spread = -0.1), 1_030_000L, 60_000L)
        assertEquals(FeedHealth.INVALID, result.health)
    }

    @Test
    fun rejectsMissingFeedIdentity() {
        val invalid = MarketDataBatch("", "EURUSD", "M1", 1_000_000L, candles())
        val result = quality.validate(invalid, 1_030_000L, 60_000L)
        assertEquals(FeedHealth.INVALID, result.health)
    }

    @Test
    fun reportsInsufficientCandles() {
        val result = quality.validate(batch(candles = candles(count = 2)), 1_030_000L, 60_000L)
        assertEquals(FeedHealth.INSUFFICIENT, result.health)
    }
}
