package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class CodexChatGptOAuthTest {
    @Test
    fun loginAttemptUsesCodexPkceLoopbackFlow() {
        val attempt = CodexChatGptOAuth.createLoginAttempt()
        val uri = URI(attempt.authorizeUrl)
        val query = uri.rawQuery
            .split("&")
            .associate { pair ->
                val parts = pair.split("=", limit = 2)
                val key = URLDecoder.decode(parts[0], Charsets.UTF_8.name())
                val value = URLDecoder.decode(
                    parts.getOrElse(1) { "" },
                    Charsets.UTF_8.name(),
                )
                key to value
            }

        assertEquals("auth.openai.com", uri.host)
        assertEquals("/oauth/authorize", uri.path)
        assertEquals("code", query["response_type"])
        assertEquals(CodexChatGptOAuth.clientId, query["client_id"])
        assertEquals(attempt.redirectUri, query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("true", query["codex_cli_simplified_flow"])
        assertTrue(attempt.codeVerifier.length > 20)
    }
}
