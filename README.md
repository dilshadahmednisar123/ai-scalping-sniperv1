# AI Scalping Sniper V1

Android-first Forex and Gold research scanner.

## Live scanner milestone (experimental)

A user-started Android foreground service now polls the public **biquote.io** OHLC endpoint without an API key.

- Watchlist: EURUSD, GBPUSD, USDJPY and XAUUSD.
- Timeframe: M5 closed candles.
- Poll cycle: approximately every 60 seconds.
- Signal delivery: Android notifications, deduplicated by symbol/timeframe/direction/signal-candle timestamp.
- Feed checks: source identity, symbol/timeframe identity, strictly increasing timestamps, candle gaps, fetch age, latest closed-candle age, and a fresh bid/ask tick.
- Failed, stale, incomplete or malformed feeds are rejected; they do not produce a signal.

The source documents public OHLC endpoints and a live tick stream. The OHLC endpoint describes a mixture of Yahoo Finance bootstrap data, tick aggregation and MT5 historical feeds. The provider does not publish a contractual uptime or latency SLA. Treat it as an experimental data source, not a broker quote or execution-grade feed.

**Important:** this is not guaranteed 24/7 scanning. Android can stop foreground data-sync services under operating-system policies; Android 15 also applies time limits to data-sync foreground services. The user must start scanning while the app is visible and keep the foreground notification enabled. Reopen the app and restart scanning if Android stops it.

The adapter also requests a fresh provider bid/ask tick and records its spread, but there is no configured broker-specific spread cap and the provider's spread is not the user's MT5 broker spread. Every alert must be checked against the user's broker price, spread, symbol suffix and execution conditions before any manual trade.

## Historical scanner and backtesting

- Deterministic EMA trend, confirmed swing support/resistance, ATR stop distance and closed-candle confirmation.
- CSV import from Android's document picker.
- Strict OHLCV CSV parser supporting comma, semicolon or tab delimiters, timestamp or date/time columns, epoch seconds/milliseconds and UTC timestamps.
- Historical scan of imported candles.
- Walk-forward backtest using next-bar-open entries, conservative stop-first handling when SL and TP touch in one candle, time exits, win rate, expectancy, profit factor and maximum drawdown.
- Experimental SMC context engine: confirmed swing pivots, close-based BOS, wick-and-close liquidity sweeps, three-candle FVG zones and a mechanical order-block proxy.
- Feed-quality validation primitives and JVM unit tests.

## CSV format

See [sample-data/ohlcv-template.csv](sample-data/ohlcv-template.csv).

Required columns: `timestamp,open,high,low,close`. Optional: `volume`. Timestamps without an explicit timezone are interpreted as UTC. Candles must be strictly chronological, with no duplicates. Do not label local-time data as UTC; convert it before import.

## Build and install

GitHub Actions runs JVM tests and assembles a debug APK.

1. Open the repository's **Actions** tab.
2. Open the latest successful **Android APK** workflow run on `feature/scanner-engine-v1`.
3. Download the `ai-scalping-sniper-debug` artifact.
4. Extract the ZIP and install `app-debug.apk` on Android. Allow installation from that source if Android asks.

This is a debug build, not a Play Store release.

## Current limitations

- No broker connection, account access, order placement or automated execution.
- SMC context is experimental and is not yet integrated into the baseline signal decision.
- Rule score is not a calibrated probability or a measured win rate.
- No instrument-specific spread cap, commission, swap or variable-slippage model is active in the live scanner.
- Public-feed symbol availability and freshness may vary; unavailable XAUUSD or Forex candles are shown as unhealthy rather than fabricated.
- The service polls M5 data and is not a tick-scalping engine.

## Next milestones

1. Verify live symbol availability and response quality across market sessions.
2. Add provider tick bid/ask checks and configurable per-instrument spread caps.
3. Integrate MTF context and SMC confluence into signal scoring without look-ahead.
4. Build a proper live watchlist/status screen and user-configurable symbols/timeframes.
5. Add realistic spread/slippage and out-of-sample backtests before trusting strategy performance.

Research and educational software only. Verify every quote and risk parameter independently.
