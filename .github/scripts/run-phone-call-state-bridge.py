from pathlib import Path

patch = Path(__file__).with_name("apply-phone-call-state-bridge.py")
text = patch.read_text(encoding="utf-8")

old = (
    "    '    private var remotePhoneCapabilities = PhoneHubCapabilities(0, null)\\n',\n"
    "    '    private var remotePhoneCapabilities = PhoneHubCapabilities(0, null)\\n'\n"
    "    '    private var phoneCallStateBridge: PhoneCallStateBridge? = null\\n',\n"
)
new = (
    "    '    @Volatile private var lastAnnouncedPhoneCapabilities: PhoneHubCapabilities? = null\\n',\n"
    "    '    @Volatile private var lastAnnouncedPhoneCapabilities: PhoneHubCapabilities? = null\\n'\n"
    "    '    private var phoneCallStateBridge: PhoneCallStateBridge? = null\\n',\n"
)

count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one obsolete BusHubService marker; got {count}")
text = text.replace(old, new, 1)

code = compile(text, str(patch), "exec")
namespace = {
    "__name__": "__main__",
    "__file__": str(patch),
    "__package__": None,
}
exec(code, namespace, namespace)
