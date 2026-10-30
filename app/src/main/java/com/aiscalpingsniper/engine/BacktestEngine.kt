package com.aiscalpingsniper.engine

import kotlin.math.abs
import kotlin.math.max

data class BacktestConfig(
    val maxBarsInTrade: Int = 48,
    /** Estimated adverse price movement on each side of a round trip. */
    val executionCostPerSide: Double = 0.0
) {
    init {
        require(maxBarsInTrade >= 1)
        require(executionCostPerSide.isFinite() && executionCostPerSide >= 0.0)
    }
}

enum class ExitReason { STOP_LOSS, TAKE_PROFIT, TIME_EXIT }

data class BacktestTrade(
    val setup: TradeSetup,
    val entryFill: Double,
    val exitFill: Double,
    val exitTime: Long,
    val exitReason: ExitReason,
    val resultR: Double
)

data class BacktestReport(
    val symbol: String,
    val timeframe: String,
    val trades: List<BacktestTrade>,
    val skippedSignals: Int,
    val winRatePercent: Double,
    val expectancyR: Double,
    val profitFactor: Double,
    val maxDrawdownR: Double
)

/**
 * Walk-forward, closed-bar backtest. A signal is generated from data ending at
 * bar N and can only enter after that bar. If stop and target are both touched
 * in one later candle, stop is assumed first (conservative ambiguity handling).
 */
class BacktestEngine(
    private val scanner: ScannerEngine = ScannerEngine(),
    private val config: BacktestConfig = BacktestConfig()
) {
    fun run(symbol: String, timeframe: String, candles: List<Candle>): BacktestReport {
        require(symbol.isNotBlank() && timeframe.isNotBlank())
        require(candles.zipWithNext().none { it.first.timestamp >= it.second.timestamp }) {
            "Candles must be strictly chronological."
        }

        val trades = mutableListOf<BacktestTrade>()
        var skipped = 0
        var index = 0
        while (index < candles.size) {
            val history = candles.subList(0, index + 1)
            val result = scanner.scan(symbol, timeframe, history)
            val setup = result.setup?.takeIf { result.status == SetupStatus.CONFIRMED }
            if (setup == null) {
                index++
                continue
            }

            val entryIndex = index + 1
            if (entryIndex >= candles.size) {
                skipped++
                break
            }
            val entryBar = candles[entryIndex]
            val entryFill = if (setup.direction == Direction.LONG) {
                setup.entry + config.executionCostPerSide
            } else {
                setup.entry - config.executionCostPerSide
            }
            val exit = resolveExit(setup, entryBar, candles, entryIndex, config)
            if (exit == null) {
                skipped++
                index++
                continue
            }
            val rawRisk = abs(setup.entry - setup.stopLoss)
            if (rawRisk <= 0.0 || !rawRisk.isFinite()) {
                skipped++
                index = exit.index + 1
                continue
            }
            val netMove = if (setup.direction == Direction.LONG) {
                exit.fill - entryFill
            } else {
                entryFill - exit.fill
            }
            trades += BacktestTrade(
                setup = setup,
                entryFill = entryFill,
                exitFill = exit.fill,
                exitTime = candles[exit.index].timestamp,
                exitReason = exit.reason,
                resultR = netMove / rawRisk
            )
            // One position at a time; do not overlap trades or reuse bars.
            index = exit.index + 1
        }

        val results = trades.map { it.resultR }
        val wins = results.count { it > 0.0 }
        val grossWins = results.filter { it > 0.0 }.sum()
        val grossLosses = -results.filter { it < 0.0 }.sum()
        val equity = results.runningFold(0.0) { total, r -> total + r }
        var peak = 0.0
        var maxDrawdown = 0.0
        equity.forEach { value ->
            peak = max(peak, value)
            maxDrawdown = max(maxDrawdown, peak - value)
        }
        return BacktestReport(
            symbol = symbol,
            timeframe = timeframe,
            trades = trades,
            skippedSignals = skipped,
            winRatePercent = if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size,
            expectancyR = if (results.isEmpty()) 0.0 else results.average(),
            profitFactor = when {
                grossLosses > 0.0 -> grossWins / grossLosses
                grossWins > 0.0 -> Double.POSITIVE_INFINITY
                else -> 0.0
            },
            maxDrawdownR = maxDrawdown
        )
    }

    private data class Exit(val index: Int, val fill: Double, val reason: ExitReason)

    private fun resolveExit(
        setup: TradeSetup,
        entryBar: Candle,
        candles: List<Candle>,
        entryIndex: Int,
        config: BacktestConfig
    ): Exit? {
        val lastIndex = minOf(candles.lastIndex, entryIndex + config.maxBarsInTrade - 1)
        for (i in entryIndex..lastIndex) {
            val bar = candles[i]
            val stopHit: Boolean
            val targetHit: Boolean
            if (setup.direction == Direction.LONG) {
                stopHit = bar.low <= setup.stopLoss
                targetHit = bar.high >= setup.takeProfit
            } else {
                stopHit = bar.high >= setup.stopLoss
                targetHit = bar.low <= setup.takeProfit
            }
            // Conservative: if both levels are touched in the same OHLC bar,
            // assume the stop was reached first because intrabar order is unknown.
            if (stopHit) {
                val rawExit = if (setup.direction == Direction.LONG) {
                    minOf(setup.stopLoss, bar.open)
                } else {
                    maxOf(setup.stopLoss, bar.open)
                }
                val fill = if (setup.direction == Direction.LONG) {
                    rawExit - config.executionCostPerSide
                } else {
                    rawExit + config.executionCostPerSide
                }
                return Exit(i, fill, ExitReason.STOP_LOSS)
            }
            if (targetHit) {
                val rawExit = if (setup.direction == Direction.LONG) {
                    maxOf(setup.takeProfit, bar.open)
                } else {
                    minOf(setup.takeProfit, bar.open)
                }
                val fill = if (setup.direction == Direction.LONG) {
                    rawExit - config.executionCostPerSide
                } else {
                    rawExit + config.executionCostPerSide
                }
                return Exit(i, fill, ExitReason.TAKE_PROFIT)
            }
        }

        val bar = candles[lastIndex]
        val rawExit = bar.close
        val fill = if (setup.direction == Direction.LONG) {
            rawExit - config.executionCostPerSide
        } else {
            rawExit + config.executionCostPerSide
        }
        return Exit(lastIndex, fill, ExitReason.TIME_EXIT)
    }
}
