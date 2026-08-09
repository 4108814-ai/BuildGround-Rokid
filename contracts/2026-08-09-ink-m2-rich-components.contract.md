---
task: ink-m2-rich-components
date: 2026-08-09
status: active
scope_globs:
  - "ink-engine/**"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/Ink*.kt"
  - "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/Ink*.kt"
  - "glasses-hub/build.gradle.kts"
  - "plugins/sample/src/main/java/com/anezium/rokidbus/plugin/sample/HelloPluginService.kt"
forbidden_globs:
  - "phone-hub/**"
  - "bus-client/**"
  - "shared/**"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/Reader*.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceModels.kt"
  - "**/local.properties"
  - "settings.gradle.kts"
test_commands:
  - ".\\gradlew.bat :ink-engine:test --console=plain"
  - ".\\gradlew.bat :glasses-hub:testDebugUnitTest --console=plain"
  - ".\\gradlew.bat :glasses-hub:assembleDebug :plugin-sample:assembleDebug --console=plain"
  - ".\\gradlew.bat :glasses-hub:lintDebug --console=plain"
max_failures: 2
---

# Goal

Milestone M2 of `plans/020-ink-surface.md`: `.ink` pages rendered by Nexus can now
contain the rich components — `chart` (line, area, pie, radar per the official
contract, plus bar flagged sample-derived), `lottie-view` (inline JSON only),
`progress`, and the `nx-canvas` declarative extension — all rendered natively on
the glasses in monochrome green, animated within the existing motion conventions,
and data-bindable so a `setData` patch animates a chart in place. The sample
plugin's demo page gains a live chart to prove it end to end.

# Non-goals

- Asset manifests / by-name references (images and Lottie by asset id) — later slice.
- `swiper`, `button`, `audio`, `camera`, `map`, A2UI — out of the v1 matrix.
- Remote URLs of any kind. CSS keyframes. The `<script setup>` runtime.
- Any phone-hub/SDK/wire-path change: the existing INK_DOC_V1 node model already
  carries arbitrary component tags, attributes, and data bindings — these
  components ride it unchanged.

# Constraints

- MUST read first: `plans/020-ink-surface.md` §3-4, `E:\Tools\Rokid\tmp\ink-slice2-recon.md`
  §4 (HudMotion conventions), and the official contracts:
  `E:\Tools\Rokid\_tmp_aiui_official\skills\aiui-dev\components.md` (chart contract
  at lines ~266-345, lottie-view ~348-381), plus `samples/capabilities/pages/chart/`
  and `lottie/` as behavioral reference. Strict-subset doctrine: what we support
  behaves exactly as documented; docs win over samples.
- MUST extend the `:ink-engine` component allowlist (`view/text/image/scroll-view`
  today — see `Markup.kt`) with `chart`, `lottie-view`, `progress`, `nx-canvas`,
  including per-component attribute validation with typed problems
  (`INK_COMPONENT_*`/`INK_ATTRIBUTE_*` codes, existing pattern) and budget
  enforcement in `InkWireValidator`: ≤ 4 series × 256 points per chart,
  ≤ 512 nx-canvas commands, ≤ 32 KiB inline Lottie JSON (constants exist or are
  added beside the existing budget constants).
- MUST implement glasses-side views as new `Ink*`-prefixed files registered in
  `InkHudView`'s composition (the existing per-tag view factory): a Canvas-drawn
  chart view, a Lottie view, a progress bar, and an `nx-canvas` interpreter whose
  command vocabulary mirrors `CanvasRenderingContext2D` names 1:1 (the declared
  design goal: the future JS runtime reuses this backend).
- MUST keep every color on the monochrome ramp via the existing `InkColorClamp`;
  chart series MUST be distinguished by dash pattern, marker shape, and stroke
  width — never by hue (`design-system-green.md` doctrine). Lottie output MUST be
  green-tinted (single-channel colorization over pure black).
- MUST animate through the existing `InkMotionAdapter`/`HudMotion` conventions:
  chart `animate` uses value interpolation on data change; no decorative idle
  loops; every recurring animation (Lottie loops, canvas sequences) stops on
  view detach/hide and on patch replacement — follow the cancellation paths the
  slice-2 renderer already implements (recon risks 10/12).
- MUST cap continuous redraw at 30 fps (plan §4 budget) — a single frame-callback
  gate, not per-component timers.
- MUST keep chart/canvas/progress geometry logic in Android-free classes covered
  by JVM unit tests (the `InkRenderLogicTest` pattern).
