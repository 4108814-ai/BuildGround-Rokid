package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * Settings for the voice assistant: connect an account, pick the model, remove the plugin.
 *
 * The account block is the whole screen's centre of gravity — it is the only thing
 * standing between the assist button and an answer — so it renders one of three
 * states (nothing connected, ChatGPT account, pasted API key) plus an amber warning
 * for unexpected account connection failures.
 */
class AssistantSettingsActivity : Activity() {
    private val authStore by lazy { CodexAuthStore(applicationContext) }

    private lateinit var accountCard: LinearLayout
    private lateinit var accountActions: LinearLayout
    private lateinit var modelSection: LinearLayout
    private lateinit var reasoningSection: LinearLayout
    private lateinit var apiKeyField: EditText
    private lateinit var planModelSection: LinearLayout
    private lateinit var windowSection: LinearLayout
    private lateinit var memoryField: EditText
    private lateinit var memoryStatus: TextView
    private lateinit var conversationsSlot: LinearLayout
    private val modelDots = mutableMapOf<String, View>()
    private val modelNames = mutableMapOf<String, TextView>()
    private val reasoningDots = mutableMapOf<String, View>()
    private val reasoningNames = mutableMapOf<String, TextView>()
    private val planModelDots = mutableMapOf<String, View>()
    private val planModelNames = mutableMapOf<String, TextView>()
    private val keepDots = mutableMapOf<Boolean, View>()
    private val keepNames = mutableMapOf<Boolean, TextView>()
    private val photosDots = mutableMapOf<Boolean, View>()
    private val photosNames = mutableMapOf<Boolean, TextView>()
    private val windowDots = mutableMapOf<Int, View>()
    private val windowNames = mutableMapOf<Int, TextView>()

    private val threadStore by lazy { AssistantThreadStore(applicationContext) }

    private enum class AccountMode { EMPTY, CHATGPT, API_KEY }

    private data class AccountState(
        val mode: AccountMode,
        val title: String,
        val detail: String,
        val statusLabel: String?,
        val statusColor: Int,
        val ready: Boolean,
        val warning: String?,
    )

    private data class ModelChoice(val id: String, val title: String, val caption: String)
    private data class ReasoningChoice(val id: String, val title: String, val hint: String? = null)

