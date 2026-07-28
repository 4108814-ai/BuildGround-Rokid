package com.anezium.rokidbus.glasses

import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CameraOverlayVisibilityBridgeTest {
    @Test
    fun `visibility intent is explicit and round-trips both edges`() {
        val context = RuntimeEnvironment.getApplication()

        listOf(true, false).forEach { active ->
            val token = Binder()
            val intent = CameraOverlayVisibilityBridge.intent(context, token, active)
            val edge = CameraOverlayVisibilityBridge.read(intent)

            assertEquals(
                ComponentName(context, CameraOverlayVisibilityReceiver::class.java),
                intent.component,
            )
            assertSame(token, edge?.token)
            assertEquals(active, edge?.active)
        }
    }

    @Test
    fun `malformed or unrelated broadcasts cannot change visibility`() {
        assertNull(CameraOverlayVisibilityBridge.read(Intent("some.other.action")))
        assertNull(
            CameraOverlayVisibilityBridge.read(
                Intent(CameraOverlayVisibilityBridge.ACTION),
            ),
        )
    }

    @Test
    fun `overlapping camera views stay active until the last token detaches`() {
        val states = mutableListOf<Boolean>()
        val registry = CameraOverlayVisibilityRegistry(states::add)
        val first = Binder()
        val second = Binder()

        registry.update(CameraOverlayVisibilityBridge.Edge(first, active = true))
        registry.update(CameraOverlayVisibilityBridge.Edge(second, active = true))
        registry.update(CameraOverlayVisibilityBridge.Edge(first, active = false))
        assertEquals(listOf(true), states)

        registry.update(CameraOverlayVisibilityBridge.Edge(second, active = false))
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun `camera process death supplies the missing detach edge`() {
        val states = mutableListOf<Boolean>()
        val registry = CameraOverlayVisibilityRegistry(states::add)
        val token = Binder()

        registry.update(CameraOverlayVisibilityBridge.Edge(token, active = true))
        registry.tokenDied(token)
        registry.tokenDied(token)

        assertEquals(listOf(true, false), states)
    }

}
