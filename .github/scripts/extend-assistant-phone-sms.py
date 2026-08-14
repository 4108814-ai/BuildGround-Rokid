from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {relative_path}, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


manifest = "plugins/assistant/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.CALL_PHONE" />\n',
    '    <uses-permission android:name="android.permission.CALL_PHONE" />\n'
    '    <uses-permission android:name="android.permission.SEND_SMS" />\n',
)

settings = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
replace_once(
    settings,
    '                                            "Contacts & calling",\n',
    '                                            "Contacts, calling & SMS",\n',
)
replace_once(
    settings,
    '                                            "Say ‘call…’ or ‘позвони…’ to place calls from your phone"\n',
    '                                            "Say ‘call…’, ‘позвони…’ or ‘напиши SMS…’"\n',
)
replace_once(
    settings,
    '                                            "Allow the assistant to find contacts and place phone calls"\n',
    '                                            "Allow contacts, phone calls and SMS"\n',
)
replace_once(
    settings,
    "            Manifest.permission.CALL_PHONE,\n        )\n",
    "            Manifest.permission.CALL_PHONE,\n"
    "            Manifest.permission.SEND_SMS,\n"
    "        )\n",
)

assistant_tool = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantTool.kt"
replace_once(
    assistant_tool,
    "    CALL_CONTACT_TOOL_NAME,\n)",
    "    CALL_CONTACT_TOOL_NAME,\n"
    "    REDIAL_LAST_ASSISTANT_CALL_TOOL_NAME,\n"
    "    SEND_SMS_CONTACT_TOOL_NAME,\n"
    ")",
)

