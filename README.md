# AI Scalping Sniper V1

Android-first Forex and Gold research scanner.

## Current build

The project currently builds a **debug APK** through GitHub Actions. The workflow runs JVM unit tests, assembles the APK, and uploads it as the `ai-scalping-sniper-debug` artifact.

To download it:
1. Open the repository's **Actions** tab.
2. Open the successful **Android APK** workflow run on `feature/scanner-engine-v1`.
3. Scroll to **Artifacts** and download `ai-scalping-sniper-debug`.
4. Extract the ZIP; install `app-debug.apk` on Android. Android may ask you to allow installation from that source.

This is a debug build, not a Play Store release.

## Implemented

- Deterministic EMA trend, confirmed swing support/resistance, ATR stop distance and closed-candle confirmation scanner.
- CSV import from Android's document picker.
- Strict OHLCV CSV parser supporting comma, semicolon or tab delimiters, timestamp or date/time columns, epoch seconds/milliseconds and UTC timestamps.
- Historical scan of imported candles.
- Walk-forward backtest using next-bar-open entries, conservative stop-first handling when SL and TP touch in the same candle, time exits, win rate, expectancy, profit factor and maximum drawdown.
- Experimental SMC context engine: confirmed swing pivots, close-based BOS, wick-and-close liquidity sweeps, three-candle FVG zones and a mechanical order-block proxy.
- Feed-quality validation primitives for freshness, ordering, gaps, spread and symbol/timeframe identity.
- JVM unit tests and CI build.

## CSV format

See [sample-data/ohlcv-template.csv](sample-data/ohlcv-template.csv).

Required columns: `timestamp,open,high,low,close`. Optional: `volume`. Timestamps without an explicit timezone are interpreted as UTC. Candles must be strictly chronological, with no duplicates. Do not label local-time data as UTC; convert it before import.

## Important limitations

**There is no live market-data provider connected yet.** The app does not currently scan live Forex/Gold prices, run continuously in the background, or send live trade alerts. Imported CSV data is historical/manual input only.

The SMC module is experimental and is not yet integrated into the baseline signal decision. Its order-block logic is a transparent heuristic, not evidence of institutional orders. A scanner score is a rule score, not a calibrated probability. No broker order execution is implemented.

The backtest is only as reliable as the imported candles and its assumptions. It does not yet model instrument-specific spread, commission, swap, variable slippage or tick-level intrabar ordering. Do not interpret it as proof of profitability.

## Build locally

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

## Next engineering milestones

1. Verify free data sources and their licensing, coverage, latency and continuity.
2. Implement provider adapters with strict stale-feed and missing-candle rejection.
3. Integrate multi-symbol and multi-timeframe scanning.
4. Integrate and test SMC confluence in signal scoring without look-ahead.
5. Add notifications only after live-feed and signal tests pass.
6. Run realistic 90-day and out-of-sample backtests with instrument-specific execution costs.

Research and educational software only. Independently verify market data and risk before making trading decisions.
