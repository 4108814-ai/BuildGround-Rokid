package com.anezium.rokidbus.phone

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhoneUiBridgeContractTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `remote input parser accepts typed commands and rejects oversized payload`() {
        val valid = RemoteInputPhoneContract.commitText(context, "field-1", 4L, "hello")
        assertEquals(
            PhoneRemoteCommand.CommitText("field-1", 4L, "hello"),
            RemoteInputPhoneContract.parseCommand(valid),
        )

        val oversized = Intent(valid).putExtra("text", "界".repeat(200))
        assertNull(RemoteInputPhoneContract.parseCommand(oversized))
        val noSequence = Intent(valid).putExtra("sequence", 0L)
        assertNull(RemoteInputPhoneContract.parseCommand(noSequence))
    }

    @Test
    fun `navigation is independent from text session and whitelists its action`() {
        val valid = RemoteInputPhoneContract.navigate(
            context,
            "request-1",
            RemoteInputPhoneContract.KEY_SELECT,
        )
        assertEquals(
            PhoneRemoteNavigation("request-1", RemoteInputPhoneContract.KEY_SELECT),
            RemoteInputPhoneContract.parseNavigation(valid),
        )

        assertNull(
            RemoteInputPhoneContract.parseNavigation(
                Intent(valid).putExtra("navigation_action", "factory_reset"),
            ),
        )
    }

    @Test
    fun `native app parser requires request and app identities`() {
        val open = NativeAppsPhoneContract.open(context, "request-2", "youtube")
        assertEquals(
            PhoneNativeAppsCommand.Open("request-2", "youtube"),
            NativeAppsPhoneContract.parseCommand(open),
        )
        assertNull(
            NativeAppsPhoneContract.parseCommand(Intent(open).putExtra("app_id", "")),
        )
    }
}
