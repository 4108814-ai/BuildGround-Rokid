---
task: ink-m3-assistant-tool
date: 2026-08-09
status: active
scope_globs:
  - "plugins/assistant/**"
forbidden_globs:
  - "ink-engine/**"
  - "glasses-hub/**"
  - "phone-hub/**"
  - "bus-client/**"
  - "shared/**"
  - "plugins/sample/**"
  - "**/local.properties"
test_commands:
  - ".\\gradlew.bat :plugin-assistant:testDebugUnitTest --console=plain"
  - ".\\gradlew.bat :plugin-assistant:assembleDebug --console=plain"
  - ".\\gradlew.bat :plugin-assistant:lintDebug --console=plain"
max_failures: 2
---

# Goal

Milestone M3 of `plans/020-ink-surface.md`: the Assistant plugin exposes a
side-effecting LLM tool `render_ink_page` so any provider with function calling
(ChatGPT/Codex path and OpenAI-compat path) can answer with a rich `.ink` page —
a chart, a metric layout, an animated status — rendered on the glasses through
the `ink_surface` SDK session shipped in M1/M2. A normal text answer is always
produced too; the page is presentation, never the sole carrier of the answer.

# Non-goals

- No engine/renderer/SDK/hub changes — the platform is complete; this is a pure
  consumer. If something in the platform seems missing, STOP and report; do not
  work around it inside the assistant.
- No new provider plumbing: the tool registry already declares tools to both
  provider paths generically.
- No prompt-engineering beyond the focused authoring guidance described below.
- No changes to existing tools or their budgets.

# Constraints

- MUST read first: `plans/020-ink-surface.md` §7/§9, the SDK surface
  (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/InkSurfaceModels.kt`
  and the `inkSurfaceSession` API on `NexusPluginClient`), the sample consumer
  (`plugins/sample/.../HelloPluginService.kt` ink demo), and the tool registry
  (`plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantToolRegistry.kt`)
  plus one existing side-effecting tool end to end (take_photo) as the pattern.
- MUST register `render_ink_page` as a side-effecting tool with JSON-schema
  arguments `{ page: string (required), title?: string, data?: object }`,
  following the registry's existing validation/memoization/once-per-phase rules.
- MUST gate availability on: active assistant session + `ink_surface` granted +
  `nexusClient.supportsInkSurface == true`. When unavailable the tool is not
  declared to the model at all (registry pattern), and nothing else changes.
- MUST execute by compiling nothing locally: pass the page/data straight to
  `inkSurfaceSession(...).show(...)`. Tool result back to the model:
  `{status:"shown"}` on accepted send; on `onNexusInkError`, feed the typed
  problems back as the tool error result so the model can correct the page and
  retry within the registry's existing retry budget.
- MUST keep the spoken/text answer path untouched: the model's final text still
  renders as today (card/TTS). The ink page replaces the answer CARD only when
  the tool succeeded (mirror how take_photo coexists with answers).
- MUST handle lifecycle: hide the ink surface when the assistant session ends or
  a new question starts (wherever the assistant currently resets its surface),
  and route `onNexusInkAction`/`onNexusInkClosed` minimally: closed → clear
  state; actions are logged (no action vocabulary for the model in M3).
- MUST add `ink_surface` to the assistant's requested capabilities in its
  manifest (this correctly re-pends the grant).
- MUST add authoring guidance to the tool's DESCRIPTION (not the global system
  prompt): when to render (numbers, comparisons, trends, multi-value states —
  not plain prose), the v1 support matrix in compressed form (components,
  display:flex explicit, no script block, budgets, monochrome — no color
  styling, series identified by label), and one compact example page. Keep it
  under ~2 KiB; the format itself is what the model already knows from AIUI.
- MUST NOT touch any forbidden glob, run device commands, or commit.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Assistant suite green incl. new tool tests | `.\gradlew.bat :plugin-assistant:testDebugUnitTest` | BUILD SUCCESSFUL; tests cover: declared only when supported, schema validation, error problems fed back, once-per-phase cap |
| 2 | APK builds | `.\gradlew.bat :plugin-assistant:assembleDebug` | BUILD SUCCESSFUL |
| 3 | Lint clean | `.\gradlew.bat :plugin-assistant:lintDebug` | 0 errors (repo rule: always lint plugins) |
| 4 | Diff scope | `git status --short` | plugins/assistant only |

Manual (reviewer, on device): ask the assistant a numeric/trend question, watch
it call `render_ink_page`, page renders on glasses; ask a prose question, no
tool call; malformed page from a forced test → typed error → model retry.

# Plan sketch

1. Read the listed files; mirror the take_photo tool structure.
2. Tool declaration + schema + availability gating + description with authoring
   guidance.
3. Execution path: session management (one ink session field, show/hide
   lifecycle), error feedback loop.
4. Manifest capability + any settings surface the assistant uses to list
   capabilities (check for a hardcoded list).
5. Tests; full module verification.

# Context the executor cannot re-derive

- Worktree `E:\Tools\Rokid\wt-ink`, branch `ink-surface` (M1+M2 committed:
  engine, renderer, /ink routes, SDK, sample consumer — the sample's
  `showInkDemo()` is a working reference consumer).
- The registry filters tools per provider/session and enforces
  argument validation, call-id memoization, phase caps (three executions/phase,
  side-effecting once) — reuse, do not reimplement.
- Providers: `OpenAiCompatProvider` and `ChatGptCodexProvider` both already
  send tool declarations and replay tool results; no changes needed there.
- The assistant currently renders answers through `NexusSurfaceSession`
  (`AssistantPluginService.kt`, answer-card path) — the ink session is a sibling
  field with the same reset points.
- Compile errors arrive ASYNC via `onNexusInkError` (Binder is one-way; the
  show() result only means "sent"). The registry's tool execution is
  synchronous-ish — bridge with a bounded wait (the registry pattern for
  take_photo already awaits an async capture result; mirror that mechanism and
  its timeout).
- Trap: the assistant manifest change re-pends the grant — the reviewer
  handles re-approval on device; tests must not assume granted state.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff touches files outside plugins/assistant/**.
- The SDK/platform lacks something the tool needs (e.g. no way to await
  ready/error) — stop and report; do not patch other modules.

# Autonomy

Executor decides alone: internal structure, description wording within the
guidance constraints, test organization. Executor stops for: anything requiring
platform changes, ambiguity about the answer-card/ink coexistence UX beyond the
stated rule.
