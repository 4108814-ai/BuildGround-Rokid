package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PluginGuardianCoordinatorTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val connections = mutableListOf<ServiceConnection>()
        val flags = mutableListOf<Int>()
        val events = mutableListOf<String>()
        var unbindCount = 0

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            events += "bind"
            connections += conn
            this.flags += flags
            return true
        }

        override fun unbindService(conn: ServiceConnection) {
            unbindCount += 1
        }
    }

    private val target = PluginGuardianTarget(
        grantKey = PluginGrantKey("dev.example.relay", "relay", "digest"),
        component = ComponentName("dev.example.relay", "dev.example.relay.RelayGuardianService"),
    )

    @Test
    fun `transient disconnect preserves the binding while binding death retries`() {
        val context = RecordingContext()
        var stoppedReads = 0
        val coordinator = PluginGuardianCoordinator(
            context = context,
            targetProvider = { listOf(target) },
            logger = {},
            stoppedFlagReader = {
                context.events += "stopped"
                stoppedReads += 1
                true
            },
        )

        coordinator.onLinkStateChanged(true)
        idleMain()
        assertEquals(1, context.connections.size)
        assertEquals(
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            context.flags.single(),
        )
        assertEquals(1, stoppedReads)
        assertEquals(listOf("stopped", "bind"), context.events)

        val firstConnection = context.connections.single()
        firstConnection.onServiceDisconnected(target.component)
        idleMain()
        assertEquals(0, context.unbindCount)
        assertEquals(1, context.connections.size)

        firstConnection.onBindingDied(target.component)
        idleMain()
        assertEquals(1, context.unbindCount)
        assertEquals(1, context.connections.size)

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(2, context.connections.size)
        assertEquals(2, stoppedReads)
        assertEquals(listOf("stopped", "bind", "stopped", "bind"), context.events)
        assertEquals(
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            context.flags.last(),
        )

        coordinator.close()
        idleMain()
        assertEquals(2, context.unbindCount)
    }

    @Test
    fun `link flap cancels linger and stable loss releases after thirty seconds`() {
        val context = RecordingContext()
        val coordinator = PluginGuardianCoordinator(
            context = context,
            targetProvider = { listOf(target) },
            logger = {},
            stoppedFlagReader = { false },
        )

        coordinator.onLinkStateChanged(true)
        idleMain()
        coordinator.onLinkStateChanged(false)
        idleMain()
        shadowOf(Looper.getMainLooper()).idleFor(29, TimeUnit.SECONDS)
        assertEquals(0, context.unbindCount)

        coordinator.onLinkStateChanged(true)
        idleMain()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(0, context.unbindCount)
        assertEquals(1, context.connections.size)

        coordinator.onLinkStateChanged(false)
        idleMain()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertEquals(1, context.unbindCount)
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
