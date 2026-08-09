# Adversarial code review — READ-ONLY, no edits, no commits

Review the diff `git diff 974841a3..HEAD` in this worktree (commit "Open
Relay conversations as reader documents on the glasses") against its contract
at `contracts/2026-08-09-relay-reader.contract.md`. Read the contract, the
full diff, then `RelayPluginService.kt`, `RelayReaderDocument.kt`,
`RelayInboxModel.kt` and the catalog/`threadMessages` code IN FULL, plus the
SDK reader contract in
`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt`
(NexusReader, lines ~124-185, and its `require` caps at ~628-638).

You are the second, hostile pair of eyes. Hunt for:

1. **Crash paths on the glasses**: any input to `RelayReaderDocument.from`
   that produces a `NexusReader` whose `init` requires throw — blank title,
   zero segments, a segment over 4 096 chars (watch the header-join path and
   surrogate handling), total over 40 000, contentKey over 128 chars (thread
   ids come from notification keys — how long can they get?), footer over
   240. The Relay surface dies SILENTLY when a constructor throws; this is
   the #1 risk class.
2. **Mode-machine regressions**: READING now early-returns before the card
   `when` — trace every caller of `renderThread` (send-countdown tick,
   leaveSentThread, onCaptureChanged, back(), move(), confirm()) and check
   each still behaves with the reader up; check the
   `error("Reading threads render as reader surfaces")` branch really is
   unreachable from every path, including future-looking ones like a mode
   reset racing a render.
3. **Contract fidelity**: solo vs group header rules, emphasis rule, oldest-
   drop budget, unchanged contentKey, `handlesBack`, footer wording, reply
   modes and `RelayNoticeRuntime.kt` byte-untouched.
4. **Test theater**: do the new unit tests actually pin the caps (a 4 097-char
   message, a >20 000-char thread, an emoji at the split boundary), or only
   happy paths?

For EACH finding, actively try to REFUTE it first (trace the real call path,
compute the real string lengths). Report only survivors: file:line, concrete
failure story, severity (BLOCKER / REAL / NIT), refutation attempt in one
line. Findings only, ranked; no praise, no summary. If nothing survives, say
exactly that. Do NOT modify any file. You may NOT run gradle (it fails in
this sandbox); reason statically.
