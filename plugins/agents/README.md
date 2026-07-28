# Agents

Agents is the Phase 1 read-only mission-control plugin for coding-agent
sessions. It merges Claude Code sessions from `nexus-agentd` with OpenClaw
Gateway sessions, keeps the connections in a low-priority foreground service,
posts transition-only phone notifications, and renders a structured Nexus card
on the glasses.

## Configuration

- Claude Code: paste the single JSON pairing line emitted by `nexus-agentd`.
  The plugin validates `v:1` and `kind:"nexus-agentd"` and stores one
  host/port/token/name slot.
- OpenClaw: enter the Gateway host, port (default `18789`), and token. A host
  may include `ws://` or `wss://`; a plain host uses `ws://`.
- Each provider has an independent enable switch and connection test.
- **Agents holds no notification permission and posts nothing on the phone.**
  The permission is deliberately absent from the manifest, so Android keeps the
  monitoring foreground service running but never shows its notification.

Settings, auth tokens, the OpenClaw device seed, alert fingerprints, and the
Gateway-issued device token use the plugin's private `nexus_plugin_agents`
`SharedPreferences`. This follows Transit’s existing plain-`SharedPreferences`
pattern; no token or pairing payload is logged.

### Linking a computer

The daemon dials the phone, so the phone decides who is allowed in:

- The **first** computer ever links itself — nothing exists yet to impersonate,
  and this is what keeps the zero-setup path zero-setup.
- Every computer **after** that must arrive while the wearer holds the door
  open: *Link a computer* on the settings screen opens a two-minute window.
- A known computer presenting the **wrong token is refused**, and its stored
  token is never overwritten.
- A new link is announced on the glasses, never on the phone.

The current OpenClaw protocol requires device identity in addition to the
shared Gateway token. On the first remote connection, approve the new
**Nexus Agents** device in OpenClaw. The plugin persists its identity so this is
normally a one-time step.

## HUD and background behavior

The HUD is a typed `NexusCard` with `NexusCardLine` list rows: a title, an
optional second line saying what the session is doing to you or for you, a tone
carrying its urgency, and a selection flag. No preformatted text surface is used.

DPAD up/down moves the selection, ENTER opens the selected session's
conversation, and BACK leaves the conversation or hides the surface. The
selection is held **by session key, not row index**: the board re-sorts itself
as agents work, so a positional cursor would drift onto a different session
between looking and pressing.

A session that starts asking for the wearer raises an **interactive notice** —
the band from hub 1.0.46 — rather than fighting for the whole surface. Several
sessions asking at once become one band, and a tap on it opens the board on the
session that rang. The fingerprint that stops an alert repeating is committed
only once the hub has taken the notice, so an alert that could not be delivered
is offered again on the next update instead of being silently marked as sent.
Nothing is queued for glasses that are asleep: the session simply stays at the
top of the board, which is where the wearer will look.

Unlike ordinary dormant Nexus plugins, this product explicitly requires a
monitoring foreground service and phone alerts while its HUD is closed. The
network owner is therefore a separate, non-exported `AgentsMonitorService`;
the one exported `AgentsPluginService` remains the normal SDK adapter.

## Implemented protocols

### nexus-agentd (`nexus-agents/1`)

Implemented exactly:

- client `hello` and server `hello_ack`;
- authoritative `snapshot`;
- contiguous `session_upsert` and `session_removed`;
- a single `refresh` request on any sequence gap, with deltas ignored until the
  replacement snapshot;
- `ping`/`pong`;
- close `4401` as non-retrying `auth_failed`, and jittered 1/2/4/5-second
  reconnects for other closes and network failures;
- ignored unknown message types;
- the conversation exchange: the plugin sends `detail_open` with a session id
  when the wearer opens a session and `detail_close` when they leave, and the
  daemon answers with one `detail` carrying the recent messages followed by
  `detail_append` for each new one while the view stays open.

Every reconnect resets sequence state, sends a fresh hello immediately, and
waits for a new authoritative snapshot.

### OpenClaw Gateway v4

The implementation was derived from the source clone at
`C:\Users\saim2\.codex-detached\rokidnexus\agents-p1\openclaw`:

- Protocol version 4 and minimum general-client version 4:
  `packages/gateway-protocol/src/version.ts:2`.
- `connect` parameters (protocol range, client, role/scopes, device, and token
  auth) and `hello-ok`:
  `packages/gateway-protocol/src/schema/frames.ts:33` and
  `packages/gateway-protocol/src/schema/frames.ts:75`.
- `req`, `res`, and `event` frame envelopes:
  `packages/gateway-protocol/src/schema/frames.ts:155`,
  `packages/gateway-protocol/src/schema/frames.ts:163`, and
  `packages/gateway-protocol/src/schema/frames.ts:172`.
