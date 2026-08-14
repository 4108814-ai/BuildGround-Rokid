from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {relative_path}, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "plugins/assistant/src/main/AndroidManifest.xml",
    '    <uses-permission android:name="android.permission.INTERNET" />\n',
    '    <uses-permission android:name="android.permission.INTERNET" />\n'
    '    <uses-permission android:name="android.permission.READ_CONTACTS" />\n'
    '    <uses-permission android:name="android.permission.CALL_PHONE" />\n',
)

replace_once(
    "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantTool.kt",
    "    DELETE_CALENDAR_EVENT_TOOL_NAME,\n)",
    "    DELETE_CALENDAR_EVENT_TOOL_NAME,\n    CALL_CONTACT_TOOL_NAME,\n)",
)

replace_once(
    "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt",
    "    private val calendarGateway by lazy { AndroidCalendarGateway(applicationContext) }\n",
    "    private val calendarGateway by lazy { AndroidCalendarGateway(applicationContext) }\n"
    "    private val phoneGateway by lazy { AndroidPhoneGateway(applicationContext) }\n",
)
replace_once(
    "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt",
    "                ) + assistantCalendarTools(calendarGateway),\n",
    "                ) + assistantCalendarTools(calendarGateway) +\n"
    "                assistantPhoneTools(phoneGateway),\n",
)

