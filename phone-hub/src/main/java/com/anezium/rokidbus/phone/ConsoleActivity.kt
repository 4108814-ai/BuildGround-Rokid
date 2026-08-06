package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The hub's traces, readable without developer mode. Opens on the backlog kept by
 * [NexusPhoneState] so an incident that already happened is still on screen, then tails the
 * live broadcast. Share exists because the next thing anyone does with a console is send it
 * to whoever can read it.
 */
class ConsoleActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appendLine(intent.getStringExtra("line").orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        // Rebuild from the backlog rather than appending across visits: lines that fired
        // while this screen was stopped would otherwise be missing from the middle.
        logView.text = NexusPhoneState.logBacklog().joinToString(separator = "\n", postfix = "\n")
        scrollToEnd()
        ContextCompat.registerReceiver(
            this,
            logReceiver,
            IntentFilter(NexusPhoneState.ACTION_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(logReceiver) }
        super.onStop()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        logView = TextView(this)
        logScroll = NexusUi.console(this, logView)

        val content = NexusUi.contentColumn(this).apply {
            addView(
                logScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }

        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader("CONSOLE"), NexusUi.block())
                addView(
                    content,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun appendLine(line: String) {
        if (line.isBlank()) return
        logView.append(line + "\n")
        scrollToEnd()
    }

    private fun scrollToEnd() {
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shareLog() {
        val text = logView.text.toString().trim()
        if (text.isEmpty()) return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Rokid Nexus console")
                    .putExtra(Intent.EXTRA_TEXT, text),
                "Share console",
            ),
        )
    }

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@ConsoleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@ConsoleActivity, 10),
                        NexusUi.dp(this@ConsoleActivity, 12),
                        NexusUi.dp(this@ConsoleActivity, 22),
                        NexusUi.dp(this@ConsoleActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@ConsoleActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.metaLabel(this@ConsoleActivity, "SHARE", NexusUi.GREEN).apply {
                            background = NexusUi.pressed(
                                this@ConsoleActivity,
                                Color.TRANSPARENT,
                                10,
                            )
                            setPadding(
                                NexusUi.dp(this@ConsoleActivity, 10),
                                NexusUi.dp(this@ConsoleActivity, 8),
                                NexusUi.dp(this@ConsoleActivity, 10),
                                NexusUi.dp(this@ConsoleActivity, 8),
                            )
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { shareLog() }
                        },
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@ConsoleActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@ConsoleActivity, 1),
                    )
                },
            )
        }

    private fun backButton(): TextView =
        TextView(this).apply {
            text = "‹"
            textSize = 26f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(NexusUi.INK)
            background = NexusUi.pressed(this@ConsoleActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@ConsoleActivity, 44),
                NexusUi.dp(this@ConsoleActivity, 44),
            )
        }
}
