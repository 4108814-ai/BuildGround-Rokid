from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
phone_path = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/PhoneCallTools.kt"
text = phone_path.read_text(encoding="utf-8")

old = '''        val phone = ContactsContract.CommonDataKinds.Phone
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
'''

new = '''        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        return resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            val numberColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val typeColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )
            val labelColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.LABEL,
            )
            val primaryColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            )
            val superPrimaryColumn = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            )
'''

count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one generated Phone constants block, found {count}")
phone_path.write_text(text.replace(old, new, 1), encoding="utf-8")

policy_test_path = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/NexusAgentPolicyTest.kt"
policy_test = policy_test_path.read_text(encoding="utf-8")
old_allowlist = '''                DELETE_CALENDAR_EVENT_TOOL_NAME,
            ),
            HERMES_TEXT_TOOL_NAMES,
'''
new_allowlist = '''                DELETE_CALENDAR_EVENT_TOOL_NAME,
                CALL_CONTACT_TOOL_NAME,
            ),
            HERMES_TEXT_TOOL_NAMES,
'''
count = policy_test.count(old_allowlist)
if count != 1:
    raise SystemExit(f"Expected exactly one Hermes tool allowlist assertion, found {count}")
policy_test_path.write_text(policy_test.replace(old_allowlist, new_allowlist, 1), encoding="utf-8")
