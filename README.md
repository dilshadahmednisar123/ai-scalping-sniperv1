# AI Scalping Sniper

Android Forex and Gold research scanner. The project is being rebuilt as a professional market-monitoring terminal.

## Current build: dashboard rebuild
- Replaced the initial plain form screen with a dark trading-terminal layout.
- Added market overview, watchlist, feed-health messaging, signal-intelligence area, scan controls and a dedicated historical research section.
- The UI intentionally displays unavailable values as dashes / waiting states. It does not invent prices, signal counts or performance.
- Existing scanner, CSV import and backtest engines remain available.

## Live market data status — experimental, not broker-grade
The current background scanner still uses the public no-key biquote.io adapter. It polls four symbols (EURUSD, GBPUSD, USDJPY, XAUUSD) on M5 approximately every 60 seconds and checks closed M15/H1 EMA50 alignment before alerts.

This source has no contractual uptime or latency SLA and is not verified as a broker-equivalent real-time feed. Symbol coverage, licensing/terms, freshness and data continuity must be independently validated. Do not treat this as execution-grade data or as proof of signal accuracy.

## Signal methodology and limitations
The baseline scanner uses EMA trend, confirmed swing support/resistance, ATR-based stop placement and closed-candle confirmation. The experimental SMC context engine detects confirmed pivots, close-based BOS, wick-and-close liquidity sweeps, three-candle FVGs and a mechanical order-block proxy. SMC context is not yet fully integrated into the live decision score.

A rule score is not a probability of winning. No accuracy percentage is promised. Signals must be evaluated through reproducible out-of-sample and forward testing.

## Historical research
CSV import supports timestamped OHLC(V) data. Backtests use next-bar-open entries, conservative stop-first handling when both stop and target are touched in one candle, time exits, win rate, expectancy, profit factor and max drawdown. The current backtest does not yet fully model broker-specific variable spread, commissions, swaps or slippage.

## Required engineering milestones before real-money reliance
1. Validate a free data provider's API, symbol coverage, timestamps, market-session behavior, licensing and sustained freshness; reject the provider if it cannot meet explicit thresholds.
2. Add provider health telemetry, reconnect/backoff, rate-limit handling and user-visible per-symbol feed status.
3. Integrate MTF structure and SMC confluence without look-ahead or repainting.
4. Add broker-specific spread/commission/slippage assumptions and walk-forward/out-of-sample validation.
5. Add charting, signal history, configurable watchlists/timeframes, alert preferences and robust Android scheduling.
6. Run paper/forward tests and publish measured metrics with sample size and confidence intervals before claiming signal quality.

## Safety
Research and educational software only. No broker login, order placement or automated execution. Android may stop long-running background work under OS power policies. Verify every quote, spread and risk parameter independently in MT5.