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

    private fun candles() = (0 until 60).map { index ->
        val price = 1.0 + index * 0.001
        Candle(1_000_000L + index * 300_000L, price, price + 0.002, price - 0.002, price + 0.001)
    }

    private fun batch(symbol: String): MarketDataBatch {
        val data = candles()
        val now = data.last().timestamp + 300_000L + 30_000L
        return MarketDataBatch(
            source = "TEST_FIXTURE",
            symbol = symbol,
            timeframe = "M5",
            fetchedAtMillis = now - 1_000L,
            candles = data,
            spread = 0.0001,
            bid = data.last().close - 0.00005,
            ask = data.last().close + 0.00005,
            tickTimestampMillis = now - 500L
        )
    }

    @Test
    fun isolatesProviderFailureAndContinuesOtherSymbols() {
        val provider = object : MarketDataProvider {
            override fun fetch(symbol: String, timeframe: String, limit: Int): MarketDataBatch {
                if (symbol == "EURUSD") error("source unavailable")
                return batch(symbol)
            }
        }
        val now = batch("XAUUSD").fetchedAtMillis + 1_000L
        val result = WatchlistScanner(provider).scan(watchlist, nowMillis = now)
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
        val now = batch("GBPUSD").fetchedAtMillis + 1_000L
        val result = WatchlistScanner(provider).scan(listOf(watchlist.first()), now)
        assertEquals(FeedHealth.INVALID, result.items.single().feedCheck.health)
        assertNull(result.items.single().scanResult)
    }

    @Test
    fun rejectsBatchWithoutBidAskTick() {
        val provider = object : MarketDataProvider {
            override fun fetch(symbol: String, timeframe: String, limit: Int) =
                batch(symbol).copy(bid = null, ask = null, tickTimestampMillis = null, spread = null)
        }
        val now = batch("EURUSD").fetchedAtMillis + 1_000L
        val result = WatchlistScanner(provider).scan(listOf(watchlist.first()), now)
        assertEquals(FeedHealth.INVALID, result.items.single().feedCheck.health)
        assertTrue(result.items.single().feedCheck.detail.contains("bid/ask"))
        assertNull(result.items.single().scanResult)
    }

    @Test
    fun rejectsStaleBidAskTick() {
        val provider = object : MarketDataProvider {
            override fun fetch(symbol: String, timeframe: String, limit: Int): MarketDataBatch {
                val data = batch(symbol)
                return data.copy(tickTimestampMillis = data.fetchedAtMillis - 200_000L)
            }
        }
        val now = batch("EURUSD").fetchedAtMillis + 1_000L
        val result = WatchlistScanner(provider).scan(listOf(watchlist.first()), now)
        assertEquals(FeedHealth.STALE, result.items.single().feedCheck.health)
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