phone = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/PhoneCallTools.kt"
replace_once(
    phone,
    "import android.telecom.TelecomManager\n",
    "import android.telecom.TelecomManager\nimport android.telephony.SmsManager\n",
)
replace_once(
    phone,
    'internal const val CALL_CONTACT_TOOL_NAME = "call_contact"\n',
    'internal const val CALL_CONTACT_TOOL_NAME = "call_contact"\n'
    'internal const val REDIAL_LAST_ASSISTANT_CALL_TOOL_NAME = "redial_last_assistant_call"\n'
    'internal const val SEND_SMS_CONTACT_TOOL_NAME = "send_sms_to_contact"\n',
)
replace_once(
    phone,
    "    fun canPlaceCalls(): Boolean\n    fun phoneEntries(): List<AssistantPhoneEntry>\n    fun placeCall(number: String): Boolean\n",
    "    fun canPlaceCalls(): Boolean\n"
    "    fun canSendSms(): Boolean\n"
    "    fun phoneEntries(): List<AssistantPhoneEntry>\n"
    "    fun placeCall(number: String): Boolean\n"
    "    fun sendSms(number: String, message: String): Boolean\n"
    "    fun rememberAssistantCall(entry: AssistantPhoneEntry)\n"
    "    fun lastAssistantCall(): AssistantPhoneEntry?\n",
)
replace_once(
    phone,
    "    private val resolver = appContext.contentResolver\n",
    "    private val resolver = appContext.contentResolver\n"
    "    private val prefs = appContext.getSharedPreferences(\"assistant_phone_actions\", Context.MODE_PRIVATE)\n",
)
replace_once(
    phone,
    "    override fun canPlaceCalls(): Boolean =\n"
    "        appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) ==\n"
    "            PackageManager.PERMISSION_GRANTED\n\n",
    "    override fun canPlaceCalls(): Boolean =\n"
    "        appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) ==\n"
    "            PackageManager.PERMISSION_GRANTED\n\n"
    "    override fun canSendSms(): Boolean =\n"
    "        appContext.checkSelfPermission(Manifest.permission.SEND_SMS) ==\n"
    "            PackageManager.PERMISSION_GRANTED\n\n",
)
replace_once(
    phone,
    "    override fun placeCall(number: String): Boolean {\n"
    "        if (!canPlaceCalls()) return false\n"
    "        val telecom = appContext.getSystemService(TelecomManager::class.java) ?: return false\n"
    "        return runCatching {\n"
    "            telecom.placeCall(Uri.fromParts(\"tel\", number, null), Bundle.EMPTY)\n"
    "        }.isSuccess\n"
    "    }\n"
    "}\n\n"
    "internal fun assistantPhoneTools(gateway: AssistantPhoneGateway): List<AssistantToolDefinition> =\n"
    "    listOf(CallContactTool(gateway))\n",
    "    override fun placeCall(number: String): Boolean {\n"
    "        if (!canPlaceCalls()) return false\n"
    "        val telecom = appContext.getSystemService(TelecomManager::class.java) ?: return false\n"
    "        return runCatching {\n"
    "            telecom.placeCall(Uri.fromParts(\"tel\", number, null), Bundle.EMPTY)\n"
    "        }.isSuccess\n"
    "    }\n\n"
    "    override fun sendSms(number: String, message: String): Boolean {\n"
    "        if (!canSendSms() || message.isBlank()) return false\n"
    "        val manager = appContext.getSystemService(SmsManager::class.java) ?: return false\n"
    "        return runCatching {\n"
    "            val parts = manager.divideMessage(message)\n"
    "            if (parts.size > 1) {\n"
    "                manager.sendMultipartTextMessage(number, null, parts, null, null)\n"
    "            } else {\n"
    "                manager.sendTextMessage(number, null, message, null, null)\n"
    "            }\n"
    "        }.isSuccess\n"
    "    }\n\n"
    "    override fun rememberAssistantCall(entry: AssistantPhoneEntry) {\n"
    "        prefs.edit().putString(\n"
    "            LAST_ASSISTANT_CALL_KEY,\n"
    "            JSONObject()\n"
    "                .put(\"contact_id\", entry.contactId)\n"
    "                .put(\"name\", entry.displayName)\n"
    "                .put(\"number\", entry.number)\n"
    "                .put(\"label\", entry.label)\n"
    "                .put(\"primary\", entry.isPrimary)\n"
    "                .put(\"super_primary\", entry.isSuperPrimary)\n"
    "                .toString(),\n"
    "        ).apply()\n"
    "    }\n\n"
    "    override fun lastAssistantCall(): AssistantPhoneEntry? {\n"
    "        val raw = prefs.getString(LAST_ASSISTANT_CALL_KEY, null) ?: return null\n"
    "        return runCatching {\n"
    "            val value = JSONObject(raw)\n"
    "            AssistantPhoneEntry(\n"
    "                contactId = value.getLong(\"contact_id\"),\n"
    "                displayName = value.getString(\"name\"),\n"
    "                number = value.getString(\"number\"),\n"
    "                label = value.getString(\"label\"),\n"
    "                isPrimary = value.optBoolean(\"primary\"),\n"
    "                isSuperPrimary = value.optBoolean(\"super_primary\"),\n"
    "            )\n"
    "        }.getOrNull()\n"
    "    }\n\n"
    "    private companion object {\n"
    "        const val LAST_ASSISTANT_CALL_KEY = \"last_assistant_call\"\n"
    "    }\n"
    "}\n\n"
    "internal fun assistantPhoneTools(gateway: AssistantPhoneGateway): List<AssistantToolDefinition> =\n"
    "    listOf(\n"
    "        CallContactTool(gateway),\n"
    "        RedialLastAssistantCallTool(gateway),\n"
    "        SendSmsToContactTool(gateway),\n"
    "    )\n",
)
replace_once(
    phone,
    "        if (!gateway.placeCall(selected.number)) {\n"
    "            return@withContext AssistantToolResult.Error(executionFailureCode)\n"
    "        }\n"
    "        AssistantToolResult.Json(\n",
    "        if (!gateway.placeCall(selected.number)) {\n"
    "            return@withContext AssistantToolResult.Error(executionFailureCode)\n"
    "        }\n"
    "        gateway.rememberAssistantCall(selected)\n"
    "        AssistantToolResult.Json(\n",
)

insert_before = "private data class RankedPhoneContact(\n"
text = (ROOT / phone).read_text(encoding="utf-8")
if text.count(insert_before) != 1:
    raise SystemExit("Could not locate RankedPhoneContact insertion point")
