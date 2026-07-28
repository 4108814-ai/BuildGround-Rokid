package com.anezium.rokidbus.plugin.agents

import java.io.StringReader
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentdLinkSecurityTest {
    private val hello = AgentdAction.Hello(
        machineId = "pc-1",
        machineName = "Desk",
        token = "secret",
    )

    @Test
    fun preAuthenticationGateRejectsEveryNonHelloFrameWithoutAuthorizing() {
        var authorizationCalls = 0
        val gate = AgentdInboundGate {
            authorizationCalls += 1
            MachineTrustResult.TRUSTED
        }

        assertEquals(
            AgentdInboundDecision.ProtocolRejected,
            gate.receive(AgentdAction.Snapshot(1L, emptyList())),
        )
        assertEquals(0, authorizationCalls)
    }

    @Test
    fun matchingOrNewHelloAuthenticatesAndOnlyNewLinkSignalsTrust() {
        val knownGate = AgentdInboundGate { MachineTrustResult.TRUSTED }
        val known = knownGate.receive(hello) as AgentdInboundDecision.HelloAccepted
        assertFalse(known.newlyTrusted)
        assertTrue(
            knownGate.receive(AgentdAction.Snapshot(1L, emptyList())) is
                AgentdInboundDecision.Frame,
        )

        val newGate = AgentdInboundGate { MachineTrustResult.NEWLY_TRUSTED }
        val newlyTrusted = newGate.receive(hello) as AgentdInboundDecision.HelloAccepted
        assertTrue(newlyTrusted.newlyTrusted)
    }

    @Test
    fun rejectedHelloNeverAuthenticatesTheConnection() {
        var calls = 0
        val gate = AgentdInboundGate {
            calls += 1
            MachineTrustResult.REJECTED_NOT_INVITED
        }

        assertEquals(
            AgentdInboundDecision.AuthRejected(AgentdProtocolCodec.REJECT_UNKNOWN_MACHINE),
            gate.receive(hello),
        )
        assertEquals(
            AgentdInboundDecision.ProtocolRejected,
            gate.receive(AgentdAction.Removed(1L, "s1")),
        )
        assertEquals(1, calls)
    }

    @Test
    fun trustDecisionNeverReplacesKnownWrongToken() {
        assertEquals(
            MachineTrustResult.TRUSTED,
            decideMachineTrust("secret", true, false, "secret"),
        )
        assertEquals(
            MachineTrustResult.REJECTED_BAD_TOKEN,
            decideMachineTrust("secret", true, true, "attacker"),
        )
        assertEquals(
            MachineTrustResult.NEWLY_TRUSTED,
            decideMachineTrust(null, false, false, "first-use"),
        )
        assertEquals(
            MachineTrustResult.NEWLY_TRUSTED,
            decideMachineTrust(null, true, true, "armed"),
        )
        assertEquals(
            MachineTrustResult.REJECTED_NOT_INVITED,
            decideMachineTrust(null, true, false, "not-armed"),
        )
    }

    @Test
    fun linkWindowUsesBoundedMonotonicDeadline() {
        val now = 10_000L
        val deadline = linkWindowDeadline(now)
        assertEquals(LINK_WINDOW_DURATION_MS, deadline - now)
        assertTrue(isLinkWindowDeadlineOpen(deadline, now))
        assertTrue(isLinkWindowDeadlineOpen(deadline, deadline - 1L))
        assertFalse(isLinkWindowDeadlineOpen(deadline, deadline))
        // A persisted pre-reboot elapsed-realtime value cannot reopen for hours.
        assertFalse(isLinkWindowDeadlineOpen(9_000_000L, 100L))
    }

    @Test
    fun boundedReaderAcceptsLimitAndRejectsUnterminatedOverflow() {
        val atLimit = "x".repeat(MAX_LINK_LINE_CHARS)
        assertEquals(atLimit, StringReader("$atLimit\n").readBoundedLine(MAX_LINK_LINE_CHARS))
        assertEquals("line", StringReader("line\r\n").readBoundedLine(MAX_LINK_LINE_CHARS))
        assertNull(StringReader("").readBoundedLine(MAX_LINK_LINE_CHARS))

        try {
            StringReader("x".repeat(MAX_LINK_LINE_CHARS + 1))
                .readBoundedLine(MAX_LINK_LINE_CHARS)
            fail("Expected an overlong frame to be rejected")
        } catch (_: LineTooLongException) {
            // Expected.
        }
    }

    @Test
    fun linkConnectionInstallsNinetySecondSocketReadTimeout() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { dialer ->
                server.accept().use { accepted ->
                    val connection = LinkConnection(accepted)
                    assertEquals(LINK_IDLE_TIMEOUT_MS, accepted.soTimeout)
                    connection.close()
                }
                assertTrue(dialer.isConnected)
            }
        }
    }

    @Test
    fun pairingParserRejectsCoercedScalarTypes() {
        assertTrue(
            AgentdPairingParser.parse(
                """{"v":1,"kind":"nexus-agentd","host":7,"port":8792,"token":"secret"}""",
            ) is AgentdPairingParseResult.Invalid,
        )
        assertTrue(
            AgentdPairingParser.parse(
                """{"v":"1","kind":"nexus-agentd","host":"desk","port":8792,"token":"secret"}""",
            ) is AgentdPairingParseResult.Invalid,
        )
    }
}
