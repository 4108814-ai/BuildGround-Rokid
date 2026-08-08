package com.anezium.rokidbus.phone

import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderPatch
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.InkSurfaceContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PhoneInkSurfaceCoordinatorTest {
    private val owner = PhoneInkSurfaceOwner("hello", "main", "hello:main")

    @Test
    fun `show update patch resync hide lifecycle stays ordered`() {
        var nowMs = 0L
        val coordinator = PhoneInkSurfaceCoordinator(nowMs = { nowMs })
        try {
            val shown = command { callback ->
                coordinator.show(owner, PAGE, JSONObject().put("value", "one"), false, callback)
            } as PhoneInkCommandResult.Outgoing
            assertEquals(BusPaths.SURFACE_SHOW, shown.path)
            assertEquals(InkSurfaceContract.KIND, shown.payload.getString("kind"))
            val documentWire = shown.payload.getJSONObject("ink").getString("document")
            val document = RenderDocument.fromWireJson(documentWire).value!!
            assertEquals(0, document.revision)

            val updated = command { callback ->
                coordinator.update(owner, JSONObject().put("value", "two"), callback)
            } as PhoneInkCommandResult.Outgoing
            assertEquals(BusPaths.SURFACE_UPDATE, updated.path)
            val patchWire = updated.payload.getJSONObject("ink").getString("patch")
            val patch = RenderPatch.fromWireJson(patchWire).value!!
            assertEquals(document.documentId, patch.documentId)
            assertEquals(0, patch.baseRevision)
            assertEquals(1, patch.targetRevision)
            assertTrue(patch.changes.isNotEmpty())

            val resync = remote { callback ->
                coordinator.onRemoteEvent(owner.wireSurfaceId, InkSurfaceContract.EVENT_RESYNC, callback)
            } as PhoneInkRemoteEventResult.Resync
            val full = RenderDocument.fromWireJson(
                resync.outgoing.payload.getJSONObject("ink").getString("document"),
            ).value!!
            assertEquals(document.documentId, full.documentId)
            assertEquals(1, full.revision)

            assertEquals(
                PhoneInkRemoteEventResult.Ignore,
                remote { callback ->
                    coordinator.onRemoteEvent(owner.wireSurfaceId, InkSurfaceContract.EVENT_RESYNC, callback)
                },
            )
            nowMs = 1_000L
            assertTrue(
                remote { callback ->
                    coordinator.onRemoteEvent(owner.wireSurfaceId, InkSurfaceContract.EVENT_RESYNC, callback)
                } is PhoneInkRemoteEventResult.Resync,
            )

            val hidden = command { callback -> coordinator.hide(owner, callback) }
                as PhoneInkCommandResult.Outgoing
            assertEquals(BusPaths.SURFACE_HIDE, hidden.path)
            assertTrue(
                remote { callback ->
                    coordinator.onRemoteEvent(owner.wireSurfaceId, InkSurfaceContract.EVENT_CLOSED, callback)
                } is PhoneInkRemoteEventResult.Closed,
            )
            val missing = command { callback ->
                coordinator.update(owner, JSONObject().put("value", "three"), callback)
            } as PhoneInkCommandResult.Error
            assertEquals(InkProblemCodes.SESSION_NOT_FOUND, missing.problems.single().code)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `compile errors return their typed engine problems and keep the old session`() {
        val coordinator = PhoneInkSurfaceCoordinator()
        try {
            command { callback -> coordinator.show(owner, PAGE, null, false, callback) }
            val invalid = command { callback ->
                coordinator.show(owner, INVALID_PAGE, null, false, callback)
            } as PhoneInkCommandResult.Error

            assertTrue(invalid.problems.any { it.code == InkProblemCodes.SCRIPT_UNSUPPORTED })
            assertTrue(
                command { callback ->
                    coordinator.update(owner, JSONObject().put("value", "still-live"), callback)
                } is PhoneInkCommandResult.Outgoing,
            )
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `owner teardown cancels a compile result before it can publish`() {
        val posted = LinkedBlockingQueue<() -> Unit>()
        val results = LinkedBlockingQueue<PhoneInkCommandResult>()
        val coordinator = PhoneInkSurfaceCoordinator(postResult = { action -> posted.add(action) })
        try {
            coordinator.show(owner, PAGE, null, false) { result -> results.add(result) }
            val pendingShow = posted.poll(5, TimeUnit.SECONDS)
            assertTrue("Ink compile did not post its result", pendingShow != null)

            coordinator.clearOwner(owner.pluginId)
            requireNotNull(pendingShow).invoke()

            assertTrue(results.poll(5, TimeUnit.SECONDS) is PhoneInkCommandResult.Noop)
        } finally {
            coordinator.close()
        }
    }

    private fun command(
        action: ((PhoneInkCommandResult) -> Unit) -> Unit,
    ): PhoneInkCommandResult {
        val latch = CountDownLatch(1)
        lateinit var result: PhoneInkCommandResult
        action {
            result = it
            latch.countDown()
        }
        assertTrue("Ink command timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun remote(
        action: ((PhoneInkRemoteEventResult) -> Unit) -> Unit,
    ): PhoneInkRemoteEventResult {
        val latch = CountDownLatch(1)
        lateinit var result: PhoneInkRemoteEventResult
        action {
            result = it
            latch.countDown()
        }
        assertTrue("Ink event timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private companion object {
        val PAGE = """
            <script type="application/json" def>{"data":{"value":"zero"}}</script>
            <page><view><text>{{ value }}</text></view></page>
            <style></style>
        """.trimIndent()

        val INVALID_PAGE = """
            <script type="application/json" def>{"data":{}}</script>
            <script setup>setData({"value":"forbidden"})</script>
            <page><view><text>invalid</text></view></page>
        """.trimIndent()
    }
}