settings = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
replace_once(
    settings,
    "    private lateinit var calendarAccessSlot: LinearLayout\n",
    "    private lateinit var calendarAccessSlot: LinearLayout\n"
    "    private lateinit var phoneAccessSlot: LinearLayout\n",
)
replace_once(
    settings,
    "        renderProductivityCard()\n        renderCalendarAccess()\n        maybeDetectHermes(ProviderCatalog.custom)\n",
    "        renderProductivityCard()\n        renderCalendarAccess()\n        renderPhoneAccess()\n"
    "        maybeDetectHermes(ProviderCatalog.custom)\n",
)
replace_once(
    settings,
    "            addView(calendarAccessSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, \"Plugin\"), NexusUi.block())\n",
    "            addView(calendarAccessSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(\n"
    "                NexusUi.sectionRow(this@AssistantSettingsActivity, \"Phone calls\"),\n"
    "                NexusUi.block(),\n"
    "            )\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))\n"
    "            phoneAccessSlot = LinearLayout(this@AssistantSettingsActivity).apply {\n"
    "                orientation = LinearLayout.VERTICAL\n"
    "            }\n"
    "            addView(phoneAccessSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, \"Plugin\"), NexusUi.block())\n",
)
replace_once(
    settings,
    "        renderMemory()\n        renderProductivityCard()\n        renderCalendarAccess()\n    }\n\n    // ------------------------------------------------------------------ providers\n",
    "        renderMemory()\n        renderProductivityCard()\n        renderCalendarAccess()\n        renderPhoneAccess()\n"
    "    }\n\n    // ------------------------------------------------------------------ providers\n",
)
replace_once(
    settings,
    "        super.onRequestPermissionsResult(requestCode, permissions, grantResults)\n"
    "        if (requestCode == REQUEST_CALENDAR_ACCESS) renderCalendarAccess()\n"
    "    }\n\n    // ------------------------------------------------------------------ memory\n",
    "        super.onRequestPermissionsResult(requestCode, permissions, grantResults)\n"
    "        when (requestCode) {\n"
    "            REQUEST_CALENDAR_ACCESS -> renderCalendarAccess()\n"
    "            REQUEST_PHONE_ACCESS -> renderPhoneAccess()\n"
    "        }\n"
    "    }\n\n"
    "    private fun renderPhoneAccess() {\n"
    "        val granted = PHONE_PERMISSIONS.all { permission ->\n"
    "            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED\n"
    "        }\n"
    "        phoneAccessSlot.removeAllViews()\n"
    "        phoneAccessSlot.addView(\n"
    "            NexusUi.card(this).apply {\n"
    "                addView(\n"
    "                    LinearLayout(this@AssistantSettingsActivity).apply {\n"
    "                        orientation = LinearLayout.HORIZONTAL\n"
    "                        gravity = Gravity.CENTER_VERTICAL\n"
    "                        addView(\n"
    "                            LinearLayout(this@AssistantSettingsActivity).apply {\n"
    "                                orientation = LinearLayout.VERTICAL\n"
    "                                addView(\n"
    "                                    NexusUi.rowTitle(\n"
    "                                        this@AssistantSettingsActivity,\n"
    "                                        \"Contacts & calling\",\n"
    "                                    ),\n"
    "                                    NexusUi.block(),\n"
    "                                )\n"
    "                                addView(\n"
    "                                    NexusUi.rowSub(\n"
    "                                        this@AssistantSettingsActivity,\n"
    "                                        if (granted) {\n"
    "                                            \"Say ‘call…’ or ‘позвони…’ to place calls from your phone\"\n"
    "                                        } else {\n"
    "                                            \"Allow the assistant to find contacts and place phone calls\"\n"
    "                                        },\n"
    "                                    ),\n"
    "                                    NexusUi.block(),\n"
    "                                )\n"
    "                            },\n"
    "                            LinearLayout.LayoutParams(\n"
    "                                0,\n"
    "                                ViewGroup.LayoutParams.WRAP_CONTENT,\n"
    "                                1f,\n"
    "                            ).apply {\n"
    "                                marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)\n"
    "                            },\n"
    "                        )\n"
    "                        if (granted) {\n"
    "                            addView(\n"
    "                                NexusUi.metaLabel(\n"
    "                                    this@AssistantSettingsActivity,\n"
    "                                    \"Granted\",\n"
    "                                    NexusUi.GREEN,\n"
    "                                ),\n"
    "                            )\n"
    "                        } else {\n"
    "                            addView(\n"
    "                                NexusUi.textButton(\n"
    "                                    this@AssistantSettingsActivity,\n"
    "                                    \"Grant access\",\n"
    "                                ).apply {\n"
    "                                    setOnClickListener { requestPhoneAccess() }\n"
    "                                },\n"
    "                            )\n"
    "                        }\n"
    "                    },\n"
    "                    NexusUi.block(),\n"
    "                )\n"
    "            },\n"
    "            NexusUi.block(),\n"
    "        )\n"
    "    }\n\n"
    "    private fun requestPhoneAccess() {\n"
    "        requestPermissions(PHONE_PERMISSIONS, REQUEST_PHONE_ACCESS)\n"
    "    }\n\n"
    "    // ------------------------------------------------------------------ memory\n",
)
replace_once(
    settings,
    "        const val REQUEST_CALENDAR_ACCESS = 1201\n"
    "        val CALENDAR_PERMISSIONS = arrayOf(\n"
    "            Manifest.permission.READ_CALENDAR,\n"
    "            Manifest.permission.WRITE_CALENDAR,\n"
    "        )\n",
    "        const val REQUEST_CALENDAR_ACCESS = 1201\n"
    "        const val REQUEST_PHONE_ACCESS = 1202\n"
    "        val CALENDAR_PERMISSIONS = arrayOf(\n"
    "            Manifest.permission.READ_CALENDAR,\n"
    "            Manifest.permission.WRITE_CALENDAR,\n"
    "        )\n"
    "        val PHONE_PERMISSIONS = arrayOf(\n"
    "            Manifest.permission.READ_CONTACTS,\n"
    "            Manifest.permission.CALL_PHONE,\n"
    "        )\n",
)

