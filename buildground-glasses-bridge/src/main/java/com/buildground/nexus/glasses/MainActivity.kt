package com.buildground.nexus.glasses

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 64, 32, 32)
            setBackgroundColor(Color.rgb(27, 27, 27))
        }

        root.addView(TextView(this).apply {
            text = "BUILDGROUND"
            textSize = 24f
            setTextColor(Color.rgb(255, 122, 0))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "NEXUS GLASSES BRIDGE"
            textSize = 16f
            setTextColor(Color.rgb(245, 245, 245))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 40)
        })

        status = TextView(this).apply {
            text = "Starting hardware bridge…"
            textSize = 14f
            setTextColor(Color.rgb(184, 184, 184))
            gravity = Gravity.CENTER
        }
        root.addView(status)
        setContentView(root)

        BuildGroundGlassesBridge.start { message ->
            runOnUiThread { status.text = message }
        }
    }
}
