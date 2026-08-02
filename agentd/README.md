# nexus-agentd

`nexus-agentd` is the Windows PC-side monitor for Nexus Agents mission control. It receives
Claude Code lifecycle hooks on loopback, tails active Claude transcript JSONL files, and
publishes authenticated session snapshots and ordered deltas to the Nexus Agents Android
plugin.

The daemon can hold Claude Code `PreToolUse` hooks while the linked phone asks the wearer
to allow or deny a tool call. It does not auto-approve requests, accept voice commands,
provide TLS, or install itself as a Windows service.

## Requirements and setup

- Windows 11
- Node.js 20 or newer
- A Tailscale connection between the PC and the Android device

```powershell
cd agentd
npm install
npm run build
node dist/cli.js install-hooks
node dist/cli.js run
```

`run` is the default command, so `node dist/cli.js` is equivalent. If installed through
`npm link` or as a package, use `agentd` in place of `node dist/cli.js`.

Claude Code hooks post only to `127.0.0.1:8791`; this listener never accepts remote
connections. Plugin WebSockets listen on `0.0.0.0:8792` and require the pairing token.
Allow inbound TCP 8792 through Windows Firewall:

```powershell
netsh advfirewall firewall add rule name="nexus-agentd" dir=in action=allow protocol=TCP localport=8792
```

Port 8792 carries an authenticated but unencrypted WebSocket in Phase 1. Expose it only
over the user's trusted Tailscale tailnet, not through router port forwarding or a public
interface.

## Pair the plugin

```powershell
node dist/cli.js pair
```

The command prints a one-line JSON pairing payload and a terminal QR code. It prefers the
first IPv4 address on an interface whose name contains `Tailscale`. The Android plugin can
scan the QR code or accept the printed JSON pasted directly.

The token, stable machine ID, ports, and machine name live in
`~/.nexus-agentd/config.json`. Treat this file and pairing payload as credentials.
Runtime logs are appended to `~/.nexus-agentd/agentd.log`; at 5 MB the daemon rotates the
file to `agentd.log.old`.

## Codex monitoring

Codex monitoring is disabled by default. Enable it in the existing
`~/.nexus-agentd/config.json`:

```json
"codex": {
  "enabled": true,
  "port": 8390
}
```

The daemon first tries to attach to `codex app-server` on that port. If nothing is
listening, it starts and owns an app-server process. Both paths are fixed to
`127.0.0.1`; the unauthenticated app-server WebSocket is never exposed to the network.
Codex thread snapshots, live status changes, and approval requests use the already
authenticated phone link. This integration is monitoring and approvals only: it does
not create threads, send messages, steer turns, or interrupt work.

If no phone decision is available, agentd sends no approval response to Codex. The
app-server request remains pending for another subscribed Codex UI to answer and is
replayed when a client resumes the thread.

## Away from home (Tailscale)

Install Tailscale on the PC and phone, then read the phone's tailnet IP from the
Tailscale app. Add it as a direct target and restart the daemon:

```powershell
agentd link-phone 100.x.y.z
```

The daemon then dials the phone directly whenever LAN broadcast discovery cannot find
it. A phone already linked at home accepts the tailnet dial without re-pairing because
the daemon uses the same machine identity.

## Hook management

```powershell
node dist/cli.js install-hooks
node dist/cli.js uninstall-hooks
```

Both commands merge `~/.claude/settings.json` without replacing unrelated settings or
hooks. Before changing an existing settings file, they create a timestamped
`settings.json.agentd-backup-*` copy. Reinstalling is idempotent. Malformed JSON aborts
without writing.

The generated hook forwarder always exits successfully. Ordinary lifecycle hooks return
immediately. A `PreToolUse` hook waits for the linked phone for up to 120 seconds; set
`NEXUS_AGENTD_APPROVAL_TIMEOUT_MS` to a positive millisecond value to change that timeout.
If the daemon or phone is unavailable, the hook returns no decision so Claude Code uses
its own local permission prompt.

## Operations and verification

```powershell
node dist/cli.js status
npm test
npm run smoke
```

`status` checks the loopback health endpoint and prints whether the daemon is running plus
its session count. `npm test` uses Node's built-in test runner. `npm run smoke` starts an
isolated daemon, sends `SessionStart` and `UserPromptSubmit` with `curl`, authenticates a
WebSocket client, verifies `hello_ack`, `snapshot`, and ordered `session_upsert` frames,
prints those frames, and shuts the daemon down.

On startup, recent transcripts under `~/.claude/projects/*/*.jsonl` are shown as stale
idle sessions. They are not tailed until a hook references them. Completed sessions remain
available for 30 minutes.