phone_tools = r'''package com.anezium.rokidbus.plugin.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val CALL_CONTACT_TOOL_NAME = "call_contact"
internal const val TOOL_ERROR_PHONE_PERMISSION_REQUIRED = "phone_permission_required"
internal const val TOOL_ERROR_CONTACT_NOT_FOUND = "contact_not_found"

internal data class AssistantPhoneEntry(
    val contactId: Long,
    val displayName: String,
    val number: String,
    val label: String,
    val isPrimary: Boolean,
    val isSuperPrimary: Boolean,
)

internal interface AssistantPhoneGateway {
    fun canReadContacts(): Boolean
    fun canPlaceCalls(): Boolean
    fun phoneEntries(): List<AssistantPhoneEntry>
    fun placeCall(number: String): Boolean
}

internal class AndroidPhoneGateway(context: Context) : AssistantPhoneGateway {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun canReadContacts(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override fun canPlaceCalls(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    override fun phoneEntries(): List<AssistantPhoneEntry> {
        if (!canReadContacts()) return emptyList()
        val phone = ContactsContract.CommonDataKinds.Phone
        val projection = arrayOf(
            phone.CONTACT_ID,
            phone.DISPLAY_NAME,
            phone.NUMBER,
            phone.TYPE,
            phone.LABEL,
            phone.IS_PRIMARY,
            phone.IS_SUPER_PRIMARY,
        )
        return resolver.query(
            phone.CONTENT_URI,
            projection,
            null,
            null,
            phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(phone.CONTACT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(phone.DISPLAY_NAME)
            val numberColumn = cursor.getColumnIndexOrThrow(phone.NUMBER)
            val typeColumn = cursor.getColumnIndexOrThrow(phone.TYPE)
            val labelColumn = cursor.getColumnIndexOrThrow(phone.LABEL)
            val primaryColumn = cursor.getColumnIndexOrThrow(phone.IS_PRIMARY)
            val superPrimaryColumn = cursor.getColumnIndexOrThrow(phone.IS_SUPER_PRIMARY)
            buildList {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameColumn).orEmpty().trim()
                    val number = cursor.getString(numberColumn).orEmpty().trim()
                    if (displayName.isBlank() || number.isBlank()) continue
                    val type = cursor.getInt(typeColumn)
                    val customLabel = cursor.getString(labelColumn)
                    add(
                        AssistantPhoneEntry(
                            contactId = cursor.getLong(idColumn),
                            displayName = displayName,
                            number = number,
                            label = ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(appContext.resources, type, customLabel)
                                .toString()
                                .ifBlank { "phone" },
                            isPrimary = cursor.getInt(primaryColumn) == 1,
                            isSuperPrimary = cursor.getInt(superPrimaryColumn) == 1,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override fun placeCall(number: String): Boolean {
        if (!canPlaceCalls()) return false
        val telecom = appContext.getSystemService(TelecomManager::class.java) ?: return false
        return runCatching {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle.EMPTY)
        }.isSuccess
    }
}

internal fun assistantPhoneTools(gateway: AssistantPhoneGateway): List<AssistantToolDefinition> =
    listOf(CallContactTool(gateway))

internal class CallContactTool(
    private val gateway: AssistantPhoneGateway,
) : TextAssistantTool() {
    override val name = CALL_CONTACT_TOOL_NAME
    override val description =
        "Place a real phone call to a person in the phone's contacts when the user explicitly " +
            "asks to call, phone, ring, dial, or 'набери/позвони' that person. Pass the contact " +
            "name in the form most likely stored in the address book (for inflected languages, " +
            "use the canonical name without changing who the user meant). If the tool returns " +
            "needs_clarification, ask the user which listed person or phone label they mean, then " +
            "call this tool again with the same query and their clarification in detail. Never " +
            "silently choose between ambiguous people or non-default numbers."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"query":{"type":"string","minLength":1,"description":"Contact name to call"},"detail":{"type":["string","null"],"description":"User clarification such as surname, company, mobile/work/home label, or last digits"}},"required":["query","detail"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val progressLabel = "Calling…"
    override val retiresProgressOnSuccess = true
    override val executionFailureCode = "phone_call_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictPhoneArguments(argumentsJson)
            ?: return AssistantToolValidation.Invalid()
        val query = arguments.opt("query") as? String
        if (query.isNullOrBlank()) return AssistantToolValidation.Invalid()
        val detailValue = arguments.opt("detail")
        if (detailValue != null && detailValue != JSONObject.NULL && detailValue !is String) {
            return AssistantToolValidation.Invalid()
        }
        return AssistantToolValidation.Valid(
            JSONObject()
                .put("query", query.trim())
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
        if (!gateway.canReadContacts() || !gateway.canPlaceCalls()) {
            return@withContext phonePermissionError()
        }
        val query = arguments.getString("query")
        val detail = if (arguments.isNull("detail")) null else arguments.getString("detail")
        val ranked = rankContacts(query, gateway.phoneEntries())
        if (ranked.isEmpty()) {
            return@withContext AssistantToolResult.Error(
                code = TOOL_ERROR_CONTACT_NOT_FOUND,
                detailsJson = JSONObject()
                    .put("message", "No matching phone contact was found.")
                    .put("query", query)
                    .toString(),
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
        if (candidates.isEmpty()) {
            return@withContext clarificationResult(query, bestEntries, detail)
        }
        val selected = when {
            candidates.size == 1 -> candidates.single()
            candidates.map { it.contactId }.distinct().size == 1 ->
                candidates.singleOrNull { it.isSuperPrimary }
            else -> null
        }
        if (selected == null) {
            return@withContext clarificationResult(query, candidates, detail)
        }
        if (!gateway.placeCall(selected.number)) {
            return@withContext AssistantToolResult.Error(executionFailureCode)
        }
        AssistantToolResult.Json(
            JSONObject()
                .put("placed", true)
                .put("contact", selected.displayName)
                .put("label", selected.label)
                .toString(),
        )
    }
}

private data class RankedPhoneContact(
    val score: Int,
    val entries: List<AssistantPhoneEntry>,
)

private fun rankContacts(
    query: String,
    entries: List<AssistantPhoneEntry>,
): List<RankedPhoneContact> {
    val normalizedQuery = normalizeContactText(query)
    if (normalizedQuery.isBlank()) return emptyList()
    return entries
        .groupBy { it.contactId }
        .mapNotNull { (_, contactEntries) ->
            val displayName = contactEntries.firstOrNull()?.displayName.orEmpty()
            val score = contactMatchScore(normalizedQuery, normalizeContactText(displayName))
                ?: return@mapNotNull null
            RankedPhoneContact(score, contactEntries)
        }
        .sortedWith(
            compareBy<RankedPhoneContact> { it.score }
                .thenBy { it.entries.first().displayName },
        )
}

private fun contactMatchScore(query: String, name: String): Int? {
    if (query == name) return 0
    val queryTokens = query.split(' ').filter(String::isNotBlank)
    val nameTokens = name.split(' ').filter(String::isNotBlank)
    if (queryTokens.isNotEmpty() && queryTokens.all { queryToken ->
            nameTokens.any { nameToken -> nameToken == queryToken || nameToken.startsWith(queryToken) }
        }
    ) return 1
    if (name.startsWith(query)) return 2
    if (name.contains(query)) return 3
    return null
}

private fun detailMatches(detail: String, entry: AssistantPhoneEntry): Boolean {
    val normalized = normalizeContactText(detail)
    if (normalized.isBlank()) return false
    val haystack = normalizeContactText(entry.displayName + " " + entry.label)
    if (haystack.contains(normalized)) return true
    val requestedDigits = normalizedPhoneDigits(detail)
    return requestedDigits.length >= 2 && normalizedPhoneDigits(entry.number).endsWith(requestedDigits)
}

private fun clarificationResult(
    query: String,
    candidates: List<AssistantPhoneEntry>,
    detail: String?,
): AssistantToolResult = AssistantToolResult.Json(
    JSONObject()
        .put("placed", false)
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

private fun phonePermissionError(): AssistantToolResult.Error = AssistantToolResult.Error(
    code = TOOL_ERROR_PHONE_PERMISSION_REQUIRED,
    detailsJson = JSONObject()
        .put(
            "message",
            "Phone calls need Contacts and Phone access. Open Assistant settings and grant Phone calls access.",
        )
        .toString(),
)

private fun strictPhoneArguments(argumentsJson: String): JSONObject? {
    val parsed = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return null
    val allowed = setOf("query", "detail")
    val keys = parsed.keys()
    while (keys.hasNext()) {
        if (keys.next() !in allowed) return null
    }
    if (!parsed.has("query") || !parsed.has("detail")) return null
    return parsed
}

private fun normalizeContactText(value: String): String =
    value
        .lowercase(Locale.getDefault())
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun normalizedPhoneDigits(value: String): String = value.filter(Char::isDigit)

private const val MAX_PHONE_CLARIFICATION_MATCHES = 6
'''

