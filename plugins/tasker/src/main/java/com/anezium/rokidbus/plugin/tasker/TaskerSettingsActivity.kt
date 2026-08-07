package com.anezium.rokidbus.plugin.tasker

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TaskerSettingsActivity : Activity() {
    private val repository by lazy { TaskerRepository(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    private lateinit var installedValue: TextView
    private lateinit var enabledValue: TextView
    private lateinit var externalAccessValue: TextView
    private lateinit var permissionValue: TextView
    private lateinit var taskCountValue: TextView
    private lateinit var statusMessage: TextView
    private lateinit var setupSection: LinearLayout
    private lateinit var permissionAction: LinearLayout
    private lateinit var externalAccessHint: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUN_TASKS) refreshStatus()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        installedValue = NexusUi.rowValue(this)
        enabledValue = NexusUi.rowValue(this)
        externalAccessValue = NexusUi.rowValue(this)
        permissionValue = NexusUi.rowValue(this)
        taskCountValue = NexusUi.rowValue(this)
        statusMessage = NexusUi.statusLine(this)
        permissionAction = permissionActionCard()
        externalAccessHint = externalAccessHintCard()
        setupSection = setupSection()

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@TaskerSettingsActivity,
                    "Run the Tasker tasks on this phone from the glasses HUD.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@TaskerSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@TaskerSettingsActivity, "Tasker status"), NexusUi.block())
            addView(BusTheme.gap(this@TaskerSettingsActivity, 10))
            addView(statusCard(), NexusUi.block())
            addView(setupSection, NexusUi.block())
            addView(BusTheme.gap(this@TaskerSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@TaskerSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@TaskerSettingsActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@TaskerSettingsActivity,
                    NexusPluginIcons.drawableFor("bolt"),
                    "Tasker",
                    "Task automation on your glasses · v1.0",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@TaskerSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderLoading()
    }

    private fun statusCard(): LinearLayout = NexusUi.card(this).apply {
        addView(statusRow("Installed", installedValue), NexusUi.block())
        addView(NexusUi.divider(this@TaskerSettingsActivity))
        addView(statusRow("Enabled", enabledValue), NexusUi.block())
        addView(NexusUi.divider(this@TaskerSettingsActivity))
        addView(statusRow("External access", externalAccessValue), NexusUi.block())
        addView(NexusUi.divider(this@TaskerSettingsActivity))
        addView(statusRow("Run permission", permissionValue), NexusUi.block())
        addView(NexusUi.divider(this@TaskerSettingsActivity))
        addView(statusRow("Tasks", taskCountValue), NexusUi.block())
        addView(BusTheme.gap(this@TaskerSettingsActivity, 10))
        addView(statusMessage, NexusUi.block())
    }

    private fun statusRow(label: String, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                NexusUi.rowLabel(this@TaskerSettingsActivity, label),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(value)
        }

    private fun setupSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(BusTheme.gap(this@TaskerSettingsActivity, 22))
        addView(NexusUi.sectionRow(this@TaskerSettingsActivity, "Setup"), NexusUi.block())
        addView(BusTheme.gap(this@TaskerSettingsActivity, 10))
        addView(permissionAction, NexusUi.block())
        addView(BusTheme.gap(this@TaskerSettingsActivity, 8))
        addView(externalAccessHint, NexusUi.block())
    }

    private fun permissionActionCard(): LinearLayout = NexusUi.pressableCard(this).apply {
        val request = { requestRunTaskPermission() }
        addView(
            LinearLayout(this@TaskerSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@TaskerSettingsActivity, "Grant run permission"))
                addView(BusTheme.gap(this@TaskerSettingsActivity, 4))
                addView(
                    NexusUi.rowSub(
                        this@TaskerSettingsActivity,
                        "Required before Tasker accepts task broadcasts",
                    ),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            NexusUi.textButton(this@TaskerSettingsActivity, "Grant").apply {
                setOnClickListener { request() }
            },
        )
        setOnClickListener { request() }
    }

    private fun externalAccessHintCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.rowTitle(this@TaskerSettingsActivity, "Allow External Access"))
        addView(BusTheme.gap(this@TaskerSettingsActivity, 4))
        addView(
            NexusUi.rowSub(
                this@TaskerSettingsActivity,
                "Enable it inside Tasker preferences, then return here.",
            ),
        )
    }

    private fun refreshStatus() {
        refreshJob?.cancel()
        renderLoading()
        refreshJob = scope.launch {
            val snapshot = try {
                repository.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TaskerSnapshot(
                    installed = false,
                    enabled = false,
                    externalAccess = false,
                    runPermissionGranted = false,
                    tasks = emptyList(),
                    message = "Could not read Tasker status.",
                )
            }
            renderSnapshot(snapshot)
        }
    }

    private fun renderLoading() {
        listOf(installedValue, enabledValue, externalAccessValue, permissionValue, taskCountValue).forEach {
            it.text = "Checking..."
            it.setTextColor(NexusUi.INK3)
        }
        statusMessage.text = "Reading Tasker settings and tasks..."
        setupSection.visibility = View.GONE
    }

    private fun renderSnapshot(snapshot: TaskerSnapshot) {
        renderFlag(installedValue, snapshot.installed)
        renderFlag(enabledValue, snapshot.enabled)
        renderFlag(externalAccessValue, snapshot.externalAccess)
        renderFlag(permissionValue, snapshot.runPermissionGranted)
        taskCountValue.text = snapshot.tasks.size.toString()
        taskCountValue.setTextColor(NexusUi.INK2)
        statusMessage.text = snapshot.message

        permissionAction.visibility = if (snapshot.runPermissionGranted) View.GONE else View.VISIBLE
        externalAccessHint.visibility =
            if (snapshot.installed && !snapshot.externalAccess) View.VISIBLE else View.GONE
        setupSection.visibility =
            if (permissionAction.visibility == View.VISIBLE || externalAccessHint.visibility == View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun renderFlag(view: TextView, value: Boolean) {
        view.text = if (value) "Yes" else "No"
        view.setTextColor(if (value) NexusUi.GREEN_DIM else NexusUi.AMBER)
    }

    private fun requestRunTaskPermission() {
        if (checkSelfPermission(TaskerRepository.PERMISSION_RUN_TASKS) == PackageManager.PERMISSION_GRANTED) {
            refreshStatus()
            return
        }
        requestPermissions(arrayOf(TaskerRepository.PERMISSION_RUN_TASKS), REQUEST_RUN_TASKS)
    }

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Tasker") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    private companion object {
        const val REQUEST_RUN_TASKS = 82
    }
}
