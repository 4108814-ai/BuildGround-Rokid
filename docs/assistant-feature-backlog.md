# Assistant feature backlog — BuildGround / Rokid RV101

Recorded from user decisions on 2026-08-14. This file is a product backlog only; it does not authorize merge to PR #1 or changes outside the active feature branch.

## Approved / useful — implement in priority order after stable signing + calls

1. **Advanced calling** — YES.
   - Voice commands such as «набери…», «позвони…».
   - Resolve contacts and numbers intelligently; ask when ambiguous.

2. **SMS by voice** — YES.
   - Voice command to compose/send SMS to a contact.
   - Start with Android/SMS; other messengers are separate future work.

4. **Daily agenda / “what do I have today?”** — YES.
   - Show meetings, reminders, tasks/events in HUD; support follow-up queries such as tomorrow.

5. **BuildGround HUD / corporate Data Layer access** — YES.
   - Queries such as object status, remaining quantities, latest GI decisions, project facts.
   - Must use BuildGround corporate data, not only ChatGPT account memory.

9. **Engineering calculator by voice** — YES.
   - Typical BuildGround calculations (pipe mass, concrete volume, quantities, etc.).
   - Prefer deterministic/local formulas where possible rather than LLM arithmetic.

13. **Meeting mode** — YES, high interest.
   - Transcription → decisions → tasks → responsible persons → deadlines → BuildGround meeting minutes.

18. **Assistant for incoming notifications** — YES.
   - Actions such as explain, draft/reply, assess importance, remind later.
   - Do not implement through Relay unless explicitly revisited; Relay itself is frozen.

20. **Visual Conversation Mode** — YES.
   - Conversational multimodal flow over what the user sees: photo/vision → answer → hands-free follow-up → another visual question with context.

## Explicitly frozen / not now

3. **Relay notifications/messages** — DO NOT TOUCH.

6. **Field PTO walkthrough mode** — too early; keep for future.

7. **“Remember what I see” visual field journal** — too early; keep for future.

8. **QR codes on site / object linking** — too early; keep for future.

10. **Drawing/document HUD navigation** — NO for current roadmap.

11. **Live translation** — glasses already provide this; no current need. May revisit later.

12. **Live conversation subtitles** — NO for current roadmap.

14. **Incoming caller + BuildGround context card** — too early; keep for future.

15. **“Take photo and file/send it to project folder”** — too early; keep for future.

16. **Persistent active-project context** — too early; keep for future.

17. **Navigation / route HUD** — NO for current roadmap.

19. **Voice macros / compound routines** — too early; keep for future.

## Current implementation sequence

1. Permanent BuildGround APK signing.
2. Phone calling tool and physical validation on RV101 + phone + existing Hi Rokid call path.
3. Then proceed through the approved backlog one feature at a time, with physical tests before merge.

## Guardrails

- Keep Hi Rokid handling of incoming calls/gestures intact.
- Do not modify or uninstall Nexus Hub or the glasses-side Rokid Nexus Glasses app unless explicitly required and approved.
- Do not touch Relay unless the user explicitly reopens it.
- Do not merge PR #1 or feature work without explicit user approval after testing.
