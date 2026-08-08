# Plan 020 — Ink Surface: a native port of the AIUI page format

**Status:** approved direction, spec for implementation
**Supersedes:** the first draft of this plan (rich-surface tiers over Rokid's live AIUI runtime). The runtime path was proven on device and then deliberately set aside — it depends on private firmware surfaces and composes with nothing. Everything learned about it is preserved in [docs/AIUI_RUNTIME.md](../docs/AIUI_RUNTIME.md); this plan does not use it.

## 1. What this is

**Ink Surface** is a new Nexus glasses surface tier that renders pages written in the AIUI `.ink` page format — Rokid's published, Apache-2.0-documented framework for AI-glasses UI (WXML-like markup, WXSS-like styles, data binding, charts, Lottie) — using **our own native renderer** inside the existing glasses-hub surface stack. We adopt their *format*; we own the *engine*.

Why port the format instead of inventing a schema or driving their runtime:

- **LLMs already speak it.** Rokid publishes the `aiui-dev` skill (complete API reference + samples, Apache 2.0) precisely so coding assistants can author AIUI pages. If our payload *is* that format, any function-calling provider renders rich pages with their docs as the prompt — no bespoke schema to invent, document, or teach.
- **Their samples become our test suite.** `samples/capabilities` (charts, canvas, Lottie, lists, grids, transitions) is a ready-made conformance corpus.
- **Their design system is our hardware's design system.** The monochrome-green token spec targets exactly these optics.
- **No firmware dependency.** The page renders in our overlay with our lifecycle, input, BACK semantics, and single-owner arbitration. Rokid updates can't break it.
- **Legally clean.** Reimplementing a published Apache-2.0 spec without touching their (closed, native) runtime is standard compatible-implementation work.

The first consumer is the Assistant plugin (an LLM tool that renders `.ink` pages); the public plugin SDK follows.

## 2. Compatibility doctrine

**Strict subset.** Everything we support behaves exactly as the official documentation describes — same tags, same property names, same semantics — so that official docs, the `aiui-dev` skill, and official samples work unmodified within our supported set. Rules:

1. The supported set is an explicit, versioned matrix (`INK_SURFACE_V1` capability announces it; the validator rejects out-of-matrix input with typed errors naming the unsupported feature — never silent degradation of semantics).
2. Anything we add that AIUI does not define is prefixed **`nx-`** (elements, attributes, style properties) so a page's portability is visible at a glance.
3. Where the official docs and official samples disagree (their documented drift: e.g. CSS keyframes are declared unsupported yet used by a sample), **the documentation wins**; sample-only behavior enters the matrix only after we choose to support it deliberately.
4. Conformance is verified against the official sample corpus on real glasses (golden screenshots), not against the docs alone.

## 3. The format, as we implement it

A page is a `.ink` single-file component. V1 accepts three of its four blocks:

```html
<script type="application/json" def>   → accepted: page metadata + initial data
<page>                                 → accepted: WXML-like markup
<style>                                → accepted: WXSS subset
<script setup>                         → REJECTED in v1 (typed error INK_SCRIPT_UNSUPPORTED)
```

**Data without JS.** In real AIUI, initial state lives in the script block and mutates via `setData`. V1 replaces that with host-supplied data: the page's `def` block may carry initial `data`, and the SDK call supplies/merges a data object. Updates are **data-only patches** (`ink.update(data)`) — the markup is parsed once, bindings re-resolve, renderer animates value changes. This preserves `setData` semantics exactly (same merge behavior), so pages upgrade transparently when the v2 script runtime lands.

### V1 support matrix

**Template engine:**
- Interpolation `{{ expr }}` in text and attribute positions.
- `wx:if` / `wx:elif` / `wx:else`, `wx:for` (with `wx:for-item`, `wx:for-index`, `wx:key`).
- Expression subset: literals, data paths, `!`, comparisons, `&&`/`||`, arithmetic, ternary. **No function calls, no assignment, no member access beyond data paths.** The evaluator is a bounded interpreter (depth/step limits), not `eval`.
- Event attributes `bindtap` / `catchtap` (and the input events of supported components): the handler name becomes an **action id** delivered to the plugin (`onInkAction(id, dataset)`), with `data-*` attributes as the dataset — matching AIUI authoring shape without executing anything.

**Components:** `view`, `text`, `image` (assets only, see §6), `scroll-view`, and list rendering via `wx:for`; `chart` per the official contract — `line`, `area`, `pie`, `radar` (multi-series, axes, smoothing, `animate`), plus `bar` flagged sample-derived; `lottie-view` (inline or asset JSON, autoplay/loop/speed/progress, size-capped, green-tinted); `progress`; **`nx-canvas`** — our declarative extension: a JSON array of draw commands mirroring `CanvasRenderingContext2D` names 1:1 (`moveTo`, `lineTo`, `arc`, `rect`, `fillText`, `setTransform`, gradients…), so the v2 JS canvas reuses the same backend verbatim.

**Styles (WXSS subset):** class selectors (single-class, no combinators v1); flexbox layout (direction, wrap, justify, align, grow/shrink/basis, gap); box model (size, margin, padding, border width/radius); text (size, weight, line-height, align, overflow/ellipsis); `opacity`; `transform` (translate/scale/rotate); `transition` (property allowlist, duration, easing); `rpx` and `%` units; CSS custom properties for the design tokens. **Excluded v1:** keyframe animations (per official docs), filters/blur (banned on these optics by both design systems), positioned layout beyond `relative`/`absolute` basics, media queries.

**Color doctrine:** pages author against the monochrome-green token set. The validator warns on literal colors and the renderer clamps everything to the green channel over pure black — a page physically cannot render a second hue.

## 4. Rendering architecture (glasses-hub)

```
.ink text ──► SFC splitter ──► WXML parser ──► node tree
                              WXSS parser ──► style rules
data (init + patches) ──► binding engine (expr interpreter, dirty tracking)
                                   │
                     style resolver (class → resolved style)
                                   ▼
                composition layer: native View tree
    FlexboxLayout (layout) · TextView/ImageView (leaves)
    ChartView · LottieAnimationView (green-tint) · NxCanvasView · ProgressView
    Animators for transitions and value changes
```

- **Native, not WebView** — settled by the HUD-motion measurements on this hardware.
- Parsers run phone-side in the hub (validation + normalization to a compact node/style document) so the glasses receive pre-validated structures and old glasses-hubs fail cleanly at the capability check. The glasses-side renderer trusts hub-validated input only.
- Data patches invalidate only bound nodes (dirty paths), so a chart point update never relayouts the page.
- Frame budget: transitions and chart animations run on Android animators (display-refresh driven); continuous `nx-canvas` sequences are capped by a battery budget (initial cap 30 fps, measured down or up in M5).
- Viewport: the existing surface viewport (same box the card surface owns today); `rpx` maps to it per the AIUI scaling rule (750 rpx = viewport width).

## 5. Surface-tier integration

`ink` is a **foreground surface tier** sharing the existing single-owner arbitration (`SURFACE_BUSY` semantics, BACK handling, dpad focus/scroll routing, display-wake on show). It is a sibling of the card surface, *not* a notice: notice bands remain short messages/questions (plan 011); a notice may announce a result and open an ink page on confirm.

Wire shape (existing surface channel, existing 64 KiB envelope):
- `/ink/show` — normalized page document + initial data + asset manifest.
- `/ink/update` — data patch (small; coalesced ≤ 4/s).
- `/ink/hide`, plus owner-only events back: `ready`, `action(id, dataset)`, `closed(reason)`, `error(code)`.
- Budgets v1: ≤ 32 KiB page document, ≤ 16 KiB data, ≤ 64 KiB total with assets, ≤ 256 nodes, ≤ 4 chart series × 256 points, ≤ 512 canvas commands, ≤ 32 KiB per Lottie JSON. Tightened or raised after M5 measurement.

## 6. Assets and security

- **Inert by construction (v1):** no script block, no URLs anywhere (`image`/`lottie` reference only names in the call's asset manifest — bytes shipped over the channel, size-capped, image-decoded phone-side into safe bitmaps), no external fetch of any kind from the page.
- The expression interpreter is bounded (no recursion into user code — there is none) and fuzzed in M5.
- Plugins need the new signer-bound **`ink_surface`** grant (distinct from `surfaces`, visible re-approval on request-set change).
- Everything a page can *do* is: render, animate, and emit action ids. Capability creep (JS, network) arrives only with v2's sandboxed runtime, gated by the same grant system.

## 7. SDK and Assistant integration

```kotlin
val ink = client.inkSurfaceSession()
ink.show(page = inkText, data = json, assets = mapOf("logo" to bytes))
ink.update(data = patch)
ink.close()
// callbacks: onInkReady(), onInkAction(id, dataset), onInkClosed(reason), onInkError(code)
```

Feature detection follows the existing pattern (capability bit + link state → `CAPABILITY_NOT_AVAILABLE` fallback to cards). The API is designed public from day one but stays Assistant-only until M4.

**Assistant tool `render_ink_page`:** arguments = `{ page, data?, title? }`. Tool availability requires active session + grant + capability bit. Prompting reuses the official `aiui-dev` reference plus our delta doc ("what Nexus Ink v1 supports/rejects"); the fallback is today's text card, always produced. Side-effecting tool rules (once per phase, validated, memoized) apply unchanged. The model authors real `.ink` — the format it already knows from Rokid's own docs.

## 8. Milestones

| # | Milestone | Contents | Size |
|---|---|---|---|
| M1 | **Core engine** | SFC splitter, WXML/WXSS parsers + hub-side validator, binding engine with expression interpreter, flexbox composition, `view`/`text`/`image`/`scroll-view`, `wx:if`/`wx:for`, transitions, actions/dataset routing, `ink` tier plumbing (show/update/hide, arbitration, input), dev harness (render a local .ink from the phone). | **L** |
| M2 | **Rich components** | `chart` (line/area/pie/radar/bar), `lottie-view`, `nx-canvas`, `progress`; value-change animations; asset manifest pipeline. | **M** |
| M3 | **Assistant tool** | `render_ink_page` in the tool registry, prompting pack (aiui-dev + delta doc), card fallback, UX policy (when to render). First user-visible release. | **S–M** |
| M4 | **Public SDK** | `ink_surface` grant, typed session API, docs + sample plugin, support-matrix doc. | **M** |
| M5 | **Conformance & hardening** | Official-sample conformance runs (golden screenshots on device), fps/battery budgets measured, expression/parser fuzzing, limits finalized, drift policy vs upstream AIUI releases. | **M** |
| v2 | **Script runtime** (separate plan when scheduled) | Sandboxed QuickJS: `setData`, timers, page lifecycle, `wx.request` through hub grants, JS-driven canvas on the `nx-canvas` backend. | **L** |

M1+M2 are the engine investment (the port proper); from M3 the Assistant ships charts and animated pages inside our own surface stack, in the format Rokid documented for us.

## 9. Risks

1. **Engine effort realism (top risk).** A WXML/WXSS/binding engine is real compiler-adjacent work; scope discipline on the v1 matrix is the mitigation — the matrix can grow every release, but only deliberately.
2. **Upstream drift:** AIUI's docs evolve; we track their releases and version our matrix. Strict-subset doctrine means drift can only *add* work, not break shipped pages.
3. **Expression evaluator security:** bounded interpreter + fuzzing (M5); no user code executes in v1 by construction.
4. **LLM authoring quality:** models may author outside the v1 matrix; typed validator errors are fed back to the model (tool error → retry), and the delta doc keeps the prompt honest.
5. **Battery under animation:** capped budgets, measured in M5 before limits are final.

## 10. Non-goals

- No WebView renderer (measured and settled).
- No dependency on Rokid's AIUI runtime, JsaiService, or `.aix` packaging — see [docs/AIUI_RUNTIME.md](../docs/AIUI_RUNTIME.md) for the preserved findings.
- No arbitrary JS in v1; no network access from pages in v1.
- No notice-band rendering of ink pages (notices stay messages/questions).
- No wake-word/agent registration; ink pages open only through Nexus surfaces.
