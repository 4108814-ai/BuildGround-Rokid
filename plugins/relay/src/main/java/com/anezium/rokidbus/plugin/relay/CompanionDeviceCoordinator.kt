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
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import java.util.UUID
import java.util.regex.Pattern

/**
 * Companion Device Manager association with the glasses.
 *
 * Android 14+ is strict about background foreground-service starts. A CDM association with
 * presence observation gives the relay the connected-device exemptions it needs while the
 * glasses are nearby. Android CXR audio itself is still routed through the CXR PCM pipe.
 *
 * The glasses are often already bonded through Hi Rokid and neither respond to classic inquiry
 * nor advertise their name over BLE. Explicit address filters let CDM include those devices,
 * while scan filters still cover glasses that have not been bonded yet.
 */
object CompanionDeviceCoordinator {
    const val COMPANION_REQUEST_CODE = 7204
    const val BLUETOOTH_PERMISSION_REQUEST_CODE = 7205

    private const val TAG = "RelayCompanion"
    // The glasses report "Glasses_XXXX" as their Bluetooth name; "Rokid Glasses" is only the
    // alias Android shows in Settings, so the filter must accept both spellings.
    private val GLASSES_NAME_PATTERN = Pattern.compile("rokid|glasses", Pattern.CASE_INSENSITIVE)

    // Custom SDP service the glasses firmware registers on the bonded link.
    private val ROKID_GLASSES_SERVICE_UUID = UUID.fromString("3c36c196-e056-4e4f-b88e-2cb249365f00")

    /**
     * Whether we are linked at all — which is not the same question as which
     * devices we can watch, however alike the two reads look.
     *
     * CDM hands back associations with no MAC address on it: the chooser path
     * produces them, and the standalone app left one behind on this very phone.
     * [associatedAddresses] drops those, because presence observation is keyed by
     * address and there is nothing to observe. Answering "are we linked?" from
     * that filtered list was measured on hardware to say no while Android's own
     * settings said yes, so the card offered to link again and the tap stacked a
     * second association on top of the first. Ask the unfiltered list.
     */
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

    fun requestAssociation(activity: Activity, showMessage: (String) -> Unit) {
        // CDM happily stacks duplicate associations for the same device; keep one.
        if (hasAssociation(activity)) {
            startObserving(activity)
            showMessage("Glasses already linked")
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
        val likely = bonded.filter { device -> looksLikeGlasses(activity, device) }
        when {
            likely.size == 1 -> associateWithAddress(activity, likely.first().address, showMessage)
            else -> associateByChooser(activity, bonded, showMessage)
        }
    }

    fun handleBluetoothPermissionResult(
        activity: Activity,
        requestCode: Int,
        grantResults: IntArray,
        showMessage: (String) -> Unit,
    ): Boolean {
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST_CODE) return false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestAssociation(activity, showMessage)
        } else if (activity.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            // Naming the permission, not the outcome: "Association failed" tells
            // the wearer nothing they can act on, and they have just tapped the
            // dialog that caused it.
            showMessage("Relay needs the nearby-devices permission to find your glasses")
        } else {
            showMessage("Nearby devices permission is off. Turn it on in Android's app settings for Relay.")
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
        }
        return true
    }

    /**
     * Single-device request with an explicit address: CDM matches it against bonded and
     * connected devices before scanning, so the confirmation shows without any discovery.
     */
    private fun associateWithAddress(activity: Activity, address: String, showMessage: (String) -> Unit) {
        Log.i(TAG, "requesting association with bonded device")
        associate(activity, showMessage) {
            setSingleDevice(true)
            addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setAddress(address)
                    .build(),
            )
        }
    }

    /**
     * We cannot reliably tell the devices apart, so the system chooser is the honest answer.
     * Listing bonded addresses alongside scan filters lets both already-bonded and not-yet-bonded
     * glasses appear.
     */
    private fun associateByChooser(
        activity: Activity,
        bonded: List<BluetoothDevice>,
        showMessage: (String) -> Unit,
    ) {
        associate(activity, showMessage) {
            bonded.forEach { device ->
                addDeviceFilter(
                    BluetoothDeviceFilter.Builder()
                        .setAddress(device.address)
                        .build(),
                )
            }
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
        showMessage: (String) -> Unit,
        configure: AssociationRequest.Builder.() -> Unit,
    ) {
        val manager = manager(activity) ?: run {
            showMessage("Companion device service unavailable")
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
                launchChooser(activity, intentSender, showMessage)
            }

            @Deprecated("Android 12L delivers the chooser through onDeviceFound")
            override fun onDeviceFound(intentSender: IntentSender) {
                launchChooser(activity, intentSender, showMessage)
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
                showMessage(error?.toString().orEmpty().ifBlank { "Association failed" })
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, null)
        }.onFailure {
            Log.w(TAG, "associate call failed", it)
            showMessage(it.message ?: "Association failed")
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

    /**
     * Idempotent; safe to call on every app open and after each association.
     *
     * Presence observation arrived in Android 12, and Relay runs as far back as 11 —
     * where an association is still worth having for what it is, just without anything
     * watching the glasses come and go.
     */
    fun startObserving(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = manager(context) ?: return
        associatedAddresses(context).forEach { address ->
            runCatching {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }.onFailure {
                val deviceDiscriminator = address.replace(":", "").takeLast(4)
                Log.w(TAG, "observe presence failed [$deviceDiscriminator]: ${it.message}")
            }
        }
    }

    /** Only the associations that carry an address; see [hasAssociation] for why that differs. */
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

    /**
     * Empty when the permission is missing or Bluetooth is off.
     *
     * The caller asks for BLUETOOTH_CONNECT before it ever gets here, so the check
     * below should never be the one that fires — it is here because the alternative
     * was letting the bonded-device read throw and catching it, and a SecurityException
     * swallowed by a `runCatching` reads to every tool, and every later reader, as an
     * unguarded call.
     */
    private fun bondedDevices(context: Context): List<BluetoothDevice> {
        // Spelled out here rather than behind a helper, and again in
        // looksLikeGlasses, because lint only credits a permission check it can
        // see in the same function — put it one call away and every bonded-device
        // read reads as unguarded. The permission is install-time before
        // Android 12, so there is nothing to ask and nothing to check there.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList()
        }.getOrElse {
            Log.w(TAG, "bonded device lookup failed: ${it.message}")
            null
        }.orEmpty()
    }

    /**
     * The user can rename the glasses to anything, so the name match is only a heuristic;
     * the SDP service UUID published by the glasses firmware is checked as a second signal.
     * The heuristic is only used for the unambiguous fast path; all ambiguity stays with the
     * system chooser.
     */
    private fun looksLikeGlasses(context: Context, device: BluetoothDevice): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            sequenceOf(device.alias, device.name).filterNotNull().any {
                GLASSES_NAME_PATTERN.matcher(it).find()
            } || device.uuids?.any { it.uuid == ROKID_GLASSES_SERVICE_UUID } == true
        }.getOrDefault(false)
    }

    private fun launchChooser(activity: Activity, intentSender: IntentSender, showMessage: (String) -> Unit) {
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
            showMessage(it.message ?: "Could not open device chooser")
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
        return when (val device = data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> device.address
            is ScanResult -> device.device?.address
            else -> null
        }
    }

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)
}
