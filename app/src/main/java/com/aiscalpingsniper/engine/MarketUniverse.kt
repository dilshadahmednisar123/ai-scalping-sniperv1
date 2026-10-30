package com.aiscalpingsniper.engine

/**
 * Scope requested for the cross-market research scanner.
 *
 * Listing a symbol here does not imply that a provider supports it or that
 * its feed is healthy. Each instrument must pass identity, freshness,
 * chronology, spread and closed-candle validation before it is rankable.
 */
object MarketUniverse {
    val forexPairs: List<String> = listOf(
        "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "USDCAD", "AUDUSD", "NZDUSD",
        "EURJPY", "GBPJPY", "AUDJPY", "CADJPY", "CHFJPY", "NZDJPY",
        "EURAUD", "EURNZD", "EURGBP", "EURCHF", "EURCAD",
        "GBPAUD", "GBPNZD", "GBPCAD", "GBPCHF",
        "AUDNZD", "AUDCAD", "AUDCHF", "NZDCAD", "NZDCHF", "CADCHF",
        "XAUUSD"
    )

    /** Intraday through daily timeframes requested in the product specification. */
    val timeframes: List<String> = listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1")

    val intervalsMillis: Map<String, Long> = mapOf(
        "M1" to 60_000L,
        "M5" to 300_000L,
        "M15" to 900_000L,
        "M30" to 1_800_000L,
        "H1" to 3_600_000L,
        "H4" to 14_400_000L,
        "D1" to 86_400_000L
    )

    val requestedInstrumentCount: Int get() = forexPairs.size * timeframes.size

    fun watchlist(): List<WatchlistInstrument> = forexPairs.flatMap { symbol ->
        timeframes.map { timeframe ->
            WatchlistInstrument(symbol, timeframe, intervalsMillis.getValue(timeframe))
        }
    }
}

/**
 * Cross-pair ordering helper. It ranks only confirmed, feed-validated
 * candidates. The scanner's 0..100 rule score is NOT a win probability.
 * A rating below 8.0/10 is never promoted as the best trade.
 */
object BestTradeSelector {
    data class RankedCandidate(
        val setup: TradeSetup,
        val ratingOutOfTen: Double
    )

    fun rank(confirmed: List<TradeSetup>): List<RankedCandidate> =
        confirmed.asSequence()
            .filter { it.score >= 80 }
            .filter { it.riskReward >= 1.5 && it.riskReward.isFinite() }
            .map { RankedCandidate(it, it.score / 10.0) }
            .sortedWith(
                compareByDescending<RankedCandidate> { it.ratingOutOfTen }
                    .thenByDescending { it.setup.riskReward }
                    .thenBy { it.setup.symbol }
            )
            .toList()

    fun bestOrNull(confirmed: List<TradeSetup>): RankedCandidate? = rank(confirmed).firstOrNull()
}
