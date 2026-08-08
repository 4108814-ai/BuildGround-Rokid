# Plan 020 — Rich Surface: a rendering framework for assistants

**Status:** draft for owner review
**Depends on:** feasibility report `E:\Tools\Rokid\tmp\aiui-surface-feasibility\report.md`, on-device probe results `probe-results-2026-08-08.md` (same folder)
**Ships value first in:** the Assistant plugin; generalizes to the public SDK later.

## 1. Motivation

Rokid's own assistant renders rich, animated pages on the glasses through AIUI — their agentic JS runtime (`.ink` pages: canvas, charts, Lottie, transitions). Nexus plugins today render structured text lines, cards, and static images. The goal: give the Assistant plugin (and eventually any plugin) a **declarative rich-rendering framework** so an LLM can answer with a chart, a metric grid, a gauge, or an animated page — near-natively, on any provider (ChatGPT, OpenAI-compat, etc.) via function calling.

Two decisive transport facts are now **proven on real hardware** (2026-08-08):

- An AIUI page on the glasses reaches an HTTP server bound to the **phone's loopback** through Rokid's CXR network proxy (the phone opens the socket).
- This works with **glasses Wi-Fi off** — the proxy carries everything over BT/CXR. 1 Hz polling ran at perfect cadence.

So the full-page AIUI path is real. This plan turns it into a coherent framework instead of a one-off.

## 2. The embedding question, answered

**Can AIUI content render inside our existing Notice/card surface, as a child view? No — and this is a hard platform boundary, not a design choice.**

The Ink runtime lives in Rokid's `com.rokid.os.sprite.assistserver` process; pages render inside its private `JsaiActivity`/`InkViewHost`. Android only allows embedding another process's view when the *host of that view* cooperates (`SurfaceControlViewHost` handoff, or shipping the runtime as an embeddable library). Rokid exposes neither. Field evidence agrees: the jsai scene is a fullscreen scene that other surfaces (our overlay, CXR CustomView) cover or kill — it composes with nothing.

**But the *experience* the question is really asking for — rich blocks inline in our surface — is achievable natively.** Our glasses overlay is built from our own Android views; we can render charts, gauges, grids, and progress natively inside the existing card/notice surface at 60 fps, driven by the same declarative payload. That insight shapes the whole architecture:

> **One schema, two renderers.** A single `RichSurfaceSpec` JSON payload; an **inline tier** rendered natively by the glasses-hub inside existing surfaces (the "child" mode), and a **full-scene tier** rendered by a Nexus-owned AIUI `.aix` app (the "wow" mode: canvas animation, Lottie, full pages). Plus a snapshot fallback that rasterizes phone-side and rides the existing image channel.

Plugins and the LLM never choose a renderer directly; they declare content and a presentation hint, and the hub picks the best tier available.

## 3. Architecture overview

```
Assistant plugin (LLM tool call: render_rich_surface)
        │  RichSurfaceSpec v1 (constrained JSON)
        ▼
Phone hub ── validate (schema, limits, monochrome rules)
        │       arbitrate (single foreground owner, grants)
        ├── Tier A "inline": /surface/show with rich block lines ──► glasses-hub
        │        native block renderer inside card/notice surface (60 fps, ours)
        ├── Tier B "snapshot": phone rasterizes spec → PNG ──► existing image channel
        │        (degradation path for old glasses-hub versions; static)
        └── Tier C "full-scene": open Nexus .aix via JsaiService
                 page polls hub loopback server (proven transport)
                 canvas / chart / Lottie / transitions, full 480px page
```

Degradation chain: **C → A → B → plain card/text.** The capability is never load-bearing: if the AIUI runtime disappears in a firmware update, Tier C silently vanishes and content still renders via A/B. If the glasses-hub predates the block renderer, A degrades to B. Everything degrades to the text card that exists today.

## 4. RichSurfaceSpec v1 — the payload language

A narrow, versioned, **inert** JSON document. Never `.ink`, never JS/CSS, never URLs, never raw A2UI. The phone hub is the single validator; renderers trust validated specs only.

