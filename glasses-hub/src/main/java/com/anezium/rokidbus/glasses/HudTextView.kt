package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.widget.TextView

internal fun monoHudText(
    context: Context,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
): TextView = TextView(context).apply {
    textSize = sizeSp
    setTextColor(color)
    typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    includeFontPadding = false
    isSingleLine = false
    setHorizontallyScrolling(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }
}
