from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:120]!r}; got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Speech manager: tee the exact PCM used by STT to the speech-session listener.
speech = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/speech/SpeechSessionManager.kt"
replace_once(
    speech,
    "interface SpeechUtteranceListener {\n"
    "    fun onState(state: SpeechSessionState)\n",
    "interface SpeechUtteranceListener {\n"
    "    fun onPcm(data: ByteArray, offset: Int, length: Int) = Unit\n"
    "    fun onState(state: SpeechSessionState)\n",
)
replace_once(
    speech,
    "        if (run.ended.get() || run.cancelRequested.get() || run.endpointReached) return\n"
    "        val hadSpeech = run.vad.speechDetected\n",
    "        if (run.ended.get() || run.cancelRequested.get() || run.endpointReached) return\n"
    "        // One microphone lease, two consumers: STT and the owning plugin's archival recorder.\n"
    "        // This callback runs on the same serial audio executor as the STT feed, preserving order.\n"
    "        run.listener.onPcm(pcm, 0, pcm.size)\n"
    "        val hadSpeech = run.vad.speechDetected\n",
)

# 2) Wire protocol: binary PCM frames on the active STT session.
protocol = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/SttWireProtocol.kt"
replace_once(
    protocol,
    "    const val STATE_PATH = \"/stt/state\"\n",
    "    const val AUDIO_PATH = \"/stt/audio\"\n"
    "    const val STATE_PATH = \"/stt/state\"\n",
)
replace_once(
    protocol,
    "    fun stateId(sessionId: String, sequence: Long): String = \"$sessionId:s$sequence\"\n",
    "    fun audioId(sessionId: String, sequence: Long): String = \"$sessionId:a$sequence\"\n\n"
    "    fun audioPayload(\n"
    "        pluginId: String,\n"
    "        sessionId: String,\n"
    "        sequence: Long,\n"
    "    ): JSONObject = basePayload(pluginId, sessionId)\n"
    "        .put(\"seq\", sequence)\n"
    "        .put(\"sampleRateHz\", 16_000)\n"
    "        .put(\"channels\", 1)\n"
    "        .put(\"encoding\", \"pcm16le\")\n\n"
    "    fun stateId(sessionId: String, sequence: Long): String = \"$sessionId:s$sequence\"\n",
)

# 3) Phone hub: sequence + binary envelope from the active speech session.
hub = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt"
replace_once(
    hub,
    "        val stateSeq: AtomicLong = AtomicLong(),\n"
    "        val partialSeq: AtomicLong = AtomicLong(),\n",
    "        val audioSeq: AtomicLong = AtomicLong(),\n"
    "        val stateSeq: AtomicLong = AtomicLong(),\n"
    "        val partialSeq: AtomicLong = AtomicLong(),\n",
)
replace_once(
    hub,
    "    ) : SpeechUtteranceListener {\n"
    "        override fun onState(state: SpeechSessionState) {\n",
    "    ) : SpeechUtteranceListener {\n"
    "        override fun onPcm(data: ByteArray, offset: Int, length: Int) {\n"
    "            if (length <= 0) return\n"
    "            val safeOffset = offset.coerceIn(0, data.size)\n"
    "            val safeLength = length.coerceAtMost(data.size - safeOffset)\n"
    "            if (safeLength <= 0) return\n"
    "            val pcm = data.copyOfRange(safeOffset, safeOffset + safeLength)\n"
    "            speechBusExecutor.execute {\n"
    "                if (!isCurrentSpeechBusSession(session)) return@execute\n"
    "                val sequence = session.audioSeq.getAndIncrement()\n"
    "                deliverSpeechBusEnvelope(\n"
    "                    session,\n"
    "                    BusEnvelope(\n"
    "                        path = SttWireProtocol.AUDIO_PATH,\n"
    "                        id = SttWireProtocol.audioId(session.sessionId, sequence),\n"
    "                        payload = SttWireProtocol.audioPayload(\n"
    "                            session.pluginId,\n"
    "                            session.sessionId,\n"
    "                            sequence,\n"
    "                        ),\n"
    "                        binary = pcm,\n"
    "                    ),\n"
    "                )\n"
    "            }\n"
    "        }\n\n"
    "        override fun onState(state: SpeechSessionState) {\n",
)

# 4) SDK speech session: expose PCM only for the matching active session.
sdk = ROOT / "bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusSpeechSession.kt"
replace_once(
    sdk,
    "interface NexusSpeechCallbacks {\n"
    "    fun onSpeechStarted(realtime: Boolean)\n",
    "interface NexusSpeechCallbacks {\n"
    "    fun onSpeechAudioPcm(\n"
    "        pcm: ByteArray,\n"
    "        sampleRateHz: Int,\n"
    "        channels: Int,\n"
    "        encoding: String,\n"
    "    ) = Unit\n"
    "    fun onSpeechStarted(realtime: Boolean)\n",
)
replace_once(
    sdk,
    "    internal fun onState(payload: JSONObject) {\n",
    "    internal fun onAudio(payload: JSONObject, data: ByteArray) {\n"
    "        synchronized(stateLock) {\n"
    "            if (!matchesActiveSession(payload) || data.isEmpty()) return\n"
    "            val sampleRateHz = payload.optInt(\"sampleRateHz\", 0)\n"
    "            val channels = payload.optInt(\"channels\", 0)\n"
    "            val encoding = payload.optString(\"encoding\")\n"
    "            if (sampleRateHz <= 0 || channels <= 0 || encoding.isBlank()) return\n"
    "            callbacks.onSpeechAudioPcm(data, sampleRateHz, channels, encoding)\n"
    "        }\n"
    "    }\n\n"
    "    internal fun onState(payload: JSONObject) {\n",
)
replace_once(
    sdk,
    "internal const val NEXUS_STT_STATE_PATH = \"/stt/state\"\n",
    "internal const val NEXUS_STT_AUDIO_PATH = \"/stt/audio\"\n"
    "internal const val NEXUS_STT_STATE_PATH = \"/stt/state\"\n",
)

# 5) Client binary router: delegate /stt/audio to NexusSpeechSession.
client = ROOT / "bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginClient.kt"
replace_once(
    client,
    "        if (routeSpeechBinary(path)) return\n",
    "        if (routeSpeechBinary(path, payload, data)) return\n",
)
replace_once(
    client,
    "    private fun routeSpeechBinary(path: String): Boolean {\n"
    "        if (!isSpeechPath(path)) return false\n"
    "        return synchronized(speechSessionLock) { speechSessionApiUsed }\n"
    "    }\n",
    "    private fun routeSpeechBinary(path: String, payload: JSONObject, data: ByteArray): Boolean {\n"
    "        if (!isSpeechPath(path)) return false\n"
    "        val (session, consume) = synchronized(speechSessionLock) {\n"
    "            registeredSpeechSession to speechSessionApiUsed\n"
    "        }\n"
    "        if (path == NEXUS_STT_AUDIO_PATH) session?.onAudio(payload, data)\n"
    "        return consume\n"
    "    }\n",
)

checks = {
    speech: ["fun onPcm(data: ByteArray", "run.listener.onPcm(pcm, 0, pcm.size)"],
    protocol: ["AUDIO_PATH = \"/stt/audio\"", "sampleRateHz"],
    hub: ["val audioSeq: AtomicLong", "SttWireProtocol.AUDIO_PATH", "binary = pcm"],
    sdk: ["fun onSpeechAudioPcm(", "internal fun onAudio(", "NEXUS_STT_AUDIO_PATH"],
    client: ["routeSpeechBinary(path, payload, data)", "session?.onAudio(payload, data)"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"Missing marker in {path}: {marker}")