extra_tools = r'''internal class RedialLastAssistantCallTool(
    private val gateway: AssistantPhoneGateway,
) : TextAssistantTool() {
    override val name = REDIAL_LAST_ASSISTANT_CALL_TOOL_NAME
    override val description =
        "Redial the last contact successfully called through this Assistant. Use only when the " +
            "user explicitly says to redial/call the last person. This does not inspect the phone's " +
            "system call log."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{},"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val progressLabel = "Calling…"
    override val retiresProgressOnSuccess = true

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val parsed = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        return if (parsed.length() == 0) AssistantToolValidation.Valid(parsed)
        else AssistantToolValidation.Invalid()
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        if (!gateway.canPlaceCalls()) return@withContext phonePermissionError()
        val last = gateway.lastAssistantCall()
            ?: return@withContext AssistantToolResult.Error(
                code = "assistant_call_history_empty",
                detailsJson = JSONObject()
                    .put("message", "No previous Assistant-initiated call is available.")
                    .toString(),
            )
        if (!gateway.placeCall(last.number)) {
            return@withContext AssistantToolResult.Error("phone_call_failed")
        }
        gateway.rememberAssistantCall(last)
        AssistantToolResult.Json(
            JSONObject()
                .put("placed", true)
                .put("contact", last.displayName)
                .put("label", last.label)
                .toString(),
        )
    }
}

internal class SendSmsToContactTool(
    private val gateway: AssistantPhoneGateway,
) : TextAssistantTool() {
    override val name = SEND_SMS_CONTACT_TOOL_NAME
    override val description =
        "Send a real SMS text message to a person in the phone contacts. Use only when the user " +
            "explicitly asks to send/write an SMS. Preserve the user's intended message. If the " +
            "contact or phone number is ambiguous, return needs_clarification and ask before sending."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"query":{"type":"string","minLength":1},"detail":{"type":["string","null"]},"message":{"type":"string","minLength":1}},"required":["query","detail","message"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val progressLabel = "Sending SMS…"
    override val retiresProgressOnSuccess = true

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val parsed = strictSmsArguments(argumentsJson) ?: return AssistantToolValidation.Invalid()
        val query = parsed.optString("query").trim()
        val message = parsed.optString("message").trim()
        val detailValue = parsed.opt("detail")
        if (query.isBlank() || message.isBlank()) return AssistantToolValidation.Invalid()
        if (detailValue != null && detailValue != JSONObject.NULL && detailValue !is String) {
            return AssistantToolValidation.Invalid()
        }
        return AssistantToolValidation.Valid(
            JSONObject()
                .put("query", query)
                .put("message", message)
                .put(
                    "detail",
                    if (detailValue is String) detailValue.trim().ifBlank { JSONObject.NULL }
                    else JSONObject.NULL,
                ),
        )
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        if (!gateway.canReadContacts() || !gateway.canSendSms()) {
            return@withContext AssistantToolResult.Error(
                code = "sms_permission_required",
                detailsJson = JSONObject()
                    .put("message", "SMS needs Contacts and SMS access in Assistant settings.")
                    .toString(),
            )
        }
        val query = arguments.getString("query")
        val message = arguments.getString("message")
        val detail = if (arguments.isNull("detail")) null else arguments.getString("detail")
        val ranked = rankContacts(query, gateway.phoneEntries())
        if (ranked.isEmpty()) {
            return@withContext AssistantToolResult.Error(
                code = TOOL_ERROR_CONTACT_NOT_FOUND,
                detailsJson = JSONObject().put("query", query).toString(),
            )
        }
        val bestScore = ranked.minOf { it.score }
        val bestEntries = ranked
            .filter { it.score == bestScore }
            .flatMap { it.entries }
            .distinctBy { it.contactId to normalizedPhoneDigits(it.number) }
        val narrowed = detail?.let { requestedDetail ->
            bestEntries.filter { entry -> detailMatches(requestedDetail, entry) }
        }.orEmpty()
        val candidates = if (detail == null) bestEntries else narrowed
        val selected = when {
            candidates.size == 1 -> candidates.single()
            candidates.map { it.contactId }.distinct().size == 1 ->
                candidates.singleOrNull { it.isSuperPrimary }
            else -> null
        }
        if (selected == null) {
            return@withContext smsClarificationResult(query, candidates.ifEmpty { bestEntries }, detail)
        }
        if (!gateway.sendSms(selected.number, message)) {
            return@withContext AssistantToolResult.Error("sms_send_failed")
        }
        AssistantToolResult.Json(
            JSONObject()
                .put("sent", true)
                .put("contact", selected.displayName)
                .put("label", selected.label)
                .put("message", message)
                .toString(),
        )
    }
}

private fun smsClarificationResult(
    query: String,
    candidates: List<AssistantPhoneEntry>,
    detail: String?,
): AssistantToolResult = AssistantToolResult.Json(
    JSONObject()
        .put("sent", false)
        .put("needs_clarification", true)
        .put("query", query)
        .apply { if (!detail.isNullOrBlank()) put("detail", detail) }
        .put(
            "matches",
            JSONArray().apply {
                candidates.take(MAX_PHONE_CLARIFICATION_MATCHES).forEach { entry ->
                    put(
                        JSONObject()
                            .put("name", entry.displayName)
                            .put("label", entry.label)
                            .put("number_hint", normalizedPhoneDigits(entry.number).takeLast(4)),
                    )
                }
            },
        )
        .toString(),
)

private fun strictSmsArguments(argumentsJson: String): JSONObject? {
    val parsed = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return null
    val allowed = setOf("query", "detail", "message")
    val keys = parsed.keys()
    while (keys.hasNext()) {
        if (keys.next() !in allowed) return null
    }
    if (!parsed.has("query") || !parsed.has("detail") || !parsed.has("message")) return null
    return parsed
}

'''
(ROOT / phone).write_text(text.replace(insert_before, extra_tools + insert_before, 1), encoding="utf-8")

