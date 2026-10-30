package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketUniverseTest {
    @Test
    fun requestedUniverseContainsAllPairsAndTimeframes() {
        assertEquals(29, MarketUniverse.forexPairs.size)
        assertEquals(listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1"), MarketUniverse.timeframes)
        assertEquals(203, MarketUniverse.requestedInstrumentCount)
        assertEquals(203, MarketUniverse.watchlist().size)
        assertEquals(60_000L, MarketUniverse.intervalsMillis["M1"])
        assertEquals(86_400_000L, MarketUniverse.intervalsMillis["D1"])
    }

    @Test
    fun selectorNeverPromotesCandidatesBelowEightOutOfTenOrBelowMinimumRr() {
        val lowScore = setup("EURUSD", 79, 2.0)
        val lowRr = setup("GBPUSD", 95, 1.4)
        assertTrue(BestTradeSelector.rank(listOf(lowScore, lowRr)).isEmpty())
        assertNull(BestTradeSelector.bestOrNull(listOf(lowScore, lowRr)))
    }

    @Test
    fun selectorReturnsOnlySingleHighestRuleScoreCandidateFirst() {
        val first = setup("EURUSD", 84, 2.0)
        val second = setup("GBPUSD", 91, 1.8)
        val ranked = BestTradeSelector.rank(listOf(first, second))
        assertEquals(2, ranked.size)
        assertEquals("GBPUSD", ranked.first().setup.symbol)
        assertEquals(9.1, ranked.first().ratingOutOfTen, 0.0001)
    }

    private fun setup(symbol: String, score: Int, rr: Double) = TradeSetup(
        symbol = symbol,
        timeframe = "M5",
        direction = Direction.LONG,
        entry = 1.0,
        stopLoss = 0.99,
        takeProfit = 1.0 + 0.01 * rr,
        riskReward = rr,
        score = score,
        reasons = emptyList(),
        signalCandleTime = 1L
    )
}
