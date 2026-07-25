package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.phone.speech.HubSecretStore
import com.anezium.rokidbus.phone.speech.SpeechCredentialKind
import com.anezium.rokidbus.phone.speech.SpeechCredits
import com.anezium.rokidbus.phone.speech.SpeechEngine
import com.anezium.rokidbus.phone.speech.SpeechProvider
import com.anezium.rokidbus.phone.speech.SpeechReadiness
import com.anezium.rokidbus.phone.speech.SpeechSessionState
import com.anezium.rokidbus.phone.speech.SpeechSettingsStore
import com.anezium.rokidbus.phone.speech.SpeechStartResult
import com.anezium.rokidbus.phone.speech.SpeechUtteranceListener
import com.anezium.rokidbus.phone.speech.SpeechEndReason
import com.anezium.rokidbus.phone.speech.SttError
import com.anezium.rokidbus.phone.speech.TranscriptionLanguage

class SpeechSettingsActivity : Activity() {
    private val settings by lazy { SpeechSettingsStore(this) }
    private val secrets by lazy { HubSecretStore(this) }

    private var shownProvider: SpeechProvider = SpeechProvider.OPENAI
    private var testActive = false
    private var statusText: String? = null
    private var statusColor: Int = NexusUi.INK2
    private var transcriptText: String? = null
    private var transcriptFinal = false