# Extend generated unit-test fake and add behavior tests.
test = "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/PhoneCallToolsTest.kt"
replace_once(
    test,
    "        private val callAllowed: Boolean = true,\n    ) : AssistantPhoneGateway {\n"
    "        val placedNumbers = mutableListOf<String>()\n"
    "        var phoneEntriesRead = false\n\n"
    "        override fun canReadContacts(): Boolean = readAllowed\n"
    "        override fun canPlaceCalls(): Boolean = callAllowed\n",
    "        private val callAllowed: Boolean = true,\n"
    "        private val smsAllowed: Boolean = true,\n"
    "    ) : AssistantPhoneGateway {\n"
    "        val placedNumbers = mutableListOf<String>()\n"
    "        val sentSms = mutableListOf<Pair<String, String>>()\n"
    "        var phoneEntriesRead = false\n"
    "        var lastCall: AssistantPhoneEntry? = null\n\n"
    "        override fun canReadContacts(): Boolean = readAllowed\n"
    "        override fun canPlaceCalls(): Boolean = callAllowed\n"
    "        override fun canSendSms(): Boolean = smsAllowed\n",
)
replace_once(
    test,
    "        override fun placeCall(number: String): Boolean {\n"
    "            placedNumbers += number\n"
    "            return true\n"
    "        }\n"
    "    }\n"
    "}\n",
    "        override fun placeCall(number: String): Boolean {\n"
    "            placedNumbers += number\n"
    "            return true\n"
    "        }\n\n"
    "        override fun sendSms(number: String, message: String): Boolean {\n"
    "            sentSms += number to message\n"
    "            return true\n"
    "        }\n\n"
    "        override fun rememberAssistantCall(entry: AssistantPhoneEntry) {\n"
    "            lastCall = entry\n"
    "        }\n\n"
    "        override fun lastAssistantCall(): AssistantPhoneEntry? = lastCall\n"
    "    }\n"
    "}\n",
)

# Insert tests before helper methods.
path = ROOT / test
text = path.read_text(encoding="utf-8")
marker = "    private fun phone(\n"
if text.count(marker) != 1:
    raise SystemExit("Could not locate phone test helper")
new_tests = r'''    @Test
    fun `sms to unique contact sends exact message`() = runTest {
        val gateway = FakePhoneGateway(
            entries = listOf(phone(1, "Анна", "+79990001122", "Mobile")),
        )
        val result = SendSmsToContactTool(gateway).execute(
            AssistantToolCall("sms-1", SEND_SMS_CONTACT_TOOL_NAME, ""),
            JSONObject()
                .put("query", "Анна")
                .put("detail", JSONObject.NULL)
                .put("message", "Буду через 20 минут"),
        ) as AssistantToolResult.Json

        assertTrue(JSONObject(result.text).getBoolean("sent"))
        assertEquals(listOf("+79990001122" to "Буду через 20 минут"), gateway.sentSms)
    }

    @Test
    fun `redial uses last Assistant initiated contact`() = runTest {
        val gateway = FakePhoneGateway()
        gateway.lastCall = phone(1, "Анна", "+79990001122", "Mobile")
        val result = RedialLastAssistantCallTool(gateway).execute(
            AssistantToolCall("redial-1", REDIAL_LAST_ASSISTANT_CALL_TOOL_NAME, ""),
            JSONObject(),
        ) as AssistantToolResult.Json

        assertTrue(JSONObject(result.text).getBoolean("placed"))
        assertEquals(listOf("+79990001122"), gateway.placedNumbers)
    }

'''
path.write_text(text.replace(marker, new_tests + marker, 1), encoding="utf-8")
