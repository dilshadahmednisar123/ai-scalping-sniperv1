package com.aiscalpingsniper.engine

/**
 * Adapter contract for a real market-data source. Implementations must return
 * actual provider candles and must not synthesize missing prices.
 */
interface MarketDataProvider {
    fun fetch(symbol: String, timeframe: String, limit: Int): MarketDataBatch
}

data class WatchlistInstrument(
    val symbol: String,
    val timeframe: String,
    val expectedIntervalMillis: Long
) {
    init {
        require(symbol.isNotBlank() && timeframe.isNotBlank())
        require(expectedIntervalMillis > 0)
    }

    val key: String get() = "${symbol.trim().uppercase()}|${timeframe.trim().uppercase()}"
}

data class InstrumentScan(
    val instrument: WatchlistInstrument,
    val feedCheck: FeedCheck,
    val scanResult: ScanResult? = null
)

data class WatchlistScan(
    val scannedAtMillis: Long,
    val items: List<InstrumentScan>
) {
    val confirmedSetups: List<TradeSetup>
        get() = items.mapNotNull { item ->
            item.scanResult?.takeIf { it.status == SetupStatus.CONFIRMED }?.setup
        }
}

/**
 * Isolates provider failures per instrument, validates each returned batch,
 * and only passes healthy feeds to the deterministic closed-candle engine.
 */
class WatchlistScanner(
    private val provider: MarketDataProvider,
    private val scanner: ScannerEngine = ScannerEngine(),
    private val quality: MarketDataQuality = MarketDataQuality()
) {
    fun scan(
        watchlist: List<WatchlistInstrument>,
        nowMillis: Long,
        candleLimit: Int = 500
    ): WatchlistScan {
        require(nowMillis > 0)
        require(candleLimit >= 3)
        require(watchlist.map { it.key }.distinct().size == watchlist.size) {
            "Watchlist contains duplicate symbol/timeframe entries."
        }

        val results = watchlist.map { instrument ->
            try {
                val batch = provider.fetch(instrument.symbol, instrument.timeframe, candleLimit)
                val health = quality.validate(batch, nowMillis, instrument.expectedIntervalMillis)
                if (health.health != FeedHealth.HEALTHY) {
                    InstrumentScan(instrument, health)
                } else if (!batch.symbol.equals(instrument.symbol, ignoreCase = true) ||
                    !batch.timeframe.equals(instrument.timeframe, ignoreCase = true)) {
                    InstrumentScan(
                        instrument,
                        FeedCheck(FeedHealth.INVALID, "Provider returned a different symbol or timeframe.")
                    )
                } else {
                    InstrumentScan(
                        instrument,
                        health,
                        scanner.scan(instrument.symbol, instrument.timeframe, batch.candles)
                    )
                }
            } catch (error: Exception) {
                InstrumentScan(
                    instrument,
                    FeedCheck(
                        FeedHealth.INVALID,
                        "Market-data request failed: ${error.message ?: error::class.java.simpleName}"
                    )
                )
            }
        }
        return WatchlistScan(nowMillis, results)
    }
}
