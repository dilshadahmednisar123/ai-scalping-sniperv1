# AI Scalping Sniper V1

Android-first Forex and Gold scanner project.

## Current milestone: scanner core foundation

The Android app now contains a deterministic Kotlin signal engine with:

- EMA trend filter.
- Confirmed swing support/resistance pivots.
- ATR-based volatility and stop-distance calculation.
- Closed-candle breakout/rejection confirmation.
- Configurable minimum score and risk/reward target (default 2R).
- Input validation for candle ordering and OHLC ranges.
- JVM unit tests for insufficient history, invalid ordering and invalid OHLC.

## Important data-integrity rules

**The scanner is not yet connected to a live market-data provider.** The app intentionally displays this limitation and does not fabricate quotes, signals or backtest results.

ScannerEngine.scan() expects chronological candles and reserves the final list item as the potentially-forming candle; it analyses only the preceding, closed candles. Feed adapters must supply that final forming candle consistently. A future provider must also expose symbol, timeframe, timestamp, bid/ask or spread where available, source and last-update time.

The current rule set is a first technical baseline, not a complete Smart Money Concepts implementation and not a validated trading edge. The score is a rule score, not a statistically calibrated probability. No order execution is implemented.

## Build and test

From the repository root:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

GitHub Actions builds a debug APK and uploads it as an artifact when the workflow runs on its configured branch.

## Next milestones

1. Implement and verify free-data feed adapters, with stale-data and missing-candle handling.
2. Add multi-symbol/timeframe scanning and a visible signal list.
3. Add reproducible 90-day historical backtesting with spread/slippage assumptions and out-of-sample reporting.
4. Add notifications only after feed and signal tests pass.
5. Validate strategy performance before presenting any signal as trade-ready.

This software is for research and education, not financial advice. Always verify market data and risk independently.
