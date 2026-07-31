package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAdmissionTest {
    @Test
    fun `a reply action needs a pending intent and a free form input`() {
        assertFalse(
            NotificationAdmission.isRepliableAction(
                action(hasActionIntent = true, allowsFreeForm = false),
            ),
        )
        assertFalse(
            NotificationAdmission.isRepliableAction(
                action(hasActionIntent = false, allowsFreeForm = true),
            ),
        )
        assertTrue(
            NotificationAdmission.isRepliableAction(
                action(hasActionIntent = true, allowsFreeForm = true),
            ),
        )
    }

    @Test
    fun `the first qualifying reply action wins`() {
        val actions = listOf(
            action(hasActionIntent = true, allowsFreeForm = false),
            action(hasActionIntent = true, allowsFreeForm = true),
            action(hasActionIntent = true, allowsFreeForm = true),
        )

        assertEquals(1, NotificationAdmission.firstRepliableActionIndex(actions))
        assertNull(
            NotificationAdmission.firstRepliableActionIndex(
                listOf(action(hasActionIntent = false, allowsFreeForm = true)),
            ),
        )
    }

    @Test
    fun `a disabled plugin admits no package`() {
        assertFalse(
            NotificationAdmission.appIsAdmitted(
                enabled = false,
                allowedPackages = setOf("com.example.allowed"),
                packageName = "com.example.allowed",
            ),
        )
    }

    @Test
    fun `an empty allowlist admits no package`() {
        assertFalse(
            NotificationAdmission.appIsAdmitted(
                enabled = true,
                allowedPackages = emptySet(),
                packageName = "com.example.app",
            ),
        )
        assertTrue(
            NotificationAdmission.appIsAdmitted(
                enabled = true,
                allowedPackages = setOf("com.example.app"),
                packageName = "com.example.app",
            ),
        )
    }

    private fun action(hasActionIntent: Boolean, allowsFreeForm: Boolean) =
        NotificationActionShape(
            hasActionIntent = hasActionIntent,
            remoteInputs = listOf(RemoteInputShape(allowsFreeForm = allowsFreeForm)),
        )
}
