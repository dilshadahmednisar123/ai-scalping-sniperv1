package com.aiscalpingsniper.engine

import org.junit.Assert.*
import org.junit.Test

class MarketOpportunityEngineTest {
    @Test fun fullUniverseContainsRequestedPairTimeframeMatrix() {
        assertEquals(29, MarketUniverse.forexPairs.size)
        assertEquals(7, MarketUniverse.timeframes.size)
        assertEquals(203, MarketUniverse.watchlist().size)
        assertEquals(203, MarketUniverse.requestedInstrumentCount)
    }

    @Test fun incompleteTimeframesNeverProduceBestTrade() {
        val report = MarketOpportunityEngine().analyze(
            listOf("EURUSD", "GBPUSD"),
            mapOf("EURUSD" to mapOf("M5" to candles(100, 1.1)))
        )
        assertEquals(2, report.analyzedPairs)
        assertNull(report.best)
        assertEquals("NO TRADE - WAIT", report.decision)
        assertTrue(report.ranked.all { it.rating == null })
    }

    @Test fun selectorRejectsScoresBelowEightAndRiskRewardBelowMinimum() {
        val valid = setup("EURUSD", 85, 2.0)
        val weakScore = setup("GBPUSD", 79, 3.0)
        val weakRr = setup("USDJPY", 95, 1.4)
        val ranked = BestTradeSelector.rank(listOf(valid, weakScore, weakRr))
        assertEquals(1, ranked.size)
        assertEquals("EURUSD", ranked.first().setup.symbol)
        assertEquals(8.5, ranked.first().ratingOutOfTen, 0.0)
    }

    private fun setup(symbol: String, score: Int, rr: Double) = TradeSetup(
        symbol, "M5", Direction.LONG, 1.1, 1.09, 1.1 + 0.01 * rr,
        rr, score, listOf("test fixture"), 1_700_000_000_000L
    )

    private fun candles(count: Int, base: Double): List<Candle> =
        (0 until count).map { i ->
            val close = base + i * 0.0001
            Candle(i * 300_000L, close - 0.00005, close + 0.0001, close - 0.0001, close)
        }
}