- MUST add exactly one dependency: `com.airbnb.android:lottie` (pick the latest
  stable 6.x), glasses-hub only. No other new dependencies.
- MUST update the sample plugin's ink demo page with a `chart` bound to data that
  the existing tap action updates (the tap already patches values — extend it to
  also shift chart points), staying within the sample's current structure.
- MUST NOT touch `SurfaceController.kt`, `SurfaceModels.kt`, reader files, or any
  forbidden glob. The component work lands entirely in the engine and the
  `Ink*` renderer layer.
- MUST NOT commit. MUST NOT run adb or device commands. If a build fails for an
  environment reason (SDK file access), retry once, then stop and report.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Engine suites green incl. new component cases | `.\gradlew.bat :ink-engine:test` | BUILD SUCCESSFUL, new tests for each component's positive + typed-error cases |
| 2 | Renderer logic suites green | `.\gradlew.bat :glasses-hub:testDebugUnitTest` | BUILD SUCCESSFUL, new JVM tests for chart geometry, canvas command validation, progress mapping |
| 3 | APKs build | `.\gradlew.bat :glasses-hub:assembleDebug :plugin-sample:assembleDebug` | BUILD SUCCESSFUL |
| 4 | Lint clean | `.\gradlew.bat :glasses-hub:lintDebug` | 0 errors |
| 5 | Official chart sample ingests | engine golden test compiling a chart page derived from `samples/capabilities/pages/chart/` (fixture committed to test resources) | Guaranteed types compile; out-of-matrix features produce the exact typed problems asserted |
| 6 | Diff scope | `git status --short` reviewed against scope_globs | No file outside scope |

Manual (performed by the reviewer, not the executor): device render of the sample
chart page + tap-driven chart animation, via the existing DEBUG_INK harness and
the sample plugin.

# Plan sketch

1. Engine: component/attribute definitions + validation + budgets + tests + chart
   sample fixture.
2. Glasses: `InkChartView` (axes, series, dash/marker differentiation, animate),
   `InkProgressView`, `InkLottieView` (inline JSON, tint, lifecycle),
   `InkNxCanvasView` (command interpreter) — each with its Android-free geometry
   core + JVM tests.
3. Register tags in `InkHudView`'s factory + patch-path updates (a data patch that
   changes chart points must update in place, not rebuild the node).
4. Sample page chart + tap wiring.
5. Full verification matrix; report.

# Context the executor cannot re-derive

- Worktree `E:\Tools\Rokid\wt-ink`, branch `ink-surface`, freshly rebased onto the
  agents merge (`974841a3`); the glasses now ALSO have a `reader` surface kind —
  do not touch it, it shares nothing with ink components.
- The 30 fps cap and budgets come from `plans/020-ink-surface.md` §4/§5; wire
  budget constants live in `InkWireValidator`/`InkEngine` companion.
- `InkHudView` owns a per-tag view construction path and a persistent node
  registry keyed by node id; patches arrive as `RenderChange` lists — chart data
  changes will arrive as `AttributeChanged`/`DatasetChanged` on the chart node
  (series come through the node's attributes; check how slice 1 serializes
  attribute values — they are JSON values, not only strings).
- Chart contract detail (components.md): `type` default `line`; multi-series with
  labels; `smooth` default true for line/area; `animate` flag; `show-average`
  reference line for single-series line/area.
- Windows/JVM environment: gradle daemon warm; `-PskipCxrGlobal=true` must NOT be
  used for phone modules but is irrelevant here (no phone modules in scope).
- Trap from slice 2: `org.json` must stay compileOnly in ink-engine; glasses tests
  need real org.json on the test classpath (already configured — do not remove).
- Trap from slice 3 review: containers without `display:flex` are blocks; chart/
  lottie/canvas/progress are leaf components and must size like replaced elements
  (explicit width/height styles, else sensible defaults ~full-width × 200rpx-ish
  per the official samples' shapes).

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff touches files outside scope_globs or matching forbidden_globs.
- A needed decision is not covered by Constraints (e.g. a chart attribute whose
  documented behavior is ambiguous even after reading the sample): stop, report
  the ambiguity and the two candidate readings — do not pick silently.

# Autonomy

Executor decides alone: internal class layout, geometry algorithms, how the
Lottie tint is applied, test structure, fixture derivation. Executor stops and
reports for: anything expanding the wire format, any new dependency beyond
lottie, any change to files outside scope, any doc-vs-sample contradiction not
already resolved by the strict-subset rule.
