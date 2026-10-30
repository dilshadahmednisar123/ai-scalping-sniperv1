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
            text = "V1 • Scanner not connected yet\n\nThis is the initial app shell. Live signals will only be enabled after a reliable market-data feed and strategy validation are implemented."
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
