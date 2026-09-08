from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-nexus-phone-1425-network-bridge.py <BuildGround-Rokid-root>")

ROOT = Path(sys.argv[1]).resolve()
POLICY = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/HttpProxyPolicy.kt"
TEST = ROOT / "phone-hub/src/test/java/com/anezium/rokidbus/phone/HttpProxyPolicyTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Network Bridge v1 deliberately reuses the existing, hardware-validated
# /http/request -> phone hub -> /http/request/reply transport.  Do not create a
# second socket stack and do not touch CXR/SPP routing.  The phone performs DNS,
# TLS and the actual Internet request, so Android naturally sends that request
# through whatever upstream network/VPN is active on the phone.
replace_once(
    POLICY,
    '    private const val ALLOWED_HOST = "api.transitous.org"\n',
    '    private val allowedHosts = setOf(\n'
    '        "api.transitous.org",\n'
    '        "api.openai.com",\n'
    '    )\n',
    "network bridge host allowlist",
)

replace_once(
    POLICY,
    '        "user-agent" to "User-Agent",\n'
    '    )\n',
    '        "user-agent" to "User-Agent",\n'
    '        "authorization" to "Authorization",\n'
    '        "openai-beta" to "OpenAI-Beta",\n'
    '        "openai-organization" to "OpenAI-Organization",\n'
    '        "openai-project" to "OpenAI-Project",\n'
    '    )\n',
    "network bridge request headers",
)

replace_once(
    POLICY,
    '        if (!uri.host.equals(ALLOWED_HOST, ignoreCase = true)) {\n'
    '            return Validation.Rejected("HOST_NOT_ALLOWED")\n'
    '        }\n',
    '        val normalizedHost = uri.host?.lowercase(Locale.US)\n'
    '            ?: return Validation.Rejected("INVALID_URL")\n'
    '        if (normalizedHost !in allowedHosts) {\n'
    '            return Validation.Rejected("HOST_NOT_ALLOWED")\n'
    '        }\n',
    "network bridge host validation",
)

replace_once(
    TEST,
    '    @Test\n'
    '    fun nonHttpsSchemeIsRejected() {\n',
    '    @Test\n'
    '    fun openAiHostIsAllowedForNetworkBridge() {\n'
    '        val validation = validate(\n'
    '            url = "https://api.openai.com/v1/models",\n'
    '            headers = mapOf("Authorization" to "Bearer bridge-token"),\n'
    '        )\n\n'
    '        val request = (validation as HttpProxyPolicy.Validation.Allowed).request\n'
    '        assertEquals("api.openai.com", request.url.host.lowercase())\n'
    '        assertEquals("Bearer bridge-token", request.headers["Authorization"])\n'
    '    }\n\n'
    '    @Test\n'
    '    fun openAiLookalikeHostIsRejected() {\n'
    '        assertRejected("HOST_NOT_ALLOWED", url = "https://api.openai.com.example.org/v1/models")\n'
    '    }\n\n'
    '    @Test\n'
    '    fun nonHttpsSchemeIsRejected() {\n',
    "network bridge OpenAI policy tests",
)

replace_once(
    TEST,
    '        assertEquals(\n'
    '            setOf("Accept", "Content-Type", "If-None-Match", "If-Modified-Since", "User-Agent"),\n'
    '            headers.keys,\n'
    '        )\n'
    '        assertFalse(headers.containsKey("Authorization"))\n'
    '        assertFalse(headers.containsKey("Cookie"))\n',
    '        assertEquals(\n'
    '            setOf(\n'
    '                "Accept",\n'
    '                "Content-Type",\n'
    '                "If-None-Match",\n'
    '                "If-Modified-Since",\n'
    '                "User-Agent",\n'
    '                "Authorization",\n'
    '            ),\n'
    '            headers.keys,\n'
    '        )\n'
    '        assertEquals("secret", headers["Authorization"])\n'
    '        assertFalse(headers.containsKey("Cookie"))\n',
    "network bridge authorization header test",
)

final_policy = POLICY.read_text(encoding="utf-8")
for marker in (
    '"api.transitous.org"',
    '"api.openai.com"',
    '"authorization" to "Authorization"',
    '"openai-beta" to "OpenAI-Beta"',
    'normalizedHost !in allowedHosts',
):
    if marker not in final_policy:
        raise SystemExit(f"Network Bridge marker missing after patch: {marker}")

if 'private const val ALLOWED_HOST = "api.transitous.org"' in final_policy:
    raise SystemExit("Legacy single-host proxy policy survived Network Bridge patch")

print("NEXUS Network Bridge v1 HTTP policy patch applied")
