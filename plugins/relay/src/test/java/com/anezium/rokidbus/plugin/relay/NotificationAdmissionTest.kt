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
                blockedPackages = emptySet(),
                packageName = "com.example.app",
            ),
        )
    }

    @Test
    fun `an app nobody has ever seen is admitted, because repliable is the filter`() {
        // The first message from any app used to be lost while the wearer went
        // and ticked it. Nothing is ticked now — only silenced.
        assertTrue(
            NotificationAdmission.appIsAdmitted(
                enabled = true,
                blockedPackages = emptySet(),
                packageName = "com.example.never.seen.before",
            ),
        )
    }

    @Test
    fun `a silenced app stays out, and silencing one leaves the others alone`() {
        assertFalse(
            NotificationAdmission.appIsAdmitted(
                enabled = true,
                blockedPackages = setOf("com.example.noisy"),
                packageName = "com.example.noisy",
            ),
        )
        assertTrue(
            NotificationAdmission.appIsAdmitted(
                enabled = true,
                blockedPackages = setOf("com.example.noisy"),
                packageName = "com.example.other",
            ),
        )
    }

    private fun action(hasActionIntent: Boolean, allowsFreeForm: Boolean) =
        NotificationActionShape(
            hasActionIntent = hasActionIntent,
            remoteInputs = listOf(RemoteInputShape(allowsFreeForm = allowsFreeForm)),
        )
}
