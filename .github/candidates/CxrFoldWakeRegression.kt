package com.buildground.wakeregression

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
interface Callback {
 fun onCXRLConnected(connected:Boolean)
 fun onGlassBtConnected(connected:Boolean)
 fun onGlassWearingStatus(wearing:Boolean)
}
open class Harness {
 val localGlassesGeneration=AtomicLong()
 var cxrConnected=false
 var glassBtConnected=false
 var glassesWorn=false
 val retry=Semaphore(0)
 val reasons=mutableListOf<String>()
 fun wakeSppRetry(reason:String) { reasons.add(reason); retry.release() }
 fun log(message:String) {}
 fun notifyLinkState() {}
 fun failActiveGlassesAppOperation(message:String) {}
 fun isCxrUp()=cxrConnected && glassBtConnected
 fun revokeAudioLease(reason:String) {}
 fun cancelSnapshotForLinkDown() {}
}
class Baseline: Harness(), Callback {
override fun onCXRLConnected(connected: Boolean) {
            localGlassesGeneration.incrementAndGet()
            cxrConnected = connected
            if (!connected) glassesWorn = false
            log("CXR-L connected=$connected")
            notifyLinkState()
            if (!connected) failActiveGlassesAppOperation("Connection to the glasses was lost.")
            if (!isCxrUp()) {
                revokeAudioLease("LINK_DOWN")
                cancelSnapshotForLinkDown()
            }
        }
override fun onGlassBtConnected(connected: Boolean) {
            localGlassesGeneration.incrementAndGet()
            glassBtConnected = connected
            if (!connected) glassesWorn = false
            log("Hi Rokid glass BT connected=$connected")
            if (connected) wakeSppRetry("glass_bt_up")
            notifyLinkState()
            if (!connected) failActiveGlassesAppOperation("Connection to the glasses was lost.")
            if (!isCxrUp()) {
                revokeAudioLease("LINK_DOWN")
                cancelSnapshotForLinkDown()
            }
        }
override fun onGlassWearingStatus(wearing: Boolean) {
            glassesWorn = wearing
            notifyLinkState()
        }
}
class Candidate: Harness(), Callback {
override fun onCXRLConnected(connected: Boolean) {
            localGlassesGeneration.incrementAndGet()
            cxrConnected = connected
            if (connected) wakeSppRetry("cxr_up")
            if (!connected) glassesWorn = false
            log("CXR-L connected=$connected")
            notifyLinkState()
            if (!connected) failActiveGlassesAppOperation("Connection to the glasses was lost.")
            if (!isCxrUp()) {
                revokeAudioLease("LINK_DOWN")
                cancelSnapshotForLinkDown()
            }
        }
override fun onGlassBtConnected(connected: Boolean) {
            localGlassesGeneration.incrementAndGet()
            glassBtConnected = connected
            if (!connected) glassesWorn = false
            log("Hi Rokid glass BT connected=$connected")
            if (connected) wakeSppRetry("glass_bt_up")
            notifyLinkState()
            if (!connected) failActiveGlassesAppOperation("Connection to the glasses was lost.")
            if (!isCxrUp()) {
                revokeAudioLease("LINK_DOWN")
                cancelSnapshotForLinkDown()
            }
        }
override fun onGlassWearingStatus(wearing: Boolean) {
            glassesWorn = wearing
            log("Glasses wearing=$wearing")
            if (wearing) wakeSppRetry("glasses_worn")
            notifyLinkState()
        }
}

fun main() {
 val old=Baseline()
 old.onGlassWearingStatus(true)
 check(!old.retry.tryAcquire())
 old.onCXRLConnected(true)
 check(!old.retry.tryAcquire())
 println("PASS baseline wear/CXR edges fail to wake retry wait")
 val c=Candidate()
 c.onGlassWearingStatus(true)
 check(c.retry.tryAcquire())
 check(c.reasons.last()=="glasses_worn")
 println("PASS wearing wakes existing retry wait")
 c.onGlassWearingStatus(false)
 check(!c.retry.tryAcquire())
 println("PASS removal does not start retry")
 c.onCXRLConnected(true)
 check(c.retry.tryAcquire())
 check(c.reasons.last()=="cxr_up")
 println("PASS CXR reconnect wakes existing retry wait")
 c.onCXRLConnected(false)
 check(!c.retry.tryAcquire())
 println("PASS CXR disconnect does not spin retry")
 c.onGlassBtConnected(true)
 check(c.retry.tryAcquire())
 check(c.reasons.last()=="glass_bt_up")
 println("PASS original BT-up wake preserved")
}
