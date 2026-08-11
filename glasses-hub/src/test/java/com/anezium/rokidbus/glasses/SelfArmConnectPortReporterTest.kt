package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmConnectPortReporterTest {
    @Test
    fun initialReplyPollsUntilTheControllerReportsAConnectPort() {
        var now = 0L
        var reads = 0
        val reporter = SelfArmConnectPortReporter { timeoutMs ->
            SelfArmWirelessAdbController.waitForWirelessPort(
                timeoutMs = timeoutMs,
                readPort = {
                    reads += 1
                    if (reads == 1) 0 else CONNECT_PORT
                },
                elapsedRealtime = { now },
                sleepFor = { delayMs -> now += delayMs },
            )
        }
        reporter.begin(REQUEST_ID, ACTION)

        val report = requireNotNull(
            reporter.initialReport(
                SelfArmManualAction.OPEN_WIRELESS_DEBUGGING.connectPortWaitTimeoutMs(),
            ),
        )

        assertEquals(2, reads)
        assertEquals(CONNECT_PORT, report.connectPort)
        assertFalse(report.updateOnly)
        assertEquals(REQUEST_ID, report.requestId)
    }

    @Test
    fun nonPortNavigationActionAcknowledgesWithoutWaitingForAConnectPort() {
        var waitCalled = false
        val reporter = SelfArmConnectPortReporter {
            waitCalled = true
            CONNECT_PORT
        }
        reporter.begin(REQUEST_ID, SelfArmManualAction.OPEN_DEVELOPER_OPTIONS.wireValue)

        val report = requireNotNull(
            reporter.initialReport(
                SelfArmManualAction.OPEN_DEVELOPER_OPTIONS.connectPortWaitTimeoutMs(),
            ),
        )

        assertFalse(waitCalled)
        assertNull(report.connectPort)
        assertFalse(report.updateOnly)
        assertEquals(REQUEST_ID, report.requestId)
    }

    @Test
    fun lateKnownPortPushReusesTheLiveRequestAfterAnEmptyInitialReply() {
        val reporter = SelfArmConnectPortReporter { 0 }
        reporter.begin(REQUEST_ID, ACTION)
        val initial = requireNotNull(reporter.initialReport(2_000L))

        val update = requireNotNull(reporter.pushKnownPort(CONNECT_PORT))

        assertNull(initial.connectPort)
        assertFalse(initial.updateOnly)
        assertEquals(REQUEST_ID, update.requestId)
        assertEquals(ACTION, update.action)
        assertEquals(CONNECT_PORT, update.connectPort)
        assertTrue(update.updateOnly)
        assertNull(reporter.pushKnownPort(CONNECT_PORT))

        reporter.clear()
        assertNull(reporter.pushKnownPort(CONNECT_PORT + 1))
    }

    private companion object {
        const val REQUEST_ID = "manual-request"
        const val ACTION = "open_wireless_debugging"
        const val CONNECT_PORT = 39876
    }
}
