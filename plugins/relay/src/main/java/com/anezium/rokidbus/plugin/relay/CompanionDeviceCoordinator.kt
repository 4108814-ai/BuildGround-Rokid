package com.anezium.rokidbus.plugin.relay

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.regex.Pattern

/**
 * Companion Device Manager association with the glasses.
 *
 * Android 14+ is strict about background foreground-service starts. A CDM association with
 * presence observation gives the relay the connected-device exemptions it needs while the
 * glasses are nearby. Android CXR audio itself is still routed through the CXR PCM pipe.
 *
 * The glasses are already bonded through Hi Rokid and neither respond to classic inquiry nor
 * advertise their name over BLE, so a plain CDM discovery never finds them. CDM has a
 * shortcut for exactly this case: a single-device request whose BluetoothDeviceFilter carries
 * an explicit address is matched against bonded and connected devices before any scan starts,
 * so the confirmation dialog appears immediately. The scan-based filters remain as a fallback
 * for glasses that are not bonded yet.
 */
object CompanionDeviceCoordinator {
    const val COMPANION_REQUEST_CODE = 7204
    const val BLUETOOTH_PERMISSION_REQUEST_CODE = 7205

    private const val TAG = "RelayCompanion"
    // The glasses report "Glasses_XXXX" as their Bluetooth name; "Rokid Glasses" is only the
    // alias Android shows in Settings, so the filter must accept both spellings.
    private val GLASSES_NAME_PATTERN = Pattern.compile("rokid|glasses", Pattern.CASE_INSENSITIVE)

    // Custom SDP service the glasses firmware registers on the bonded link.
    private val ROKID_GLASSES_SERVICE_UUID = java.util.UUID.fromString("3c36c196-e056-4e4f-b88e-2cb249365f00")

