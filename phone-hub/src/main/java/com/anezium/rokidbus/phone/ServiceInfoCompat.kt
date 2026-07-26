package com.anezium.rokidbus.phone

import android.content.pm.ServiceInfo

object ServiceInfoCompat {
    fun connectedDeviceType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    fun microphoneType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    fun hubTypes(includeMicrophone: Boolean = false): Int =
        connectedDeviceType() or if (includeMicrophone) microphoneType() else 0
}
