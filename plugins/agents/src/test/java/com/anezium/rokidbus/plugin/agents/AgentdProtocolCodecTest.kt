package com.anezium.rokidbus.plugin.agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentdProtocolCodecTest {
    @Test
    fun helloUsesFrozenProtocolShape() {
        val hello = JSONObject(AgentdProtocolCodec().hello("secret", "1.2.3"))
        assertEquals("hello", hello.getString("type"))
        assertEquals(1, hello.getInt("v"))
        assertEquals("secret", hello.getString("token"))
        assertEquals("plugin-agents", hello.getJSONObject("client").getString("name"))
        assertEquals("1.2.3", hello.getJSONObject("client").getString("version"))
    }

    @Test
    fun snapshotUpsertAndRemovedAreParsed() {
        val codec = AgentdProtocolCodec()
        val helloAck = codec.parse(
            """{"type":"hello_ack","v":1,"server":{"name":"nexus-agentd","version":"1.0.0","machineId":"pc-1","machineName":"Desk"}}""",
        ) as AgentdAction.HelloAcknowledged
        assertEquals("Desk", helloAck.machineName)
        val snapshot = codec.parse(
            """{"type":"snapshot","seq":7,"sessions":[{"id":"s1","provider":"claude","status":"idle","title":"One"}]}""",
        ) as AgentdAction.Snapshot
        assertEquals(7, snapshot.seq)
        assertEquals("One", snapshot.sessions.single().title)

        val upsert = codec.parse(
            """{"type":"session_upsert","seq":8,"session":{"id":"s1","provider":"claude","status":"needs_you","pendingRequest":{"kind":"permission","summary":"Run tests","createdAt":40}}}""",
        ) as AgentdAction.Upsert
        assertEquals(AgentStatus.NEEDS_YOU, upsert.session.status)
        assertEquals(40L, upsert.session.pendingRequest?.createdAt)

        val removed = codec.parse(
            """{"type":"session_removed","seq":9,"sessionId":"s1"}""",
        ) as AgentdAction.Removed
        assertEquals("s1", removed.sessionId)
    }

    @Test
    fun sequenceGapRequestsRefreshAndIgnoresDeltasUntilSnapshot() {
        val codec = AgentdProtocolCodec()
        codec.parse("""{"type":"snapshot","seq":10,"sessions":[]}""")
        val gap = codec.parse(
            """{"type":"session_removed","seq":12,"sessionId":"lost"}""",
        )
        assertEquals(AgentdAction.Send(AgentdProtocolCodec.REFRESH), gap)
        assertEquals(
            AgentdAction.Ignore,
            codec.parse("""{"type":"session_removed","seq":13,"sessionId":"also-lost"}"""),
        )
        assertTrue(codec.parse("""{"type":"snapshot","seq":15,"sessions":[]}""") is AgentdAction.Snapshot)
        assertTrue(
            codec.parse(
                """{"type":"session_upsert","seq":16,"session":{"id":"ok","provider":"claude","status":"working"}}""",
            ) is AgentdAction.Upsert,
        )
    }
}