```jsonc
{
  "v": 1,
  "title": "Battery — last 24 h",
  "presentation": "auto",          // "inline" | "fullscreen" | "auto"
  "blocks": [
    { "type": "text",   "body": "Draining 2.1 %/h since 14:00.", "emphasis": "normal" },
    { "type": "metrics", "cells": [ { "label": "Now", "value": "64 %" },
                                     { "label": "Est. empty", "value": "04:10" } ] },
    { "type": "chart",  "chart": "line", "series": [ { "label": "SoC",
        "points": [[0,100],[6,87],[12,74],[18,69],[24,64]] } ],
        "xLabel": "h", "yLabel": "%", "animate": true },
    { "type": "progress", "label": "Charge", "value": 0.64 },
    { "type": "gauge",  "label": "Draw", "value": 0.35, "max": 1.0 },
    { "type": "timeline", "items": [ { "at": "14:00", "text": "Screen session" } ] },
    { "type": "animation", "asset": "pulse-ok" },   // allowlisted Nexus asset id only
    { "type": "actions", "items": [ { "id": "details", "label": "Details" } ] }
  ]
}
```

Rules enforced by the hub validator:

- **Block set v1:** `text`, `metrics`, `chart` (`line | area | bar | pie | radar | sparkline`), `progress`, `gauge`, `timeline`, `animation` (allowlisted ids), `actions`. Each renderer advertises which blocks/chart kinds it supports; the hub downgrades (e.g. `radar` on inline tier → snapshot tier) or rejects with a typed error.
- **Limits (initial):** ≤ 64 KiB total (existing surface envelope), ≤ 32 blocks, ≤ 4 series × 256 points per chart, ≤ 8 actions, ≤ 4 remote revisions/s coalesced by the hub. Tightened after measurement.
- **Monochrome by construction:** the schema has **no color field**. Emphasis, luminance tiers, dash/marker styles, and borders are renderer-owned per the green design system (`_tmp_aiui_official/design/monochrome/design-system-green.md`). Series distinguish by label + dash/marker, never by hue. Errors/warnings use text and borders, not color.
- **Actions are ids**, routed back over the existing input path; no commands, no URLs.

The same spec type is the LLM tool argument, the SDK API argument, and both renderers' input. `update` calls carry a full replacement spec plus a monotonic revision (diffing is a renderer concern, not a wire concern).

## 5. Tier A — inline blocks in existing surfaces (the "child" mode)

**What:** a native block renderer inside the glasses-hub, extending the current card surface with a new line kind (`kind: "block"` carrying one validated block). A rich answer is then an ordinary foreground card whose body happens to contain a chart — same show/update/hide lifecycle, same input routing, same BACK behavior, same single-owner arbitration that plugins already have. Nothing about the surface protocol's shape changes; the protocol already versions renderer features per surface, so old glasses-hubs simply don't advertise blocks.

**Renderer scope v1:** `text`, `metrics`, `chart:line|bar|sparkline`, `progress`, `gauge`, `timeline`, `actions`. Custom `View`/`Canvas` drawing, pure black background, green tiers per the design system. Entry transitions and value-change animations are renderer-owned and cheap (we own the frame loop; 60 fps native, no remote frames).

**Why this tier leads the plan:**

- It is the integrated, in-surface experience the product actually wants for most answers (an assistant reply with a chart *in the card*, not a context switch).
- Zero firmware dependency, zero new process interaction, zero new transport — it reuses everything shipped through plan 018 (notice lines) and the surface channel.
- It ships value in the Assistant weeks before the AIUI tier's remaining unknowns (launch authority, display arbiter) are closed.

**Notices:** notice bands stay messages/questions (plan 011 doctrine). Rich blocks go to the foreground card surface; a notice may *announce* a rich result and open it on confirm. A single small exception is allowed later (sparkline in a notice) but is out of v1.

## 6. Tier B — phone-rendered snapshot (degradation path)

The phone hub rasterizes the same spec to a monochrome PNG (Android `Canvas`, green-on-black, 512 px, ≤ 64 KiB) and ships it through the **existing** image channel. Static, ~180 ms per frame, no animation beyond occasional refresh. Used when the glasses-hub is too old for Tier A, and as the safety net under Tier C. Costs one rendering module on the phone, no glasses work at all.

## 7. Tier C — full-scene AIUI (the "wow" mode)

