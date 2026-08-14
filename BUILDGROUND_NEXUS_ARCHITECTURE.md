# BuildGround Nexus

BuildGround Nexus is the independent host layer for BuildGround smart-glasses software.

## Core principles

1. BuildGround-owned package namespace and signing identity.
2. No mandatory runtime dependency on Anezium Registry, Anezium update services, or Anezium-controlled endpoints.
3. BuildGround-owned plugin registry and update channel.
4. Plugins are accepted by local package/signature/capability policy.
5. Existing Rokid/Anezium code is treated only as a migration donor; new BuildGround modules must not require their remote infrastructure.
6. Updates require BuildGround-signed artifacts and explicit user/device-owner approval unless a future managed-device policy is intentionally added.
7. Fail closed for signature mismatch, plugin identity mismatch, unsupported API version, and artifact checksum mismatch.
8. Keep the current working Nexus build untouched as a rollback reference until BuildGround Nexus reaches functional parity.

## Visual system

- Background: #1B1B1B
- System bars: #151515
- Surface: #242424
- Elevated surface: #2E2E2E
- Primary accent: #FF7A00
- Accent pressed: #E66E00
- Primary text: #F5F5F5
- Secondary text: #B8B8B8
- Divider: #3A3A3A
- Error: #FF5252
- Success: #65C466

## Migration phases

### Phase 1 — Independent core
- New BuildGround application identity.
- New BuildGround theme/resources.
- BuildGround-controlled registry/update interfaces.
- No hardcoded Anezium network endpoints.
- Local signature-based plugin trust.

### Phase 2 — Hardware bridge
- Port only the minimum Rokid transport/CXR integration required to talk to the glasses.
- Keep transport behind BuildGround-owned interfaces.

### Phase 3 — BuildGround plugins
- BuildGround Assistant.
- BuildGround Camera / Vision.
- BuildGround Notifications.
- BuildGround Calls / SMS.
- BuildGround Meeting Recorder / Briefing.
- Additional company agents later.

## Threat model baseline

BuildGround Nexus must continue to operate if the Anezium GitHub repositories, registry or update infrastructure disappear or change. The remaining unavoidable external dependency is the Rokid hardware/firmware interface itself.
