package com.aiscalpingsniper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleCsvParserTest {
    @Test
    fun parsesHeaderCsvAndUtcDateTime() {
        val candles = CandleCsvParser.parse(
            "date,time,open,high,low,close,volume\n" +
            "2026-10-30,10:00:00,1.1,1.2,1.0,1.15,42\n" +
            "2026-10-30,10:05:00,1.15,1.25,1.1,1.2,51"
        )
        assertEquals(2, candles.size)
        assertEquals(42.0, candles[0].volume, 0.0)
        assertEquals(300_000L, candles[1].timestamp - candles[0].timestamp)
    }

    @Test
    fun parsesSemicolonAndEpochSeconds() {
        val candles = CandleCsvParser.parse(
            "timestamp;open;high;low;close\n" +
            "1793354400;1.0;1.2;0.9;1.1\n" +
            "1793354700;1.1;1.3;1.0;1.2"
        )
        assertEquals(2, candles.size)
        assertEquals(0.0, candles[0].volume, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateOrUnorderedTimestamps() {
        CandleCsvParser.parse(
            "timestamp,open,high,low,close\n" +
            "1000,1,2,0.5,1.5\n" +
            "1000,1,2,0.5,1.5"
        )
    }

    @Test
    fun handlesQuotedCommaInUnusedColumn() {
        val candles = CandleCsvParser.parse(
            "timestamp,open,high,low,close,note\n" +
            "1000,1,2,0.5,1.5,\"source, test\""
        )
        assertTrue(candles.isNotEmpty())
    }
}
