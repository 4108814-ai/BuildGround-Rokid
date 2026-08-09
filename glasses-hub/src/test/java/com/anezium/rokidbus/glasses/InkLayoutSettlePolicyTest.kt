package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkLayoutSettlePolicyTest {
    @Test
    fun `cold projection waits for bounds then one clean layout`() {
        val policy = InkLayoutSettlePolicy()
        policy.onProjectionChanged()

        assertEquals(
            InkLayoutSettleAction.WAIT_FOR_BOUNDS,
            policy.onPostLayout(width = 0, height = 0),
        )
        assertFalse(policy.canDraw(width = 0, height = 0))
        assertEquals(
            InkLayoutSettleAction.REAPPLY_GEOMETRY,
            policy.onPostLayout(width = 444, height = 592),
        )
        assertFalse(policy.canDraw(width = 444, height = 592))

        assertEquals(
            InkLayoutSettleAction.NONE,
            policy.onPostLayout(width = 444, height = 592),
        )
        assertTrue(policy.canDraw(width = 444, height = 592))
        assertEquals(
            444f,
            InkLengthResolver.resolve("750rpx", 444f, 444f, 1f)!!,
            0.001f,
        )
    }

    @Test
    fun `same width with changed inset height settles again`() {
        val policy = settledPolicy(width = 444, height = 592)

        assertEquals(
            InkLayoutSettleAction.REAPPLY_GEOMETRY,
            policy.onPostLayout(width = 444, height = 568),
        )
        assertFalse(policy.canDraw(width = 444, height = 568))
        assertEquals(
            InkLayoutSettleAction.NONE,
            policy.onPostLayout(width = 444, height = 568),
        )
        assertTrue(policy.canDraw(width = 444, height = 568))
    }

    @Test
    fun `stable bounds do not request repeated geometry passes`() {
        val policy = settledPolicy(width = 444, height = 592)

        repeat(4) {
            assertEquals(
                InkLayoutSettleAction.NONE,
                policy.onPostLayout(width = 444, height = 592),
            )
            assertTrue(policy.canDraw(width = 444, height = 592))
        }
    }

    @Test
    fun `new projection invalidates otherwise stable bounds`() {
        val policy = settledPolicy(width = 444, height = 592)

        policy.onProjectionChanged()

        assertFalse(policy.canDraw(width = 444, height = 592))
        assertEquals(
            InkLayoutSettleAction.REAPPLY_GEOMETRY,
            policy.onPostLayout(width = 444, height = 592),
        )
    }

    private fun settledPolicy(width: Int, height: Int) = InkLayoutSettlePolicy().apply {
        onProjectionChanged()
        assertEquals(
            InkLayoutSettleAction.REAPPLY_GEOMETRY,
            onPostLayout(width, height),
        )
        assertEquals(InkLayoutSettleAction.NONE, onPostLayout(width, height))
    }
}
