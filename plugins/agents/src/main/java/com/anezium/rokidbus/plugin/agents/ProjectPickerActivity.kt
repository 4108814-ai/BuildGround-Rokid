package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.window.OnBackInvokedDispatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Walks the linked computer's folders over the daemon link, one listing at a
 * time, until the wearer anchors one as a project. Directories only — the
 * picker never sees a file name.
 */
class ProjectPickerActivity : Activity() {
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var machineId: String
    private lateinit var machineName: String
    private lateinit var breadcrumb: TextView
    private lateinit var hint: TextView
    private lateinit var folderList: LinearLayout
    private lateinit var useFolder: Button

    private var currentPath: String? = null
    private var parentPath: String? = null
    private var pendingRequestId: String? = null
    private var timeout: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        machineId = intent.getStringExtra(ComputerActivity.EXTRA_MACHINE_ID) ?: run { finish(); return }
        machineName = intent.getStringExtra(ComputerActivity.EXTRA_MACHINE_NAME) ?: machineId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Predictive back (default at targetSdk 36) never calls
            // onBackPressed, and back must climb the tree before it may leave.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { navigateBack() }
        }
        buildUi()
        uiScope.launch {
            AgentsRuntime.store.fsListing.collectLatest { listing ->
                if (listing != null && listing.requestId == pendingRequestId) {
                    pendingRequestId = null
                    timeout?.cancel()
                    render(listing)
                }
            }
        }
        val link = AgentsRuntime.store.linkMachine.value
        if (link?.machineId != machineId) {
            showHint(
                "$machineName is not connected right now. The folders live there, " +
                    "so browsing needs the link up.",
            )
        } else {
            request(path = null)
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        navigateBack()
    }

    private fun navigateBack() {
        when (currentPath) {
            null -> finish()
            else -> request(parentPath)
        }
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        breadcrumb = NexusUi.statusLine(this).apply { text = "…" }
        hint = NexusUi.rowSub(this, "").apply { visibility = View.GONE }
        folderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        useFolder = NexusUi.pillButton(this, "Use this folder as a project").apply {
            visibility = View.GONE
            setOnClickListener { anchorCurrentFolder() }
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(breadcrumb, NexusUi.block())
            addView(BusTheme.gap(this@ProjectPickerActivity, 10))
            addView(useFolder, NexusUi.block())
            addView(BusTheme.gap(this@ProjectPickerActivity, 10))
            addView(hint, NexusUi.block())
            addView(
                NexusUi.card(this@ProjectPickerActivity).apply {
                    addView(folderList, NexusUi.block())
                },
                NexusUi.block(),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(backHeader(), NexusUi.block())
            addView(
                NexusUi.screen(this@ProjectPickerActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun backHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = NexusUi.dp(this@ProjectPickerActivity, 16)
        setPadding(pad, pad, pad, NexusUi.dp(this@ProjectPickerActivity, 8))
        addView(
            TextView(this@ProjectPickerActivity).apply {
                text = "‹"
                setTextColor(NexusUi.GREEN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, NexusUi.dp(this@ProjectPickerActivity, 14), NexusUi.dp(this@ProjectPickerActivity, 2))
                setOnClickListener { navigateBack() }
            },
        )
        addView(
            LinearLayout(this@ProjectPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(this@ProjectPickerActivity).apply {
                        text = "Pick a project folder"
                        setTextColor(NexusUi.INK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                )
                addView(NexusUi.rowSub(this@ProjectPickerActivity, "on $machineName"))
            },
        )
    }

    private fun request(path: String?) {
        val requestId = UUID.randomUUID().toString()
        pendingRequestId = requestId
        breadcrumb.text = "LOADING…"
        hint.visibility = View.GONE
        AgentsMonitorService.requestFolders(applicationContext, requestId, path)
        timeout?.cancel()
        timeout = uiScope.launch {
            delay(REQUEST_TIMEOUT_MS)
            if (pendingRequestId == requestId) {
                pendingRequestId = null
                showHint("$machineName did not answer. Check that it is connected and try again.")
            }
        }
    }

    private fun render(listing: FsListing) {
        currentPath = listing.path
        parentPath = listing.parent
        breadcrumb.text = listing.path ?: "THIS COMPUTER"
        useFolder.visibility = if (listing.path != null) View.VISIBLE else View.GONE
        folderList.removeAllViews()
        if (listing.error != null) {
            showHint(listing.error)
            return
        }
        hint.visibility = View.GONE
        if (listing.entries.isEmpty()) {
            folderList.addView(NexusUi.rowSub(this, "No folders in here."), NexusUi.block())
            return
        }
        listing.entries.forEachIndexed { index, entry ->
            if (index > 0) folderList.addView(BusTheme.gap(this, 2))
            folderList.addView(folderRow(entry), NexusUi.block())
        }
        if (listing.truncated) {
            folderList.addView(BusTheme.gap(this, 6))
            folderList.addView(
                NexusUi.rowSub(this, "Only the first ${listing.entries.size} folders are shown."),
                NexusUi.block(),
            )
        }
    }

    private fun folderRow(entry: FsEntry) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, NexusUi.dp(this@ProjectPickerActivity, 8), 0, NexusUi.dp(this@ProjectPickerActivity, 8))
        setOnClickListener { request(entry.path) }
        addView(
            NexusUi.rowTitle(this@ProjectPickerActivity, entry.name),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.chevron(this@ProjectPickerActivity))
    }

    private fun anchorCurrentFolder() {
        val path = currentPath ?: return
        val name = path.trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { path }
        configStore.addProject(machineId, AgentProject(name = name, path = path))
        Toast.makeText(this, "$name anchored as a project.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showHint(message: String) {
        breadcrumb.text = currentPath ?: "THIS COMPUTER"
        hint.text = message
        hint.visibility = View.VISIBLE
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
