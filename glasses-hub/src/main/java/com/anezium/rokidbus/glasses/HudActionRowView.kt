package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusGlyphs

/** One drawable command in a [HudActionRowView], stripped of whose tier it came from. */
internal data class HudActionChip(val glyph: String, val label: String)

/**
 * The platform's row of choices, drawn identically wherever it appears.
 *
 * An activity panel and a notice band are different tiers with different
 * lifetimes, but the affordance is one thing: a short row of glyphs, exactly
 * one of them selected, stepped through with forward and backward and fired
 * with confirm. Two drawings of that would be two things for the wearer to
 * learn, so the drawing lives here and each tier only supplies its own list.
 *
 * The row hides itself when there is nothing to offer, so a caller can render
 * unconditionally.
 */
internal class HudActionRowView(context: Context) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.START
    }

    fun render(actions: List<HudActionChip>, selectedIndex: Int) {
        removeAllViews()
        actions.forEachIndexed { index, action ->
            addView(
                chip(action, selected = index == selectedIndex),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = BusTheme.dp(context, 6)
                },
            )
        }
        visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun chip(action: HudActionChip, selected: Boolean) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val horizontal = BusTheme.dp(context, 6)
        val vertical = BusTheme.dp(context, 4)
        setPadding(horizontal, vertical, horizontal, vertical)
        background = GradientDrawable().apply {
            // Pure black, like every other HUD fill: the additive optics emit
            // nothing for it, so only the border and the label light up.
            setColor(0xFF000000.toInt())
            setStroke(
                BusTheme.dp(context, if (selected) 2 else 1),
                if (selected) BusTheme.phosphor else BusTheme.hairline,
            )
            cornerRadius = BusTheme.dp(context, 5).toFloat()
        }
        addView(
            ImageView(context).apply {
                setImageDrawable(
                    requireNotNull(context.getDrawable(NexusGlyphs.drawableFor(action.glyph))),
                )
            },
            LayoutParams(
                BusTheme.dp(context, ACTION_GLYPH_DP),
                BusTheme.dp(context, ACTION_GLYPH_DP),
            ),
        )
        addView(
            label(if (selected) BusTheme.phosphor else BusTheme.muted).apply {
                text = action.label
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = BusTheme.dp(context, 4)
            },
        )
    }

    private fun label(color: Int) = TextView(context).apply {
        textSize = ACTION_LABEL_SP
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        includeFontPadding = false
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private companion object {
        const val ACTION_GLYPH_DP = 18
        const val ACTION_LABEL_SP = 10f
    }
}