- Generic `gateway-client` identity and `backend` mode:
  `packages/gateway-protocol/src/client-info.ts:23` and
  `packages/gateway-protocol/src/client-info.ts:50`.
- Challenge-first Android handshake and connect request:
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt:986`,
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt:1042`,
  and
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt:1242`.
- Canonical signed device-auth payload v3:
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/DeviceAuthPayload.kt:7`.
- Persistent Ed25519 device identity semantics:
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/DeviceIdentityStore.kt:11`
  and
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/DeviceIdentityStore.kt:174`.
- `sessions.list` parameters and server handler:
  `packages/gateway-protocol/src/schema/sessions.ts:285` and
  `src/gateway/server-methods/sessions-read.ts:220`.
- `sessions.subscribe` and its `sessions.changed` subscription:
  `src/gateway/server-methods/sessions-subscriptions.ts:35` and
  `src/gateway/server-methods/session-change-event.ts:51`.
- Session title/activity/run/error/CWD fields:
  `packages/gateway-protocol/src/schema/sessions-row.ts:27`,
  `packages/gateway-protocol/src/schema/sessions-row.ts:43`,
  `packages/gateway-protocol/src/schema/sessions-row.ts:45`,
  `packages/gateway-protocol/src/schema/sessions-row.ts:54`, and
  `packages/gateway-protocol/src/schema/sessions-row.ts:75`.
- Active-run projection returned by `sessions.list`:
  `src/gateway/server-methods/sessions-read.ts:333` and
  `src/gateway/server-methods/sessions-read.ts:364`.
- `exec.approval.requested` request fields and event timestamps:
  `packages/gateway-protocol/src/schema/exec-approvals.ts:235` and
  `src/gateway/server-methods/approval-shared.ts:255`.
- Approval event scope and session-change read scope:
  `src/gateway/server-broadcast.ts:43` and
  `src/gateway/server-broadcast.ts:71`.

Wire traffic sent by this plugin is limited to:

1. the signed `connect` request with token auth;
2. `sessions.list`;
3. `sessions.subscribe`.

It listens for `sessions.changed`, `exec.approval.requested`, and
`exec.approval.resolved`. A session-change event triggers an authoritative
re-list instead of locally guessing a patch. The approval events are observed
only; the plugin never sends an approval resolution, session message, command,
or agent-spawn request.

Mapping is deliberately conservative:

- `hasActiveRun:true` or Gateway status `running` → `working`;
- a live exec-approval request for that session → `needs_you`;
- Gateway status `failed`, `killed`, or `timeout`, or `lastRunError` →
  `error`;
- everything else → `idle`, with Gateway status notes kept as
  `statusDetail`.

The connection requests `operator.read` plus `operator.approvals`; the latter is
required by the Gateway event guard to receive approval-request events. The
implementation contains no approval method or resolution frame.

## Build and test

This folder is a standalone Gradle root that points read-only at the repository
SDK projects, so no root Gradle file needs an Agents entry:

```powershell
cd plugins\agents
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) ".gradle-user")
..\..\gradlew.bat testDebugUnitTest assembleDebug
```

The module uses the same first-party namespace, `minSdk 30`, SDK project
dependency, signing script, AndroidX Core/coroutines versions, JUnit stack, and
JSON choice as Transit, plus the repository’s existing OkHttp `4.12.0`.

## Phase 1 limits and risks

- OpenClaw approval events are live broadcasts. The permitted Phase 1 method
  subset has no authoritative pending-approval list/replay, so an approval
  already pending before a reconnect may not appear until OpenClaw emits a new
  related event.
- `sessions.list` is capped at 200 rows per refresh. The HUD can show 63 session
  rows because a Nexus card is limited to 64 rows including the header.
- Plain `ws://` sends credentials over an unencrypted tailnet connection.
  Configure `wss://` when the Gateway has TLS; the plugin does not weaken TLS or
  install a custom trust manager.
- No boot receiver is present. Monitoring starts after configuration, an
  explicit connection test, or a Nexus HUD open, as required for Phase 1.
- No approve/deny, prompt answering, session spawning, voice, or command send
  path exists.
- **Monitoring is not real-time while the phone is in Doze.** A foreground
  service keeps the process alive but does not exempt it from Doze's network
  restrictions, so a screen-off, stationary, unplugged phone may not see a
  transition until a maintenance window. With no phone notification by design,
  the alert arrives when the wearer next looks at the board.
- The device identity is signed by a hand-written Ed25519 implementation. Its
  signatures are RFC 8032 conformant but the arithmetic is not constant-time,
  which is acceptable on a private tailnet and must be replaced with a vetted
  library before any public release.
