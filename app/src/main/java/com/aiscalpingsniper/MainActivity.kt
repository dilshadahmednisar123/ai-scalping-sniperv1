package com.aiscalpingsniper

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aiscalpingsniper.engine.BacktestEngine
import com.aiscalpingsniper.engine.Candle
import com.aiscalpingsniper.engine.CandleCsvParser
import com.aiscalpingsniper.engine.ScannerEngine
import com.aiscalpingsniper.engine.SetupStatus
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Native dashboard UI. All market values remain explicitly unavailable until
 * a validated provider response supplies them; the UI never paints demo prices.
 */
class MainActivity : Activity() {
    private lateinit var symbolInput: EditText
    private lateinit var timeframeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private lateinit var scanStatus: TextView
    private lateinit var feedStatus: TextView
    private val scanner = ScannerEngine()
    private val backtester = BacktestEngine()
    private var importedCandles: List<Candle> = emptyList()
    private val openCsvRequest = 4101
    private val notificationRequest = 4102
    private val maxCsvBytes = 20 * 1024 * 1024
    private val bg = Color.rgb(9, 14, 25)
    private val panel = Color.rgb(17, 25, 41)
    private val panel2 = Color.rgb(23, 33, 52)
    private val muted = Color.rgb(143, 158, 181)
    private val white = Color.rgb(239, 244, 252)
    private val blue = Color.rgb(79, 164, 255)
    private val green = Color.rgb(39, 207, 142)
    private val red = Color.rgb(255, 103, 119)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = 0
        buildDashboard()
    }

    private fun buildDashboard() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(12), dp(18), dp(26))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(bg)
            addView(page)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = column().apply {
            addView(label("AI SCALPING", 11, blue, true))
            addView(label("SNIPER", 25, white, true))
            addView(label("MARKET INTELLIGENCE TERMINAL", 9, muted, true))
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(pill("V1.3 • REBUILD", blue))
        page.addView(top)
        page.addView(space(18))

        val hero = card()
        hero.addView(rowOf(
            label("SCANNER STATUS", 11, muted, true),
            pill("EXPERIMENTAL FEED", Color.rgb(255, 183, 77))
        ))
        hero.addView(space(13))
        val headline = label("Market data not verified", 21, white, true)
        hero.addView(headline)
        feedStatus = label("No trusted live quote is currently confirmed. Prices and signals are hidden until feed checks pass.", 12, muted)
        hero.addView(space(6))
        hero.addView(feedStatus)
        hero.addView(space(14))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = actionButton("START SCANNER", green, true)
        start.setOnClickListener { requestNotificationAndStart() }
        val stop = actionButton("STOP", Color.rgb(56, 70, 91), false)
        stop.setOnClickListener {
            stopService(Intent(this@MainActivity, LiveScanService::class.java).setAction(LiveScanService.ACTION_STOP))
            updateStatus("Scanner stop requested. Android may take a moment to remove its notification.", muted)
        }
        actions.addView(start, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(spaceWidth(8))
        actions.addView(stop, LinearLayout.LayoutParams(dp(92), dp(46)))
        hero.addView(actions)
        page.addView(hero)

        page.addView(sectionHeader("MARKET PULSE", "LIVE QUOTES ONLY"))
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(metricCard("LIVE MARKETS", "—", "Awaiting feed", blue), LinearLayout.LayoutParams(0, -1, 1f))
        metrics.addView(spaceWidth(9))
        metrics.addView(metricCard("VALID SETUPS", "—", "No verified scan", green), LinearLayout.LayoutParams(0, -1, 1f))
        page.addView(metrics)

        page.addView(sectionHeader("WATCHLIST", "M5 • AUTO SCAN"))
        val watch = card()
        listOf(
            "EUR/USD" to "Waiting for validated quote",
            "GBP/USD" to "Waiting for validated quote",
            "USD/JPY" to "Waiting for validated quote",
            "XAU/USD" to "Waiting for validated quote"
        ).forEachIndexed { index, item ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val symbol = label(item.first, 14, white, true)
            line.addView(symbol, LinearLayout.LayoutParams(dp(88), -2))
            line.addView(label(item.second, 11, muted), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(pill("NO DATA", muted))
            watch.addView(line)
            if (index != 3) watch.addView(separator())
        }
        page.addView(watch)

        page.addView(sectionHeader("SIGNAL INTELLIGENCE", "CLOSED-CANDLE ENGINE"))
        val signal = card()
        scanStatus = label("NO ACTIVE SETUP", 15, white, true)
        signal.addView(scanStatus)
        signal.addView(space(8))
        signal.addView(label("When a valid setup is detected, its direction, entry, stop loss, targets, risk/reward, evidence and invalidation conditions will appear here.", 12, muted))
        signal.addView(space(12))
        signal.addView(label("Evidence framework", 11, blue, true))
        signal.addView(label("Liquidity sweep  •  BOS / MSS  •  FVG  •  S/R  •  EMA trend  •  ATR risk", 11, muted))
        page.addView(signal)

        page.addView(sectionHeader("SCAN CONFIGURATION", "MANUAL CONTROLS"))
        val config = card()
        config.addView(label("Symbol", 11, muted, true))
        symbolInput = field("EURUSD")
        config.addView(symbolInput)
        config.addView(space(9))
        config.addView(label("Timeframe", 11, muted, true))
        timeframeInput = field("M5")
        config.addView(timeframeInput)
        config.addView(space(10))
        config.addView(label("The background scanner currently uses its fixed four-symbol M5 watchlist. These fields apply to CSV analysis and backtesting.", 11, muted))
        page.addView(config)

        page.addView(sectionHeader("RESEARCH & VALIDATION", "HISTORICAL DATA"))
        val tools = card()
        val import = actionButton("IMPORT OHLC CSV", blue, true)
        import.setOnClickListener { chooseCsv() }
        tools.addView(import)
        tools.addView(space(8))
        val backtest = actionButton("RUN HISTORICAL BACKTEST", Color.rgb(56, 70, 91), false)
        backtest.setOnClickListener { runBacktest() }
        tools.addView(backtest)
        historyText = label("No historical dataset imported. Backtests require real, correctly timestamped OHLC candles.", 11, muted)
        tools.addView(space(10))
        tools.addView(historyText)
        page.addView(tools)

        page.addView(space(18))
        statusText = label("SYSTEM READY • LIVE FEED NOT YET VERIFIED", 10, muted, true)
        page.addView(statusText)
        page.addView(space(8))
        page.addView(label("Research software only. No broker execution. Verify every quote, spread and trade plan in MT5. A rule score is not a win probability.", 10, muted))
        setContentView(scroll)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(panel, 16)
    }
    private fun sectionHeader(title: String, trailing: String): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(19), 0, dp(9))
        }
        line.addView(label(title, 12, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(label(trailing, 9, muted, true))
        return line
    }
    private fun rowOf(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(a, LinearLayout.LayoutParams(0, -2, 1f))
        addView(b)
    }
    private fun metricCard(title: String, value: String, detail: String, accent: Int): View {
        val box = card()
        box.addView(label(title, 9, muted, true))
        box.addView(space(7))
        box.addView(label(value, 25, accent, true))
        box.addView(label(detail, 10, muted))
        return box
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        includeFontPadding = false
    }
    private fun pill(value: String, color: Int) = TextView(this).apply {
        text = value
        textSize = 9f
        setTextColor(color)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(6), dp(9), dp(6))
        background = rounded(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)), 30)
    }
    private fun actionButton(value: String, color: Int, primary: Boolean) = Button(this).apply {
        text = value
        textSize = 11f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) bg else white)
        background = rounded(color, 11)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), 0, dp(10), 0)
    }
    private fun field(value: String) = EditText(this).apply {
        setText(value)
        setTextColor(white)
        textSize = 14f
        setSingleLine(true)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(panel2, 9)
    }
    private fun separator() = View(this).apply {
        setBackgroundColor(Color.rgb(37, 49, 68))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spaceWidth(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun updateStatus(message: String, color: Int) {
        statusText.text = message.uppercase(Locale.ROOT)
        statusText.setTextColor(color)
    }

    private fun requestNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
            return
        }
        startLiveScanner()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startLiveScanner()
            else updateStatus("Notifications denied. Enable them in Android settings to receive scanner alerts.", red)
        }
    }

    private fun startLiveScanner() {
        val intent = Intent(this, LiveScanService::class.java).setAction(LiveScanService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        feedStatus.text = "Scanner requested. Feed health and market prices are not verified until provider responses pass validation."
        updateStatus("SCANNER START REQUESTED • EXPERIMENTAL PUBLIC FEED", blue)
    }

    private fun chooseCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        startActivityForResult(intent, openCsvRequest)
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != openCsvRequest || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        importAndScan(uri)
    }

    private fun readCsvWithLimit(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: error("Could not open the selected file.")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxCsvBytes) { "CSV is larger than the 20 MB import limit." }
                output.write(buffer, 0, count)
            }
            return String(output.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun importAndScan(uri: Uri) {
        historyText.text = "Reading historical file…"
        try {
            val candles = CandleCsvParser.parse(readCsvWithLimit(uri))
            importedCandles = candles
            val symbol = symbolInput.text.toString().trim().uppercase(Locale.ROOT)
            val timeframe = timeframeInput.text.toString().trim().uppercase(Locale.ROOT)
            require(symbol.isNotBlank() && timeframe.isNotBlank()) { "Enter a symbol and timeframe." }
            val result = scanner.scan(symbol, timeframe, candles)
            val setup = result.setup
            val summary = buildString {
                append("CSV • " + candles.size + " candles • Last UTC: " + formatUtc(candles.last().timestamp))
                append("\n" + symbol + " / " + timeframe + " • " + result.status)
                append("\n" + result.message)
                if (setup != null) {
                    append("\n" + setup.direction + " | Entry " + formatPrice(symbol, setup.entry))
                    append("\nSL " + formatPrice(symbol, setup.stopLoss) + " | TP " + formatPrice(symbol, setup.takeProfit))
                    append("\nRR 1:" + setup.riskReward + " | Rule score " + setup.score + "/100 (not probability)")
                    append("\nEvidence: " + setup.reasons.joinToString(" • "))
                }
            }
            historyText.text = summary
            scanStatus.text = if (result.status == SetupStatus.CONFIRMED) "HISTORICAL SETUP CANDIDATE" else "NO CONFIRMED HISTORICAL SETUP"
            updateStatus("HISTORICAL CSV SCAN COMPLETE • NOT A LIVE SIGNAL", blue)
        } catch (error: Exception) {
            historyText.text = "CSV IMPORT FAILED • " + (error.message ?: "Unknown error")
            updateStatus("HISTORICAL IMPORT FAILED", red)
        }
    }

    private fun runBacktest() {
        val candles = importedCandles
        if (candles.isEmpty()) {
            historyText.text = "Import a CSV file before running a backtest."
            return
        }
        val symbol = symbolInput.text.toString().trim().uppercase(Locale.ROOT)
        val timeframe = timeframeInput.text.toString().trim().uppercase(Locale.ROOT)
        try {
            val report = backtester.run(symbol, timeframe, candles)
            historyText.text = buildString {
                append("BACKTEST • " + symbol + " / " + timeframe)
                append("\nCandles: " + candles.size + " | Trades: " + report.trades.size)
                append("\nSkipped: " + report.skippedSignals + " | Win rate: " + report.winRatePercent + "%")
                append("\nExpectancy: " + report.expectancyR + "R | Profit factor: " + report.profitFactor)
                append("\nMax drawdown: " + report.maxDrawdownR + "R")
                append("\nNext-bar open fills; stop-first on ambiguous candles.")
                append("\nHistorical results do not predict future performance.")
            }
            updateStatus("BACKTEST COMPLETE • HISTORICAL DATA ONLY", blue)
        } catch (error: Exception) {
            historyText.text = "BACKTEST FAILED • " + (error.message ?: "Unknown error")
            updateStatus("BACKTEST FAILED", red)
        }
    }

    private fun formatUtc(timestamp: Long): String =
        DateFormat.getDateTimeInstance().apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestamp))

    private fun formatPrice(symbol: String, value: Double): String {
        val decimals = when {
            symbol.endsWith("JPY") -> 3
            symbol.startsWith("XAU") -> 2
            else -> 5
        }
        return String.format(Locale.US, "%." + decimals + "f", value)
    }
}