One Nexus-owned, versioned, signed-by-us `.aix` app containing a fixed router/renderer page. No plugin content ever becomes code inside Rokid's runtime.

**Transport (proven):** while a rich session is active, the hub runs an ephemeral HTTP server bound to phone `127.0.0.1` on a fixed port. The page polls `GET /v1/surface?after=<revision>` (500–1000 ms; bounded long-poll if P5 confirms it) and posts `POST /v1/events` (action ids, ready, heartbeat). Server normalizes absolute-form request targets (`GET http://127.0.0.1:<port>/... HTTP/1.1` — observed). Works with glasses Wi-Fi off; requires no LAN, no P2P.

**Auth (adjusted by probe):** `open_params` custom fields do **not** reach the page on current firmware (proven: page `onLoad(query)` receives `{}`). So: a per-install 128-bit secret is injected into the `.aix` at pack time. The hub packs and installs the `.aix` itself, so the secret rotates on every install/update; requests without it are rejected; the server binds only during an active lease and serves only the single active session. Cross-app loopback exposure is accepted v1 risk under those constraints; revisit if firmware ever forwards `open_params`.

**Lifecycle:**

- *Install/update:* glasses-hub bundles the `.aix` as an asset, stages it to glasses storage, merges (never replaces) `agents_index.json` with backup + digest check, via **fixed-purpose** authenticated bridge/KADB operations (`aiui_stage`, `aiui_commit`, `aiui_open`, `aiui_close`) — never plugin-visible, never arbitrary shell. Hi Rokid rewrites the index at will (observed), so install is re-entrant and verified before each advertisement of the capability.
- *Open:* try direct `startService` from the glasses-hub APK (P3, untested); fall back to the KADB shell path (proven today). Wake the display first — a page launched with the display asleep loads only on resume (observed).
- *Reload trap (observed):* re-opening the same `.aix` version never re-runs `onLoad`. The renderer page is therefore long-lived and stateless-per-session: it always polls for current state, so it never *needs* a reload; forced reloads (renderer update) bump the package version.
- *Close/return:* page self-closes on `close` command via polling; the glasses-hub also watches assistserver window/scene transitions (accessibility service) plus phone-side heartbeat lease expiry, all converging on one idempotent "restore native surface" transition.

**Display arbiter (new glasses-hub component):** our accessibility overlay draws *over* the jsai scene (observed — the Launcher covered the probe page). The arbiter owns three exclusive modes — `NATIVE` (overlay/activity), `AIUI`, `CXR_CUSTOMVIEW` — suspending the native renderer without signaling the plugin, granting AIUI sole input ownership during its lease (BACK = exit and restore), and resuming the prior surface with a phone resync on exit.

**Renderer scope v1 (page side):** `document` layout of the shared blocks + `chart:line|area|pie|radar` (the guaranteed set) with `animate`, canvas-driven gauge/scene primitives, bundled Lottie ids, transitions. Sample-only chart types (bar/scatter/funnel) and CSS keyframes (documented unsupported, yet used by official samples) are excluded until device-verified (P8).

## 8. SDK surface

New signer-bound capability **`rich_surface`** (distinct grant, visible re-approval on request-set change), on top of the existing `surfaces` arbitration — one foreground owner total, `SURFACE_BUSY` semantics unchanged.

```kotlin
val rich = client.richSurfaceSession()
rich.show(spec)          // RichSurfaceSpecV1
rich.update(spec)        // full replacement, hub assigns revision
rich.close()
// callbacks: onReady(tier), onAction(id), onClosed(reason), onError(code)
```

Feature detection follows the existing pattern: hub capability bits (`RICH_INLINE_V1`, `RICH_AIUI_V1`) + link state; absent → `CAPABILITY_NOT_AVAILABLE` and the plugin falls back to today's cards. The Assistant is the only consumer in v1; the API is designed public but shipped internal until M4.

## 9. Assistant integration (the actual "framework for the assistant")