    private val modelChoices = listOf(
        ModelChoice(OpenAiApiClient.DEFAULT_MODEL_ID, "GPT-4o mini", "Faster, and cheaper to run"),
        ModelChoice(MODEL_SMARTER, "GPT-4o", "Smarter, costs more per question"),
    )
    /**
     * The GPT-5.6 family, ordered the way a wearer picks: fastest first, because the
     * glasses are a voice surface and most questions are quick ones. Captions follow
     * how OpenAI positions the tiers -- speed and depth, not price, since a ChatGPT
     * plan is not billed per token.
     */
    private val planModelChoices = listOf(
        ModelChoice(
            ChatGptCodexApiClient.FAST_MODEL_ID,
            "Luna",
            "Fastest. Best for quick questions",
        ),
        ModelChoice(
            ChatGptCodexApiClient.BALANCED_MODEL_ID,
            "Terra",
            "Balanced. Good for most things",
        ),
        ModelChoice(
            ChatGptCodexApiClient.DEEP_MODEL_ID,
            "Sol",
            "Deepest reasoning. Slower to answer",
        ),
    )
    private val reasoningChoices = listOf(
        ReasoningChoice("none", "None", "fastest"),
        ReasoningChoice("low", "Low"),
        ReasoningChoice("medium", "Medium"),
        ReasoningChoice("high", "High"),
        ReasoningChoice("xhigh", "X-High", "deepest"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // The ChatGPT flow leaves for the browser and comes back through its own
        // activity, so the account state is only ever trustworthy on resume.
        renderAccount()
        renderModelSelection()
        renderReasoningSelection()
        renderPlanModelSelection()
        renderConversationSettings()
        renderMemory()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        accountCard = NexusUi.card(this)
        accountActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantSettingsActivity,
                    "Hold the assist button on your glasses and ask out loud. The answer " +
                        "streams onto the HUD, riding your ChatGPT plan or your own " +
                        "OpenAI API key -- whichever is connected below.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Account"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(accountCard, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(accountActions, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Or use an API key"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(apiKeyCard(), NexusUi.block())
            modelSection = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(BusTheme.gap(this@AssistantSettingsActivity, 22))
                addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Model"), NexusUi.block())
                addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
                addView(modelCard(), NexusUi.block())
            }
            addView(modelSection, NexusUi.block())
            planModelSection = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(BusTheme.gap(this@AssistantSettingsActivity, 22))
                addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Model"), NexusUi.block())
                addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
                addView(planModelCard(), NexusUi.block())
            }
            addView(planModelSection, NexusUi.block())
            reasoningSection = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(BusTheme.gap(this@AssistantSettingsActivity, 22))
                addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Reasoning"), NexusUi.block())
                addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
                addView(reasoningCard(), NexusUi.block())
            }
            addView(reasoningSection, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Conversation"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(keepCard(), NexusUi.block())
            windowSection = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
                addView(
                    NexusUi.sectionRow(
                        this@AssistantSettingsActivity,
                        "Start fresh after",
                    ),
                    NexusUi.block(),
                )
                addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
                addView(windowCard(), NexusUi.block())
            }
            addView(windowSection, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 18))
            addView(
                NexusUi.sectionRow(this@AssistantSettingsActivity, "Photos"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(photosCard(), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            conversationsSlot = LinearLayout(this@AssistantSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(conversationsSlot, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Memory"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(memoryCard(), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))
            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            addView(uninstallCard(), NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@AssistantSettingsActivity,
                    NexusPluginIcons.drawableFor("chat"),
                    "Assistant",
                    "Voice assistant · v${pluginVersionName()}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@AssistantSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderAccount()
        renderModelSelection()
        renderReasoningSelection()
        renderPlanModelSelection()
        renderConversationSettings()
        renderMemory()
    }

    private fun accountState(): AccountState {
        val ready = authStore.hasUsableAuth()
        val warning = if (authStore.isConsumerChatGptAccount()) {
            null
        } else {
            authStore.apiKeyExchangeError()
        }
        val label = authStore.accountLabel()
        val mode = when (authStore.authMode()) {
            CodexAuthStore.AUTH_MODE_CHATGPT -> AccountMode.CHATGPT
            CodexAuthStore.AUTH_MODE_API_KEY -> AccountMode.API_KEY
            else -> if (ready) AccountMode.API_KEY else AccountMode.EMPTY
        }
        return when (mode) {
            AccountMode.EMPTY -> AccountState(
                mode = mode,
                title = "Not connected",
                detail = "Sign in to start asking questions",
                statusLabel = null,
                statusColor = NexusUi.INK3,
                ready = false,
                warning = null,
            )
            AccountMode.CHATGPT -> AccountState(
                mode = mode,
                title = label ?: "ChatGPT account",
                detail = chatGptDetail(),
                statusLabel = if (ready) "Connected" else "Action needed",
                statusColor = if (ready) NexusUi.GREEN else NexusUi.AMBER,
                ready = ready,
                // A stored exchange error goes quiet once a usable key exists: the
                // failure was a later refresh, and the assistant still answers.
                warning = if (ready) {
                    null
                } else {
                    warning ?: "This account has not returned an OpenAI API key yet."
                },
            )
            AccountMode.API_KEY -> AccountState(
                mode = mode,
                title = label ?: "OpenAI API key",
                detail = if (ready) "Key stored on this phone" else "Key could not be read",
                statusLabel = if (ready) "Connected" else "Action needed",
                statusColor = if (ready) NexusUi.GREEN else NexusUi.AMBER,
                ready = ready,
                warning = if (ready) null else "Paste your OpenAI API key again to reconnect.",
            )
        }
    }

    private fun chatGptDetail(): String {
        val plan = authStore.oauthTokens()?.planType?.trim()?.takeIf(String::isNotEmpty)
        val planLabel = plan?.replaceFirstChar { it.uppercaseChar() }
        return if (planLabel == null) "Signed in with ChatGPT" else "Signed in with ChatGPT · $planLabel"
    }

    private fun renderAccount() {
        val state = accountState()

        accountCard.removeAllViews()
        accountCard.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.rowTitle(this@AssistantSettingsActivity, state.title),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)
                    },
                )
                state.statusLabel?.let { label ->
                    addView(NexusUi.metaLabel(this@AssistantSettingsActivity, label, state.statusColor))
                }
            },
            NexusUi.block(),
        )
        accountCard.addView(BusTheme.gap(this, 5))
        accountCard.addView(NexusUi.rowSub(this, state.detail), NexusUi.block())
        state.warning?.let { message ->
            accountCard.addView(BusTheme.gap(this, 12))
            accountCard.addView(warningRow(message), NexusUi.block())
        }

        accountActions.removeAllViews()
        val signInLabel = if (state.mode == AccountMode.CHATGPT && state.ready) {
            "Sign in again"
        } else {
            "Sign in with ChatGPT"
        }
        // Solid accent only while the assistant cannot answer; once it works the
        // sign-in path steps back to an outline so nothing shouts for no reason.
        val signInButton = if (state.ready) {
            NexusUi.outlinePillButton(this, signInLabel)
        } else {
            NexusUi.pillButton(this, signInLabel)
        }
        accountActions.addView(
            signInButton.apply { setOnClickListener { startSignIn() } },
            NexusUi.block(),
        )
        if (state.mode != AccountMode.EMPTY) {
            accountActions.addView(BusTheme.gap(this, 10))
            accountActions.addView(
                NexusUi.pillButton(this, "Disconnect", danger = true).apply {
                    setOnClickListener { confirmDisconnect() }
                },
                NexusUi.block(),
            )
        }
        // Each path has its own model list: the GPT-4o picker steers an API key, the
        // GPT-5.6 family steers a ChatGPT plan. Only one can be true at a time.
        val onPlan = state.mode == AccountMode.CHATGPT
        modelSection.visibility = if (onPlan) View.GONE else View.VISIBLE
        planModelSection.visibility = if (onPlan) View.VISIBLE else View.GONE
        reasoningSection.visibility = if (onPlan) View.VISIBLE else View.GONE
    }

