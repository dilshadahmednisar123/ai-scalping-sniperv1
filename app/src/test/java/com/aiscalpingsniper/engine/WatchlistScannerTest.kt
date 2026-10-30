package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistScannerTest {
    private val watchlist = listOf(
        WatchlistInstrument("EURUSD", "M5", 300_000L),
        WatchlistInstrument("XAUUSD", "M5", 300_000L)
    )

    private fun batch(symbol: String) = MarketDataBatch(
        source = "TEST_FIXTURE",
        symbol = symbol,
        timeframe = "M5",
        fetchedAtMillis = 1_000_000L,
        candles = (0 until 5).map { index ->
            val price = 1.0 + index * 0.001
            Candle(index * 300_000L + 1L, price, price + 0.002, price - 0.002, price + 0.001)
        }
    )

    @Test
    fun isolatesProviderFailureAndContinuesOtherSymbols() {
        val provider = object : MarketDataProvider {
            override fun fetch(symbol: String, timeframe: String, limit: Int): MarketDataBatch {
                if (symbol == "EURUSD") error("source unavailable")
                return batch(symbol)
            }
        }
        val result = WatchlistScanner(provider).scan(watchlist, nowMillis = 1_030_000L)
        assertEquals(2, result.items.size)
        assertEquals(FeedHealth.INVALID, result.items[0].feedCheck.health)
        assertTrue(result.items[0].feedCheck.detail.contains("source unavailable"))
        assertEquals(FeedHealth.HEALTHY, result.items[1].feedCheck.health)
        assertEquals(SetupStatus.NONE, result.items[1].scanResult?.status)
    }

    @Test
    fun rejectsProviderReturningWrongSymbol() {
        val provider = object : MarketDataProvider {
            override fun fetch(symbol: String, timeframe: String, limit: Int) = batch("GBPUSD")
        }
        val result = WatchlistScanner(provider).scan(listOf(watchlist.first()), 1_030_000L)
        assertEquals(FeedHealth.INVALID, result.items.single().feedCheck.health)
        assertNull(result.items.single().scanResult)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateWatchlistEntries() {
        val provider = object : MarketDataProvider {
            override fun fetch(symbol: String, timeframe: String, limit: Int) = batch(symbol)
        }
        WatchlistScanner(provider).scan(listOf(watchlist[0], watchlist[0]), 1_030_000L)
    }
}
