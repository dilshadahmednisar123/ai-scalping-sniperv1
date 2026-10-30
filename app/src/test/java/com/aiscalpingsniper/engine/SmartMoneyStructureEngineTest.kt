package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMoneyStructureEngineTest {
    @Test
    fun doesNotReportBreakBeforePivotIsConfirmed() {
        val bars = listOf(
            Candle(1, 1.0, 1.2, 0.9, 1.0),
            Candle(2, 1.0, 1.1, 0.95, 1.0),
            Candle(3, 1.0, 1.3, 0.95, 1.1),
            Candle(4, 1.1, 1.15, 1.0, 1.1)
        )
        val result = SmartMoneyStructureEngine(SmartMoneyConfig(1, 2, 20)).analyze(bars)
        assertFalse(result.bullishBreakOfStructure)
        assertNull(result.lastConfirmedSwingHigh)
    }

    @Test
    fun flagsBullishLiquiditySweepOfConfirmedSwingLow() {
        val bars = listOf(
            Candle(1, 1.1, 1.2, 1.0, 1.1),
            Candle(2, 1.05, 1.1, 0.95, 1.0),
            Candle(3, 1.0, 1.08, 0.9, 0.98),
            Candle(4, 0.98, 1.05, 0.94, 1.0),
            Candle(5, 1.0, 1.06, 0.97, 1.02),
            Candle(6, 1.02, 1.04, 0.88, 0.99)
        )
        val result = SmartMoneyStructureEngine(SmartMoneyConfig(1, 2, 20)).analyze(bars)
        assertTrue(result.bullishLiquiditySweep)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfOrderCandles() {
        SmartMoneyStructureEngine().analyze(listOf(
            Candle(2, 1.0, 1.1, 0.9, 1.0),
            Candle(1, 1.0, 1.1, 0.9, 1.0)
        ))
    }

    @Test
    fun validatesZoneBounds() {
        val zone = PriceZone(1.0, 1.1, 10)
        assertEquals(1.0, zone.low, 0.0)
        assertEquals(1.1, zone.high, 0.0)
    }
}