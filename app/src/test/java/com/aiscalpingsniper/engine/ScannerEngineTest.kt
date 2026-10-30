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

    @Test
    fun confirmedSetupIncludesExplicitSmcEvidenceAndRuleScore() {
        val candles = (0 until 61).map { i ->
            val base = 100.0 + i * 0.08
            when (i) {
                55 -> Candle(i.toLong(), base + 0.1, base + 0.2, 100.0, base + 0.15)
                56 -> Candle(i.toLong(), base + 0.15, base + 0.35, base + 0.10, base + 0.30)
                57 -> Candle(i.toLong(), base + 0.30, base + 0.50, base + 0.25, base + 0.45)
                58 -> Candle(i.toLong(), base + 0.45, base + 0.60, base + 0.40, base + 0.50)
                59 -> Candle(i.toLong(), base + 0.50, base + 0.70, base + 0.40, base + 0.60)
                // Wick below the confirmed 100.0 swing low, then close back above it.
                60 -> Candle(i.toLong(), base + 0.50, base + 1.20, 99.99, base + 1.10)
                else -> Candle(i.toLong(), base, base + 0.18, base - 0.18, base + 0.08)
            }
        }
        val result = ScannerEngine().scan("EURUSD", "M5", candles)
        assertTrue("Expected a setup candidate, got: ${result.message}", result.setup != null)
        val reasons = result.setup!!.reasons
        assertTrue(reasons.contains("SMC: aligned liquidity sweep confirmed"))
        assertTrue(reasons.contains("SMC: no aligned BOS on signal candle"))
        assertTrue(result.setup!!.score in 0..100)
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
