# Assistant

Phone-side Rokid Nexus voice assistant plugin.

Hold the assist button, ask out loud: the words transcribe live on the HUD, the
answer streams into the same band and is spoken aloud. The model can take one
photo through the glasses camera when the question needs eyes, and it can set
reminders and timers, take notes, and read them back.

Answers come from the provider the wearer picks in Settings: a ChatGPT plan
(OAuth, no key to paste), or an API key for OpenAI, OpenRouter, MiniMax,
DeepSeek, GLM (Z.ai), or any OpenAI-compatible server. Every API preset speaks
the same chat-completions SSE dialect through one generic client
(`OpenAiCompatProvider`); the preset catalog lives in `ProviderCatalog.kt`.
Each provider keeps its own encrypted key, model, and endpoint.

Tools go through `AssistantToolRegistry`: every provider declares the tools it
can run, one client-managed tool phase per request, then the final reply. The
text tools (notes, reminders, timers) are offered to every provider; only
`take_photo` additionally requires a model that can see, and photos are
stripped gracefully for models that cannot. A server that rejects tools
outright is retried once without them.

Reminders and timers persist in app-private JSON stores and fire through
`AlarmManager` even when the plugin is closed: a short-lived foreground service
posts the phone notification and raises a notice on the glasses (a pin when the
link is down), and a boot receiver reschedules what is still pending. This is
the sanctioned scheduled-delivery exception to the dormant-plugin policy —
see [plugins/AGENTS.md](../AGENTS.md) §1.

Conversations thread with an idle window, ChatGPT memories and local notes ride
along in the system prompt, and a Personality box holds the wearer's standing
instructions. Notes and reminders live in their own settings screen.

Build and test:

```powershell
.\gradlew.bat :plugin-assistant:assembleDebug :plugin-assistant:testDebugUnitTest
```
