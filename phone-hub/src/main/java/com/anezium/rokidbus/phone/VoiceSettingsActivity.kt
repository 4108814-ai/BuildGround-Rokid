package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.Locale

/**
 * How the glasses answer out loud, as opposed to how they listen, which is Speech.
 *
 * The speaking is done by the phone's own engine, so the voice and the speed are ours to
 * set per sentence — unlike the glasses' engine, whose voice and rate are device-wide
 * properties shared with Rokid's own assistant and therefore left alone.
 *
 * Automatic follows the phone's audio route unless that route is its own speaker, when
 * the glasses take over. Glasses only always uses the glasses' device-wide voice and rate.
 */
class VoiceSettingsActivity : Activity() {
    private val voiceSettings by lazy { PhoneTtsSettingsStore(this) }

    private lateinit var introCard: TextView
    private lateinit var headerMeta: TextView
    private lateinit var outputHost: LinearLayout
    private lateinit var phoneSettingsHost: LinearLayout
    private lateinit var speedHost: LinearLayout
    private lateinit var voiceListHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildSkeleton()
        render()
    }

    override fun onResume() {
        super.onResume()
        // The voice list comes from the running hub, so it can appear or vanish while
        // this screen is open.
        render()
    }

    private fun buildSkeleton() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        introCard = NexusUi.cardBody(this, "")
        headerMeta = NexusUi.metaLabel(this, "", NexusUi.GREEN_DIM)
        outputHost = host()
        speedHost = host()
        voiceListHost = host()

        phoneSettingsHost = host().apply {
            addView(sectionHeaderRow("Speed", headerMeta), NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(speedHost, NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 26))
            addView(
                sectionHeaderRow(
                    "Voice",
                    NexusUi.metaLabel(this@VoiceSettingsActivity, "", NexusUi.INK4),
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(
                NexusUi.card(this@VoiceSettingsActivity).apply {
                    addView(voiceListHost, NexusUi.block())
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@VoiceSettingsActivity, 14))
            addView(
                NexusUi.textButton(this@VoiceSettingsActivity, "Hear it").apply {
                    setOnClickListener { hearSample() }
                },
                NexusUi.block(),
            )
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(introCard, NexusUi.block())
            addView(BusTheme.gap(this@VoiceSettingsActivity, 22))
            addView(
                sectionHeaderRow(
                    "Output",
                    NexusUi.metaLabel(this@VoiceSettingsActivity, "", NexusUi.INK4),
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@VoiceSettingsActivity, 12))
            addView(
                NexusUi.card(this@VoiceSettingsActivity).apply {
                    addView(outputHost, NexusUi.block())
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@VoiceSettingsActivity, 26))
            addView(phoneSettingsHost, NexusUi.block())
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader("VOICE"), NexusUi.block())
                addView(
                    scroll,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun host(): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@VoiceSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@VoiceSettingsActivity, 10),
                        NexusUi.dp(this@VoiceSettingsActivity, 12),
                        NexusUi.dp(this@VoiceSettingsActivity, 22),
                        NexusUi.dp(this@VoiceSettingsActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@VoiceSettingsActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@VoiceSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@VoiceSettingsActivity, 1),
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
            background = NexusUi.pressed(this@VoiceSettingsActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@VoiceSettingsActivity, 44),
                NexusUi.dp(this@VoiceSettingsActivity, 44),
            )
        }

    private fun sectionHeaderRow(label: String, metaView: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                NexusUi.sectionLabel(this@VoiceSettingsActivity, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(metaView)
        }

    private fun render() {
        val outputMode = voiceSettings.outputMode()
        introCard.text = when (outputMode) {
            PhoneTtsOutputMode.AUTO ->
                "Answers are spoken by your phone, so they follow your audio -- the glasses, " +
                    "or your earbuds if you have some in. If the sound would land on the " +
                    "phone's own speaker, the glasses speak instead."
            PhoneTtsOutputMode.GLASSES_ONLY ->
                "Answers are spoken by the glasses themselves, with the voice and speed " +
                    "they share with Rokid's own assistant."
        }
        outputHost.removeAllViews()
        outputHost.addView(
            selectableRow("Automatic", null, outputMode == PhoneTtsOutputMode.AUTO) {
                voiceSettings.setOutputMode(PhoneTtsOutputMode.AUTO)
                render()
            },
            NexusUi.block(),
        )
        outputHost.addView(
            selectableRow("Glasses only", null, outputMode == PhoneTtsOutputMode.GLASSES_ONLY) {
                voiceSettings.setOutputMode(PhoneTtsOutputMode.GLASSES_ONLY)
                render()
            },
            NexusUi.block(),
        )
        phoneSettingsHost.visibility =
            if (outputMode == PhoneTtsOutputMode.AUTO) View.VISIBLE else View.GONE
        if (outputMode == PhoneTtsOutputMode.GLASSES_ONLY) return

        val rate = voiceSettings.speechRate()
        headerMeta.text = formatRate(rate)

        speedHost.removeAllViews()
        speedHost.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                SPEECH_RATES.forEachIndexed { index, value ->
                    addView(speedChip(value, rate, first = index == 0))
                }
            },
            NexusUi.block(),
        )

        voiceListHost.removeAllViews()
        val voices = PhoneTtsUiApi.availableVoices(Locale.getDefault())
        if (voices.isEmpty()) {
            // An empty list means the hub is not running, not that the phone has no voices.
            voiceListHost.addView(
                NexusUi.metaLabel(this, "START THE HUB TO CHOOSE A VOICE", NexusUi.INK4),
                NexusUi.block(),
            )
            return
        }
        val selected = voiceSettings.voiceName()
        voiceListHost.addView(
            selectableRow("Default", null, selected == null) {
                voiceSettings.setVoiceName(null)
                render()
                hearSample()
            },
            NexusUi.block(),
        )
        voices.forEachIndexed { index, option ->
            voiceListHost.addView(
                selectableRow(
                    "Voice ${index + 1}",
                    if (option.needsNetwork) "needs network" else "on device",
                    selected == option.name,
                ) {
                    voiceSettings.setVoiceName(option.name)
                    render()
                    hearSample()
                },
                NexusUi.block(),
            )
        }
    }

    private fun speedChip(rate: Float, current: Float, first: Boolean): TextView =
        TextView(this).apply {
            text = formatRate(rate)
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            val selected = kotlin.math.abs(rate - current) < 0.01f
            setTextColor(if (selected) NexusUi.GREEN else NexusUi.INK2)
            background = if (selected) {
                NexusUi.bordered(
                    this@VoiceSettingsActivity,
                    NexusUi.alpha(NexusUi.GREEN, 0x14),
                    NexusUi.alpha(NexusUi.GREEN, 0x50),
                    11,
                )
            } else {
                NexusUi.pressedBordered(this@VoiceSettingsActivity, NexusUi.PANEL, 11)
            }
            setPadding(
                NexusUi.dp(this@VoiceSettingsActivity, 16),
                NexusUi.dp(this@VoiceSettingsActivity, 9),
                NexusUi.dp(this@VoiceSettingsActivity, 16),
                NexusUi.dp(this@VoiceSettingsActivity, 9),
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                if (!first) marginStart = NexusUi.dp(this@VoiceSettingsActivity, 8)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                voiceSettings.setSpeechRate(rate)
                render()
                hearSample()
            }
        }

    private fun selectableRow(
        label: String,
        badge: String?,
        selected: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val dot = NexusUi.dot(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@VoiceSettingsActivity, 8),
                NexusUi.dp(this@VoiceSettingsActivity, 8),
            ).apply { marginStart = NexusUi.dp(this@VoiceSettingsActivity, 12) }
        }
        NexusUi.setDotColor(dot, if (selected) NexusUi.GREEN else NexusUi.INK4)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.pressed(this@VoiceSettingsActivity, Color.TRANSPARENT, 10)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(
                0,
                NexusUi.dp(this@VoiceSettingsActivity, 7),
                0,
                NexusUi.dp(this@VoiceSettingsActivity, 7),
            )
            setOnClickListener {
                onClick()
            }
            addView(
                NexusUi.rowTitle(this@VoiceSettingsActivity, label).apply {
                    if (selected) setTextColor(NexusUi.INK)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            badge?.let {
                addView(
                    NexusUi.metaLabel(
                        this@VoiceSettingsActivity,
                        it.uppercase(),
                        if (selected) NexusUi.GREEN_DIM else NexusUi.INK4,
                    ),
                )
            }
            addView(dot)
        }
    }

    private fun hearSample() {
        if (!PhoneTtsUiApi.speakSample(sampleText())) {
            Toast.makeText(this, "Start the hub to hear the voice.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The sample has to be in the language the voice will actually speak, or it previews
     * the wrong thing entirely — a French voice reading English is exactly the mismatch
     * this screen exists to let you avoid.
     */
    private fun sampleText(): String = when (Locale.getDefault().language) {
        "fr" -> "Voilà comment je vais lire vos réponses."
        else -> "This is how I will read your answers."
    }

    private fun formatRate(rate: Float): String =
        if (rate == rate.toInt().toFloat()) "${rate.toInt()}x" else "${rate}x"
}

private val SPEECH_RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f)
