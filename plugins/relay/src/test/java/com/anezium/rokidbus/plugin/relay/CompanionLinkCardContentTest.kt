package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionLinkCardContentTest {
    @Test
    fun `linked body does not depend on sdk level`() {
        assertEquals(
            "Linked. Relay is registered as your glasses' companion app, and is allowed to keep running.",
            CompanionLinkCardContent.body(linked = true, sdkInt = 34),
        )
    }

    @Test
    fun `android 15 and newer explain hidden message exemption`() {
        assertEquals(
            "Android blanks out any message with a code in it unless Relay is linked to your glasses as their " +
                "companion app. Linking also keeps Relay running when the system would otherwise stop it.",
            CompanionLinkCardContent.body(linked = false, sdkInt = 35),
        )
    }

    @Test
    fun `below android 15 explains background exemption only`() {
        assertEquals(
            "Linking Relay to your glasses as their companion app keeps it running when the system would " +
                "otherwise stop it.",
            CompanionLinkCardContent.body(linked = false, sdkInt = 34),
        )
    }
}
