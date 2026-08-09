---
task: relay-reader
date: 2026-08-09
status: active
scope_globs:
  - "plugins/relay/src/**"
  - "plugins/relay/CHANGELOG.md"
forbidden_globs:
  - "bus-client/**"
  - "glasses-hub/**"
  - "phone-hub/**"
  - "plugins/relay/build.gradle.kts"
  - "**/AndroidManifest.xml"
test_commands:
  - "gradlew.bat :plugin-relay:testDebugUnitTest"
  - "gradlew.bat :plugin-relay:lintDebug"
  - "gradlew.bat :plugin-relay:assembleDebug"
max_failures: 2
---

# Goal

The Relay inbox's thread READING view renders on the `reader` surface kind
(`NexusReader`, already in the bus-client SDK on this branch) instead of a
`NexusCard`. When the wearer opens a conversation from the Messages list they
get a full-screen prose document: the sender as the reader title, each message
as wrapped prose, and native renderer scrolling (the hub consumes DPAD/swipe
events locally for reader surfaces — no plugin-side windowing). ENTER still
starts the reply flow; BACK still returns to the list. Every other Relay view
(the Messages list card, LISTENING/REVIEW/VOICE_FAILURE/SENT reply modes, the
incoming-notification notice band) is byte-for-byte unchanged in behavior.

# Non-goals

- Do NOT touch the notice band path (`RelayNoticeRuntime.kt`) — incoming
  notifications keep their current presentation.
- Do NOT change the reply flow (dictation, review countdown, send) — those
  modes stay on the existing card rendering.
- Do NOT modify the SDK (`bus-client/`), the glasses hub renderer, or the
  phone hub. This is a plugin-only change; the reader renderer shipped in the
  hub already.
- No version bump, no release, no registry work.

# Constraints

- MUST render ThreadMode.READING (and only READING) via
  `session.showReader(...)` / `session.updateReader(...)`
  ([SurfaceModels.kt:491-493] on this branch). All other thread modes and the
  list keep `sendCard(...)` exactly as today.
- Segment mapping (this is the design; do not improvise a different one):
  - `title` = sender name (fallback app label), same truncation discipline as
    the current `cardTitle(...)`.
  - Solo conversation (all parsed speakers blank or equal to the title,
    case-insensitive — same rule as the existing `soloVoice` logic in
    `messageRows`): ONE `HEADER` segment at the top: `"<sender> · <appLabel>"`
    (appLabel omitted when blank), then each message as its own `PROSE`
    segment.
  - Group conversation: a `HEADER` segment per speaker change (text = speaker
    name), each message's text as a `PROSE` segment under its header.
  - The wearer's own messages (speaker "You", case-insensitive) get
    `emphasis = true` on their HEADER.
  - `threadStatus`, when present, becomes one `ASIDE` segment at the end.
  - `footer` = same content as today's READING footer (appLabel + "tap to
    reply · back to inbox" / "read only · back to inbox"), except swap the
    scroll wording if any appears — the renderer owns scrolling now.
  - `contentKey` = same `"$THREAD_CONTENT_PREFIX${entry.id}"` as today.
  - `handlesBack = true` (without it the hub hides the surface on BACK and the
    wearer is dumped out of the plugin instead of back to the list).
- MUST respect the SDK caps (`NexusReader` init throws): 240 segments max,
  4 096 chars per segment, 40 000 chars total. Split any message longer than
  4 096 chars into consecutive PROSE segments at a word boundary. If the whole
  document exceeds 20 000 chars, drop OLDEST messages first (whole messages,
  never partial) — Relay threads are short; this is a safety valve, not a
  pagination scheme.
- MUST keep the mode transitions working both directions: READING → ENTER →
  LISTENING re-renders as a card (`showCard`/`updateCard` on the same surface
  session replaces the reader — this exact card↔reader swap is proven on
  device by the agents plugin); BACK from any reply mode returns to READING
  and re-renders the reader; BACK from READING returns to the list card.
- MUST keep `onNexusInput` ([RelayPluginService.kt:78-93]) working for the
  card modes. While the reader is up the hub eats DPAD/UP/DOWN/LEFT/RIGHT for
  local scroll and only forwards ENTER/BACK — the plugin MUST NOT rely on
  receiving move events in READING (today's `move()` already no-ops there;
  keep it harmless if one arrives anyway).
