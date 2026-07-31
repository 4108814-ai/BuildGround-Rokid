package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHomeRenderDispatcherTest {
    @Test
    fun `cxr callback state changes are marshalled before touching the view hierarchy`() {
        var onMainThread = false
        var renderCount = 0
        var renderedOnMain = false
        val posted = mutableListOf<() -> Unit>()
        val dispatcher = PhoneHomeRenderDispatcher(
            isMainThread = { onMainThread },
            postToMain = posted::add,
            render = {
                renderCount++
                renderedOnMain = onMainThread
            },
        )

        dispatcher.requestRender()

        assertEquals(0, renderCount)
        assertEquals(1, posted.size)
        onMainThread = true
        posted.single().invoke()
        assertEquals(1, renderCount)
        assertTrue(renderedOnMain)
    }
}
