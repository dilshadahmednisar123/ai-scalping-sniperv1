package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerEngineTest {
    @Test
    fun rejectsInsufficientHistory() {
        val candles = (0 until 10).map {
            Candle(it.toLong(), 100.0, 101.0, 99.0, 100.5)
        }
        val result = ScannerEngine().scan("EURUSD", "M5", candles)
        assertEquals(SetupStatus.NONE, result.status)
        assertTrue(result.message.contains("Need at least"))
    }

    @Test
    fun rejectsNonChronologicalCandles() {
        val candles = (0 until 80).map { index ->
            val time = if (index == 79) 78L else index.toLong()
            Candle(time, 100.0, 101.0, 99.0, 100.5)
        }
        val result = ScannerEngine().scan("EURUSD", "M5", candles)
        assertEquals(SetupStatus.NONE, result.status)
        assertTrue(result.message.contains("chronological"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidOhlcRange() {
        Candle(1L, 100.0, 99.0, 98.0, 100.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFinitePrices() {
        Candle(1L, 100.0, Double.NaN, 98.0, 99.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeVolume() {
        Candle(1L, 100.0, 101.0, 99.0, 100.5, -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInfiniteRewardRisk() {
        ScannerConfig(rewardRisk = Double.POSITIVE_INFINITY)
    }
}