- New side-effecting tool **`render_rich_surface`** in the existing tool registry (provider-filtered, argument-validated, memoized, once-per-phase like other side-effecting tools). Its JSON schema **is** `RichSurfaceSpecV1` — the model never sees tiers, renderers, or transports.
- Tool availability requires: active session + `rich_surface` grant + at least one rich tier advertised. Result returns `{surfaceId, revision, tier}`.
- Prompting: the system prompt teaches *when* to render (numeric comparisons, trends, multi-value states) and *when not to* (plain prose answers), with the monochrome constraint stated. Over-rendering is contained by the once-per-phase cap and hub-side rate limits.
- Plain text answer remains the universal fallback and is always produced alongside (the card body / TTS path is unchanged).

## 10. Security model (summary)

- Plugins ship **data, never code**: no `.ink`/JS/CSS/A2UI passthrough, no URLs, no arbitrary assets. Animations are Nexus-bundled ids.
- The hub is the only validator and the only writer to renderers; specs are size/depth/rate-bounded; logs redact content and secrets.
- Loopback server: per-install secret, active-lease-only binding, single-session serving, no unauthenticated endpoint. Fuzz + competing-plugin + revocation tests before public exposure (P12).
- The `.aix` install path is fixed-purpose and authenticated; the command-bridge allowlist grows by named operations only.

## 11. Milestones

Re-sequenced from the feasibility report: the inline tier now leads, because it is firmware-independent, answers the product's integration wish, and ships assistant value immediately. AIUI follows on its proven transport.

| # | Milestone | Contents | Size |
|---|---|---|---|
| M1 | **Schema + inline tier + assistant tool (private)** | `RichSurfaceSpecV1` + validator in hub; glasses-hub block renderer (text, metrics, line/bar/sparkline, progress, gauge, timeline, actions); `render_rich_surface` wired into the Assistant only; capability bit `RICH_INLINE_V1`. | **M** |
| M2 | **Snapshot tier** | Phone rasterizer for the same spec → existing image channel; automatic downgrade for old glasses-hubs. | **S** |
| M3 | **AIUI vertical slice** | Nexus renderer `.aix` (document + line chart), loopback server + polling protocol + per-install secret, manual install, KADB launch, display arbiter v1 (suspend/restore, BACK, close detection). Closes probes P3/P5/P7 en route. | **M–L** |
| M4 | **Managed `.aix` lifecycle** | Bundled asset, staged install, index merge/rollback, digest health, fixed bridge ops, firmware gating + capability advertisement (P10). | **L** |
| M5 | **Public SDK + hardening** | `rich_surface` grant surfaces to third-party plugins; docs + sample; P8 (renderer limits/drift), P11 (battery), P12 (isolation/fuzz); support matrix. | **L** |

M1 is the first user-visible release: the assistant answers with charts *inside* today's card surface. Each later milestone upgrades presentation without touching the tool contract.

## 12. Remaining unknowns (attached to milestones)

- **P3 launch authority** (M3): can the release-signed glasses-hub call `JsaiService` directly, or does the KADB path remain the only launcher? Shell path is proven; this decides plumbing, not feasibility.
- **P5 transports** (M3): bounded long-poll / `onChunkReceived` / EventSource vs plain 1 Hz polling (proven). Optimization only.
- **P7 full handoff matrix** (M3): overlay *and* activity modes × crash/link-loss/BACK; measured handoff latency budget.
- **P8 renderer drift** (M5): actual firmware component set (bar/scatter/funnel? keyframes?), sustained fps/thermal budget.
- **P10 index concurrency** (M4): Hi Rokid rewrites `agents_index.json` (observed same-day); merge strategy must survive it.
- **P11 battery** (M5): quantified deltas for poll cadences and animation budgets; sets production limits.
- **Firmware drift (standing risk):** the whole Tier C rests on private Rokid surfaces (JsaiService action, index schema, proxy behavior). Mitigation is architectural: Tier C is optional, version-gated, and always degradable to A/B.

## 13. Non-goals

- No arbitrary plugin `.ink`/JS/A2UI, ever, under this plan.
- No AIUI voice input (`SpeechRecognition` in-page) in v1 — Nexus STT stays the voice authority.
- No wake-word/agent-store registration of the renderer agent (deterministic hub-driven open only).
- No standing Wi-Fi/LAN/P2P transport for rich content.
- No notice-band charts in v1 (notices stay messages/questions; rich content lives on the foreground surface).
