---
task: assistant-progress-states
date: 2026-08-10
status: active
scope_globs:
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/**"
  - "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/**"
forbidden_globs:
  - "ink-engine/**"
  - "glasses-hub/**"
  - "phone-hub/**"
  - "bus-client/**"
  - "shared/**"
  - "plugins/sample/**"
  - "**/local.properties"
test_commands:
  - ".\\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-assistant:lintDebug --console=plain"
max_failures: 2
---

# Goal

While the assistant works, the band says `Thinking…` and nothing else, for the
whole turn. The wearer cannot tell a slow model from a stalled one, and the last
stretch — where the model has finished thinking and is drawing a card — reads as
dead air.

Make the band say what is actually happening, and only what is actually
happening.

# Design (decided by the owner — implement this, do not invent labels)

Two sources of truth, both already in the code.

## 1. Local tool calls

`AssistantToolExecutionPhase.execute` (`AssistantToolRegistry.kt`) is the single
place every local tool passes through. Give `AssistantToolDefinition` a
**required** short progress label, show it on the band immediately before
`definition.execute(...)`, and restore `Thinking…` when the call returns
(success, error, or throw).

Required because a tool added later must not be able to forget it. If a tool
genuinely has nothing to say, it says so explicitly with a null/None value, not
by omission.

Exact labels — use these strings verbatim, do not paraphrase:

| tool | label |
|---|---|
| `render_template` | `Drawing the card…` |
| `render_ink_page` | `Drawing the card…` |
| `take_note` | `Saving the note…` |
| `list_notes` | `Reading your notes…` |
| `search_notes` | `Searching your notes…` |
| `delete_note` | `Deleting the note…` |
| `set_reminder` | `Setting the reminder…` |
| `list_reminders` | `Checking your reminders…` |
| `cancel_reminder` | `Cancelling the reminder…` |
| `set_timer` | `Starting the timer…` |
| `take_photo` | keep its existing per-stage labels, do not override |

The ellipsis is the single character `…` (U+2026), matching `Listening…`,
`Transcribing…` and `Thinking…`.

## 2. Server-side web search

`ChatGptCodexProvider` already enables the server-side `web_search` tool (the
request builds `{"type":"web_search"}`), and the SSE loop already parses
`response.output_text.delta`, `response.output_item.done` and
`response.completed`.

Add the event that marks a search **starting** (`response.output_item.added`,
and any `response.web_search_call.*` progress events the stream actually
carries) and surface `Searching the web…` on the band for as long as the search
item is in flight, restoring `Thinking…` when it completes.

Verify against the real event names in the stream rather than assuming: if the
stream does not carry a usable start event, say so in the report and ship only
part 1 — do NOT display a search label the stream did not justify.

# The rule that matters

**A label is shown only for something observed.** Never a guess, never a
decorative stage, never a timer-driven fake. If the assistant is only waiting on
the model, the band says `Thinking…` — that is honest and it is enough. A band
the wearer learns to distrust is worse than a band that says little.

# Constraints

- MUST NOT change what the assistant does, only what it says while doing it. No
  new tools, no prompt changes, no provider behaviour changes beyond reading
  events already in the stream.
- MUST restore `Thinking…` on every exit path of a tool call, including
  validation failure, the already-used / max-calls refusals, exceptions, and
  cancellation. A stuck label is the bug this task must not create.
- MUST keep the band's engaged state exactly as today: these updates are notice
  updates within the same engaged episode, and they must not close, re-show, or
  de-engage the band (the glasses side now holds the display for the whole
  episode and a spurious re-show would re-arm its ceiling).
- MUST NOT touch `forbidden_globs`. Glasses-side rendering is already correct;
  this is a plugin-side change only.
- MUST NOT let a label outlive its cause: at most one label is current, and the
  turn always ends back at a real state (answer, error, or dismissal).
- MUST add unit tests: each tool's label is shown then cleared; refusal and
  throw paths restore `Thinking…`; the web-search label follows the stream
  events; no label leaks past the end of a turn.
- MUST NOT commit; no adb, no device commands, no gradle outside test_commands.
  If Gradle throws `AccessDenied` on `.gradle\...\fileHashes.lock`, that is a
  concurrent build: retry once, then stop and report.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Assistant suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Label lifecycle tests | — | shown, restored on every exit path, none leaked |
| 3 | Web search verdict | — | report states the exact SSE events used, or why part 2 was not shipped |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Device validation is the requester's: ask a question that draws a card and watch
the band go `Thinking…` -> `Drawing the card…` -> card.

# Context the executor cannot re-derive

- Branch `ink-surface`, worktree `E:\Tools\Rokid\wt-ink`, HEAD `c1090316`.
- `AssistantUiController.showTransient(...)` is the band writer, and its own
  comment already names Listening / Thinking / Searching as the in-flight
  states. `AssistantPluginService` calls it for `Listening…`, `Transcribing…`
  and `Thinking…`.
- `TakePhotoTool` is the precedent: it drives the band itself through a
  `showTransient` capability and restores `Thinking…` afterwards. Generalise
  that pattern into the registry rather than copying it into every tool.
- A second Codex run is working on M4 in a different worktree
  (`E:\Tools\Rokid\wt-ink-m4`). Stay inside this worktree.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- The web-search start event does not exist in the stream — report and ship
  part 1 only.
- A label cannot be guaranteed to clear on some path — report it rather than
  shipping a band that can stick.

# Autonomy

Executor decides: where the label lives on the definition, how the restore is
expressed, how the search item is tracked, test structure. Executor stops for:
new labels not in the table above, prompt or tool-behaviour changes, or anything
that could leave a stale label on the band.