    private fun warningRow(message: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantSettingsActivity,
                NexusUi.alpha(NexusUi.AMBER, 0x10),
                NexusUi.alpha(NexusUi.AMBER, 0x38),
                12,
            )
            setPadding(
                NexusUi.dp(this@AssistantSettingsActivity, 12),
                NexusUi.dp(this@AssistantSettingsActivity, 10),
                NexusUi.dp(this@AssistantSettingsActivity, 12),
                NexusUi.dp(this@AssistantSettingsActivity, 10),
            )
            addView(
                NexusUi.metaLabel(this@AssistantSettingsActivity, "No API key", NexusUi.AMBER),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))
            addView(
                NexusUi.cardBody(this@AssistantSettingsActivity, message.trim()).apply {
                    textSize = 12f
                },
                NexusUi.block(),
            )
        }

    private fun startSignIn() {
        startActivity(CodexChatGptSignInActivity.createIntent(this))
    }

    private fun apiKeyCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowLabel(this@AssistantSettingsActivity, "OpenAI API key"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(NexusUi.rowSub(this@AssistantSettingsActivity, "platform.openai.com"))
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))

            apiKeyField = keyField()
            var revealed = false
            val revealButton = NexusUi.textButton(this@AssistantSettingsActivity, "Show").apply {
                contentDescription = "Show OpenAI API key"
                setOnClickListener {
                    revealed = !revealed
                    apiKeyField.transformationMethod = if (revealed) {
                        null
                    } else {
                        PasswordTransformationMethod.getInstance()
                    }
                    apiKeyField.setSelection(apiKeyField.text.length)
                    text = if (revealed) "Hide" else "Show"
                    contentDescription = if (revealed) "Hide OpenAI API key" else "Show OpenAI API key"
                }
            }
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        apiKeyField,
                        LinearLayout.LayoutParams(0, NexusUi.dp(this@AssistantSettingsActivity, 52), 1f),
                    )
                    addView(
                        revealButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = NexusUi.dp(this@AssistantSettingsActivity, 6) },
                    )
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 4))
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowSub(
                            this@AssistantSettingsActivity,
                            "Stays encrypted on this phone",
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Save").apply {
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setOnClickListener { saveApiKey() }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun keyField(): EditText =
        NexusUi.field(this, "Paste your key").apply {
            // Only mirror back a key the owner pasted here; an OAuth-issued key is
            // never surfaced as if it were something they typed.
            if (authStore.authMode() == CodexAuthStore.AUTH_MODE_API_KEY) {
                setText(authStore.apiKey().orEmpty())
            }
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            // Sink the field below the card so it reads as an input, not a flat row.
            background = NexusUi.bordered(this@AssistantSettingsActivity, NexusUi.BG, NexusUi.LINE, 12)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveApiKey()
                    true
                } else {
                    false
                }
            }
        }

    private fun saveApiKey() {
        val key = apiKeyField.text.toString().trim()
        if (key.isEmpty()) {
            toast("Paste an OpenAI API key first.")
            return
        }
        // Keep whichever model is selected; saveApiKey() otherwise resets it.
        val saved = runCatching { authStore.saveApiKey(key, authStore.model()) }.isSuccess
        if (!saved) {
            toast("That key could not be saved.")
            return
        }
        hideKeyboard()
        renderAccount()
        renderModelSelection()
        toast("API key saved.")
    }

    private fun modelCard(): LinearLayout =
        NexusUi.card(this).apply {
            modelChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(modelRow(choice), NexusUi.block())
            }
        }

    private fun modelRow(choice: ModelChoice): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Use ${choice.title}"
            background = NexusUi.pressed(this@AssistantSettingsActivity, Color.TRANSPARENT, 10)
            setPadding(
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
            )
            setOnClickListener { selectModel(choice.id) }
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        NexusUi.rowLabel(this@AssistantSettingsActivity, choice.title).also {
                            modelNames[choice.id] = it
                        },
                        NexusUi.block(),
                    )
                    addView(NexusUi.rowSub(this@AssistantSettingsActivity, choice.caption), NexusUi.block())
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.dot(this@AssistantSettingsActivity).also { modelDots[choice.id] = it },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                ),
            )
        }

    private fun selectModel(model: String) {
        authStore.setModel(model)
        renderModelSelection()
    }

    private fun renderModelSelection() {
        val selected = authStore.model()
        modelDots.forEach { (model, dotView) ->
            NexusUi.setDotColor(dotView, if (model == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        modelNames.forEach { (model, nameView) ->
            nameView.setTextColor(if (model == selected) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun reasoningCard(): LinearLayout =
        NexusUi.card(this).apply {
            reasoningChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(reasoningRow(choice), NexusUi.block())
            }
        }

    private fun reasoningRow(choice: ReasoningChoice): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Use ${choice.title} reasoning"
            background = NexusUi.pressed(this@AssistantSettingsActivity, Color.TRANSPARENT, 10)
            setPadding(
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
            )
            setOnClickListener { selectReasoningEffort(choice.id) }
            addView(
                NexusUi.rowLabel(this@AssistantSettingsActivity, choice.title).also {
                    reasoningNames[choice.id] = it
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            choice.hint?.let { hint ->
                addView(
                    NexusUi.rowSub(this@AssistantSettingsActivity, hint),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)
                    },
                )
            }
            addView(
                NexusUi.dot(this@AssistantSettingsActivity).also { reasoningDots[choice.id] = it },
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                ),
            )
        }

    private fun selectReasoningEffort(effort: String) {
        authStore.setChatGptReasoningEffort(effort)
        renderReasoningSelection()
    }

    private fun renderReasoningSelection() {
        val selected = authStore.chatGptReasoningEffort()
        reasoningDots.forEach { (effort, dotView) ->
            NexusUi.setDotColor(dotView, if (effort == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        reasoningNames.forEach { (effort, nameView) ->
            nameView.setTextColor(if (effort == selected) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun planModelCard(): LinearLayout =
        NexusUi.card(this).apply {
            planModelChoices.forEachIndexed { index, choice ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(
                    pickerRow(
                        title = choice.title,
                        caption = choice.caption,
                        description = "Use ${choice.title}",
                        onClick = {
                            authStore.setChatGptModel(choice.id)
                            renderPlanModelSelection()
                        },
                        nameSink = { planModelNames[choice.id] = it },
                        dotSink = { planModelDots[choice.id] = it },
                    ),
                    NexusUi.block(),
                )
            }
        }

    private fun renderPlanModelSelection() {
        val selected = authStore.chatGptModel()
        planModelDots.forEach { (id, dotView) ->
            NexusUi.setDotColor(dotView, if (id == selected) NexusUi.GREEN else NexusUi.INK4)
        }
        planModelNames.forEach { (id, nameView) ->
            nameView.setTextColor(if (id == selected) NexusUi.INK else NexusUi.INK2)
        }
    }

    private fun keepCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(keepRow(true, "Continue", "follow-ups keep context"), NexusUi.block())
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(keepRow(false, "Always fresh", "every question starts over"), NexusUi.block())
        }

    private fun keepRow(
        keep: Boolean,
        title: String,
        hint: String,
    ): LinearLayout =
        pickerRow(
            title = title,
            hint = hint,
            description = if (keep) "Continue the conversation" else "Always start fresh",
            onClick = {
                authStore.setKeepConversation(keep)
                renderConversationSettings()
            },
            nameSink = { keepNames[keep] = it },
            dotSink = { keepDots[keep] = it },
        )

    private fun photosCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                photosRow(true, "Keep photos", "the assistant can look again"),
                NexusUi.block(),
            )
            addView(NexusUi.divider(this@AssistantSettingsActivity))
            addView(
                photosRow(false, "Text only", "a turn is just marked as a photo"),
                NexusUi.block(),
            )
        }

    private fun photosRow(
        keep: Boolean,
        title: String,
        hint: String,
    ): LinearLayout =
        pickerRow(
            title = title,
            hint = hint,
            description = if (keep) "Keep photos in conversations" else "Do not keep photos",
            onClick = {
                authStore.setKeepPhotosInConversations(keep)
                // Turning this off is a promise the wearer expects kept now, not
                // at the next question: erase what is already on disk.
                if (!keep) {
                    Thread { runCatching { threadStore.deleteAllPhotos() } }.start()
                }
                renderConversationSettings()
            },
            nameSink = { photosNames[keep] = it },
            dotSink = { photosDots[keep] = it },
        )

    private fun windowCard(): LinearLayout =
        NexusUi.card(this).apply {
            CodexAuthStore.SUPPORTED_IDLE_WINDOW_MINUTES.forEachIndexed { index, minutes ->
                if (index > 0) addView(NexusUi.divider(this@AssistantSettingsActivity))
                addView(windowRow(minutes), NexusUi.block())
            }
        }

    private fun windowRow(minutes: Int): LinearLayout =
        pickerRow(
            title = "$minutes minutes",
            hint = if (minutes == CodexAuthStore.DEFAULT_IDLE_WINDOW_MINUTES) "default" else null,
            description = "Start fresh after $minutes minutes",
            onClick = {
                authStore.setConversationIdleWindowMinutes(minutes)
                renderConversationSettings()
            },
            nameSink = { windowNames[minutes] = it },
            dotSink = { windowDots[minutes] = it },
        )

    /**
     * The one-of-N row shared by every picker on this screen: label, optional hint, and
     * the dot that carries the selection. Selection colouring is applied by the render
     * pass, not here, so a row never has to know what else is on screen.
     */
    private fun pickerRow(
        title: String,
        hint: String? = null,
        caption: String? = null,
        description: String,
        onClick: () -> Unit,
        nameSink: (TextView) -> Unit,
        dotSink: (View) -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = description
            background = NexusUi.pressed(this@AssistantSettingsActivity, Color.TRANSPARENT, 10)
            setPadding(
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
                0,
                NexusUi.dp(this@AssistantSettingsActivity, 4),
            )
            setOnClickListener { onClick() }
            addView(
                if (caption == null) {
                    NexusUi.rowLabel(this@AssistantSettingsActivity, title).also(nameSink)
                } else {
                    LinearLayout(this@AssistantSettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            NexusUi.rowLabel(this@AssistantSettingsActivity, title).also(nameSink),
                            NexusUi.block(),
                        )
                        addView(
                            NexusUi.rowSub(this@AssistantSettingsActivity, caption),
                            NexusUi.block(),
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            hint?.let {
                addView(
                    NexusUi.rowSub(this@AssistantSettingsActivity, it),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)
                    },
                )
            }
            addView(
                NexusUi.dot(this@AssistantSettingsActivity).also(dotSink),
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                    NexusUi.dp(this@AssistantSettingsActivity, 8),
                ),
            )
        }

    private fun renderConversationSettings() {
        val keep = authStore.keepConversation()
        keepDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == keep) NexusUi.GREEN else NexusUi.INK4)
        }
        keepNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == keep) NexusUi.INK else NexusUi.INK2)
        }

        val window = authStore.conversationIdleWindowMinutes()
        windowDots.forEach { (minutes, dotView) ->
            NexusUi.setDotColor(dotView, if (minutes == window) NexusUi.GREEN else NexusUi.INK4)
        }
        windowNames.forEach { (minutes, nameView) ->
            nameView.setTextColor(if (minutes == window) NexusUi.INK else NexusUi.INK2)
        }
        windowSection.visibility = if (keep) View.VISIBLE else View.GONE

        val keepPhotos = authStore.keepPhotosInConversations()
        photosDots.forEach { (value, dotView) ->
            NexusUi.setDotColor(dotView, if (value == keepPhotos) NexusUi.GREEN else NexusUi.INK4)
        }
        photosNames.forEach { (value, nameView) ->
            nameView.setTextColor(if (value == keepPhotos) NexusUi.INK else NexusUi.INK2)
        }

        renderConversationsCard()
    }

    private fun renderConversationsCard() {
        // The store is a file read; keep it off the frame that is drawing the screen.
        Thread {
            val count = runCatching { threadStore.threads().size }.getOrDefault(0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                conversationsSlot.removeAllViews()
                conversationsSlot.addView(
                    NexusUi.navCard(
                        this,
                        "Conversations",
                        conversationsSubtitle(count),
                    ) {
                        startActivity(
                            Intent(this, AssistantConversationsActivity::class.java),
                        )
                    },
                    NexusUi.block(),
                )
            }
        }.start()
    }

    private fun conversationsSubtitle(count: Int): String = when (count) {
        0 -> "Nothing saved yet"
        1 -> "1 saved on this phone"
        else -> "$count saved on this phone"
    }

    private fun memoryCard(): LinearLayout =
        NexusUi.card(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantSettingsActivity,
                    "ChatGPT has no memory export. Open ChatGPT, go to Settings -> " +
                        "Personalization -> Manage memories, copy the list, and paste it " +
                        "here. It is added to every question you ask.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            memoryField = NexusUi.field(
                this@AssistantSettingsActivity,
                "Paste what ChatGPT knows about you",
            ).apply {
                setSingleLine(false)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_NONE
                gravity = Gravity.TOP or Gravity.START
                minHeight = NexusUi.dp(this@AssistantSettingsActivity, 132)
                setPadding(
                    NexusUi.dp(this@AssistantSettingsActivity, 16),
                    NexusUi.dp(this@AssistantSettingsActivity, 14),
                    NexusUi.dp(this@AssistantSettingsActivity, 16),
                    NexusUi.dp(this@AssistantSettingsActivity, 14),
                )
            }
            addView(memoryField, NexusUi.block())
            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))
            memoryStatus = NexusUi.rowSub(this@AssistantSettingsActivity, "")
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        memoryStatus,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Save").apply {
                            setOnClickListener { saveMemory() }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun saveMemory() {
        val pasted = memoryField.text?.toString().orEmpty()
        authStore.setAssistantMemory(pasted)
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(memoryField.windowToken, 0)
        memoryField.clearFocus()
        renderMemory()
        toast(if (authStore.assistantMemory().isBlank()) "Memory cleared." else "Memory saved.")
    }

    private fun renderMemory() {
        val stored = authStore.assistantMemory()
        if (memoryField.text?.toString().orEmpty() != stored) {
            memoryField.setText(stored)
        }
        memoryStatus.text = when {
            stored.isBlank() -> "Nothing pasted yet"
            stored.length >= CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS ->
                "Saved, trimmed to ${CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS} characters"
            else -> "Saved, ${stored.length} characters"
        }
    }

    private fun confirmDisconnect() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantSettingsActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantSettingsActivity, 18),
                NexusUi.dp(this@AssistantSettingsActivity, 18),
                NexusUi.dp(this@AssistantSettingsActivity, 18),
                NexusUi.dp(this@AssistantSettingsActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantSettingsActivity, "Disconnect account"))
            addView(BusTheme.gap(this@AssistantSettingsActivity, 6))
            addView(
                NexusUi.cardBody(
                    this@AssistantSettingsActivity,
                    "The account and any saved key are removed from this phone. " +
                        "The assistant stops answering until you connect again.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantSettingsActivity, 14))
            addView(
                LinearLayout(this@AssistantSettingsActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Cancel").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(this@AssistantSettingsActivity, "Disconnect", danger = true).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                disconnect()
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun disconnect() {
        authStore.clear()
        apiKeyField.setText("")
        renderAccount()
        renderModelSelection()
        renderReasoningSelection()
        toast("Disconnected.")
    }

    private fun uninstallCard(): LinearLayout =
        NexusUi.uninstallCard(this, "Assistant") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }

    private fun pluginVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "1.0.0" }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(apiKeyField.windowToken, 0)
        apiKeyField.clearFocus()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MODEL_SMARTER = "gpt-4o"
    }
}
