---
task: ink-m3b-template-library
date: 2026-08-09
status: active
scope_globs:
  - "plugins/assistant/**"
forbidden_globs:
  - "ink-engine/src/main/**"
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

A fast path beside `render_ink_page`: a **data-only** tool `render_template`
where the model picks a template id and supplies a small JSON of values —
roughly fifty output tokens instead of authoring a page — and the Assistant
plugin fills a pre-written, pre-validated `.ink` template and shows it through
the existing ink session. Latency target: the model's tool call is one short
JSON; everything slow (markup) already exists on disk.

V1 template set (each an `.ink` asset in the plugin, named exactly):
`weather` (now + short forecast), `chart` (line/area/bar/pie via the chart
component), `metrics` (2-6 labeled value cells), `ranking` (ordered list with
values), `comparison` (two columns of labeled values, optional verdict line),
`schedule` (time-labeled entries), `steps` (progress through named steps).

# Non-goals

- No platform changes of any kind — engine, renderer, hub, SDK are frozen.
- No removal or weakening of `render_ink_page` (it stays the freeform escape
  hatch; its description gains ONE line pointing models at templates first).
- No remote/asset images, no Lottie in v1 templates, no per-template user
  settings, no localization framework (templates render the strings they are
  given; the model localizes).

# Constraints

- MUST read first: `plans/020-ink-surface.md` §3 (v1 matrix), the existing
  `RenderInkPageTool.kt` + `AssistantToolRegistry.kt` + `AssistantPluginService`
  ink session wiring (M3, just landed), the sample ink page in
  `plugins/sample/.../HelloPluginService.kt`, and the design references:
  `E:\Tools\Rokid\_tmp_aiui_official\design\monochrome\design-system-green.md`
  and `E:\Tools\Rokid\_tmp_aiui_official\design\monochrome\preview-green.html`.
- MUST implement ONE tool `render_template` with arguments
  `{ template: enum, title?: string, data: object }`; side-effecting, same
  availability gating, phase caps, and async result/error bridging as
  `render_ink_page` (share the session/show plumbing — extract, do not copy).
- MUST validate `data` per template BEFORE showing, with typed, actionable
  error results (missing/extra keys, wrong types, out-of-range counts) so the
  model can correct in one retry. Limits per template are explicit constants
  (e.g. chart ≤ 4 series × 64 points here, metrics 2-6 cells, ranking ≤ 10
  rows, schedule ≤ 12 entries, steps ≤ 8).
- MUST store templates as `.ink` files under the plugin's assets (one file per
  template), using ONLY the v1 matrix: explicit `display:flex` everywhere
  (containers without it are blocks), `{{ }}` bindings + `wx:if`/`wx:for` over
  the tool data, design-system-green tokens (no literal colors beyond the
  token green if unavoidable), `rpx` units. Templates carry NO `<script>`.
- MUST design template layouts after the official monochrome patterns: bordered
  metric cells, thin 1-2rpx strokes, opacity tiers for secondary text, pure
  black background, generous spacing — reference the landing-page card shapes
  (weather: big temperature + condition + compact forecast row; comparison:
  two bordered columns). Data the tool did not receive must collapse cleanly
  (`wx:if`), never render empty boxes.
- MUST unit-test EVERY template by compiling it through the real engine with
  representative data AND with minimal data: add `testImplementation(project(":ink-engine"))`
  to the plugin's build file (test classpath ONLY — the runtime compile stays
  hub-side; do NOT add it to `implementation`). Assert zero problems and, for
  each template, assert one representative binding value lands in the compiled
  document. Also test the validator's typed errors per template.
- MUST keep the tool description compact: template list with one-line purpose +
  data schema each, total ≤ 2.5 KiB. The model must be able to choose correctly
  from the description alone (e.g. weather question → `weather`).
- MUST NOT touch any forbidden glob, run device/adb commands, or commit.
- MUST NOT invent templates beyond the listed seven, and MUST NOT let the
  templates or validation drift outside the engine's v1 matrix (if a template
  idea needs an unsupported feature, simplify the template — never the check).

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Suite green incl. per-template compile tests | `.\gradlew.bat :plugin-assistant:testDebugUnitTest` | BUILD SUCCESSFUL; 7 templates × (rich + minimal) compile clean through InkEngine; validator negatives typed |
| 2 | APK builds, ink-engine absent from runtime deps | `.\gradlew.bat :plugin-assistant:assembleDebug` then inspect the build file diff | BUILD SUCCESSFUL; `:ink-engine` only under testImplementation |
| 3 | Lint clean | `.\gradlew.bat :plugin-assistant:lintDebug` | 0 errors |
| 4 | Diff scope | `git status --short` | plugins/assistant only |
| 5 | Description budget | test asserting description byte length ≤ 2560 | passes |

Manual (reviewer, on device): weather-style question → model picks `weather`
and fills it; numeric comparison → `comparison` or `chart`; latency visibly
shorter than a freeform `render_ink_page` call.

# Plan sketch

1. Extract the shared show/await/error plumbing from `RenderInkPageTool` into a
   helper both tools use.
2. Template assets: author the seven `.ink` files against the design refs.
3. Validator: per-template schema constants + typed errors.
4. `render_template` tool: enum, description, execution via the shared helper.
5. Tests: per-template compile (rich + minimal data), validator negatives,
   availability/caps, description budget.
6. Full module verification; report with a table template → data schema.

# Context the executor cannot re-derive

- Branch `ink-surface` in `E:\Tools\Rokid\wt-ink`; M1-M3 all committed and
  device-validated. `RenderInkPageTool` (M3) is the pattern for gating,
  timeout, and typed-problem feedback; `AssistantPluginService` owns the ink
  session lifecycle.
- The engine compiles host data over the def-block data (deep merge, host
  wins); templates should carry safe default `data` in their def block so
  minimal tool data still renders.
- Trap (M1 lesson): a view without `display:flex` is a block (column, flex
  props inert) — the official samples always declare it; do so everywhere.
- Trap: `org.json` stays compileOnly in ink-engine; the plugin's unit tests
  already have real org.json if the existing test setup provides it — check
  before adding.
- Chart component contract: line/area/pie/radar guaranteed + bar; series get
  dash/marker differentiation automatically from the renderer — templates just
  pass labeled series.
- AIUI ships NO built-in template library (verified 2026-08-09): these seven
  files are original work following their design tokens; do not hunt for
  upstream template files that do not exist.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff touches files outside plugins/assistant/**.
- A template cannot express its purpose within the v1 matrix — stop and
  report which feature is missing; do not extend the matrix.

# Autonomy

Executor decides alone: exact template markup within the design constraints,
validator structure, helper extraction shape, test organization. Executor
stops for: anything platform-level, an eighth template, description over
budget it cannot compress.
