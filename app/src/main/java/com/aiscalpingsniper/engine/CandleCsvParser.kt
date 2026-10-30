package com.aiscalpingsniper.engine

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Strict importer for common OHLC CSV exports. Supported columns:
 * timestamp,open,high,low,close[,volume] or date,time,open,high,low,close[,volume].
 * Timestamps without an offset are interpreted as UTC, never device-local time.
 */
object CandleCsvParser {
    fun parse(csv: String): List<Candle> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        require(lines.size >= 2) { "CSV must contain a header and at least one data row." }
        val delimiter = listOf(',', ';', '\t').maxByOrNull { lines.first().count { ch -> ch == it } }!!
        val header = split(lines.first(), delimiter).map { it.trim().lowercase().replace("\"", "") }
        fun index(vararg names: String): Int? = header.indexOfFirst { it in names }.takeIf { it >= 0 }
        val timestampIndex = index("timestamp", "time", "datetime", "date_time", "date")
            ?: throw IllegalArgumentException("CSV is missing timestamp/date column.")
        val dateIndex = index("date")
        val timeIndex = index("time").takeIf { dateIndex != null && it != dateIndex }
        val openIndex = index("open") ?: throw IllegalArgumentException("CSV is missing open column.")
        val highIndex = index("high") ?: throw IllegalArgumentException("CSV is missing high column.")
        val lowIndex = index("low") ?: throw IllegalArgumentException("CSV is missing low column.")
        val closeIndex = index("close") ?: throw IllegalArgumentException("CSV is missing close column.")
        val volumeIndex = index("volume", "tick_volume", "vol")
        val requiredIndex = listOf(timestampIndex, openIndex, highIndex, lowIndex, closeIndex, volumeIndex ?: 0).maxOrNull()!!
        val candles = lines.drop(1).mapIndexed { row, line ->
            val cells = split(line, delimiter)
            require(cells.size > requiredIndex) { "CSV row ${row + 2} has too few columns." }
            val timestampText = if (timeIndex != null) "${cells[dateIndex!!].trim()} ${cells[timeIndex].trim()}" else cells[timestampIndex].trim()
            fun number(i: Int, field: String): Double = cells[i].trim().replace("\"", "").toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid $field on CSV row ${row + 2}.")
            Candle(
                timestamp = parseTimestamp(timestampText, row + 2),
                open = number(openIndex, "open"),
                high = number(highIndex, "high"),
                low = number(lowIndex, "low"),
                close = number(closeIndex, "close"),
                volume = volumeIndex?.let { number(it, "volume") } ?: 0.0
            )
        }
        require(candles.zipWithNext().all { it.first.timestamp < it.second.timestamp }) {
            "CSV candles must be strictly chronological and contain no duplicate timestamps."
        }
        return candles
    }

    private fun parseTimestamp(value: String, row: Int): Long {
        value.toLongOrNull()?.let { raw ->
            return if (raw < 100_000_000_000L) raw * 1000L else raw
        }
        try { return Instant.parse(value).toEpochMilli() } catch (_: Exception) { }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
            "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd HH:mm",
            "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm"
        )
        for (pattern in patterns) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern))
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) { }
        }
        throw IllegalArgumentException("Unsupported timestamp '$value' on CSV row $row; use ISO-8601, UTC date/time, or epoch seconds/milliseconds.")
    }

    private fun split(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> { result += field.toString(); field.setLength(0) }
                else -> field.append(ch)
            }
            i++
        }
        require(!quoted) { "CSV contains an unterminated quoted field." }
        result += field.toString()
        return result
    }
}
