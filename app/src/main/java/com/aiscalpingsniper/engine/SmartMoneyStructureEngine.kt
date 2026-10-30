package com.aiscalpingsniper.engine

import kotlin.math.max

data class PriceZone(val low: Double, val high: Double, val formedAt: Long) {
    init { require(low.isFinite() && high.isFinite() && low <= high) }
}

data class SmartMoneySnapshot(
    val lastConfirmedSwingHigh: Double?,
    val lastConfirmedSwingLow: Double?,
    val bullishBreakOfStructure: Boolean,
    val bearishBreakOfStructure: Boolean,
    val bullishLiquiditySweep: Boolean,
    val bearishLiquiditySweep: Boolean,
    val bullishFairValueGap: PriceZone?,
    val bearishFairValueGap: PriceZone?,
    val bullishOrderBlock: PriceZone?,
    val bearishOrderBlock: PriceZone?
)

data class SmartMoneyConfig(
    val pivotLeft: Int = 2,
    val pivotRight: Int = 2,
    val lookback: Int = 100
) {
    init { require(pivotLeft >= 1 && pivotRight >= 1 && lookback >= 10) }
}

/**
 * Deterministic SMC-style context using confirmed pivots and closed candles.
 * BOS is a close beyond the latest confirmed swing. A sweep is a wick beyond
 * that swing followed by a close back inside. FVG and OB definitions are
 * explicit mechanical approximations, not subjective institutional footprints.
 */
class SmartMoneyStructureEngine(private val config: SmartMoneyConfig = SmartMoneyConfig()) {
    fun analyze(candles: List<Candle>): SmartMoneySnapshot {
        require(candles.zipWithNext().none { it.first.timestamp >= it.second.timestamp }) {
            "Candles must be strictly chronological."
        }
        if (candles.size < config.pivotLeft + config.pivotRight + 3) {
            return SmartMoneySnapshot(null, null, false, false, false, false, null, null, null, null)
        }
        val bars = candles.takeLast(config.lookback)
        val last = bars.last()
        val pivotHighs = mutableListOf<Pair<Int, Double>>()
        val pivotLows = mutableListOf<Pair<Int, Double>>()
        val lastPivotIndex = bars.lastIndex - config.pivotRight
        for (i in config.pivotLeft..lastPivotIndex) {
            val high = bars[i].high
            val low = bars[i].low
            val isHigh = (i - config.pivotLeft until i).all { high > bars[it].high } &&
                (i + 1..i + config.pivotRight).all { high >= bars[it].high }
            val isLow = (i - config.pivotLeft until i).all { low < bars[it].low } &&
                (i + 1..i + config.pivotRight).all { low <= bars[it].low }
            if (isHigh) pivotHighs += i to high
            if (isLow) pivotLows += i to low
        }
        val swingHigh = pivotHighs.lastOrNull()?.second
        val swingLow = pivotLows.lastOrNull()?.second
        val bullishBos = swingHigh != null && last.close > swingHigh
        val bearishBos = swingLow != null && last.close < swingLow
        val bullishSweep = swingLow != null && last.low < swingLow && last.close > swingLow
        val bearishSweep = swingHigh != null && last.high > swingHigh && last.close < swingHigh

        var bullishFvg: PriceZone? = null
        var bearishFvg: PriceZone? = null
        for (i in 2 until bars.size) {
            val first = bars[i - 2]
            val third = bars[i]
            if (third.low > first.high) {
                val zone = PriceZone(first.high, third.low, third.timestamp)
                val mitigated = bars.drop(i + 1).any { it.low <= zone.high }
                if (!mitigated) bullishFvg = zone
            }
            if (third.high < first.low) {
                val zone = PriceZone(third.high, first.low, third.timestamp)
                val mitigated = bars.drop(i + 1).any { it.high >= zone.low }
                if (!mitigated) bearishFvg = zone
            }
        }

        val bullishOrderBlock = if (bullishBos) {
            val breakIndex = bars.lastIndex
            (breakIndex - 1 downTo max(0, breakIndex - 10))
                .firstOrNull { bars[it].close < bars[it].open }
                ?.let { PriceZone(bars[it].low, bars[it].high, bars[it].timestamp) }
        } else null
        val bearishOrderBlock = if (bearishBos) {
            val breakIndex = bars.lastIndex
            (breakIndex - 1 downTo max(0, breakIndex - 10))
                .firstOrNull { bars[it].close > bars[it].open }
                ?.let { PriceZone(bars[it].low, bars[it].high, bars[it].timestamp) }
        } else null

        return SmartMoneySnapshot(
            lastConfirmedSwingHigh = swingHigh,
            lastConfirmedSwingLow = swingLow,
            bullishBreakOfStructure = bullishBos,
            bearishBreakOfStructure = bearishBos,
            bullishLiquiditySweep = bullishSweep,
            bearishLiquiditySweep = bearishSweep,
            bullishFairValueGap = bullishFvg,
            bearishFairValueGap = bearishFvg,
            bullishOrderBlock = bullishOrderBlock,
            bearishOrderBlock = bearishOrderBlock
        )
    }
}