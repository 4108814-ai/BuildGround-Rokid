# Assistant

Phone-side Rokid Nexus voice assistant plugin.

Hold the assist button, ask out loud: the words transcribe live on the HUD, the
answer streams into the same band and is spoken aloud. The model can take one
photo through the glasses camera when the question needs eyes.

Answers come from the provider the wearer picks in Settings: a ChatGPT plan
(OAuth, no key to paste), or an API key for OpenAI, OpenRouter, MiniMax,
DeepSeek, GLM (Z.ai), or any OpenAI-compatible server. Every API preset speaks
the same chat-completions SSE dialect through one generic client
(`OpenAiCompatProvider`); the preset catalog lives in `ProviderCatalog.kt`.
Each provider keeps its own encrypted key, model, and endpoint. The ChatGPT
path keeps the Nexus tool loop (photo tool, web search); API providers are
plain chat, and photos are stripped gracefully for models that cannot see.

Conversations thread with an idle window, ChatGPT memories and local notes ride
along in the system prompt, and a Personality box holds the wearer's standing
instructions.

Build and test:

```powershell
.\gradlew.bat :plugin-assistant:assembleDebug :plugin-assistant:testDebugUnitTest
```