    private lateinit var testStatusView: TextView
    private lateinit var testTranscriptView: TextView
    private lateinit var testButton: Button
    private lateinit var readinessValue: TextView
    private var creditsView: TextView? = null
    private var creditsGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownProvider = settings.selectedEngine()?.provider ?: SpeechProvider.OPENAI
        buildUi()
    }

    override fun onStop() {
        stopDictationIfActive()
        super.onStop()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@SpeechSettingsActivity, "Engine", shownProvider.displayName), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(providerSegments(), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 10))
            addView(modelCard(), NexusUi.block())

            addView(BusTheme.gap(this@SpeechSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@SpeechSettingsActivity, "Language", settings.selectedLanguage().summaryName), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(languageGrid(), NexusUi.block())
            settings.selectedLanguage().uiNote?.let { note ->
                addView(BusTheme.gap(this@SpeechSettingsActivity, 8))
                addView(NexusUi.rowSub(this@SpeechSettingsActivity, note).apply { maxLines = 3 }, NexusUi.block())
            }

            addView(BusTheme.gap(this@SpeechSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@SpeechSettingsActivity, "API key", shownProvider.displayName), NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(credentialCard(), NexusUi.block())

            addView(BusTheme.gap(this@SpeechSettingsActivity, 28))
            readinessValue = NexusUi.metaLabel(this@SpeechSettingsActivity, "", NexusUi.GREEN_DIM)
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(NexusUi.dp(this@SpeechSettingsActivity, 2), NexusUi.dp(this@SpeechSettingsActivity, 2), NexusUi.dp(this@SpeechSettingsActivity, 2), 0)
                    addView(
                        NexusUi.sectionLabel(this@SpeechSettingsActivity, "Try it"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(readinessValue)
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(dictationCard(), NexusUi.block())
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

        val root = NexusUi.fixedRoot(this).apply {
            addView(titleHeader("Speech"), NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

        renderReadiness()
        renderDictation()
        setContentView(root)
    }

    // --- Engine ---

    private fun providerSegments(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            SpeechProvider.values().forEachIndexed { index, provider ->
                addView(
                    providerChip(provider),
                    LinearLayout.LayoutParams(0, NexusUi.dp(this@SpeechSettingsActivity, 42), 1f).apply {
                        if (index > 0) marginStart = NexusUi.dp(this@SpeechSettingsActivity, 8)
                    },
                )
            }
        }

    private fun providerChip(provider: SpeechProvider): TextView =
        TextView(this).apply {
            text = provider.displayName
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            val selected = provider == shownProvider
            setTextColor(if (selected) NexusUi.GREEN else NexusUi.INK2)
            background = if (selected) {
                NexusUi.bordered(this@SpeechSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x14), NexusUi.alpha(NexusUi.GREEN, 0x50), 12)
            } else {
                NexusUi.pressedBordered(this@SpeechSettingsActivity, NexusUi.PANEL, 12)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (shownProvider != provider) {
                    shownProvider = provider
                    refreshUi()
                }
            }
        }

    /** Rebuild after a configuration change: stop any running test and recompute guidance. */
    private fun refreshUi() {
        stopDictationIfActive()
        statusText = null
        transcriptText = null
        transcriptFinal = false
        buildUi()
    }

    private fun modelCard(): LinearLayout =
        NexusUi.card(this).apply {
            val models = SpeechEngine.values().filter { it.provider == shownProvider }
            models.forEachIndexed { index, engine ->
                if (index > 0) addView(NexusUi.divider(this@SpeechSettingsActivity))
                addView(modelRow(engine), NexusUi.block())
            }
        }

    private fun modelRow(engine: SpeechEngine): LinearLayout {
        val selected = settings.selectedEngine() == engine
        val dot = NexusUi.dot(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@SpeechSettingsActivity, 8),
                NexusUi.dp(this@SpeechSettingsActivity, 8),
            ).apply { marginStart = NexusUi.dp(this@SpeechSettingsActivity, 12) }
        }
        NexusUi.setDotColor(dot, if (selected) NexusUi.GREEN else NexusUi.INK4)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.pressed(this@SpeechSettingsActivity, Color.TRANSPARENT, 10)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (settings.selectedEngine() != engine) {
                    settings.selectedEngineId = engine.id
                    refreshUi()
                }
            }
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowTitle(
                            this@SpeechSettingsActivity,
                            engine.displayName.removePrefix(engine.provider.displayName).trim(),
                        ).apply { if (selected) setTextColor(NexusUi.INK) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(dot)
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SpeechSettingsActivity, 4))
            addView(
                NexusUi.cardBody(this@SpeechSettingsActivity, engine.choiceDescription).apply {
                    textSize = 12f
                    maxLines = 2
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@SpeechSettingsActivity, 5))
            addView(
                NexusUi.metaLabel(
                    this@SpeechSettingsActivity,
                    engine.choiceBadges.joinToString("  ·  "),
                    if (selected) NexusUi.GREEN_DIM else NexusUi.INK4,
                ),
                NexusUi.block(),
            )
        }
    }

    // --- Language ---

    private fun languageGrid(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val languages = TranscriptionLanguage.values().toList()
            languages.chunked(3).forEachIndexed { rowIndex, row ->
                addView(
                    LinearLayout(this@SpeechSettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        row.forEachIndexed { index, language ->
                            addView(
                                languageChip(language),
                                LinearLayout.LayoutParams(0, NexusUi.dp(this@SpeechSettingsActivity, 40), 1f).apply {
                                    if (index > 0) marginStart = NexusUi.dp(this@SpeechSettingsActivity, 8)
                                },
                            )
                        }
                        repeat(3 - row.size) {
                            addView(
                                View(this@SpeechSettingsActivity),
                                LinearLayout.LayoutParams(0, 1, 1f).apply {
                                    marginStart = NexusUi.dp(this@SpeechSettingsActivity, 8)
                                },
                            )
                        }
                    },
                    NexusUi.block().apply {
                        if (rowIndex > 0) topMargin = NexusUi.dp(this@SpeechSettingsActivity, 8)
                    },
                )
            }
        }

    private fun languageChip(language: TranscriptionLanguage): TextView =
        TextView(this).apply {
            text = language.label
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            val selected = settings.selectedLanguage() == language
            setTextColor(if (selected) NexusUi.GREEN else NexusUi.INK2)
            background = if (selected) {
                NexusUi.bordered(this@SpeechSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x14), NexusUi.alpha(NexusUi.GREEN, 0x50), 11)
            } else {
                NexusUi.pressedBordered(this@SpeechSettingsActivity, NexusUi.PANEL, 11)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (settings.selectedLanguage() != language) {
                    settings.selectedLanguageId = language.id
                    refreshUi()
                }
            }
        }

    // --- API key ---

    private fun credentialCard(): LinearLayout =
        NexusUi.card(this).apply {
            val kind = shownProvider.credentialKindForUi()
            val hasKey = secrets.hasCredential(kind)

            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowLabel(this@SpeechSettingsActivity, "${shownProvider.displayName} key"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.metaLabel(
                            this@SpeechSettingsActivity,
                            if (hasKey) "Key saved" else "No key",
                            if (hasKey) NexusUi.GREEN_DIM else NexusUi.INK4,
                        ),
                    )
                },
                NexusUi.block(),
            )
            creditsView = null
            if (shownProvider == SpeechProvider.ELEVENLABS && hasKey) {
                addView(BusTheme.gap(this@SpeechSettingsActivity, 6))
                creditsView = NexusUi.metaLabel(this@SpeechSettingsActivity, "Credits —", NexusUi.INK4)
                addView(creditsView, NexusUi.block())
                fetchCredits()
            }
            addView(BusTheme.gap(this@SpeechSettingsActivity, 10))

            val keyField = secretField(
                if (hasKey) "Paste a new key to replace it" else "Paste your ${shownProvider.displayName} API key",
            )
            addView(keyField, NexusUi.block())

            var regionField: EditText? = null
            if (shownProvider == SpeechProvider.AZURE) {
                addView(BusTheme.gap(this@SpeechSettingsActivity, 8))
                regionField = plainField("Region — e.g. westeurope").apply {
                    setText(secrets.azureRegion().orEmpty())
                }
                addView(regionField, NexusUi.block())
            }

            addView(BusTheme.gap(this@SpeechSettingsActivity, 10))
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(this@SpeechSettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    if (hasKey) {
                        addView(
                            NexusUi.textButton(this@SpeechSettingsActivity, "Clear", danger = true).apply {
                                setOnClickListener { onClearCredential() }
                            },
                        )
                        addView(View(this@SpeechSettingsActivity), LinearLayout.LayoutParams(NexusUi.dp(this@SpeechSettingsActivity, 8), 1))
                    }
                    addView(
                        NexusUi.pillButton(this@SpeechSettingsActivity, "Save").apply {
                            minHeight = NexusUi.dp(this@SpeechSettingsActivity, 40)
                            minimumHeight = NexusUi.dp(this@SpeechSettingsActivity, 40)
                            setOnClickListener { onSaveCredential(keyField, regionField) }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun secretField(hintText: String): EditText =
        plainField(hintText).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.MONOSPACE
        }

    private fun plainField(hintText: String): EditText =
        NexusUi.field(this, hintText).apply {
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = NexusUi.bordered(this@SpeechSettingsActivity, NexusUi.BG, NexusUi.LINE, 12)
        }

    private fun onSaveCredential(keyField: EditText, regionField: EditText?) {
        val kind = shownProvider.credentialKindForUi()
        val key = keyField.text?.toString()?.trim().orEmpty()
        val region = regionField?.text?.toString()?.trim().orEmpty()
        var savedAnything = false

        if (key.isNotBlank()) {
            if (!secrets.saveApiKey(kind, key)) {
                toast("Could not save the key on this phone")
                return
            }
            savedAnything = true
        }
        if (regionField != null && region.isNotBlank() && region != secrets.azureRegion().orEmpty()) {
            if (!secrets.saveAzureRegion(region)) {
                toast("Region looks invalid — use the short form, e.g. westeurope")
                return
            }
            savedAnything = true
        }
        if (!savedAnything) {
            toast(if (regionField != null) "Paste a key or region first" else "Paste a key first")
            return
        }
        toast("Saved")
        refreshUi()
    }

    private fun onClearCredential() {
        val kind = shownProvider.credentialKindForUi()
        secrets.clearApiKey(kind)
        if (shownProvider == SpeechProvider.AZURE) secrets.clearAzureRegion()
        toast("Key removed")
        refreshUi()
    }

    /** Async ElevenLabs quota refresh; the generation counter drops stale results. */
    private fun fetchCredits() {
        val generation = ++creditsGeneration
        Thread {
            val key = secrets.apiKey(SpeechCredentialKind.ELEVENLABS)
            val quota = key?.let { SpeechCredits.fetchElevenLabs(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != creditsGeneration) return@runOnUiThread
                val view = creditsView ?: return@runOnUiThread
                if (quota == null) {
                    view.text = "CREDITS UNAVAILABLE"
                    view.setTextColor(NexusUi.INK4)
                } else {
                    val format = java.text.NumberFormat.getIntegerInstance()
                    view.text = "${format.format(quota.remaining)} OF ${format.format(quota.limit)} CREDITS LEFT"
                    view.setTextColor(
                        if (quota.remaining < quota.limit / 10) NexusUi.AMBER else NexusUi.GREEN_DIM,
                    )
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // --- Try it ---

    private fun dictationCard(): LinearLayout =
        NexusUi.card(this).apply {
            testStatusView = NexusUi.statusLine(this@SpeechSettingsActivity)
            testTranscriptView = NexusUi.cardBody(this@SpeechSettingsActivity, "").apply {
                textSize = 15f
                minHeight = NexusUi.dp(this@SpeechSettingsActivity, 64)
            }
            testButton = NexusUi.pillButton(this@SpeechSettingsActivity, "Start dictation").apply {
                setOnClickListener { onDictationButton() }
            }
            addView(testStatusView, NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 10))
            addView(testTranscriptView, NexusUi.block())
            addView(BusTheme.gap(this@SpeechSettingsActivity, 12))
            addView(testButton, NexusUi.block())
        }

    private fun onDictationButton() {
        if (testActive) {
            BusHubService.cancelSpeechDictationTest()
            return
        }
        transcriptText = null
        transcriptFinal = false
        when (val result = BusHubService.startSpeechDictationTest(dictationListener)) {
            SpeechStartResult.OK -> {
                testActive = true
                setStatus("Starting…", NexusUi.INK2)
            }
            SpeechStartResult.BUSY ->
                setStatus("Microphone is busy — another plugin is using it.", NexusUi.AMBER)
            SpeechStartResult.NO_LINK ->
                setStatus("Connect the glasses first — Nexus must be running.", NexusUi.AMBER)
            SpeechStartResult.NOT_READY ->
                setStatus(readinessGuidance(), NexusUi.AMBER)
            SpeechStartResult.START_FAILED ->
                setStatus("Could not start — try again.", NexusUi.AMBER)
            else -> setStatus("Could not start ($result)", NexusUi.AMBER)
        }
        renderDictation()
    }

    private val dictationListener = object : SpeechUtteranceListener {
        override fun onState(state: SpeechSessionState) {
            if (isFinishing || isDestroyed) return
            when (state) {
                SpeechSessionState.LISTENING ->
                    setStatus("Listening — speak toward the glasses…", NexusUi.INK2)
                SpeechSessionState.RECOGNIZING ->
                    setStatus("Hearing you…", NexusUi.INK2)
                SpeechSessionState.PROCESSING ->
                    setStatus("Transcribing…", NexusUi.INK2)
            }
        }

        override fun onPartial(text: String) {
            if (isFinishing || isDestroyed) return
            transcriptText = text
            transcriptFinal = false
            renderDictation()
        }

        override fun onFinal(text: String) {
            if (isFinishing || isDestroyed) return
            transcriptText = text
            transcriptFinal = true
            renderDictation()
        }

        override fun onEnded(reason: SpeechEndReason, error: SttError?) {
            if (isFinishing || isDestroyed) return
            testActive = false
            if (reason == SpeechEndReason.COMPLETED && shownProvider == SpeechProvider.ELEVENLABS) {
                fetchCredits()
            }
            when (reason) {
                SpeechEndReason.COMPLETED -> setStatus("Done.", NexusUi.GREEN_DIM)
                SpeechEndReason.CANCELLED -> setStatus("Stopped.", NexusUi.INK3)
                SpeechEndReason.NO_SPEECH ->
                    setStatus("No speech heard — try again, closer to the glasses.", NexusUi.AMBER)
                SpeechEndReason.LINK_LOST -> setStatus("Glasses link lost.", NexusUi.AMBER)
                SpeechEndReason.ERROR -> setStatus(errorLabel(error), NexusUi.AMBER)
            }
            renderDictation()
        }
    }

    private fun errorLabel(error: SttError?): String {
        val base = error?.detail ?: "Something went wrong — try again."
        val provider = error?.providerLabel
        return if (provider != null) "$provider: $base" else base
    }

    private fun readinessGuidance(): String =
        when (settings.readiness(secrets)) {
            SpeechReadiness.READY -> "Ready."
            SpeechReadiness.NO_ENGINE -> "Pick an engine above to get started."
            SpeechReadiness.MISSING_KEY -> "Add your ${settings.selectedEngine()?.provider?.displayName ?: "provider"} API key above."
            SpeechReadiness.MISSING_REGION -> "Add your Azure region above."
        }

    private fun renderReadiness() {
        val readiness = settings.readiness(secrets)
        readinessValue.text = when (readiness) {
            SpeechReadiness.READY -> "Ready"
            SpeechReadiness.NO_ENGINE -> "Pick engine"
            SpeechReadiness.MISSING_KEY -> "Add key"
            SpeechReadiness.MISSING_REGION -> "Add region"
        }.uppercase()
        readinessValue.setTextColor(
            if (readiness == SpeechReadiness.READY) NexusUi.GREEN_DIM else NexusUi.AMBER,
        )
        if (statusText == null) {
            statusText = readinessGuidance()
            statusColor = if (readiness == SpeechReadiness.READY) NexusUi.INK2 else NexusUi.AMBER
        }
    }

    private fun renderDictation() {
        if (!::testButton.isInitialized) return
        testStatusView.text = statusText.orEmpty()
        testStatusView.setTextColor(statusColor)
        val transcript = transcriptText
        if (transcript.isNullOrBlank()) {
            testTranscriptView.text = "Your words appear here."
            testTranscriptView.setTextColor(NexusUi.INK4)
        } else {
            testTranscriptView.text = transcript
            testTranscriptView.setTextColor(if (transcriptFinal) NexusUi.INK else NexusUi.INK2)
        }
        testButton.text = if (testActive) "Stop" else "Start dictation"
        if (testActive) {
            NexusUi.stylePillAsDanger(this, testButton)
        } else {
            NexusUi.stylePillAsPrimary(this, testButton)
        }
        val ready = settings.readiness(secrets) == SpeechReadiness.READY
        testButton.isEnabled = testActive || ready
        testButton.alpha = if (testButton.isEnabled) 1f else 0.55f
    }

    private fun setStatus(text: String, color: Int) {
        statusText = text
        statusColor = color
        renderDictation()
    }

    private fun stopDictationIfActive() {
        if (testActive) {
            BusHubService.cancelSpeechDictationTest()
            testActive = false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // --- Shell ---

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@SpeechSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@SpeechSettingsActivity, 10),
                        NexusUi.dp(this@SpeechSettingsActivity, 12),
                        NexusUi.dp(this@SpeechSettingsActivity, 22),
                        NexusUi.dp(this@SpeechSettingsActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@SpeechSettingsActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@SpeechSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@SpeechSettingsActivity, 1),
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
            background = NexusUi.pressed(this@SpeechSettingsActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@SpeechSettingsActivity, 44),
                NexusUi.dp(this@SpeechSettingsActivity, 44),
            )
        }
}

private fun SpeechProvider.credentialKindForUi(): SpeechCredentialKind =
    when (this) {
        SpeechProvider.OPENAI -> SpeechCredentialKind.OPENAI
        SpeechProvider.ELEVENLABS -> SpeechCredentialKind.ELEVENLABS
        SpeechProvider.AZURE -> SpeechCredentialKind.AZURE
    }
