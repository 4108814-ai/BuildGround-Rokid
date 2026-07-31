package com.anezium.rokidbus.plugin.relay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.concurrent.CopyOnWriteArrayList

class RelaySettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val settings by lazy { RelaySettings(this) }
    private lateinit var content: LinearLayout
    private var unobserveData: (() -> Unit)? = null
    private var unobserveHarness: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@RelaySettingsActivity,
                    R.drawable.nexus_glyph_relay,
                    "Relay",
                    "Voice replies to phone notifications",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@RelaySettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        unobserveData = observeData { main.post(::render) }
        unobserveHarness = FakeNotificationHarness.observe { main.post(::render) }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        unobserveData?.invoke()
        unobserveData = null
        unobserveHarness?.invoke()
        unobserveHarness = null
        super.onStop()
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()

        content.addView(NexusUi.sectionRow(this, "Access"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(notificationAccessCard(), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                title = "Relay notifications",
                subtitle = "Nothing is forwarded while this is off",
                checked = settings.enabled(),
            ) { enabled ->
                settings.setEnabled(enabled)
                NotificationControl.refreshFromSettings()
            },
            NexusUi.block(),
        )

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Notification handling"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            switchCard(
                "Image previews",
                "Off by default · 512 px / 64 KiB maximum",
                settings.imagePreviewsEnabled(),
            ) { enabled ->
                settings.setImagePreviewsEnabled(enabled)
                NotificationControl.refreshFromSettings()
            },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(messageLimitCard(), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Pause while phone screen is on",
                "The next post or listener refresh resumes capture",
                settings.pauseWhilePhoneScreenOn(),
            ) { enabled -> settings.setPauseWhilePhoneScreenOn(enabled) },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Clear after reply",
                "Requests source-notification removal three times",
                settings.clearAfterReply(),
            ) { enabled -> settings.setClearAfterReply(enabled) },
            NexusUi.block(),
        )

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Fake notification harness"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(harnessCard(), NexusUi.block())

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Plugin"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            NexusUi.uninstallCard(this, "Relay") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            },
            NexusUi.block(),
        )
    }

    private fun notificationAccessCard(): LinearLayout = NexusUi.card(this).apply {
        val granted = hasNotificationAccess()
        addView(NexusUi.cardTitle(this@RelaySettingsActivity, "Notification access"))
        addView(BusTheme.gap(this@RelaySettingsActivity, 5))
        addView(
            NexusUi.cardBody(
                this@RelaySettingsActivity,
                if (granted) "Granted. Relay can inspect repliable notifications."
                else "Required to discover notifications and retain their live reply actions.",
            ),
        )
        addView(BusTheme.gap(this@RelaySettingsActivity, 10))
        addView(
            NexusUi.outlinePillButton(
                this@RelaySettingsActivity,
                if (granted) "Review access" else "Grant access",
            ).apply {
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun switchCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout = NexusUi.card(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@RelaySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@RelaySettingsActivity, title))
                addView(BusTheme.gap(this@RelaySettingsActivity, 4))
                addView(NexusUi.rowSub(this@RelaySettingsActivity, subtitle))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(Switch(this@RelaySettingsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
    }

    private fun messageLimitCard(): LinearLayout = NexusUi.card(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@RelaySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@RelaySettingsActivity, "Messages per thread"))
                addView(BusTheme.gap(this@RelaySettingsActivity, 4))
                addView(NexusUi.rowSub(this@RelaySettingsActivity, "Newest messages survive the 1024-char cap"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(stepButton("−") { changeMessageLimit(-1) })
        addView(NexusUi.rowValue(this@RelaySettingsActivity).apply {
            text = settings.messagesPerThread().toString()
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(NexusUi.dp(this@RelaySettingsActivity, 42), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(stepButton("+") { changeMessageLimit(1) })
    }

    private fun stepButton(label: String, onClick: () -> Unit): Button =
        NexusUi.textButton(this, label).apply { setOnClickListener { onClick() } }

    private fun changeMessageLimit(delta: Int) {
        settings.setMessagesPerThread(settings.messagesPerThread() + delta)
        NotificationControl.refreshFromSettings()
        render()
    }

    private fun harnessCard(): LinearLayout = NexusUi.card(this).apply {
        val snapshot = FakeNotificationHarness.snapshot()
        addView(
            NexusUi.cardBody(
                this@RelaySettingsActivity,
                "Posts a real MessagingStyle notification with a mutable RemoteInput, so the listener " +
                    "captures it exactly as it captures any other app's. No second phone needed.",
            ),
        )
        addView(BusTheme.gap(this@RelaySettingsActivity, 10))
        addView(buttonRow(
            "Post thread" to {
                ensureCanPost() && FakeNotificationHarness.resetAndPost(this@RelaySettingsActivity)
            },
            "Append" to {
                ensureCanPost() && FakeNotificationHarness.appendAndPost(this@RelaySettingsActivity)
            },
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 8))
        addView(buttonRow(
            "Attach image" to {
                settings.setImagePreviewsEnabled(true)
                ensureCanPost() && FakeNotificationHarness.attachImageAndPost(this@RelaySettingsActivity)
            },
            "Open access" to {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 10))
        addView(NexusUi.rowSub(
            this@RelaySettingsActivity,
            buildString {
                append("messages=${snapshot.messageCount} · image=${if (snapshot.imageAttached) "yes" else "no"}")
                val reply = snapshot.deliveredReply
                if (reply != null) append(" · reply received (${reply.length} chars)")
                if (!hasNotificationAccess()) append(" · grant listener access")
            },
        ).apply { maxLines = 3 })
        snapshot.deliveredReply?.let { reply ->
            addView(BusTheme.gap(this@RelaySettingsActivity, 8))
            addView(NexusUi.cardBody(this@RelaySettingsActivity, reply))
        }
    }

    private fun buttonRow(
        first: Pair<String, () -> Unit>,
        second: Pair<String, () -> Unit>,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(harnessButton(first), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(harnessButton(second), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = NexusUi.dp(this@RelaySettingsActivity, 8)
        })
    }

    private fun harnessButton(spec: Pair<String, () -> Unit>): Button =
        NexusUi.outlinePillButton(this, spec.first).apply {
            setOnClickListener {
                val injected = spec.second.invoke().let { true }
                if (!injected) return@setOnClickListener
                main.postDelayed(::render, 100L)
            }
        }

    /**
     * The harness posts a real notification, so on Android 13+ it needs the
     * post grant or the system drops it before the listener is ever called.
     * Asked for here, at the press, rather than at startup: Relay does not
     * notify the wearer of anything, and a permission dialog on first launch
     * for a feature only the owner uses would be a lie about what it wants.
     */
    private fun ensureCanPost(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        return granted
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it.packageName == packageName }
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        private val dataListeners = CopyOnWriteArrayList<() -> Unit>()

        internal fun notifyDataChanged() {
            dataListeners.forEach { listener -> runCatching { listener() } }
        }

        private fun observeData(listener: () -> Unit): () -> Unit {
            dataListeners += listener
            return { dataListeners.remove(listener) }
        }
    }
}