phone_test = r'''package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallToolsTest {
    @Test
    fun `unique contact places call`() = runTest {
        val gateway = FakePhoneGateway(
            entries = listOf(phone(1, "Сергей Иванов", "+79990001122", "Mobile")),
        )
        val result = CallContactTool(gateway).execute(
            AssistantToolCall("call-1", CALL_CONTACT_TOOL_NAME, ""),
            JSONObject().put("query", "Сергей Иванов").put("detail", JSONObject.NULL),
        )

        assertTrue(result is AssistantToolResult.Json)
        assertEquals(listOf("+79990001122"), gateway.placedNumbers)
        assertTrue(JSONObject((result as AssistantToolResult.Json).text).getBoolean("placed"))
    }

    @Test
    fun `ambiguous contacts ask for clarification without calling`() = runTest {
        val gateway = FakePhoneGateway(
            entries = listOf(
                phone(1, "Сергей Иванов", "+79990001122", "Mobile"),
                phone(2, "Сергей Петров", "+79990003344", "Mobile"),
            ),
        )
        val result = CallContactTool(gateway).execute(
            AssistantToolCall("call-2", CALL_CONTACT_TOOL_NAME, ""),
            JSONObject().put("query", "Сергей").put("detail", JSONObject.NULL),
        ) as AssistantToolResult.Json

        assertTrue(JSONObject(result.text).getBoolean("needs_clarification"))
        assertTrue(gateway.placedNumbers.isEmpty())
    }

    @Test
    fun `phone label clarification selects requested number`() = runTest {
        val gateway = FakePhoneGateway(
            entries = listOf(
                phone(1, "Анна", "+79990001122", "Mobile"),
                phone(1, "Анна", "+74950003344", "Work"),
            ),
        )
        val result = CallContactTool(gateway).execute(
            AssistantToolCall("call-3", CALL_CONTACT_TOOL_NAME, ""),
            JSONObject().put("query", "Анна").put("detail", "Work"),
        ) as AssistantToolResult.Json

        assertTrue(JSONObject(result.text).getBoolean("placed"))
        assertEquals(listOf("+74950003344"), gateway.placedNumbers)
    }

    @Test
    fun `missing permissions never queries or calls`() = runTest {
        val gateway = FakePhoneGateway(readAllowed = false, callAllowed = false)
        val result = CallContactTool(gateway).execute(
            AssistantToolCall("call-4", CALL_CONTACT_TOOL_NAME, ""),
            JSONObject().put("query", "Анна").put("detail", JSONObject.NULL),
        )

        assertTrue(result is AssistantToolResult.Error)
        assertEquals(
            TOOL_ERROR_PHONE_PERMISSION_REQUIRED,
            (result as AssistantToolResult.Error).code,
        )
        assertFalse(gateway.phoneEntriesRead)
        assertTrue(gateway.placedNumbers.isEmpty())
    }

    private fun phone(
        contactId: Long,
        name: String,
        number: String,
        label: String,
    ) = AssistantPhoneEntry(
        contactId = contactId,
        displayName = name,
        number = number,
        label = label,
        isPrimary = false,
        isSuperPrimary = false,
    )

    private class FakePhoneGateway(
        private val entries: List<AssistantPhoneEntry> = emptyList(),
        private val readAllowed: Boolean = true,
        private val callAllowed: Boolean = true,
    ) : AssistantPhoneGateway {
        val placedNumbers = mutableListOf<String>()
        var phoneEntriesRead = false

        override fun canReadContacts(): Boolean = readAllowed
        override fun canPlaceCalls(): Boolean = callAllowed

        override fun phoneEntries(): List<AssistantPhoneEntry> {
            phoneEntriesRead = true
            return entries
        }

        override fun placeCall(number: String): Boolean {
            placedNumbers += number
            return true
        }
    }
}
'''

phone_path = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/PhoneCallTools.kt"
test_path = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/PhoneCallToolsTest.kt"
if phone_path.exists() or test_path.exists():
    raise SystemExit("Phone call generated files already exist; refusing to overwrite")
phone_path.write_text(phone_tools, encoding="utf-8")
test_path.write_text(phone_test, encoding="utf-8")
