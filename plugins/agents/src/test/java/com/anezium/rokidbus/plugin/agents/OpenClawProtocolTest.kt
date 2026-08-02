package com.anezium.rokidbus.plugin.agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawProtocolTest {
    @Test
    fun mapsRealGatewaySessionListFields() {
        val payload = JSONObject(
            """
            {
              "ts": 1000,
              "path": "/state/sessions.json",
              "count": 3,
              "defaults": {"modelProvider":"anthropic","model":"claude","contextTokens":200000},
              "sessions": [
                {"key":"agent:main:one","kind":"direct","displayName":"Streaming","updatedAt":900,"lastActivityAt":880,"hasActiveRun":true,"activeRunIds":["run-1"],"execCwd":"/work/one"},
                {"key":"agent:main:two","kind":"direct","derivedTitle":"Failed","updatedAt":800,"status":"failed","lastRunError":"tool crashed"},
                {"key":"agent:main:three","kind":"direct","label":"Resting","updatedAt":700,"status":"done","agentStatus":{"note":"Wrapped up","expiresAt":2000}}
              ]
            }
            """.trimIndent(),
        )
        val sessions = OpenClawProtocol.mapSessions(payload)
        assertEquals(AgentStatus.WORKING, sessions[0].status)
        assertEquals("/work/one", sessions[0].cwd)
        assertEquals(AgentStatus.ERROR, sessions[1].status)
        assertEquals("tool crashed", sessions[1].statusDetail)
        assertEquals(AgentStatus.IDLE, sessions[2].status)
        assertEquals("Wrapped up", sessions[2].statusDetail)
    }

    @Test
    fun approvalRequestedFixtureMapsToNeedsYouOverlay() {
        val frame = JSONObject(
            """
            {
              "type":"event",
              "event":"exec.approval.requested",
              "payload":{
                "id":"approval-1",
                "request":{"command":"git status","sessionKey":"agent:main:one"},
                "createdAtMs":1234,
                "expiresAtMs":4234
              }
            }
            """.trimIndent(),
        )
        val event = OpenClawProtocol.parseEvent(frame) as OpenClawEvent.ApprovalRequested
        val payload = JSONObject(
            """{"sessions":[{"key":"agent:main:one","kind":"direct","displayName":"One","updatedAt":1200}]}""",
        )
        val mapped = OpenClawProtocol.mapSessions(payload, listOf(event.approval)).single()
        assertEquals(AgentStatus.NEEDS_YOU, mapped.status)
        assertEquals("git status", mapped.pendingRequest?.summary)
        assertEquals(1234L, mapped.pendingRequest?.createdAt)
    }

    @Test
    fun challengeAndChangedEventsUseRealEnvelopes() {
        val challenge = OpenClawProtocol.parseEvent(
            JSONObject("""{"type":"event","event":"connect.challenge","payload":{"nonce":"abc"}}"""),
        )
        assertEquals(OpenClawEvent.ConnectChallenge("abc"), challenge)
        assertTrue(
            OpenClawProtocol.parseEvent(
                JSONObject("""{"type":"event","event":"sessions.changed","payload":{"sessionKey":"agent:main:one","reason":"run"}}"""),
            ) is OpenClawEvent.SessionsChanged,
        )
        assertEquals(
            OpenClawEvent.ApprovalResolved("approval-1"),
            OpenClawProtocol.parseEvent(
                JSONObject(
                    """{"type":"event","event":"exec.approval.resolved","payload":{"id":"approval-1","decision":"allow-once","resolvedAtMs":2000}}""",
                ),
            ),
        )
    }

    @Test
    fun missingOrWrongSessionsFieldIsMalformedRatherThanEmpty() {
        assertNull(OpenClawProtocol.mapSessionsOrNull(JSONObject("""{"count":0}""")))
        assertNull(
            OpenClawProtocol.mapSessionsOrNull(
                JSONObject("""{"sessions":{"key":"not-an-array"}}"""),
            ),
        )
        assertEquals(
            emptyList<AgentSession>(),
            OpenClawProtocol.mapSessionsOrNull(JSONObject("""{"sessions":[]}""")),
        )
    }

    @Test
    fun listIsCappedAndScalarTypesAreStrict() {
        val rows = (0 until MAX_SESSIONS_PER_PROVIDER + 20).joinToString(",") {
            """{"key":"agent:$it","displayName":"Session $it","updatedAt":$it}"""
        }
        val mapped = OpenClawProtocol.mapSessions(JSONObject("""{"sessions":[$rows]}"""))
        assertEquals(MAX_SESSIONS_PER_PROVIDER, mapped.size)

        val strict = OpenClawProtocol.mapSessions(
            JSONObject(
                """{"sessions":[
                    {"key":7,"displayName":"coerced"},
                    {"key":"valid","displayName":8,"hasActiveRun":"true"}
                ]}""".trimIndent(),
            ),
        )
        assertEquals(1, strict.size)
        assertEquals("valid", strict.single().title)
        assertEquals(AgentStatus.IDLE, strict.single().status)
    }

    @Test
    fun retainedOpenClawStringsAreBounded() {
        val longTitle = "x".repeat(MAX_HUD_LABEL_CHARS + 50)
        val mapped = OpenClawProtocol.mapSessions(
            JSONObject(
                """{"sessions":[{"key":"valid","displayName":"$longTitle","lastRunError":"${"e".repeat(MAX_STATUS_DETAIL_CHARS + 50)}"}]}""",
            ),
        ).single()
        assertEquals(MAX_HUD_LABEL_CHARS, mapped.title?.length)
        assertEquals(MAX_STATUS_DETAIL_CHARS, mapped.statusDetail?.length)
    }

    @Test
    fun approvalEventRejectsWrongScalarTypes() {
        assertTrue(
            OpenClawProtocol.parseEvent(
                JSONObject(
                    """{"type":"event","event":"exec.approval.requested","payload":{"id":5,"request":{"sessionKey":"s","command":"run"}}}""",
                ),
            ) is OpenClawEvent.Unknown,
        )
        assertTrue(
            OpenClawProtocol.parseEvent(
                JSONObject(
                    """{"type":"event","event":"connect.challenge","payload":{"nonce":{"nested":true}}}""",
                ),
            ) is OpenClawEvent.Unknown,
        )
    }
}
