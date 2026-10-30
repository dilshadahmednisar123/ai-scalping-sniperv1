package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestEngineTest {
    @Test
    fun emptyHistoryProducesZeroedMetrics() {
        val report = BacktestEngine().run("EURUSD", "M5", emptyList())
        assertTrue(report.trades.isEmpty())
        assertEquals(0.0, report.expectancyR, 0.0)
        assertEquals(0.0, report.winRatePercent, 0.0)
    }

    @Test
    fun rejectsOutOfOrderHistory() {
        val candles = listOf(
            Candle(2L, 1.0, 1.1, 0.9, 1.0),
            Candle(1L, 1.0, 1.1, 0.9, 1.0)
        )
        try {
            BacktestEngine().run("EURUSD", "M5", candles)
            throw AssertionError("Expected chronological validation to fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("chronological"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeExecutionCost() {
        BacktestConfig(slippagePipsPerSide = -0.0001)
    }

    @Test
    fun standardFrictionPresetsAreAppliedInPipUnits() {
        val config = BacktestConfig()
        assertEquals(0.00006, config.costPerSide("EURUSD"), 1e-10)
        assertEquals(0.006, config.costPerSide("USDJPY"), 1e-10)
        assertEquals(0.06, config.costPerSide("XAUUSD"), 1e-10)
    }

    @Test
    fun emptyBacktestDoesNotClaimAnyPerformance() {
        val candles = (0 until 20).map { index ->
            Candle(index.toLong(), 1.0, 1.001, 0.999, 1.0)
        }
        val report = BacktestEngine().run("EURUSD", "M5", candles)
        assertEquals(0, report.trades.size)
        assertEquals(0.0, report.winRatePercent, 0.0)
        assertEquals(0.0, report.profitFactor, 0.0)
    }
}
