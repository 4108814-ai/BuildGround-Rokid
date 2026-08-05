package com.anezium.rokidbus.phone

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

internal class PhoneSpeakerRouteProbe(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    fun wouldUseOwnSpeaker(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val devices = audioManager.getAudioDevicesForAttributes(phoneTtsAudioAttributes())
            devices.isEmpty() || devices.all { device ->
                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
            }
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
            }
        }
    }.getOrDefault(false)
}
