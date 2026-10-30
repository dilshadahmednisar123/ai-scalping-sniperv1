package com.aiscalpingsniper.engine

import kotlin.math.abs

/**
 * Cross-pair, multi-timeframe opportunity selector.
 *
 * Inputs must already have passed MarketDataQuality validation. Missing or
 * stale frames are never inferred. The 0..100 rating is a rule score, not
 * a statistical win probability. News filtering remains an explicit external
 * prerequisite and is not silently treated as passed here.
 */
data class PairOpportunity(
    val symbol: String,
    val rating: Double?,
    val direction: Direction?,
    val setup: TradeSetup?,
    val timeframeBias: Map<String, Direction?>,
    val blockers: List<String>,
    val reasons: List<String>
)

data class MarketOpportunityReport(
    val analyzedPairs: Int,
    val completePairs: Int,
    val ranked: List<PairOpportunity>,
    val best: PairOpportunity?,
    val decision: String
)

class MarketOpportunityEngine(
    private val scanner: ScannerEngine = ScannerEngine()
) {
    private val requiredFrames = listOf("D1", "H4", "H1", "M15", "M5")

    fun analyze(
        symbols: List<String>,
        candlesBySymbolAndFrame: Map<String, Map<String, List<Candle>>>
    ): MarketOpportunityReport {
        require(symbols.distinct().size == symbols.size) { "Duplicate symbols are not allowed." }
        val results = symbols.map { symbol ->
            analyzePair(symbol, candlesBySymbolAndFrame.entries
                .firstOrNull { it.key.equals(symbol, true) }?.value.orEmpty())
        }
        val complete = results.count { it.blockers.none { b -> b.startsWith("MISSING:") || b.startsWith("INSUFFICIENT:") } }
        val ranked = results.filter { it.rating != null && it.rating >= 8.0 && it.setup != null }
            .sortedWith(compareByDescending<PairOpportunity> { it.rating }
                .thenByDescending { it.setup!!.riskReward }
                .thenBy { it.symbol })
        val best = ranked.firstOrNull()
        return MarketOpportunityReport(
            analyzedPairs = symbols.size,
            completePairs = complete,
            ranked = results.sortedWith(compareByDescending<PairOpportunity> { it.rating ?: -1.0 }.thenBy { it.symbol }),
            best = best,
            decision = if (best == null) "NO TRADE - WAIT" else "ONE BEST CANDIDATE - NEWS AND EXECUTION CHECK REQUIRED"
        )
    }

    private fun analyzePair(symbol: String, input: Map<String, List<Candle>>): PairOpportunity {
        val frames = input.entries.associate { it.key.uppercase() to it.value }
        val blockers = mutableListOf<String>()
        val bias = linkedMapOf<String, Direction?>()
        requiredFrames.forEach { frame ->
            val bars = frames[frame]
            if (bars == null) {
                blockers += "MISSING: $frame data"
                bias[frame] = null
            } else if (bars.size < 60 || bars.zipWithNext().any { it.first.timestamp >= it.second.timestamp }) {
                blockers += "INSUFFICIENT: $frame needs 60+ chronological closed candles"
                bias[frame] = null
            } else {
                bias[frame] = emaBias(bars, 50)
            }
        }
        if (blockers.isNotEmpty()) return PairOpportunity(symbol, null, null, null, bias, blockers,
            listOf("Pair is not rankable because required timeframe data is incomplete."))
        val daily = bias["D1"]
        val h4 = bias["H4"]
        val h1 = bias["H1"]
        val m15 = bias["M15"]
        val m5 = bias["M5"]
        if (listOf(daily, h4, h1, m15, m5).any { it == null }) {
            return PairOpportunity(symbol, null, null, null, bias,
                listOf("One or more timeframe EMA biases are neutral."),
                listOf("No directional consensus; no trade."))
        }
        val setup15 = scanner.scan(symbol, "M15", frames.getValue("M15"))
        val setup5 = scanner.scan(symbol, "M5", frames.getValue("M5"))
        val candidate = setup5.setup?.takeIf { setup5.status == SetupStatus.CONFIRMED }
        val blockersForSetup = mutableListOf<String>()
        if (candidate == null) blockersForSetup += "No confirmed M5 support/resistance setup."
        if (setup15.status != SetupStatus.CONFIRMED || setup15.setup?.direction != candidate?.direction)
            blockersForSetup += "M15 confirmation is absent or disagrees with M5."
        if (setOf(daily, h4, h1).any { it != candidate?.direction })
            blockersForSetup += "D1/4H/1H trend alignment is not unanimous."
        if (candidate != null && candidate.riskReward < 1.5)
            blockersForSetup += "Risk-to-reward is below 1:1.5."
        if (candidate == null || blockersForSetup.isNotEmpty()) {
            return PairOpportunity(symbol, null, candidate?.direction, candidate, bias, blockersForSetup,
                listOf("Setup rejected by multi-timeframe or risk/reward gates."))
        }

        val direction = candidate.direction
        val alignedFrames = listOf(daily, h4, h1, m15, m5).count { it == direction }
        val candle = frames.getValue("M5").last()
        val previous = frames.getValue("M5").dropLast(1).last()
        val body = abs(candle.close - candle.open) / (candle.high - candle.low).coerceAtLeast(1e-12)
        val momentumPoints = if (body >= 0.60 && (if (direction == Direction.LONG) candle.close > previous.high else candle.close < previous.low)) 10 else 0
        val rrPoints = if (candidate.riskReward >= 3.0) 10 else if (candidate.riskReward >= 2.0) 8 else 5
        val score = (candidate.score * 0.60 + alignedFrames * 4.0 + momentumPoints + rrPoints)
            .coerceIn(0.0, 100.0)
        val rating = kotlin.math.round(score) / 10.0
        val reasons = candidate.reasons + listOf(
            "D1, H4, H1, M15 and M5 directional bias aligned",
            if (momentumPoints > 0) "M5 momentum candle body >= 60% with structure break" else "M5 momentum confirmation is limited",
            "Rule rating is not a measured win probability",
            "Economic-calendar/news clearance must be checked before execution"
        )
        return PairOpportunity(symbol, rating, direction, candidate, bias, emptyList(), reasons)
    }

    private fun emaBias(bars: List<Candle>, period: Int): Direction? {
        if (bars.size < period) return null
        val closes = bars.map { it.close }
        val alpha = 2.0 / (period + 1.0)
        var ema = closes.take(period).average()
        closes.drop(period).forEach { ema = it * alpha + ema * (1.0 - alpha) }
        return when {
            closes.last() > ema -> Direction.LONG
            closes.last() < ema -> Direction.SHORT
            else -> null
        }
    }
}
