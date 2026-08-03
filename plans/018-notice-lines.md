# Plan 018 — A notice that knows where a message ends

Status: DONE — `lines` is in the notice contract (shared
NoticeSurfaceContract, validated create and update paths, contract tests).
Motivated by 017 (relay notifications), which is what made the gap visible on
hardware.

## Goal

Let a notice carry **several things** instead of one paragraph.

A relayed conversation is the case that exposed it. Three messages arrive on the
optics today as:

```text
Mika: Can you check the build when you have a minute? Mika: I added a
second message to exercise thread extraction. Mika: Reply from the
glasses when you are ready.
```

Everything about that is correct per the contract and unreadable in the eye. The
wearer has to parse where one message ends and the next begins, in an optic they
are glancing at, while walking.

## Why it is like this, and what is actually wrong

[`NoticeSurfaceContract.readText`](../shared/src/main/java/com/anezium/rokidbus/shared/NoticeSurfaceContract.kt)
collapses every newline to a space, deliberately: *"the renderer owns wrapping
and paging, and a plugin cannot be allowed to lay the band out by hand."*

That rule is right and this plan does not weaken it. But it conflates two things:

- **Laying out** — choosing where lines break, how much space sits between them,
  what size the text is. Forbidden, and stays forbidden.
- **Structure** — saying "this is three messages, not one". That is not layout,
  it is what the content *is*, and the platform needs it to render well.

The pin tier already makes this distinction: a pin carries `NexusPinLine`s, not
a pre-formatted string, and the note that settled it is on record — structured
lines, never pre-formatted monospace strings. The notice tier simply never
needed it until something asked it to carry a conversation.

## The design

**`body` is untouched.** Every plugin sending a string today renders exactly as
it does today, and an un-updated plugin talking to a new hub is unaffected. This
is additive in the only direction that matters.

**A new optional `lines`**, an array of strings, carried on `/notice/show` and
`/notice/update`. When present it replaces `body`; sending both is
`INVALID_NOTICE` rather than a silent precedence rule nobody remembers.

- At most **16** lines. More than that is a surface, not a notice.
- The **sum** of the lines, plus one separator each, must fit
  `MAX_BODY_CHARS` (1024). One budget, so a plugin cannot buy more text by
  splitting it, and the paging maths downstream does not change.
- Each line is trimmed and has its own newlines collapsed, exactly like `body`
  does now. The rule survives *within* a line; the array is the only place a
  break can be asked for.
- An empty line is dropped, not rendered as a blank. Vertical rhythm is the
  platform's.

**The renderer breaks at every line, then wraps what does not fit.** A wrapped
continuation is visually part of its line — same left edge, no bullet, no
indent. The wearer sees one message per line until a message is too long, at
which point it takes two, which is what they expect.

**Paging is unchanged in principle**: measured on the real layout, eight body
lines to a page, count computed where the pixels are. It simply measures a
layout that now contains hard breaks.

It does **not** follow that the page count cannot grow — an earlier draft of
this plan claimed it and was wrong. Sixteen one-word lines occupy sixteen
measured lines where the same characters as prose would occupy one, so a `lines`
notice can page where a `body` notice would not. That is correct behaviour, not
a regression: it is the whole point that a message gets its own line. It is
bounded, which is what matters — sixteen lines is at most two unwrapped pages,
and the 1024-character budget caps the wrapped case.

This is a wire change twice over. The SDK bumps to **0.9.0** — `NexusNotice` and
`NexusNoticeUpdate` gain `lines: List<String> = emptyList()` — and
`NoticeSurfaceContract.VERSION` goes **2 → 3**.

The contract bump is not optional, for the same reason it was not optional at
v2. Both hubs gate the notice capability on an *exact* version match, so leaving
it at 2 would let an updated phone believe older glasses can render a `lines`
payload. They would take the handshake and then reject the band in silence — it
carries no `body`, so it fails the "title or body" rule — and the wearer would
see a plugin that simply stopped talking. Bumped, the old pair declines the
capability outright and the plugin hears `CAPABILITY_NOT_AVAILABLE`, which it
can act on.

## What does not change

- Geometry stays platform-owned. No plugin learns the width, the text size, the
  line height, or how many lines fit a page.
- The one-answer rule, the action row, the TTL and lifetime, `wakeDisplay`,
  images, and paged-xor-answerable are all untouched.
- The 1024-character budget is the same number it is today.

## MUST NOT

- MUST NOT let a line carry style: no weight, no colour, no size, no alignment,
  no leading marker chosen by the plugin. Lines are structure; style is ours.
- MUST NOT honour newlines *inside* a line — that is the hand-layout escape
  hatch this plan exists to avoid reopening.
- MUST NOT accept `body` and `lines` together.
- MUST NOT raise the character budget, the page height, or the action cap.
- MUST NOT change how a `body`-only notice looks, measures, or pages. A
  screenshot of today's band must be reproducible after this lands.
- MUST NOT let a plugin infer the page count, and MUST NOT send it upstream.

## Acceptance

1. Contract tests: the 16-line cap, the shared character budget including
   separators, both-fields rejection, per-line trimming and newline collapse,
   empty-line dropping, an absent `lines` behaving byte-for-byte as today, and
   `VERSION` reading 3.
2. A pure paging test over a lines payload, alongside the existing body one:
   same budget, same page arithmetic, hard breaks respected.
3. `assembleDebug` green for `phone-hub`, `glasses-hub`, the SDK and
   `:plugin-relay`.
4. On-device: a three-message relay thread reads as three lines; a message long
   enough to wrap takes two lines with no bullet or indent on the second; a
   sixteen-line payload pages correctly; a `body`-only notice is pixel-identical
   to the shipped build.

## Settled, and why they are no longer open

- **Newlines inside `body`** — still collapsed, permanently. The array is the
  supported way to ask for a break, and having exactly one way is the point.
- **Per-line styling** — no. The moment a plugin can bold a line it is laying
  out the band, and every plugin's notice starts looking different.
- **A separator character instead** (`•`, `—`) — rejected. It is a plugin
  drawing punctuation to fake structure the platform refuses to model, and it
  still cannot break a line.
- **Sending each message as its own notice** — rejected. A notice is one event;
  three messages from one conversation are one event with three parts.
