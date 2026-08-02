package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptAccountContextTest {
    @Test
    fun parseMemoriesExtractsContentsFromFullFixture() {
        val parsed = parseMemories(
            """
            {
              "memories": [
                {"id":"one","content":"Lives in Paris.","status":"warm"},
                {"id":"two","content":" Prefers metric units. ","status":"warm"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Lives in Paris.", "Prefers metric units."), parsed)
    }

    @Test
    fun parseMemoriesReturnsEmptyForEmptyFixture() {
        assertEquals(emptyList<String>(), parseMemories("""{"memories":[]}"""))
    }

    @Test
    fun parseMemoriesSkipsBlankAndMissingContent() {
        val parsed = parseMemories(
            """
            {"memories":[
              {"content":""},
              {"content":"  \n  "},
              {"id":"missing"},
              {"content":"Kept."}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("Kept."), parsed)
    }

    @Test
    fun parseMemoriesReturnsEmptyForMalformedJson() {
        assertEquals(emptyList<String>(), parseMemories("<html>challenge</html>"))
        assertEquals(emptyList<String>(), parseMemories("{"))
    }

    @Test
    fun parseUserSystemMessagesMapsFullFixtureAndDeduplicatesPreferences() {
        val parsed = parseUserSystemMessages(
            """
            {
              "object":"user_system_message_detail",
              "about_user_message":"Lives in Paris.",
              "about_model_message":"Be detailed and cite sources.",
              "name_user_message":"Sam",
              "role_user_message":"Engineer",
              "traits_model_message":"Be detailed and cite sources.",
              "other_user_message":"Uses metric units.",
              "personality_type_selection":"default",
              "enabled":true
            }
            """.trimIndent(),
        )

        assertEquals("Sam", parsed.callName)
        assertEquals("Engineer", parsed.userRole)
        assertEquals("Lives in Paris.", parsed.aboutUser)
        assertEquals("Be detailed and cite sources.", parsed.responsePrefs)
        assertEquals("Uses metric units.", parsed.other)
        assertTrue(parsed.memories.isEmpty())
    }

    @Test
    fun parseUserSystemMessagesKeepsDistinctPreferenceFields() {
        val parsed = parseUserSystemMessages(
            """
            {
              "about_model_message":"Be concise.",
              "traits_model_message":"Use citations."
            }
            """.trimIndent(),
        )

        assertEquals("Be concise.\nUse citations.", parsed.responsePrefs)
    }

    @Test
    fun parseUserSystemMessagesReturnsEmptyWhenAllFieldsAreBlank() {
        val parsed = parseUserSystemMessages(
            """
            {
              "name_user_message":" ",
              "role_user_message":"",
              "about_user_message":"\n",
              "about_model_message":"",
              "traits_model_message":" ",
              "other_user_message":""
            }
            """.trimIndent(),
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun parseUserSystemMessagesReturnsEmptyWhenDisabled() {
        val parsed = parseUserSystemMessages(
            """
            {
              "enabled":false,
              "name_user_message":"Sam",
              "about_user_message":"Should be ignored.",
              "about_model_message":"Should also be ignored."
            }
            """.trimIndent(),
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun renderForPromptOmitsEmptySections() {
        val aboutOnly = ChatGptAccountContext(aboutUser = "Lives in Paris.")
            .renderForPrompt(maxChars = 1_000)
        val preferencesOnly = ChatGptAccountContext(responsePrefs = "Use citations.")
            .renderForPrompt(maxChars = 1_000)

        assertEquals("About the user: Lives in Paris.", aboutOnly)
        assertFalse(aboutOnly.contains("response preferences"))
        assertEquals("The user's response preferences:\nUse citations.", preferencesOnly)
        assertFalse(preferencesOnly.contains("Saved memories"))
    }

    @Test
    fun renderForPromptOrdersAboutMaterialThenResponsePreferences() {
        val rendered = ChatGptAccountContext(
            memories = listOf("First memory.", "Second memory."),
            callName = "Sam",
            userRole = "Engineer",
            aboutUser = "Lives in Paris.",
            responsePrefs = "Use citations.",
            other = "Uses metric units.",
        ).renderForPrompt(maxChars = 2_000)

        val markers = listOf(
            "Call the user: Sam",
            "User role: Engineer",
            "About the user: Lives in Paris.",
            "Saved memories:\n- First memory.\n- Second memory.",
            "Other user context: Uses metric units.",
            "The user's response preferences:\nUse citations.",
        )
        markers.zipWithNext().forEach { (before, after) ->
            assertTrue("Expected '$before' before '$after'", rendered.indexOf(before) < rendered.indexOf(after))
        }
    }

    @Test
    fun renderForPromptTruncatesOnAWordBoundary() {
        val rendered = ChatGptAccountContext(aboutUser = "alpha beta gamma delta")
            .renderForPrompt(maxChars = 28)

        assertEquals("About the user: alpha beta", rendered)
        assertTrue(rendered.length <= 28)
    }

    @Test
    fun renderForPromptReturnsEmptyForEmptyContext() {
        assertEquals("", ChatGptAccountContext().renderForPrompt(maxChars = 1_000))
    }

    @Test
    fun fetchRefreshesOnceOnUnauthorizedAndRetriesWithNewTokens() = runTest {
        val requests = mutableListOf<ChatGptAccountContextHttpRequest>()
        val responses = ArrayDeque(
            listOf(
                ChatGptAccountContextHttpResponse(401, "<html>challenge</html>"),
                ChatGptAccountContextHttpResponse(200, """{"memories":[{"content":"One"}]}"""),
                ChatGptAccountContextHttpResponse(200, """{"about_user_message":"About"}"""),
            ),
        )
        var refreshCount = 0
        val client = ChatGptAccountContextClient(
            tokenProvider = { tokens("old-token") },
            isChatGptMode = { true },
            refreshTokens = {
                refreshCount += 1
                tokens("new-token")
            },
            transport = ChatGptAccountContextHttpTransport { request ->
                requests += request
                responses.removeFirst()
            },
        )

        val context = client.fetch()

        assertEquals(1, refreshCount)
        assertEquals(listOf("One"), context.memories)
        assertEquals("About", context.aboutUser)
        assertEquals("Bearer old-token", requests[0].headers["Authorization"])
        assertEquals("Bearer new-token", requests[1].headers["Authorization"])
        assertEquals("Bearer new-token", requests[2].headers["Authorization"])
    }

    @Test
    fun fetchTreatsNonJsonSuccessAsUnavailable() = runTest {
        val client = ChatGptAccountContextClient(
            tokenProvider = { tokens("token") },
            isChatGptMode = { true },
            refreshTokens = { error("Refresh was not expected") },
            transport = ChatGptAccountContextHttpTransport {
                ChatGptAccountContextHttpResponse(200, "<html>challenge</html>")
            },
        )

        val error = runCatching { client.fetch() }.exceptionOrNull()

        assertTrue(error is ChatGptAccountContextUnavailableException)
        assertEquals(
            SyncUnavailableReason.INVALID_RESPONSE,
            (error as ChatGptAccountContextUnavailableException).reason,
        )
    }

    private fun tokens(accessToken: String) = CodexChatGptOAuthTokenBundle(
        accessToken = accessToken,
        idToken = "id-token",
        refreshToken = "refresh-token",
        accountId = "account-id",
        planType = null,
        email = null,
    )
}
