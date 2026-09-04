from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]

# The historical bridge patch was authored against a transient Phone field name.
# Recreate that one marker only long enough to apply the already-tested bridge,
# then remove it again so the generated source matches the current 1.4.6 baseline.
hub = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt"
hub_text = hub.read_text(encoding="utf-8")
current_marker = "    @Volatile private var lastAnnouncedPhoneCapabilities: PhoneHubCapabilities? = null\n"
compat_marker = "    private var remotePhoneCapabilities = PhoneHubCapabilities(0, null)\n"
if hub_text.count(current_marker) != 1 or compat_marker in hub_text:
    raise SystemExit("Unexpected Phone 1.4.6 capability-field baseline")
hub.write_text(hub_text.replace(current_marker, compat_marker + current_marker, 1), encoding="utf-8")

runpy.run_path(str(ROOT / ".github/scripts/apply-phone-call-state-bridge.py"), run_name="__main__")

hub_text = hub.read_text(encoding="utf-8")
if hub_text.count(compat_marker) != 1:
    raise SystemExit("Call-state bridge compatibility marker was not preserved exactly once")
hub.write_text(hub_text.replace(compat_marker, "", 1), encoding="utf-8")

bridge = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/PhoneCallStateBridge.kt"
bridge.write_text(
    '''package com.anezium.rokidbus.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.anezium.rokidbus.shared.PhoneCallStateContract
import java.util.concurrent.Executor

/** Observes the actual phone call state. No caller identity or phone number is read or transported. */
internal class PhoneCallStateBridge(
    context: Context,
    private val onState: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private var unregisterObserver: (() -> Unit)? = null
    @Volatile private var state: String = PhoneCallStateContract.IDLE

    fun start(): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            state = PhoneCallStateContract.IDLE
            return false
        }
        if (unregisterObserver != null) return true

        unregisterObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31.register(telephony, appContext.mainExecutor, ::accept)
        } else {
            Legacy.register(telephony, ::accept)
        }

        @Suppress("DEPRECATION")
        accept(mapState(telephony.callState), force = true)
        return true
    }

    fun stop() {
        val unregister = unregisterObserver ?: return
        unregisterObserver = null
        runCatching { unregister() }
    }

    fun currentState(): String = state

    private fun accept(next: String, force: Boolean = false) {
        if (!force && next == state) return
        state = next
        onState(next)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun register(
            telephony: TelephonyManager,
            executor: Executor,
            sink: (String) -> Unit,
        ): () -> Unit {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    sink(mapState(state))
                }
            }
            telephony.registerTelephonyCallback(executor, callback)
            return { telephony.unregisterTelephonyCallback(callback) }
        }
    }

    @Suppress("DEPRECATION")
    private object Legacy {
        fun register(
            telephony: TelephonyManager,
            sink: (String) -> Unit,
        ): () -> Unit {
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    sink(mapState(state))
                }
            }
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            return { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
    }

    companion object {
        private fun mapState(state: Int): String = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> PhoneCallStateContract.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> PhoneCallStateContract.OFFHOOK
            else -> PhoneCallStateContract.IDLE
        }
    }
}
''',
    encoding="utf-8",
)

text = bridge.read_text(encoding="utf-8")
for marker in (
    'Build.VERSION_CODES.S',
    'TelephonyCallback.CallStateListener',
    'PhoneStateListener.LISTEN_CALL_STATE',
    'PhoneStateListener.LISTEN_NONE',
):
    if marker not in text:
        raise SystemExit(f"Missing Android 11 compatibility marker: {marker}")
