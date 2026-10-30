package com.aiscalpingsniper.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic, closed-candle scanner core.
 *
 * Pivots are confirmed only after [pivotRight] later candles have closed.
 * The engine never uses the currently-forming candle, so historical signals
 * do not move after confirmation. This is a signal candidate engine, not an
 * execution system or a guarantee of profitability.
 */
data class Candle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
) {
    init {
        require(high >= max(open, close) && low <= min(open, close))
        require(high >= low)
    }
}

enum class Direction { LONG, SHORT }
enum class SetupStatus { WATCH, CONFIRMED, NONE }

data class TradeSetup(
    val symbol: String,
    val timeframe: String,
    val direction: Direction,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val riskReward: Double,
    val score: Int,
    val reasons: List<String>,
    val signalCandleTime: Long
)

data class ScanResult(
    val status: SetupStatus,
    val setup: TradeSetup? = null,
    val message: String
)

data class ScannerConfig(
    val emaPeriod: Int = 50,
    val atrPeriod: Int = 14,
    val pivotLeft: Int = 3,
    val pivotRight: Int = 3,
    val zoneAtrFraction: Double = 0.20,
    val stopAtrBuffer: Double = 0.25,
    val rewardRisk: Double = 2.0,
    val minimumScore: Int = 75
) {
    init {
        require(emaPeriod >= 2 && atrPeriod >= 2)
        require(pivotLeft >= 1 && pivotRight >= 1)
        require(zoneAtrFraction > 0 && stopAtrBuffer >= 0)
        require(rewardRisk > 0 && minimumScore in 1..100)
    }
}

class ScannerEngine(private val config: ScannerConfig = ScannerConfig()) {
    fun scan(symbol: String, timeframe: String, candles: List<Candle>): ScanResult {
        val required = max(config.emaPeriod, config.atrPeriod) + config.pivotLeft + config.pivotRight + 5
        if (candles.size < required) {
            return ScanResult(SetupStatus.NONE, message = "Need at least $required closed candles; received ${candles.size}.")
        }
        if (candles.zipWithNext().any { it.first.timestamp >= it.second.timestamp }) {
            return ScanResult(SetupStatus.NONE, message = "Candles must be strictly chronological.")
        }

        // Exclude the last candle: callers may include a still-forming candle.
        val bars = candles.dropLast(1)
        if (bars.size < required - 1) {
            return ScanResult(SetupStatus.NONE, message = "Not enough confirmed candles.")
        }

        val closes = bars.map { it.close }
        val ema = ema(closes, config.emaPeriod)
        val atr = atr(bars, config.atrPeriod)
        if (ema == null || atr == null || atr <= 0.0) {
            return ScanResult(SetupStatus.NONE, message = "Indicators are not ready.")
        }

        val last = bars.last()
        val prior = bars[bars.lastIndex - 1]
        val support = confirmedPivotLow(bars)
        val resistance = confirmedPivotHigh(bars)
        val nearSupport = support != null && abs(last.low - support) <= atr * config.zoneAtrFraction
        val nearResistance = resistance != null && abs(last.high - resistance) <= atr * config.zoneAtrFraction
        val bullishTrend = last.close > ema
        val bearishTrend = last.close < ema
        val bullishRejection = last.close > last.open && last.close > prior.high
        val bearishRejection = last.close < last.open && last.close < prior.low

        val longCandidate = bullishTrend && nearSupport && bullishRejection
        val shortCandidate = bearishTrend && nearResistance && bearishRejection
        if (!longCandidate && !shortCandidate) {
            return ScanResult(SetupStatus.WATCH, message = "No confirmed setup. Waiting for trend, level retest and candle confirmation.")
        }

        val direction = if (longCandidate) Direction.LONG else Direction.SHORT
        val level = if (direction == Direction.LONG) support!! else resistance!!
        val entry = last.close
        val structuralStop = if (direction == Direction.LONG) {
            min(last.low, level) - atr * config.stopAtrBuffer
        } else {
            max(last.high, level) + atr * config.stopAtrBuffer
        }
        val risk = abs(entry - structuralStop)
        if (!risk.isFinite() || risk <= 0.0) {
            return ScanResult(SetupStatus.NONE, message = "Invalid risk distance; setup rejected.")
        }
        val target = if (direction == Direction.LONG) entry + risk * config.rewardRisk else entry - risk * config.rewardRisk
        // A transparent rule score, not a win probability. Each component
        // varies with the actual setup quality rather than being always maxed.
        val candleRange = max(last.high - last.low, 1e-12)
        val bodyRatio = abs(last.close - last.open) / candleRange
        val levelDistance = if (direction == Direction.LONG) {
            abs(last.low - level)
        } else {
            abs(last.high - level)
        }
        val proximityScore = (20.0 * (1.0 - levelDistance / (atr * config.zoneAtrFraction)))
            .coerceIn(0.0, 20.0).toInt()
        val trendDistance = abs(last.close - ema) / atr
        val trendScore = (15.0 * (trendDistance / 1.5)).coerceIn(0.0, 15.0).toInt()
        val candleScore = (20.0 * bodyRatio).coerceIn(0.0, 20.0).toInt()
        val score = (45 + proximityScore + trendScore + candleScore).coerceIn(0, 100)
        val reasons = listOf(
            if (direction == Direction.LONG) "Price is above EMA trend filter" else "Price is below EMA trend filter",
            if (direction == Direction.LONG) "Retest of confirmed support" else "Retest of confirmed resistance",
            if (direction == Direction.LONG) "Bullish close above prior high" else "Bearish close below prior low",
            "Signal uses closed candles only"
        )
        val setup = TradeSetup(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            entry = entry,
            stopLoss = structuralStop,
            takeProfit = target,
            riskReward = config.rewardRisk,
            score = score,
            reasons = reasons,
            signalCandleTime = last.timestamp
        )
        return if (score >= config.minimumScore) {
            ScanResult(SetupStatus.CONFIRMED, setup, "Rule-based setup confirmed. Validate spread, feed quality and execution before trading.")
        } else {
            ScanResult(SetupStatus.WATCH, setup, "Candidate below minimum score; not a confirmed signal.")
        }
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1)
        var value = values.take(period).average()
        for (price in values.drop(period)) value = price * alpha + value * (1.0 - alpha)
        return value
    }

    private fun atr(bars: List<Candle>, period: Int): Double? {
        if (bars.size <= period) return null
        val ranges = bars.zipWithNext().map { (previous, current) ->
            max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
        }
        return ranges.takeLast(period).average()
    }

    // Return only a pivot whose right-side confirmation candles have closed.
    private fun confirmedPivotLow(bars: List<Candle>): Double? {
        val end = bars.lastIndex - config.pivotRight
        if (end < config.pivotLeft) return null
        for (i in end downTo config.pivotLeft) {
            val candidate = bars[i].low
            val left = (i - config.pivotLeft until i).all { candidate < bars[it].low }
            val right = (i + 1..i + config.pivotRight).all { candidate <= bars[it].low }
            if (left && right) return candidate
        }
        return null
    }

    private fun confirmedPivotHigh(bars: List<Candle>): Double? {
        val end = bars.lastIndex - config.pivotRight
        if (end < config.pivotLeft) return null
        for (i in end downTo config.pivotLeft) {
            val candidate = bars[i].high
            val left = (i - config.pivotLeft until i).all { candidate > bars[it].high }
            val right = (i + 1..i + config.pivotRight).all { candidate >= bars[it].high }
            if (left && right) return candidate
        }
        return null
    }
}