    fun hasAssociation(context: Context): Boolean {
        val manager = manager(context) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                manager.associations.isNotEmpty()
            }
        }.getOrElse {
            Log.w(TAG, "read associations failed: ${it.message}")
            false
        }
    }

    fun requestAssociation(activity: Activity, onFailure: (String) -> Unit) {
        // CDM happily stacks duplicate associations for the same device; keep one.
        if (hasAssociation(activity)) {
            startObserving(activity)
            onFailure("Glasses already linked")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_PERMISSION_REQUEST_CODE,
            )
            return
        }
        val bonded = bondedDevices(activity)
        val likely = bonded.filter(::looksLikeGlasses)
        when {
            likely.size == 1 -> associateWithAddress(activity, likely.first().address, onFailure)
            bonded.isEmpty() -> associateByScan(activity, onFailure)
            else -> pickBondedDevice(activity, likely.ifEmpty { bonded }, onFailure)
        }
    }

    fun handleBluetoothPermissionResult(
        activity: Activity,
        requestCode: Int,
        grantResults: IntArray,
        onFailure: (String) -> Unit,
    ): Boolean {
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST_CODE) return false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestAssociation(activity, onFailure)
        } else {
            // Naming the permission, not the outcome: "Association failed" tells
            // the wearer nothing they can act on, and they have just tapped the
            // dialog that caused it.
            onFailure("Relay needs the nearby-devices permission to find your glasses")
        }
        return true
    }

    /** The glasses name is user-editable, so let the user point at the right bonded device. */
    private fun pickBondedDevice(activity: Activity, devices: List<BluetoothDevice>, onFailure: (String) -> Unit) {
        val labels = devices.map { device ->
            runCatching { device.alias ?: device.name }.getOrNull() ?: device.address
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Select your glasses")
            .setItems(labels.toTypedArray()) { _, index ->
                associateWithAddress(activity, devices[index].address, onFailure)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Single-device request with an explicit address: CDM matches it against bonded and
     * connected devices before scanning, so the confirmation shows without any discovery.
     */
    private fun associateWithAddress(activity: Activity, address: String, onFailure: (String) -> Unit) {
        Log.i(TAG, "requesting association with bonded device")
        associate(activity, onFailure) {
            setSingleDevice(true)
            addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setAddress(address)
                    .build(),
            )
        }
    }

    /** Discovery fallback for glasses that are not bonded yet (they must be in pairing mode). */
    private fun associateByScan(activity: Activity, onFailure: (String) -> Unit) {
        associate(activity, onFailure) {
            addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setNamePattern(GLASSES_NAME_PATTERN)
                    .build(),
            )
            addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(GLASSES_NAME_PATTERN)
                    .build(),
            )
        }
    }

    private fun associate(
        activity: Activity,
        onFailure: (String) -> Unit,
        configure: AssociationRequest.Builder.() -> Unit,
    ) {
        val manager = manager(activity) ?: run {
            onFailure("Companion device service unavailable")
            return
        }
        val request = AssociationRequest.Builder().apply {
            configure()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setDeviceProfile(AssociationRequest.DEVICE_PROFILE_GLASSES)
            }
        }.build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                launchChooser(activity, intentSender, onFailure)
            }

            @Deprecated("Android 12L delivers the chooser through onDeviceFound")
            override fun onDeviceFound(intentSender: IntentSender) {
                launchChooser(activity, intentSender, onFailure)
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
                onFailure(error?.toString().orEmpty().ifBlank { "Association failed" })
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, null)
        }.onFailure {
            Log.w(TAG, "associate call failed", it)
            onFailure(it.message ?: "Association failed")
        }
    }

    /** Returns the associated device address, or null when the result is not a success. */
    fun handleAssociationResult(context: Context, resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val address = extractAddress(data) ?: return null
        startObserving(context)
        Log.i(TAG, "companion device associated")
        return address
    }

    /** Idempotent; safe to call on every app open and after each association. */
    fun startObserving(context: Context) {
        val manager = manager(context) ?: return
        associatedAddresses(context).forEach { address ->
            runCatching {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }.onFailure {
                Log.w(TAG, "observe presence failed: ${it.message}")
            }
        }
    }

    private fun associatedAddresses(context: Context): List<String> {
        val manager = manager(context) ?: return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.toList()
            }
        }.getOrElse {
            Log.w(TAG, "read associations failed: ${it.message}")
            emptyList()
        }
    }

    /** Needs BLUETOOTH_CONNECT; empty when not granted or Bluetooth is off. */
    private fun bondedDevices(context: Context): List<BluetoothDevice> = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList()
    }.getOrElse {
        Log.w(TAG, "bonded device lookup failed: ${it.message}")
        null
    }.orEmpty()

    /**
     * The user can rename the glasses to anything, so the name match is only a heuristic;
     * the SDP service UUID published by the glasses firmware is checked as a second signal.
     * Ambiguity falls back to the picker, so a false positive here costs one extra tap.
     */
    private fun looksLikeGlasses(device: BluetoothDevice): Boolean = runCatching {
        sequenceOf(device.alias, device.name).filterNotNull().any {
            GLASSES_NAME_PATTERN.matcher(it).find()
        } || device.uuids?.any { it.uuid == ROKID_GLASSES_SERVICE_UUID } == true
    }.getOrDefault(false)

    private fun launchChooser(activity: Activity, intentSender: IntentSender, onFailure: (String) -> Unit) {
        runCatching {
            activity.startIntentSenderForResult(
                intentSender,
                COMPANION_REQUEST_CODE,
                null,
                0,
                0,
                0,
            )
        }.onFailure {
            Log.w(TAG, "chooser launch failed", it)
            onFailure(it.message ?: "Could not open device chooser")
        }
    }

    private fun extractAddress(data: Intent?): String? {
        if (data == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val association = data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )
            association?.deviceMacAddress?.let { return it.toString() }
        }
        @Suppress("DEPRECATION")
        return when (val device = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> device.address
            is ScanResult -> device.device?.address
            else -> null
        }
    }

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)
}
