package com.aiscalpingsniper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aiscalpingsniper.engine.CandleCsvParser
import com.aiscalpingsniper.engine.ScannerEngine
import com.aiscalpingsniper.engine.SetupStatus
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var symbolInput: EditText
    private lateinit var timeframeInput: EditText
    private lateinit var resultText: TextView
    private val scanner = ScannerEngine()
    private val openCsvRequest = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(12, 18, 30))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        val title = text("AI SCALPING SNIPER", 25f, Color.WHITE)
        val subtitle = text("SCANNER ENGINE • V1.2", 13f, Color.rgb(126, 190, 255))
        symbolInput = field("Symbol (e.g. EURUSD)", "EURUSD")
        timeframeInput = field("Timeframe (e.g. M5)", "M5")
        val importButton = Button(this).apply {
            text = "IMPORT OHLC CSV"
            setOnClickListener { chooseCsv() }
        }
        resultText = text(
            "MARKET DATA: NOT CONNECTED\n\nImport an OHLC CSV file to run a historical candle scan. Imported data is not live market data and will not trigger live alerts.",
            15f,
            Color.LTGRAY
        )
        root.addView(title)
        root.addView(subtitle)
        root.addView(symbolInput)
        root.addView(timeframeInput)
        root.addView(importButton)
        root.addView(resultText)
        setContentView(scroll)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 14, 0, 14)
    }

    private fun field(hintText: String, initial: String) = EditText(this).apply {
        hint = hintText
        setText(initial)
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        setPadding(14, 10, 14, 10)
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

    private fun importAndScan(uri: Uri) {
        resultText.text = "Reading CSV…"
        try {
            val csv = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not open the selected file.")
            require(csv.length <= 20_000_000) { "CSV is larger than the 20 MB import limit." }
            val candles = CandleCsvParser.parse(csv)
            val symbol = symbolInput.text.toString().trim().uppercase()
            val timeframe = timeframeInput.text.toString().trim().uppercase()
            require(symbol.isNotBlank() && timeframe.isNotBlank()) { "Enter a symbol and timeframe." }
            val result = scanner.scan(symbol, timeframe, candles)
            val setup = result.setup
            val report = buildString {
                append("DATA SOURCE: IMPORTED CSV (HISTORICAL)\n")
                append("CANDLES LOADED: ${candles.size}\n")
                append("LAST CANDLE (UTC): ${DateFormat.getDateTimeInstance().format(Date(candles.last().timestamp))}\n")
                append("SYMBOL / TF: $symbol / $timeframe\n\n")
                append("SCAN STATUS: ${result.status}\n")
                append(result.message)
                if (setup != null) {
                    append("\n\n${setup.direction} CANDIDATE\n")
                    append("Entry: ${formatPrice(setup.entry)}\n")
                    append("Stop loss: ${formatPrice(setup.stopLoss)}\n")
                    append("Take profit: ${formatPrice(setup.takeProfit)}\n")
                    append("Risk/reward: 1:${setup.riskReward}\n")
                    append("Rule score: ${setup.score}/100 (not win probability)\n")
                    append("Signal candle UTC: ${DateFormat.getDateTimeInstance().format(Date(setup.signalCandleTime))}\n")
                    append("\nReasons:\n• " + setup.reasons.joinToString("\n• "))
                }
                append("\n\nHISTORICAL IMPORT ONLY — no live feed or automatic trade execution.")
            }
            resultText.text = report
            resultText.setTextColor(if (result.status == SetupStatus.CONFIRMED) Color.rgb(120, 230, 160) else Color.LTGRAY)
        } catch (error: Exception) {
            resultText.text = "CSV IMPORT FAILED\n${error.message ?: "Unknown error"}"
            resultText.setTextColor(Color.rgb(255, 130, 130))
        }
    }

    private fun formatPrice(value: Double): String = String.format(java.util.Locale.US, "%.5f", value)
}
