package com.anezium.rokidbus.phone

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemotePointerPhoneContractTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `all UI commands are package scoped and parse`() {
        val move = RemotePointerPhoneContract.move(context, 0.25, -0.5)

        assertEquals(context.packageName, move.`package`)
        assertEquals(
            PhonePointerCommand.Move(RemotePointerDelta(0.25, -0.5)),
            RemotePointerPhoneContract.parse(move),
        )
        assertEquals(
            PhonePointerCommand.Show,
            RemotePointerPhoneContract.parse(RemotePointerPhoneContract.show(context)),
        )
        assertEquals(
            PhonePointerCommand.Click,
            RemotePointerPhoneContract.parse(RemotePointerPhoneContract.click(context)),
        )
        assertEquals(
            PhonePointerCommand.MoveEnd,
            RemotePointerPhoneContract.parse(RemotePointerPhoneContract.moveEnd(context)),
        )
        assertEquals(
            PhonePointerCommand.LongPress,
            RemotePointerPhoneContract.parse(RemotePointerPhoneContract.longPress(context)),
        )
        assertEquals(
            PhonePointerCommand.Hide,
            RemotePointerPhoneContract.parse(RemotePointerPhoneContract.hide(context)),
        )
    }

    @Test
    fun `malformed version action and movement are rejected`() {
        fun command(action: String) = Intent(RemotePointerPhoneContract.ACTION_COMMAND)
            .setPackage(context.packageName)
            .putExtra("version", RemotePointerPhoneContract.VERSION)
            .putExtra("pointer_action", action)

        assertNull(RemotePointerPhoneContract.parse(command("root")))
        assertNull(
            RemotePointerPhoneContract.parse(
                command("move")
                    .putExtra("delta_x", Double.NaN)
                    .putExtra("delta_y", 0.1),
            ),
        )
        assertNull(
            RemotePointerPhoneContract.parse(
                command("click").putExtra("version", RemotePointerPhoneContract.VERSION + 1),
            ),
        )
    }
}