- MUST NOT introduce new dependencies, new build config, or manifest changes.
- MUST keep pure-JVM logic testable: put the message→segments mapping in a
  pure function/object (Android-free, like `RelayInboxModel`) and unit-test it
  (solo, group, You-emphasis, >4096 split, >20k oldest-drop, threadStatus
  aside, empty-thread fallback).
- MUST add a CHANGELOG entry under an Unreleased heading (create it if the
  file convention there differs, follow the existing file's convention).
- Commit style: repo convention (English, no AI attribution). If sandboxing
  prevents committing in this worktree, leave the tree dirty and say so in the
  report — the supervisor commits.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Unit tests incl. new mapping tests | `gradlew.bat :plugin-relay:testDebugUnitTest` | green |
| 2 | Lint (catches API-level crashes reviews miss) | `gradlew.bat :plugin-relay:lintDebug` | no new errors (ignore the known local.properties PropertyEscape) |
| 3 | APK builds | `gradlew.bat :plugin-relay:assembleDebug` | green |
| 4 | READING uses reader | manual code check | `renderThread` READING path calls `showReader`/`updateReader`, no `NexusCard` built for READING |
| 5 | Other modes untouched | `git diff` inspection | LISTENING/REVIEW/VOICE_FAILURE/SENT rendering and `RelayNoticeRuntime.kt` diff-free |

# Plan sketch

1. Read `RelayPluginService.kt` fully (the inbox controller lives inside it),
   `RelayInboxModel.kt`, `RelayInboxCatalog` (wherever `threadMessages` is),
   and `SurfaceModels.kt:124-185` for the reader contract.
2. Extract a pure mapper: `RelayReaderDocument.from(snapshot, threadStatus,
   canReply): NexusReader`-shaped data (keep it SDK-free if `RelayInboxModel`
   is; mirror its pure-counterpart pattern, then adapt to `NexusReader` at the
   service boundary).
3. Rewire `renderThread` READING branch to the reader; keep the returned
   Boolean semantics (`sendCard` returns whether the glasses took it —
   `showReader` returns `NexusSdkResult` too; preserve the callers' contract,
   notably the send-countdown tick which repaints only card modes).
4. Unit tests for the mapper; run the three gradle commands.
5. Update CHANGELOG. Report: what changed, any deviation from this sketch.

# Context the executor cannot re-derive

- The reader renderer already ships in the glasses hub on this branch (merge
  `974841a3`); the hub consumes DPAD/MEDIA/ring events for local scroll (45 %
  viewport steps) and forwards ENTER/BACK. Opening pins bottom; updates re-pin
  only if the wearer was at the bottom.
- The agents plugin (`plugins/agents/.../AgentsPluginService.kt:836-900`) is
  the reference reader producer — same board-card↔conversation-reader swap on
  one surface session, validated on hardware. It is a STANDALONE gradle build;
  do not try to build it from the root.
- `handlesBack` was added to the reader payload specifically because its
  omission trapped the wearer (hub hid the surface on BACK). See
  [SurfaceModels.kt:149] and :165.
- Relay rendering caps today: `MAX_CARD_LINE_CHARS = 240` per row — a row
  longer than that throws in the `NexusCardLine` constructor and the surface
  dies silently. The reader lifts this (4 096/segment) which is the point of
  the migration: no more truncation of long messages.
- `:plugin-relay` is part of the ROOT gradle build (settings.gradle.kts:31,48)
  — build from the repo root with `gradlew.bat`, module `:plugin-relay`.
- `lintDebug` on plugins is mandatory in this repo (a lint-visible Android 11
  crash once shipped because review missed it and lint wasn't run).
- Known lint noise: local.properties PropertyEscape error is environmental —
  ignore that one specifically, fix anything else new.
- Do not reformat or reflow untouched code; the repo's comment style is prose
  explaining constraints, keep additions in that voice.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff touches files outside scope_globs or matching forbidden_globs.
- The reader contract in `SurfaceModels.kt` turns out to lack something this
  spec assumes (e.g. a field) — stop and report; do NOT modify the SDK.

# Autonomy

- May decide: internal naming, file placement within `plugins/relay/src`,
  test structure, exact word-boundary split algorithm.
- Must stop and report: anything requiring SDK/hub changes, any behavior
  change to reply flow or notice band, any cap/threshold different from the
  ones specified above.
