package com.aiscalpingsniper

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(12, 18, 30))
        }

        val title = TextView(this).apply {
            text = "AI SCALPING SNIPER"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "SCANNER ENGINE • V1.1\n\nCore signal rules added:\n• EMA trend filter\n• Confirmed support / resistance pivots\n• Closed-candle confirmation\n• ATR-based stop loss and 2R target\n\nMARKET DATA: NOT CONNECTED\nNo live prices or trade alerts are being generated yet. A verified data feed and feed-quality checks are required before live scanning."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        root.addView(title)
        root.addView(status)
        setContentView(root)
    }
}
