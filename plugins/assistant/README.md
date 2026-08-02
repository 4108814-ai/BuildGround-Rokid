# Assistant

Phone-side Rokid Nexus voice assistant plugin.

Build 1 implements the assist-button microphone pipeline, OpenAI transcription,
streamed Chat Completions, compact HUD cards, and ChatGPT OAuth. Camera input and
the owner-designed settings interface are intentionally deferred.

Build and test:

```powershell
.\gradlew.bat :plugin-assistant:assembleDebug :plugin-assistant:testDebugUnitTest
```
